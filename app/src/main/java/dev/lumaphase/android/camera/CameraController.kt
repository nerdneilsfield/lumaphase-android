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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
        fun onFrame(frame: GrayFrame)
        fun onRecognition(reading: RecognitionReading, generation: Int)
        fun onAnalysisError(message: String, generation: Int)
        fun onAnalysisBusy(message: String, generation: Int)
    }

    var listener: Listener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = AtomicBoolean(false)
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
        stop()
        val texture = view.surfaceTexture
        if (texture == null) {
            notifyError("SurfaceTexture 不可用")
            return
        }
        textureView = view
        latestDisplayRotationDeg = displayRotationDegrees(view)
        val startGeneration = generation.incrementAndGet()
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
        ready = false
        pending.set(false)
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
     * Requests one frame. The request is consumed by the next image available callback; if
     * another request is already pending, the call is rejected rather than queued.
     */
    fun requestFrame() {
        if (!ready) {
            notifyError("相机预览尚未就绪")
            return
        }
        val view = textureView
        if (view == null) {
            notifyError("相机预览未连接")
            return
        }
        if (!pending.compareAndSet(false, true)) {
            notifyError("已有一个取帧请求在等待")
            return
        }
        latestDisplayRotationDeg = displayRotationDegrees(view)
        pendingDisplayRotationDeg = latestDisplayRotationDeg
    }

    /**
     * Starts continuous analysis of the newest Y frame for one selected source layout. Calling
     * with the same layout is idempotent; a different layout invalidates previous results.
     * The analysis executor constructs OpenCV on its own thread.
     */
    fun startLive(layout: ImportedLayout) {
        if (live && liveLayout?.layoutId == layout.layoutId) return
        live = true
        liveLayout = layout
        manualLayout = null
        analysisGate.nextGeneration()
        pipelineError = null
    }

    /** Stops continuous analysis and invalidates every in-flight/queued result. */
    fun stopLive() {
        live = false
        liveLayout = null
        manualLayout = null
        analysisGate.nextGeneration()
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
                            ready = false
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
                            ready = false
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
                        ready = true
                        mainHandler.post {
                            if (generation.get() == generationAtStart) {
                                listener?.onStatus(
                                    "预览就绪：${size.width}x${size.height}；" +
                                        "显示旋转=$latestDisplayRotationDeg",
                                )
                            }
                        }
                    } catch (error: Throwable) {
                        closeAll()
                        notifyError("相机请求失败：${error.message}")
                    }
                }

                override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                    if (generation.get() == generationAtStart) {
                        ready = false
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
            if (pending.compareAndSet(true, false)) {
                notifyError("无法获取相机帧：${error.message}")
            }
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
                    analysisExecutor.execute {
                        runAnalysis(frame, layout, analysisId, manual = false)
                    }
                } catch (error: Throwable) {
                    analysisGate.finish(analysisId)
                    notifyAnalysisError("无法复制相机帧：${error.message}", analysisId)
                }
                return
            }
            if (!pending.compareAndSet(true, false)) return
            val rotation = pendingDisplayRotationDeg
            val frame = GrayFrameExtractor.fromImage(image, sensorOrientationDeg, rotation)
            mainHandler.post {
                if (generation.get() == generationAtFrame) listener?.onFrame(frame)
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
    ) {
        try {
            if (!isCurrentAnalysis(analysisId, layout, manual)) return
            val pipeline = getPipeline()
            if (pipeline == null) {
                notifyAnalysisError(pipelineError ?: "OpenCV 初始化失败", analysisId)
                return
            }
            val reading = try {
                if (manual) pipeline.analyzeManual(frame, layout, targetPoints)
                else pipeline.analyze(frame, layout)
            } catch (error: Throwable) {
                notifyAnalysisError("分析失败：${error.message ?: error.javaClass.simpleName}", analysisId)
                return
            }
            if (isCurrentAnalysis(analysisId, layout, manual)) {
                mainHandler.post {
                    if (isCurrentAnalysis(analysisId, layout, manual)) {
                        listener?.onRecognition(reading, analysisId)
                    }
                }
            }
        } finally {
            analysisGate.finish(analysisId)
        }
    }

    private fun getPipeline(): RecognitionPipeline? {
        recognitionPipeline?.let { return it }
        if (pipelineFailed) return null
        return try {
            RecognitionPipeline().also { recognitionPipeline = it }
        } catch (error: Throwable) {
            pipelineFailed = true
            pipelineError = "OpenCV 初始化失败：${error.message ?: error.javaClass.simpleName}"
            // Stop copying/analyzing frames after the first failure. Do not invalidate the
            // generation here: runAnalysis must still be able to deliver this one error to UI.
            live = false
            liveLayout = null
            manualLayout = null
            null
        }
    }

    private fun isCurrentAnalysis(analysisId: Int, layout: ImportedLayout, manual: Boolean): Boolean {
        if (analysisGate.currentGeneration() != analysisId) return false
        return if (manual) !live && manualLayout?.layoutId == layout.layoutId
        else live && liveLayout?.layoutId == layout.layoutId
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
        ready = false
        pending.set(false)
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
