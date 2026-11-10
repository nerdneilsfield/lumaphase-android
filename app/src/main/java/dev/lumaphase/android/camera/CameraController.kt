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
        fun onPreviewSize(size: Size)
        fun onFrame(frame: GrayFrame)
    }

    var listener: Listener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = AtomicBoolean(false)

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private val generation = AtomicInteger(0)
    @Volatile private var ready = false
    @Volatile private var pendingDisplayRotationDeg = 0
    @Volatile private var latestDisplayRotationDeg = 0
    @Volatile private var previewSizeValue: Size? = null

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
            notifyError("SurfaceTexture is not available")
            return
        }
        textureView = view
        latestDisplayRotationDeg = displayRotationDegrees(view)
        val startGeneration = generation.incrementAndGet()
        ensureCameraThread()
        val handler = cameraHandler ?: run {
            notifyError("Camera thread is not available")
            return
        }
        handler.post { openBackCamera(startGeneration, view) }
    }

    @Synchronized
    fun stop() {
        generation.incrementAndGet()
        ready = false
        pending.set(false)
        textureView = null
        val handler = cameraHandler ?: return
        handler.post { closeAll() }
    }

    @Synchronized
    fun shutdown() {
        stop()
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
            notifyError("Camera preview is not ready")
            return
        }
        val view = textureView
        if (view == null) {
            notifyError("Camera preview is not attached")
            return
        }
        if (!pending.compareAndSet(false, true)) {
            notifyError("A frame request is already pending")
            return
        }
        latestDisplayRotationDeg = displayRotationDegrees(view)
        pendingDisplayRotationDeg = latestDisplayRotationDeg
    }

    /** Reapplies the display-only preview transform after a layout or Surface change. */
    fun configurePreview(view: TextureView) {
        val size = previewSizeValue ?: return
        latestDisplayRotationDeg = displayRotationDegrees(view)
        PreviewTransform.apply(view, size.width, size.height, latestDisplayRotationDeg)
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
                ?: throw IllegalStateException("No back camera is available")
            if (generationAtOpen != generation.get()) return

            val map = cameraCharacteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
            ) ?: throw IllegalStateException("Camera has no stream configuration map")
            val size = choosePreviewSize(map)
            val sensor = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val hardwareLevel = cameraCharacteristics.get(
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL,
            )

            characteristics = cameraCharacteristics
            sensorOrientationDeg = sensor
            previewSizeValue = size
            mainHandler.post { listener?.onPreviewSize(size) }
            mainHandler.post {
                if (generation.get() == generationAtOpen) {
                    listener?.onStatus(
                        "Back camera $cameraId, ${size.width}x${size.height}, " +
                            "sensor=$sensor, hardware level=$hardwareLevel",
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
                            notifyError("Cannot start camera preview: ${error.message}")
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        // Invalidate atomically: stop() runs on the main thread and may already
                        // have advanced the generation. A plain read-then-increment here could
                        // write back an older value and let a stale onOpened reopen the camera.
                        if (generation.compareAndSet(generationAtOpen, generationAtOpen + 1)) {
                            ready = false
                            // The callback may arrive before onOpened stored the device; make
                            // sure the object handed to this callback is released as well.
                            if (device == null) device = camera
                            closeAll()
                            notifyError("Camera disconnected")
                        } else {
                            camera.close()
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        if (generation.compareAndSet(generationAtOpen, generationAtOpen + 1)) {
                            ready = false
                            if (device == null) device = camera
                            closeAll()
                            notifyError("Camera error $error")
                        } else {
                            camera.close()
                        }
                    }
                },
                cameraHandler,
            )
        } catch (error: SecurityException) {
            notifyError("Camera permission is not granted")
        } catch (error: CameraAccessException) {
            notifyError("Camera access failed: ${error.message}")
        } catch (error: Throwable) {
            notifyError("Camera open failed: ${error.message}")
        }
    }

    private fun startPreview(
        generationAtStart: Int,
        camera: CameraDevice,
        view: TextureView,
        size: Size,
    ) {
        val texture = view.surfaceTexture
            ?: throw IllegalStateException("SurfaceTexture is not available")
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
                                    "Preview ready: ${size.width}x${size.height}; " +
                                        "display rotation=$latestDisplayRotationDeg",
                                )
                            }
                        }
                    } catch (error: Throwable) {
                        closeAll()
                        notifyError("Camera request failed: ${error.message}")
                    }
                }

                override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                    if (generation.get() == generationAtStart) {
                        ready = false
                        closeAll()
                        notifyError("Camera session configuration failed")
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
                notifyError("Cannot acquire camera frame: ${error.message}")
            }
            return
        } ?: return

        try {
            if (generationAtFrame != generation.get()) return
            if (!pending.compareAndSet(true, false)) return
            val rotation = pendingDisplayRotationDeg
            val frame = GrayFrameExtractor.fromImage(image, sensorOrientationDeg, rotation)
            mainHandler.post {
                if (generation.get() == generationAtFrame) listener?.onFrame(frame)
            }
        } catch (error: Throwable) {
            notifyError("Cannot copy camera frame: ${error.message}")
        } finally {
            image.close()
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
        Log.i(TAG, "camera closed")
        mainHandler.post { listener?.onStatus("Camera closed") }
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
