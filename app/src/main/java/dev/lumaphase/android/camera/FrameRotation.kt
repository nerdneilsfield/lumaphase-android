package dev.lumaphase.android.camera

/**
 * Pure rotation and orientation arithmetic for the camera slice.
 *
 * The camera sensor orientation is the clockwise angle needed to make the sensor output
 * upright in the device's natural orientation. `Display.getRotation()` is the display's
 * rotation from natural orientation, expressed as 0/90/180/270 degrees. For a back camera the
 * relative rotation is `sensor - display`; this is the standard Camera2 relationship and is
 * recorded on every frozen frame instead of being guessed later from the image.
 *
 * No front-camera branch is implemented: this test deliberately uses the back camera only.
 */
object FrameRotation {
    fun forBackCamera(sensorOrientationDeg: Int, displayRotationDeg: Int): Int {
        require(sensorOrientationDeg in setOf(0, 90, 180, 270)) {
            "unsupported sensor orientation $sensorOrientationDeg"
        }
        require(displayRotationDeg in setOf(0, 90, 180, 270)) {
            "unsupported display rotation $displayRotationDeg"
        }
        return (sensorOrientationDeg - displayRotationDeg + 360) % 360
    }

    /** Rotates a packed grayscale image clockwise by 0/90/180/270 degrees. */
    fun rotate(pixels: ByteArray, width: Int, height: Int, rotationDeg: Int): RotatedGray {
        require(width > 0 && height > 0) { "image size must be positive" }
        require(pixels.size == width * height) {
            "pixel array ${pixels.size} does not match ${width}x$height"
        }
        require(rotationDeg in setOf(0, 90, 180, 270)) { "unsupported rotation $rotationDeg" }
        if (rotationDeg == 0) return RotatedGray(width, height, pixels.copyOf())

        val newWidth = if (rotationDeg == 180) width else height
        val newHeight = if (rotationDeg == 180) height else width
        val output = ByteArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val source = pixels[y * width + x]
                when (rotationDeg) {
                    90 -> output[x * newWidth + (height - 1 - y)] = source
                    180 -> output[(height - 1 - y) * width + (width - 1 - x)] = source
                    270 -> output[(width - 1 - x) * newWidth + y] = source
                }
            }
        }
        return RotatedGray(newWidth, newHeight, output)
    }
}

data class RotatedGray(val width: Int, val height: Int, val pixels: ByteArray)
