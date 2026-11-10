package dev.lumaphase.android.camera

data class DisplaySize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "display size must be positive" }
    }
}

/** Uniform fit of content inside a view, in view pixels; the same scale is used on both axes. */
data class ContentFit(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val contentWidth: Float,
    val contentHeight: Float,
)

/**
 * One camera preview's raw buffer and its orientation metadata.
 *
 * `raw` is the camera output buffer. The live TextureView presents the buffer after the sensor
 * orientation, and the analysis [GrayFrame] presents it after the relative sensor/display
 * rotation. Both paths must use [uprightSize] for the view aspect and the frozen image, while
 * [naturalSize] is what the TextureView content transform starts from.
 */
data class CameraPreviewInfo(
    val rawWidthPx: Int,
    val rawHeightPx: Int,
    val sensorOrientationDeg: Int,
    val displayRotationDeg: Int,
) {
    val naturalSize: DisplaySize
        get() = CameraPreviewGeometry.naturalSize(rawWidthPx, rawHeightPx, sensorOrientationDeg)

    val uprightSize: DisplaySize
        get() = CameraPreviewGeometry.uprightSize(
            rawWidthPx,
            rawHeightPx,
            sensorOrientationDeg,
            displayRotationDeg,
        )

    val uprightRotationDeg: Int
        get() = FrameRotation.forBackCamera(sensorOrientationDeg, displayRotationDeg)
}

/**
 * Pure orientation/aspect arithmetic shared by the live preview, the frozen frame and any
 * future overlay. It contains no Android view code so the four rotation cases can be tested
 * with explicit expected values on the host JVM.
 */
object CameraPreviewGeometry {
    /**
     * The buffer as TextureView presents it before display rotation: the raw sensor image
     * rotated by the sensor orientation. A 90/270 sensor swaps the extents.
     */
    fun naturalSize(rawWidthPx: Int, rawHeightPx: Int, sensorOrientationDeg: Int): DisplaySize {
        require(rawWidthPx > 0 && rawHeightPx > 0) { "raw size must be positive" }
        require(sensorOrientationDeg in setOf(0, 90, 180, 270)) {
            "unsupported sensor orientation $sensorOrientationDeg"
        }
        return if (sensorOrientationDeg % 180 == 0) {
            DisplaySize(rawWidthPx, rawHeightPx)
        } else {
            DisplaySize(rawHeightPx, rawWidthPx)
        }
    }

    /**
     * The upright image used by the frozen frame and by the live view after display rotation.
     * It is the natural TextureView content with the display rotation applied; a 90/270
     * display rotation swaps the extents. This is the only aspect used by the screen.
     */
    fun uprightSize(
        rawWidthPx: Int,
        rawHeightPx: Int,
        sensorOrientationDeg: Int,
        displayRotationDeg: Int,
    ): DisplaySize {
        require(displayRotationDeg in setOf(0, 90, 180, 270)) {
            "unsupported display rotation $displayRotationDeg"
        }
        val natural = naturalSize(rawWidthPx, rawHeightPx, sensorOrientationDeg)
        return if (displayRotationDeg % 180 == 0) {
            natural
        } else {
            DisplaySize(natural.height, natural.width)
        }
    }

    /**
     * Inverse of [fit] for a touch in view pixels. Returns null when the touch is outside the
     * fitted content (letterbox bars), so a manual corner can never be invented from a bar.
     */
    fun viewToFrame(
        fit: ContentFit,
        viewX: Float,
        viewY: Float,
        frameWidth: Int,
        frameHeight: Int,
    ): DoublePoint? {
        if (fit.scale <= 0f || frameWidth <= 0 || frameHeight <= 0) return null
        val x = (viewX - fit.offsetX) / fit.scale
        val y = (viewY - fit.offsetY) / fit.scale
        if (x < 0f || y < 0f || x >= frameWidth || y >= frameHeight) return null
        return DoublePoint(x.toDouble(), y.toDouble())
    }

    /** Uniform fit (letterbox) of content into a view; never stretches an axis independently. */
    fun fit(viewWidth: Float, viewHeight: Float, contentWidth: Float, contentHeight: Float): ContentFit {
        require(viewWidth > 0f && viewHeight > 0f) { "view size must be positive" }
        require(contentWidth > 0f && contentHeight > 0f) { "content size must be positive" }
        val scale = minOf(viewWidth / contentWidth, viewHeight / contentHeight)
        val fittedWidth = contentWidth * scale
        val fittedHeight = contentHeight * scale
        return ContentFit(
            scale = scale,
            offsetX = (viewWidth - fittedWidth) / 2f,
            offsetY = (viewHeight - fittedHeight) / 2f,
            contentWidth = fittedWidth,
            contentHeight = fittedHeight,
        )
    }
}
