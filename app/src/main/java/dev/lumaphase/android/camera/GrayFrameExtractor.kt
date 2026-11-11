package dev.lumaphase.android.camera

import android.media.Image
import dev.lumaphase.android.layout.RectPx

/**
 * Converts an `ImageReader` YUV_420_888 [Image] into an upright [GrayFrame].
 *
 * Only plane 0 (Y) is read. The copy happens before the caller closes the Image; the resulting
 * frame owns its bytes and can be analysed or displayed asynchronously. Rotation is applied on
 * the copied bytes, so no Android image remains pinned.
 */
object GrayFrameExtractor {
    fun fromImage(
        image: Image,
        sensorOrientationDeg: Int,
        displayRotationDeg: Int,
    ): GrayFrame {
        val crop = image.cropRect
        if (crop.width() <= 0 || crop.height() <= 0) {
            throw IllegalArgumentException("image crop is empty: $crop")
        }
        val planes = image.planes
        if (planes.isEmpty()) throw IllegalArgumentException("image has no planes")
        val yPlane = planes[0]
        val raw = YPlaneCopy.copy(
            buffer = yPlane.buffer,
            cropLeft = crop.left,
            cropTop = crop.top,
            cropWidth = crop.width(),
            cropHeight = crop.height(),
            rowStride = yPlane.rowStride,
            pixelStride = yPlane.pixelStride,
        )
        val rotation = FrameRotation.forBackCamera(sensorOrientationDeg, displayRotationDeg)
        val rotated = FrameRotation.rotate(raw, crop.width(), crop.height(), rotation)
        return GrayFrame(
            width = rotated.width,
            height = rotated.height,
            pixels = rotated.pixels,
            rawWidth = crop.width(),
            rawHeight = crop.height(),
            rotationDeg = rotation,
            sensorOrientationDeg = sensorOrientationDeg,
            displayRotationDeg = displayRotationDeg,
            cropPx = RectPx(crop.left, crop.top, crop.right, crop.bottom),
            timestampNanos = image.timestamp.takeIf { it >= 0L } ?: 0L,
        )
    }
}
