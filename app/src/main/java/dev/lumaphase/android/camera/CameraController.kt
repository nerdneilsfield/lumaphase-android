package dev.lumaphase.android.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import dev.lumaphase.android.layout.ImportedLayout
import dev.lumaphase.android.recognition.RecognitionPipeline
import dev.lumaphase.android.recognition.RecognitionReading
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * One pending freeze capture plus its request identity. A request is reserved before live is
 * stopped, but is not consumable until [markReady] runs after the stop. This prevents a camera
 * callback that races with requestFrame from publishing/queuing a frame whose request was
 * cancelled, and makes a duplicate request fail without cancelling the first one.
 */
internal class FreezeCaptureState {
    data class Request(val id: Int, val layout: ImportedLayout?)

    private val lock = Any()
    private var pending = false
    private var ready = false
    private var nextId = 0
    private var active: Request? = null

    fun reserve(layout: ImportedLayout?): Request? = synchronized(lock) {
        if (pending) {
            null
        } else {
            pending = true
            ready = false
            Request(++nextId, layout).also { active = it }
        }
    }

    fun markReady(request: Request): Boolean = synchronized(lock) {
        if (!pending || active !== request) {
            false
        } else {
            ready = true
            true
        }
    }

    /** Consumes the next-frame gate only when the request is fully reserved and ready. */
    fun take(): Request? = synchronized(lock) {
        if (!pending || !ready) {
            null
        } else {
            pending = false
            active
        }
    }

    /** Cancels an unfinished capture and makes every older request id non-current. */
    fun cancel() {
        synchronized(lock) {
            pending = false
            ready = false
            active = null
            nextId++
        }
    }

    fun isActive(request: Request): Boolean = synchronized(lock) {
        active === request
    }
}

/**
 * Minimal back-camera owner for the recognition capture test.
 *
 * It deliberately does not use CameraX and does not perform continuous recognition. A single
 * YUV_420_888 frame is requested by [requestFrame]; the newest image is copied, the Image is
 * closed immediately, and the copied frame is delivered on the main thread. All camera
 * resources are released in [stop] and [shutdown].
 *
 * A monotonically increasing generation token makes stale asynchronous callbacks harmless:
 * after a stop or Surface destruction, a late `onOpened` closes its device instead of
 * reconfiguring a camera that the UI no longer owns.
 */
class CameraController(private val context: Context) {

    interface Listener {
        fun onStatus(message: String)
        fun onError(message: String)
        fun onPreviewInfo(preview: CameraPreviewInfo)
        fun onCameraInfo(info: CameraHardware)
        fun onStationInvalid(message: String)
        fun onFrame(frame: GrayFrame)
        fun onRecognition(reading: RecognitionReading, generation: Int)
        fun onFrozenRecognition(
            reading: RecognitionReading,
            frameTimestampNanos: Long,
            completedAtMillis: Long,
            elapsedMillis: Long,
        )
        fun onAnalysisError(message: String, generation: Int)
        fun onFrozenAnalysisError(message: String)
        fun onAnalysisBusy(message: String, generation: Int)
    }

    var listener: Listener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private data class PendingFreeze(
        val frame: GrayFrame,
        val request: FreezeCaptureState.Request,
        val layout: ImportedLayout,
    )

    private val freezeCapture = FreezeCaptureState()
    private val analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lumaphase-analysis").apply { isDaemon = true }
    }
    private val analysisGate = AnalysisGate()

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private val generation = AtomicInteger(0)
    @Volatile private var ready = false
    @Volatile private var pendingDisplayRotationDeg = 0
    @Volatile private var latestDisplayRotationDeg = 0
    @Volatile private var previewSizeValue: Size? = null
    @Volatile private var live = false
    @Volatile private var liveLayout: ImportedLayout? = null
    @Volatile private var manualLayout: ImportedLayout? = null
    private data class StationRun(
        val station: StationCalibration,
        val targetPoints: List<DoublePoint>,
    )

    private data class PendingStation(
        val generation: Int,
        val layout: ImportedLayout,
        val station: StationCalibration,
    )

    @Volatile private var cameraHardware: CameraHardware? = null
    @Volatile private var stationRun: StationRun? = null
    @Volatile private var pendingStation: PendingStation? = null
    @Volatile private var frozenLayout: ImportedLayout? = null
    @Volatile private var pendingFreeze: PendingFreeze? = null
    @Volatile private var recognitionPipeline: RecognitionPipeline? = null
    @Volatile private var pipelineError: String? = null
    @Volatile private var pipelineFailed = false

    private var textureView: TextureView? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var characteristics: CameraCharacteristics? = null
    private var sensorOrientationDeg = 0

    @Synchronized
    fun start(view: TextureView) {
        if (textureView === view && ready) {
            configurePreview(view)
            return
        }
        // A view/camera restart must keep an explicitly enabled station intent. The UI mode
        // effect may have posted it while the previous camera was already stopping, and the
        // intent is revalidated from onConfigured after the new generation is ready.
        val pendingStationIntent = pendingStation
        stop()
        val texture = view.surfaceTexture
        if (texture == null) {
            notifyError("SurfaceTexture 不可用")
            return
        }
        textureView = view
        latestDisplayRotationDeg = displayRotationDegrees(view)
        val startGeneration = generation.incrementAndGet()
        pendingStation = pendingStationIntent?.copy(generation = startGeneration)
        ensureCameraThread()
        val handler = cameraHandler ?: run {
            notifyError("相机线程不可用")
            return
        }
        handler.post { openBackCamera(startGeneration, view) }
    }

    @Synchronized
    fun stop() {
        generation.incrementAndGet()
        analysisGate.nextGeneration()
        live = false
        liveLayout = null
        manualLayout = null
        clearStation()
        cancelFrozenCapture()
        ready = false
        textureView = null
        val handler = cameraHandler ?: return
        handler.post { closeAll() }
    }

    @Synchronized
    fun shutdown() {
        stop()
        analysisExecutor.shutdownNow()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    /**
     * Freezes the next Y frame and analyzes it through the same pipeline. The live loop is
     * stopped immediately so its old generation cannot publish; the frame is still displayed
     * even when the single analysis slot is busy, and the analysis waits in [pendingFreeze].
     */
    fun requestFrame(layout: ImportedLayout?) {
        if (!ready) {
            notifyError("相机预览尚未就绪")
            return
        }
        val view = textureView
        if (view == null) {
            notifyError("相机预览未连接")
            return
        }
        // Reserve the frame gate first. A duplicate request must not stop the first request.
        val request = freezeCapture.reserve(layout) ?: run {
            notifyError("已有一个取帧请求在等待")
            return
        }
        // Now the request owns the gate; stop live and invalidate its generation without
        // cancelling this just-reserved capture. markReady publishes it after the stop.
        stopLiveForFreeze()
        latestDisplayRotationDeg = displayRotationDegrees(view)
        pendingDisplayRotationDeg = latestDisplayRotationDeg
        freezeCapture.markReady(request)
    }

    /**
     * Starts continuous analysis of the newest Y frame for one selected source layout. Calling
     * with the same layout is idempotent; a different layout invalidates previous results.
     * The analysis executor constructs OpenCV on its own thread.
     */
    fun startLive(layout: ImportedLayout) {
        // A layout/rotation switch or restore explicitly cancels an unfinished freeze capture
        // and must also leave a fixed-station RUN even when the layout id is unchanged.
        cancelFrozenCapture()
        if (live && liveLayout?.layoutId == layout.layoutId && stationRun == null) return
        analysisGate.nextGeneration()
        clearStation()
        live = true
        liveLayout = layout
        manualLayout = null
        pipelineError = null
    }

    private fun stopLiveForFreeze() {
        live = false
        liveLayout = null
        manualLayout = null
        pendingFreeze = null
        frozenLayout = null
        analysisGate.nextGeneration()
    }

    /** Stops continuous analysis and invalidates every in-flight/queued result. */
    fun stopLive() {
        analysisGate.nextGeneration()
        live = false
        liveLayout = null
        manualLayout = null
        clearStation()
        cancelFrozenCapture()
    }

    /**
     * Starts continuous RUN with an explicitly saved station. The station source digest,
     * camera id and preview size are checked before live starts; frame size/crop/orientation
     * are checked on every frame. It reuses the same analysisExecutor and sampler/decoder.
     */
    fun startStationLive(layout: ImportedLayout, station: StationCalibration) {
        val hardware = cameraHardware
        val layoutCheck = StationCalibrationRules.validateLayout(station, layout)
        val cameraCheck = hardware?.let { StationCalibrationRules.validateCamera(station, it) }
        when (stationStartAction(ready, hardware, layoutCheck, cameraCheck)) {
            StationStartAction.INVALID -> {
                val reason = layoutCheck.reason ?: cameraCheck?.reason ?: "unknown mismatch"
                notifyStationInvalid("固定工站失效：$reason，请重新标定")
            }
            StationStartAction.WAIT_READY -> {
                // The camera is still opening. Keep the explicit enable intent; apply it
                // from onConfigured, never turn "not ready" into a station-invalid event.
                clearStation()
                pendingStation = PendingStation(generation.get(), layout, station)
                mainHandler.post { listener?.onStatus("固定工站已启用，等待相机就绪") }
            }
            StationStartAction.START -> activateStation(layout, station)
        }
    }

    private fun activateStation(layout: ImportedLayout, station: StationCalibration) {
        clearStation()
        cancelFrozenCapture()
        analysisGate.nextGeneration()
        live = true
        liveLayout = layout
        manualLayout = null
        stationRun = StationRun(station, station.targetCorners.map { DoublePoint(it.x, it.y) })
        pipelineError = null
    }

    private fun applyPendingStation(generationAtStart: Int) {
        val pending = pendingStation ?: return
        if (pending.generation != generationAtStart || generation.get() != generationAtStart) {
            pendingStation = null
            return
        }
        pendingStation = null
        val hardware = cameraHardware
        val layoutCheck = StationCalibrationRules.validateLayout(pending.station, pending.layout)
        val cameraCheck = hardware?.let {
            StationCalibrationRules.validateCamera(pending.station, it)
        }
        when (stationStartAction(true, hardware, layoutCheck, cameraCheck)) {
            StationStartAction.START -> {
                activateStation(pending.layout, pending.station)
                mainHandler.post { listener?.onStatus("固定工站连续 RUN") }
            }
            StationStartAction.INVALID -> {
                val reason = layoutCheck.reason ?: cameraCheck?.reason ?: "unknown mismatch"
                notifyStationInvalid("固定工站失效：$reason，请重新标定")
            }
            StationStartAction.WAIT_READY -> Unit
        }
    }

    private fun clearStation() {
        stationRun = null
        pendingStation = null
    }

    /**
     * Station state invalidation is a main-thread transaction. Camera callbacks pass their
     * camera generation/analysis token; the main owner re-checks both before stopping a newer
     * live/auto run. A stale invalid callback therefore cannot clear a new station or auto mode.
     */
    private fun notifyStationInvalid(
        message: String,
        cameraGeneration: Int? = null,
        analysisId: Int? = null,
        requireActiveStation: Boolean = false,
    ) {
        val action = Runnable {
            if (cameraGeneration != null && generation.get() != cameraGeneration) return@Runnable
            if (analysisId != null && analysisGate.currentGeneration() != analysisId) return@Runnable
            if (requireActiveStation && stationRun == null) return@Runnable
            live = false
            liveLayout = null
            manualLayout = null
            clearStation()
            analysisGate.nextGeneration()
            listener?.onStationInvalid(message)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    /**
     * Runs one manual-four-corner analysis on the same owned executor. [targetPoints] are
     * upright-frame pixels for the dynamic rectangle TL/TR/BR/BL.
     */
    fun analyzeManual(frame: GrayFrame, layout: ImportedLayout, targetPoints: List<dev.lumaphase.android.camera.DoublePoint>) {
        val gen = analysisGate.beginNew()
        if (gen == null) {
            // Do not turn off live/change layout when the single slot is busy; the caller can
            // retry and automatic recognition keeps its previous generation.
            notifyAnalysisBusy("手动分析忙，请稍后重试", analysisGate.currentGeneration())
            return
        }
        live = false
        liveLayout = null
        manualLayout = layout
        clearStation()
        cancelFrozenCapture()
        analysisExecutor.execute { runAnalysis(frame, layout, gen, manual = true, targetPoints = targetPoints) }
    }

    /**
     * Explicit operator recovery after a camera or OpenCV initialization failure. It is never
     * called automatically; the screen exposes a button and the caller passes the current
     * TextureView/selected layout. A failed pipeline is reset once, then live starts again.
     */
    @Synchronized
    fun retryCamera(view: TextureView, layout: ImportedLayout?) {
        pipelineFailed = false
        pipelineError = null
        recognitionPipeline = null
        live = false
        liveLayout = null
        manualLayout = null
        clearStation()
        cancelFrozenCapture()
        start(view)
        if (layout != null) startLive(layout)
    }

    /** Reapplies the display-only preview transform after a layout or Surface change. */
    fun configurePreview(view: TextureView) {
        val size = previewSizeValue ?: return
        latestDisplayRotationDeg = displayRotationDegrees(view)
        PreviewTransform.apply(
            view,
            CameraPreviewInfo(
                rawWidthPx = size.width,
                rawHeightPx = size.height,
                sensorOrientationDeg = sensorOrientationDeg,
                displayRotationDeg = latestDisplayRotationDeg,
            ),
        )
    }

    private fun ensureCameraThread() {
        if (cameraThread != null) return
        val thread = HandlerThread("lumaphase-camera")
        thread.start()
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
    }

    private fun openBackCamera(generationAtOpen: Int, view: TextureView) {
        if (generationAtOpen != generation.get()) return
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var selected: Pair<String, CameraCharacteristics>? = null
            for (cameraId in manager.cameraIdList) {
                val candidate = manager.getCameraCharacteristics(cameraId)
                if (candidate.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
                ) {
                    selected = cameraId to candidate
                    break
                }
            }
            val (cameraId, cameraCharacteristics) = selected
                ?: throw IllegalStateException("设备没有后置相机")
            if (generationAtOpen != generation.get()) return

            val map = cameraCharacteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
            ) ?: throw IllegalStateException("相机没有可用的输出配置")
            val size = choosePreviewSize(map)
            val sensor = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val hardwareLevel = cameraCharacteristics.get(
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL,
            )

            characteristics = cameraCharacteristics
            sensorOrientationDeg = sensor
            previewSizeValue = size
            val hardware = CameraHardware(
                cameraId = cameraId,
                sensorOrientationDeg = sensor,
                previewWidthPx = size.width,
                previewHeightPx = size.height,
            )
            cameraHardware = hardware
            mainHandler.post { listener?.onCameraInfo(hardware) }
            val preview = CameraPreviewInfo(
                rawWidthPx = size.width,
                rawHeightPx = size.height,
                sensorOrientationDeg = sensor,
                displayRotationDeg = latestDisplayRotationDeg,
            )
            mainHandler.post { listener?.onPreviewInfo(preview) }
            mainHandler.post {
                if (generation.get() == generationAtOpen) {
                    listener?.onStatus(
                        "后置相机 $cameraId，${size.width}x${size.height}，" +
                            "传感器方向=$sensor，硬件级别=$hardwareLevel",
                    )
                }
            }

            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (generation.get() != generationAtOpen) {
                            camera.close()
                            return
                        }
                        device = camera
                        try {
                            startPreview(generationAtOpen, camera, view, size)
                        } catch (error: Throwable) {
                            closeAll()
                            notifyError("无法启动相机预览：${error.message}")
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        // Invalidate atomically: stop() runs on the main thread and may already
                        // have advanced the generation. A plain read-then-increment here could
                        // write back an older value and let a stale onOpened reopen the camera.
                        if (generation.compareAndSet(generationAtOpen, generationAtOpen + 1)) {
                            publishReadyFalse()
                            live = false
                            liveLayout = null
                            manualLayout = null
                            analysisGate.nextGeneration()
                            // The callback may arrive before onOpened stored the device; make
                            // sure the object handed to this callback is released as well.
                            if (device == null) device = camera
                            closeAll()
                            notifyError("相机已断开")
                        } else {
                            camera.close()
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        if (generation.compareAndSet(generationAtOpen, generationAtOpen + 1)) {
                            publishReadyFalse()
                            live = false
                            liveLayout = null
                            manualLayout = null
                            analysisGate.nextGeneration()
                            if (device == null) device = camera
                            closeAll()
                            notifyError("相机错误 $error")
                        } else {
                            camera.close()
                        }
                    }
                },
                cameraHandler,
            )
        } catch (error: SecurityException) {
            notifyError("相机权限未授予")
        } catch (error: CameraAccessException) {
            notifyError("相机访问失败：${error.message}")
        } catch (error: Throwable) {
            notifyError("相机打开失败：${error.message}")
        }
    }

    private fun startPreview(
        generationAtStart: Int,
        camera: CameraDevice,
        view: TextureView,
        size: Size,
    ) {
        val texture = view.surfaceTexture
            ?: throw IllegalStateException("SurfaceTexture 不可用")
        texture.setDefaultBufferSize(size.width, size.height)

        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader.setOnImageAvailableListener({ onImageAvailable(generationAtStart) }, cameraHandler)
        imageReader = reader

        val surface = Surface(texture)
        previewSurface = surface

        val surfaces = listOf(surface, reader.surface)
        camera.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(captureSession: CameraCaptureSession) {
                    if (generation.get() != generationAtStart) {
                        captureSession.close()
                        return
                    }
                    session = captureSession
                    try {
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        builder.addTarget(surface)
                        builder.addTarget(reader.surface)
                        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        val afModes = characteristics?.get(
                            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
                        )
                        if (afModes?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true) {
                            builder.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                            )
                        }
                        captureSession.setRepeatingRequest(builder.build(), null, cameraHandler)
                        // Ready, pending-station application and mode state are owned by the
                        // main thread. The camera thread only posts this transition.
                        mainHandler.post {
                            if (generation.get() != generationAtStart) return@post
                            ready = true
                            applyPendingStation(generationAtStart)
                            listener?.onStatus(
                                "预览就绪：${size.width}x${size.height}；" +
                                    "显示旋转=$latestDisplayRotationDeg",
                            )
                        }
                    } catch (error: Throwable) {
                        closeAll()
                        notifyError("相机请求失败：${error.message}")
                    }
                }

                override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                    if (generation.get() == generationAtStart) {
                        publishReadyFalse()
                        closeAll()
                        notifyError("相机会话配置失败")
                    }
                }
            },
            cameraHandler,
        )
    }

    private fun onImageAvailable(generationAtFrame: Int) {
        val reader = imageReader ?: return
        val image = try {
            reader.acquireLatestImage()
        } catch (error: Throwable) {
            cancelFrozenCapture()
            notifyError("无法获取相机帧：${error.message}")
            return
        } ?: return

        try {
            if (generationAtFrame != generation.get() || !ready) return
            if (live) {
                val layout = liveLayout ?: return
                val analysisId = analysisGate.currentGeneration()
                if (!analysisGate.tryBegin(analysisId)) return
                try {
                    val frame = GrayFrameExtractor.fromImage(
                        image,
                        sensorOrientationDeg,
                        latestDisplayRotationDeg,
                    )
                    val stationRunValue = stationRun
                    if (stationRunValue != null) {
                        val check = StationCalibrationRules.validateFrame(stationRunValue.station, frame)
                        if (!check.ok) {
                            analysisGate.finish(analysisId)
                            notifyStationInvalid(
                                "固定工站失效：${check.reason}，请重新标定",
                                cameraGeneration = generationAtFrame,
                                analysisId = analysisId,
                                requireActiveStation = true,
                            )
                            return
                        }
                    }
                    analysisExecutor.execute {
                        runAnalysis(
                            frame,
                            layout,
                            analysisId,
                            manual = false,
                            targetPoints = stationRunValue?.targetPoints.orEmpty(),
                            station = stationRunValue != null,
                        )
                    }
                } catch (error: Throwable) {
                    analysisGate.finish(analysisId)
                    notifyAnalysisError("无法复制相机帧：${error.message}", analysisId)
                }
                return
            }
            val request = freezeCapture.take() ?: return
            val rotation = pendingDisplayRotationDeg
            val frame = GrayFrameExtractor.fromImage(image, sensorOrientationDeg, rotation)
            // Re-check after the copy: startLive/stopLive/layout switch may have cancelled it.
            if (!freezeCapture.isActive(request)) return
            mainHandler.post {
                if (generation.get() == generationAtFrame && freezeCapture.isActive(request)) {
                    listener?.onFrame(frame)
                }
            }
            val layout = request.layout
            if (layout != null && freezeCapture.isActive(request)) {
                enqueueFrozenAnalysis(frame, request, layout)
            }
        } catch (error: Throwable) {
            if (live) notifyAnalysisError("无法复制相机帧：${error.message}", analysisGate.currentGeneration())
            else notifyError("无法复制相机帧：${error.message}")
        } finally {
            image.close()
        }
    }

    private fun runAnalysis(
        frame: GrayFrame,
        layout: ImportedLayout,
        analysisId: Int,
        manual: Boolean,
        targetPoints: List<dev.lumaphase.android.camera.DoublePoint> = emptyList(),
        frozen: Boolean = false,
        station: Boolean = false,
    ) {
        val startedAtNanos = System.nanoTime()
        try {
            if (!isCurrentAnalysis(analysisId, layout, manual, frozen, station)) return
            val pipeline = getPipeline(analysisId)
            if (pipeline == null) {
                notifyAnalysisFailure(pipelineError ?: "OpenCV 初始化失败", analysisId, frozen)
                return
            }
            val reading = try {
                if (station) pipeline.analyzeStation(frame, layout, targetPoints)
                else if (manual) pipeline.analyzeManual(frame, layout, targetPoints)
                else pipeline.analyze(frame, layout)
            } catch (error: Throwable) {
                notifyAnalysisFailure("分析失败：${error.message ?: error.javaClass.simpleName}", analysisId, frozen)
                return
            }
            if (frozen) {
                val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000
                val completedAtMillis = System.currentTimeMillis()
                mainHandler.post {
                    if (isCurrentAnalysis(analysisId, layout, manual, frozen, station)) {
                        listener?.onFrozenRecognition(
                            reading,
                            frame.timestampNanos,
                            completedAtMillis,
                            elapsedMs,
                        )
                    }
                }
            } else if (isCurrentAnalysis(analysisId, layout, manual, frozen, station)) {
                mainHandler.post {
                    if (isCurrentAnalysis(analysisId, layout, manual, frozen, station)) {
                        listener?.onRecognition(reading, analysisId)
                    }
                }
            }
        } finally {
            analysisGate.finish(analysisId)
            dispatchPendingFreeze()
        }
    }

    private fun getPipeline(analysisId: Int): RecognitionPipeline? {
        recognitionPipeline?.let { return it }
        if (pipelineFailed) return null
        return try {
            RecognitionPipeline().also { recognitionPipeline = it }
        } catch (error: Throwable) {
            pipelineFailed = true
            pipelineError = "OpenCV 初始化失败：${error.message ?: error.javaClass.simpleName}"
            // Station state is main-owner state; only clear it if this analysis is still
            // current, so an old failure cannot tear down a newer mode.
            mainHandler.post {
                if (analysisGate.currentGeneration() == analysisId) {
                    live = false
                    liveLayout = null
                    manualLayout = null
                    clearStation()
                }
            }
            null
        }
    }

    private fun isCurrentAnalysis(
        analysisId: Int,
        layout: ImportedLayout,
        manual: Boolean,
        frozen: Boolean = false,
        station: Boolean = false,
    ): Boolean {
        if (analysisGate.currentGeneration() != analysisId) return false
        return when {
            frozen -> !live && frozenLayout?.layoutId == layout.layoutId
            manual -> !live && manualLayout?.layoutId == layout.layoutId
            station -> live && liveLayout?.layoutId == layout.layoutId && stationRun != null
            else -> live && liveLayout?.layoutId == layout.layoutId
        }
    }

    private fun enqueueFrozenAnalysis(
        frame: GrayFrame,
        request: FreezeCaptureState.Request,
        layout: ImportedLayout,
    ) {
        if (!freezeCapture.isActive(request)) return
        pendingFreeze = PendingFreeze(frame, request, layout)
        dispatchPendingFreeze()
    }

    /** Cancels an unfinished freeze capture; late images/requests become non-current. */
    private fun cancelFrozenCapture() {
        freezeCapture.cancel()
        pendingFreeze = null
        frozenLayout = null
    }

    /**
     * Starts the queued freeze on the same single analysis slot. A busy live/manual/frozen
     * analysis keeps the queued frame; its runAnalysis finally retries this method.
     */
    private fun dispatchPendingFreeze() {
        val queued = pendingFreeze ?: return
        if (!freezeCapture.isActive(queued.request) || live) {
            pendingFreeze = null
            return
        }
        val token = analysisGate.beginNew() ?: return
        pendingFreeze = null
        frozenLayout = queued.layout
        analysisExecutor.execute {
            runAnalysis(queued.frame, queued.layout, token, manual = false, frozen = true)
        }
    }

    private fun notifyAnalysisFailure(message: String, analysisId: Int, frozen: Boolean) {
        if (frozen) {
            mainHandler.post {
                if (analysisGate.currentGeneration() == analysisId) {
                    listener?.onFrozenAnalysisError(message)
                }
            }
            Log.w(TAG, message)
        } else {
            notifyAnalysisError(message, analysisId)
        }
    }

    private fun notifyAnalysisError(message: String, analysisId: Int) {
        Log.w(TAG, message)
        mainHandler.post {
            if (analysisGate.currentGeneration() == analysisId) listener?.onAnalysisError(message, analysisId)
        }
    }

    private fun notifyAnalysisBusy(message: String, analysisId: Int) {
        mainHandler.post {
            if (analysisGate.currentGeneration() == analysisId) listener?.onAnalysisBusy(message, analysisId)
        }
    }

    /** Ready publication is main-owned; a stale camera callback cannot clear a newer open. */
    private fun publishReadyFalse() {
        val expectedGeneration = generation.get()
        mainHandler.post {
            if (generation.get() == expectedGeneration) ready = false
        }
    }

    private fun choosePreviewSize(map: android.hardware.camera2.params.StreamConfigurationMap): Size {
        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val common = yuvSizes.filter { yuv ->
            previewSizes.any { it.width == yuv.width && it.height == yuv.height }
        }
        if (common.isEmpty()) {
            throw IllegalStateException("No shared SurfaceTexture/YUV_420_888 output size")
        }
        // One deterministic combination, no retry ladder: prefer 1080p, then 720p, then the
        // largest shared size. A failure to configure this combination is reported as-is.
        return common.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: common.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: common.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: common.first()
    }

    private fun closeAll() {
        publishReadyFalse()
        cancelFrozenCapture()
        session?.close()
        session = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        previewSurface?.release()
        previewSurface = null
        device?.close()
        device = null
        previewSizeValue = null
        live = false
        liveLayout = null
        manualLayout = null
        analysisGate.nextGeneration()
        Log.i(TAG, "camera closed")
        mainHandler.post { listener?.onStatus("相机已关闭") }
    }

    private fun displayRotationDegrees(view: TextureView): Int =
        (view.display?.rotation ?: Surface.ROTATION_0) * 90

    private fun notifyError(message: String) {
        Log.w(TAG, message)
        mainHandler.post { listener?.onError(message) }
    }

    private companion object {
        const val TAG = "LumaphaseCamera"
    }
}
