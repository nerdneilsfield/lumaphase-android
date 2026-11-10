package dev.lumaphase.android.camera

import dev.lumaphase.android.layout.RectPx

/**
 * One upright grayscale frame. [pixels] is packed row-major at [width] x [height]; it is a
 * copy, so it remains valid after the source `Image` is closed.
 *
 * [rotationDeg] is the clockwise rotation that was applied to the raw camera Y plane to bring
 * it to the current display direction. [sensorOrientationDeg] and [displayRotationDeg] are
 * kept verbatim so the UI and logs can show the mapping that was actually used; they are not
 * inferred again later. [cropPx] is the source image's valid crop rectangle.
 */
class GrayFrame(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
    val rawWidth: Int,
    val rawHeight: Int,
    val rotationDeg: Int,
    val sensorOrientationDeg: Int,
    val displayRotationDeg: Int,
    val cropPx: RectPx,
) {
    init {
        require(width > 0 && height > 0) { "frame size must be positive" }
        require(pixels.size == width * height) {
            "pixel array ${pixels.size} does not match ${width}x$height"
        }
        require(rotationDeg in setOf(0, 90, 180, 270)) { "unsupported rotation $rotationDeg" }
    }

    /** Diagnostic identity only; no timing or sequence claim is made from it. */
    val description: String
        get() = "frame ${width}x$height raw=${rawWidth}x$rawHeight " +
            "sensor=$sensorOrientationDeg display=$displayRotationDeg " +
            "rotation=$rotationDeg crop=${cropPx.edges.joinToString(",")}"
}
