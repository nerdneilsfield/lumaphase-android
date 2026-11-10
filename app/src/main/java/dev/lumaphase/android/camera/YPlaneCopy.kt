package dev.lumaphase.android.camera

import java.nio.ByteBuffer

/**
 * Copies the Y plane of a YUV_420_888 image into a packed grayscale array.
 *
 * The source buffer may have a non-zero position, a row stride larger than the image width,
 * and a crop rectangle inset from the full image. All three are honoured. Y's pixel stride is
 * guaranteed to be 1 by the Android documentation, but the parameter is kept explicit so the
 * copy remains correct if a device reports otherwise.
 *
 * This is pure Java/Kotlin so it can be tested on a host JVM with a heap or direct
 * [ByteBuffer]; it does not depend on `android.media.Image`.
 */
object YPlaneCopy {
    fun copy(
        buffer: ByteBuffer,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        rowStride: Int,
        pixelStride: Int,
    ): ByteArray {
        require(cropWidth > 0 && cropHeight > 0) { "crop must be non-empty" }
        require(cropLeft >= 0 && cropTop >= 0) { "crop origin must be non-negative" }
        require(rowStride > 0) { "rowStride must be positive" }
        require(pixelStride > 0) { "pixelStride must be positive" }

        val base = buffer.position()
        val output = ByteArray(cropWidth * cropHeight)
        var destination = 0
        for (y in 0 until cropHeight) {
            var index = base + (cropTop + y).toLong() * rowStride +
                cropLeft.toLong() * pixelStride
            for (x in 0 until cropWidth) {
                if (index >= buffer.limit()) {
                    throw IllegalArgumentException(
                        "Y plane is shorter than crop: index $index, limit ${buffer.limit()}",
                    )
                }
                output[destination++] = buffer.get(index.toInt())
                index += pixelStride
            }
        }
        return output
    }
}
