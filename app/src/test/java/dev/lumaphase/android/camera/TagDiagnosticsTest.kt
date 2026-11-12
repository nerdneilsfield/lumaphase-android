package dev.lumaphase.android.camera

import dev.lumaphase.android.layout.RectPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagDiagnosticsTest {
    @Test
    fun sixtyBySeventyTwoRectangleHasMinEdgeSixtyAndDiv6Ten() {
        val frame = frame()
        val observation = TagDiagnostics.measure(
            0,
            doubleArrayOf(0.0, 0.0, 60.0, 0.0, 60.0, 72.0, 0.0, 72.0),
            frame,
        )
        assertTrue(observation.detected)
        assertEquals(listOf(60.0, 72.0, 60.0, 72.0), observation.sideLengthsPx)
        assertEquals(60.0, observation.minEdgePx!!, 0.0)
        assertEquals(10.0, observation.edgeMinDiv6Px!!, 0.0)
        assertEquals(4, observation.corners!!.size)
        assertEquals(100, observation.frameWidthPx)
        assertEquals(100, observation.frameHeightPx)
    }

    @Test
    fun missingMarkerHasNullDimensionsAndNoInventedConfidence() {
        val observation = TagDiagnostics.measure(1, null, frame())
        assertFalse(observation.detected)
        assertNull(observation.corners)
        assertNull(observation.sideLengthsPx)
        assertNull(observation.minEdgePx)
        assertNull(observation.edgeMinDiv6Px)
    }

    @Test
    fun targetConstantsDoNotTurnASmallDetectionIntoAFailure() {
        val observation = TagDiagnostics.measure(
            2,
            doubleArrayOf(0.0, 0.0, 6.0, 0.0, 6.0, 6.0, 0.0, 6.0),
            frame(),
        )
        assertTrue(observation.detected)
        assertEquals(1.0, observation.edgeMinDiv6Px!!, 0.0)
        assertTrue(observation.minEdgePx!! < TagDiagnostics.TARGET_MIN_OUTER_EDGE_PX)
        assertTrue(observation.edgeMinDiv6Px!! < TagDiagnostics.TARGET_MIN_CELL_PX)
    }

    @Test
    fun observeKeepsDetectedMarkerEvenWhenAnotherExpectedIdIsMissing() {
        val detected = mapOf(
            0 to doubleArrayOf(0.0, 0.0, 60.0, 0.0, 60.0, 72.0, 0.0, 72.0),
        )
        val observations = TagDiagnostics.observe(listOf(0, 1), detected, frame())
        assertEquals(2, observations.size)
        assertTrue(observations[0].detected)
        assertFalse(observations[1].detected)
        assertNull(observations[1].minEdgePx)
    }

    private fun frame(): GrayFrame = GrayFrame(
        width = 100,
        height = 100,
        pixels = ByteArray(100 * 100),
        rawWidth = 100,
        rawHeight = 100,
        rotationDeg = 0,
        sensorOrientationDeg = 0,
        displayRotationDeg = 0,
        cropPx = RectPx(0, 0, 100, 100),
        timestampNanos = 42L,
    )
}
