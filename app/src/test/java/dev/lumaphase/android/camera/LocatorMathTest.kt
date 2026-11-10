package dev.lumaphase.android.camera

import dev.lumaphase.android.layout.ImportedMarker
import dev.lumaphase.android.layout.PointPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure marker matching and homography arithmetic. The expected lists are written out
 * explicitly; this test never calls the implementation to construct its expectation.
 */
class LocatorMathTest {

    @Test
    fun markerCornersAreConsumedInLayoutOrderAndMatchedByOwnCornerOrder() {
        val markers = listOf(
            marker(id = 2, base = 200),
            marker(id = 0, base = 0),
            marker(id = 3, base = 300),
            marker(id = 1, base = 100),
        )
        val detected = mapOf(
            0 to detectedCorners(base = 1000),
            1 to detectedCorners(base = 1100),
            2 to detectedCorners(base = 1200),
            3 to detectedCorners(base = 1300),
        )

        val match = LocatorMath.matchCorners(markers, detected)
        assertTrue(match.complete)
        assertEquals(listOf(2, 0, 3, 1), match.usedMarkerIds)
        assertEquals(16, match.source.size)
        assertEquals(16, match.target.size)

        // Source order follows the marker list, not the image position.
        assertEquals(200.0, match.source[0].x, 0.0)
        assertEquals(200.0, match.source[0].y, 0.0)
        assertEquals(300.0, match.source[8].x, 0.0)
        assertEquals(300.0, match.source[8].y, 0.0)
        // Destination follows each marker's own 0..3 corner order.
        assertEquals(1200.0, match.target[0].x, 0.0)
        assertEquals(1000.0, match.target[4].x, 0.0)
        assertEquals(1300.0, match.target[8].x, 0.0)
        assertEquals(1100.0, match.target[12].x, 0.0)
    }

    @Test
    fun aMissingMarkerInvalidatesTheWholeMatchAndListsTheMissingId() {
        val markers = (0..3).map { marker(it, it * 100) }
        val match = LocatorMath.matchCorners(
            markers,
            mapOf(
                0 to detectedCorners(1000),
                1 to detectedCorners(1100),
                3 to detectedCorners(1300),
            ),
        )
        assertFalse(match.complete)
        assertEquals(listOf(2), match.missingMarkerIds)
        assertEquals(12, match.source.size)
    }

    @Test
    fun identityTranslationAndRuntimeRmsAreExplicit() {
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val point = LocatorMath.applyHomography(identity, 12.0, 34.0)!!
        assertEquals(12.0, point.x, 0.0)
        assertEquals(34.0, point.y, 0.0)

        val translated = doubleArrayOf(1.0, 0.0, 5.0, 0.0, 1.0, -3.0, 0.0, 0.0, 1.0)
        val moved = LocatorMath.applyHomography(translated, 12.0, 34.0)!!
        assertEquals(17.0, moved.x, 0.0)
        assertEquals(31.0, moved.y, 0.0)

        val source = listOf(DoublePoint(12.0, 34.0))
        val target = listOf(DoublePoint(17.0, 31.0))
        assertEquals(0.0, LocatorMath.reprojectionRms(translated, source, target)!!, 1e-12)
        assertNull(
            LocatorMath.applyHomography(
                doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0),
                1.0,
                1.0,
            ),
        )
    }

    private fun marker(id: Int, base: Int) = ImportedMarker(
        id = id,
        dictionary = "DICT_4X4_50",
        markerSizeBits = 4,
        borderBits = 1,
        cellSizePx = 1,
        quietZonePx = 1,
        cornersPx = listOf(
            PointPx(base, base),
            PointPx(base + 10, base),
            PointPx(base + 10, base + 10),
            PointPx(base, base + 10),
        ),
    )

    private fun detectedCorners(base: Int): DoubleArray = doubleArrayOf(
        base.toDouble(), base.toDouble(),
        base + 10.0, base.toDouble(),
        base + 10.0, base + 10.0,
        base.toDouble(), base + 10.0,
    )
}
