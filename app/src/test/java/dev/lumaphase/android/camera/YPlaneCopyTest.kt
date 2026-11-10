package dev.lumaphase.android.camera

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Stride/crop/buffer-position checks for the Y copy. The expectations are computed from the
 * explicitly filled buffer, not from the copy implementation.
 */
class YPlaneCopyTest {

    @Test
    fun rowStrideCropAndBufferPositionAreHonoured() {
        val buffer = ByteBuffer.allocate(30)
        for (index in 0 until buffer.capacity()) buffer.put(index, index.toByte())
        buffer.position(2)

        val copied = YPlaneCopy.copy(
            buffer = buffer,
            cropLeft = 1,
            cropTop = 1,
            cropWidth = 2,
            cropHeight = 2,
            rowStride = 5,
            pixelStride = 1,
        )

        assertArrayEquals(byteArrayOf(8, 9, 13, 14), copied)
        assertEquals("absolute reads must not move the source buffer", 2, buffer.position())
    }

    @Test
    fun pixelStrideIsApplied() {
        val buffer = ByteBuffer.allocate(24)
        for (index in 0 until buffer.capacity()) buffer.put(index, (index * 2).toByte())

        val copied = YPlaneCopy.copy(
            buffer = buffer,
            cropLeft = 0,
            cropTop = 0,
            cropWidth = 2,
            cropHeight = 2,
            rowStride = 8,
            pixelStride = 2,
        )

        assertArrayEquals(byteArrayOf(0, 4, 16, 20), copied)
    }

    @Test
    fun aShortBufferIsRejectedInsteadOfReadingPastTheLimit() {
        val buffer = ByteBuffer.allocate(6)
        assertThrows(IllegalArgumentException::class.java) {
            YPlaneCopy.copy(buffer, 0, 0, 3, 3, 3, 1)
        }
    }
}
