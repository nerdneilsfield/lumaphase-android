package dev.lumaphase.android.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pure orientation arithmetic and rotation. These expectations come from the documented
 * Camera2 relationship and from rotating a known 3x2 image, not from the implementation.
 */
class FrameRotationTest {

    @Test
    fun backCameraUsesSensorMinusDisplay() {
        assertEquals(90, FrameRotation.forBackCamera(90, 0))
        assertEquals(0, FrameRotation.forBackCamera(90, 90))
        assertEquals(270, FrameRotation.forBackCamera(90, 180))
        assertEquals(180, FrameRotation.forBackCamera(90, 270))
        assertEquals(270, FrameRotation.forBackCamera(0, 90))
        assertEquals(180, FrameRotation.forBackCamera(270, 90))
    }

    @Test
    fun everyQuarterTurnHasTheExpectedDimensionsAndPixels() {
        val source = byteArrayOf(1, 2, 3, 4, 5, 6)
        val identity = FrameRotation.rotate(source, 3, 2, 0)
        assertEquals(3, identity.width)
        assertEquals(2, identity.height)
        assertArrayEquals(source, identity.pixels)

        val clockwise = FrameRotation.rotate(source, 3, 2, 90)
        assertEquals(2, clockwise.width)
        assertEquals(3, clockwise.height)
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), clockwise.pixels)

        val upsideDown = FrameRotation.rotate(source, 3, 2, 180)
        assertEquals(3, upsideDown.width)
        assertEquals(2, upsideDown.height)
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), upsideDown.pixels)

        val counterClockwise = FrameRotation.rotate(source, 3, 2, 270)
        assertEquals(2, counterClockwise.width)
        assertEquals(3, counterClockwise.height)
        assertArrayEquals(byteArrayOf(3, 6, 2, 5, 1, 4), counterClockwise.pixels)
    }

    @Test
    fun unsupportedAnglesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameRotation.forBackCamera(45, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameRotation.rotate(byteArrayOf(1), 1, 1, 45)
        }
    }
}
