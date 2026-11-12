package dev.lumaphase.android.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-002 large-tag RUN layout: the payload geometry is unchanged, only the four tags use the
 * free long-edge bands. These checks use the actual builder, not a copy of its constants.
 */
class LargeTagLayoutTest {

    private val rotations = PatternLayoutBuilder.ROTATIONS_DEG

    @Test
    fun largeTagsKeepDynamicModulesAndPaddingPixelIdentical() {
        for (choice in listOf(LayoutChoice.F68_GRID_LARGE, LayoutChoice.C52_GRID_LARGE)) {
            for (rotation in rotations) {
                val standard = buildStandard(choice, 1080, 2400, rotation)
                val large = buildChoice(choice, 1080, 2400, rotation)
                val label = "${choice.wire} ${standard.geometry.wire} r$rotation"
                assertEquals("$label drawable width", standard.drawableWidthPx, large.drawableWidthPx)
                assertEquals("$label drawable height", standard.drawableHeightPx, large.drawableHeightPx)
                assertEquals("$label geometry", standard.geometry, large.geometry)
                assertEquals("$label profile", standard.profile, large.profile)
                assertEquals("$label dynamic", standard.dynamicRectPx, large.dynamicRectPx)
                assertEquals("$label modules", standard.modules, large.modules)
                assertEquals("$label padding", standard.padding, large.padding)
                assertEquals("$label levels", standard.levelLow to standard.levelHigh, large.levelLow to large.levelHigh)
                assertEquals("$label pattern", standard.patternWidthPx to standard.patternHeightPx, large.patternWidthPx to large.patternHeightPx)
                assertEquals("$label marker count", 4, large.markers.size)
                assertNotEquals("$label tags must be larger", standard.markers.first().cellSizePx, large.markers.first().cellSizePx)
            }
        }
    }

    @Test
    fun portrait1080x2400UsesTheTopAndBottomFreeBandsAtEveryRotation() {
        for (choice in listOf(LayoutChoice.F68_GRID_LARGE, LayoutChoice.C52_GRID_LARGE)) {
            for (rotation in rotations) {
                val layout = buildChoice(choice, 1080, 2400, rotation)
                val expectedCell = if (rotation % 180 == 0) 64 else 48
                val expectedBlock = 8 * expectedCell
                val label = "${choice.wire} r$rotation"

                assertEquals("$label cell", expectedCell, layout.markers.first().cellSizePx)
                assertEquals("$label quiet cell", expectedCell, layout.markers.first().quietZonePx)
                layout.markers.forEach { marker ->
                    assertEquals("$label marker ${marker.id} block", expectedBlock, marker.quietRectPx.widthPx)
                    assertEquals("$label marker ${marker.id} block", expectedBlock, marker.quietRectPx.heightPx)
                    assertEquals("$label marker ${marker.id} outer width", 6 * expectedCell, marker.outerRectPx.widthPx)
                    // At least one white cell on every side of the black edge.
                    assertEquals(expectedCell, marker.outerRectPx.x0 - marker.quietRectPx.x0)
                    assertEquals(expectedCell, marker.quietRectPx.x1 - marker.outerRectPx.x1)
                    assertEquals(expectedCell, marker.outerRectPx.y0 - marker.quietRectPx.y0)
                    assertEquals(expectedCell, marker.quietRectPx.y1 - marker.outerRectPx.y1)
                    assertTrue(
                        "$label marker ${marker.id} must stay above or below the dynamic area",
                        marker.quietRectPx.y1 <= layout.dynamicRectPx.y0 ||
                            marker.quietRectPx.y0 >= layout.dynamicRectPx.y1,
                    )
                    assertFalse(
                        "$label marker ${marker.id} must not intrude into dynamic",
                        marker.quietRectPx.intersects(layout.dynamicRectPx),
                    )
                }
                for (first in layout.markers.indices) {
                    for (second in first + 1 until layout.markers.size) {
                        assertFalse(
                            "$label markers ${layout.markers[first].id}/${layout.markers[second].id} overlap",
                            layout.markers[first].quietRectPx.intersects(layout.markers[second].quietRectPx),
                        )
                    }
                }
                // The two tags across the physical short side keep the requested ~2% margin.
                assertEquals("$label left edge", 21, layout.markers.minOf { it.quietRectPx.x0 })
                assertEquals("$label right edge", 1059, layout.markers.maxOf { it.quietRectPx.x1 })
            }
        }
    }

    @Test
    fun portraitRotationZeroHasExactLargeTagRectangles() {
        val layout = buildChoice(LayoutChoice.F68_GRID_LARGE, 1080, 2400, 0)
        val markers = layout.markers.associateBy { it.id }
        // dynamic is [22,552,1058,1848]; the top band is 552 px and the block is 512 px.
        assertEquals(RectPx(21, 20, 533, 532), markers.getValue(0).quietRectPx)
        assertEquals(RectPx(547, 20, 1059, 532), markers.getValue(1).quietRectPx)
        assertEquals(RectPx(547, 1868, 1059, 2380), markers.getValue(2).quietRectPx)
        assertEquals(RectPx(21, 1868, 533, 2380), markers.getValue(3).quietRectPx)
        // Black outer = 6 cells (384 px), inset one 64 px quiet cell.
        assertEquals(RectPx(85, 84, 469, 468), markers.getValue(0).outerRectPx)
        assertEquals(
            listOf(PointPx(85, 84), PointPx(469, 84), PointPx(469, 468), PointPx(85, 468)),
            markers.getValue(0).cornersPx,
        )
    }

    @Test
    fun everyLargeTagExportsTheOfficialSixteenCellsInItsOwnOrder() {
        for (choice in listOf(LayoutChoice.F68_GRID_LARGE, LayoutChoice.C52_GRID_LARGE)) {
            for (rotation in rotations) {
                val layout = buildChoice(choice, 1080, 2400, rotation)
                val fullWidth = layout.patternWidthPx
                val fullHeight = layout.patternHeightPx
                val cell = layout.markers.first().cellSizePx
                val label = "${choice.wire} r$rotation"
                layout.markers.forEach { marker ->
                    val bitmap = ArUcoDict4x4_50.bitmap(marker.id)
                    assertEquals("$label marker ${marker.id} cell count", 16, marker.cellRects.size)
                    assertEquals("$label marker ${marker.id} value count", 16, marker.cellValues.size)
                    val logicalCenters = marker.cellRects.map { cellRect ->
                        val centerX = (cellRect.x0 + cellRect.x1) / 2
                        val centerY = (cellRect.y0 + cellRect.y1) / 2
                        inversePoint(rotation, fullWidth, fullHeight, centerX, centerY)
                    }
                    val origin = logicalCenters.first()
                    for (row in 0 until 4) {
                        for (col in 0 until 4) {
                            val index = row * 4 + col
                            val center = logicalCenters[index]
                            assertEquals("$label marker ${marker.id} cell $index x", origin.x + col * cell, center.x)
                            assertEquals("$label marker ${marker.id} cell $index y", origin.y + row * cell, center.y)
                            assertEquals(
                                "$label marker ${marker.id} cell $index value",
                                bitmap[row][col],
                                marker.cellValues[index],
                            )
                        }
                    }
                    // The black outer edge contains every cell with a one-cell border.
                    marker.cellRects.forEach { cellRect ->
                        assertTrue(
                            "$label marker ${marker.id} cell $cellRect leaves outer",
                            marker.outerRectPx.contains(cellRect),
                        )
                    }
                }
            }
        }
        // Rotation 0: the first data cell is the outer edge plus the one-cell black border.
        val rotatedZero = buildChoice(LayoutChoice.F68_GRID_LARGE, 1080, 2400, 0)
        val firstMarker = rotatedZero.markers.first()
        assertEquals(RectPx(149, 148, 213, 212), firstMarker.cellRects.first())
    }

    @Test
    fun landscapeRepresentativeAlsoKeepsPayloadAndEnlargesTags() {
        for ((choice, standardChoice) in listOf(
            LayoutChoice.F68_GRID_LARGE to LayoutChoice.F68_GRID,
            LayoutChoice.C52_GRID_LARGE to LayoutChoice.C52_GRID,
        )) {
            for (rotation in rotations) {
                val standard = buildChoice(standardChoice, 2400, 1080, rotation)
                val large = buildChoice(choice, 2400, 1080, rotation)
                val label = "${choice.wire} landscape r$rotation"
                assertEquals("$label geometry", standard.geometry, large.geometry)
                assertEquals("$label dynamic", standard.dynamicRectPx, large.dynamicRectPx)
                assertEquals("$label modules", standard.modules, large.modules)
                assertEquals("$label padding", standard.padding, large.padding)
                if (rotation % 180 == 0) {
                    assertEquals("$label cell", 36, large.markers.first().cellSizePx)
                } else {
                    assertEquals("$label cell", 21, large.markers.first().cellSizePx)
                }
                assertTrue("$label must enlarge", large.markers.first().cellSizePx > standard.markers.first().cellSizePx)
            }
        }
    }

    @Test
    fun cr002FixedThreeTimesRejectsALargeTagRunInsteadOfUsingTheOldChoice() {
        // Standard landscape RUN still satisfies the unchanged CR-002 3x cell contract.
        val standardRun = buildChoice(LayoutChoice.F68_GRID, 2400, 1080, 0)
        CalibrationTargetBuilder.build("target-standard", standardRun)

        // The large-tag RUN has a 36 px cell, so 3x would need 108 px cells; the target
        // builder must reject it explicitly rather than silently rebuild from an old choice.
        val largeRun = buildChoice(LayoutChoice.F68_GRID_LARGE, 2400, 1080, 0)
        assertEquals(36, largeRun.markers.first().cellSizePx)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            CalibrationTargetBuilder.build("target-large", largeRun)
        }
        assertTrue(failure.message!!.contains("does not fit at fixed 3x"))
    }

    @Test
    fun oldChoicesAndBuilderApiKeepTheOriginalTags() {
        assertFalse(LayoutChoice.F68_GRID.largeTags)
        assertFalse(LayoutChoice.C52_GRID.largeTags)
        assertFalse(LayoutChoice.F68_SINGLE.largeTags)
        assertFalse(LayoutChoice.C52_SINGLE.largeTags)
        val standard = buildChoice(LayoutChoice.F68_GRID, 1080, 2400, 0)
        assertEquals(21, standard.markers.first().cellSizePx)
        assertEquals(RectPx(22, 384, 190, 552), standard.markers.first().quietRectPx)
        val explicitlyOld = PatternLayoutBuilder.build(
            "explicit-old",
            GeometryProfile.F68_G9X8,
            1080,
            2400,
            0,
        )
        assertEquals(standard.copy(layoutId = "explicit-old"), explicitlyOld)
    }

    private fun buildChoice(
        choice: LayoutChoice,
        widthPx: Int,
        heightPx: Int,
        rotationDeg: Int,
    ): PatternLayout = PatternLayoutBuilder.build(
        "${choice.wire}-$widthPx-$heightPx-r$rotationDeg",
        choice,
        widthPx,
        heightPx,
        rotationDeg,
    )

    private fun buildStandard(
        choice: LayoutChoice,
        widthPx: Int,
        heightPx: Int,
        rotationDeg: Int,
    ): PatternLayout = PatternLayoutBuilder.build(
        "standard-${choice.wire}-$widthPx-$heightPx-r$rotationDeg",
        choice.geometryFor(widthPx, heightPx),
        widthPx,
        heightPx,
        rotationDeg,
    )

    /** Independent rotation inverse, used only to verify the exported cell order. */
    private fun inversePoint(
        rotationDeg: Int,
        patternWidthPx: Int,
        patternHeightPx: Int,
        x: Int,
        y: Int,
    ): PointPx = when (rotationDeg) {
        0 -> PointPx(x, y)
        90 -> PointPx(y, patternHeightPx - x)
        180 -> PointPx(patternWidthPx - x, patternHeightPx - y)
        270 -> PointPx(patternWidthPx - y, x)
        else -> throw IllegalArgumentException("unsupported rotation $rotationDeg")
    }
}
