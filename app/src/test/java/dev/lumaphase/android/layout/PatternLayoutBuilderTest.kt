package dev.lumaphase.android.layout

import dev.lumaphase.android.protocol.LayoutProfile
import dev.lumaphase.android.protocol.PatternEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry checks for the static layout, written against the OPTC-1 rules rather than
 * against the builder: the module boundary formula, the clockwise rotation table, the
 * marker corners in their own order, and the quiet zone never overlapping the code.
 */
class PatternLayoutBuilderTest {

    @Test
    fun modulesFollowTheContractBoundaryFormulaForEveryRotation() {
        for ((widthPx, heightPx) in drawableCases) {
            for (rotationDeg in PatternLayoutBuilder.ROTATIONS_DEG) {
                for (profile in LayoutProfile.entries) {
                    val layout = build(profile, widthPx, heightPx, rotationDeg)
                    val logical = LogicalView(layout)
                    val label = "${profile.name} ${widthPx}x$heightPx rot=$rotationDeg"

                    assertEquals(label, profile.moduleCount, layout.modules.size)
                    for (index in 0 until profile.moduleCount) {
                        val start = index * layout.patternWidthPx / profile.moduleCount
                        val end = (index + 1) * layout.patternWidthPx / profile.moduleCount
                        val expected = logical.map(
                            RectPx(start, logical.blockPx, end, layout.patternHeightPx - logical.blockPx),
                        )
                        assertEquals("$label module $index", expected, layout.modules[index].rectPx)
                        assertEquals("$label module index $index", index, layout.modules[index].index)
                    }

                    val union = layout.modules
                        .map { it.rectPx }
                        .reduce { left, right -> left.union(right) }
                    assertEquals("$label module union", layout.dynamicRectPx, union)

                    val areaSum = layout.modules.sumOf { it.rectPx.areaPx }
                    assertEquals(
                        "$label modules must tile the dynamic area without gap or overlap",
                        layout.dynamicRectPx.areaPx,
                        areaSum,
                    )

                    val sizes = layout.modules.map { if (rotationDeg % 180 == 0) it.rectPx.widthPx else it.rectPx.heightPx }
                    assertTrue("$label module widths differ by more than one pixel", sizes.max() - sizes.min() <= 1)
                }
            }
        }
    }

    @Test
    fun nonDivisiblePatternWidthsAreExercised() {
        val nonDivisible = drawableCases.count { (widthPx, _) ->
            widthPx * PatternLayoutBuilder.WIDTH_FRACTION_NUMERATOR / PatternLayoutBuilder.WIDTH_FRACTION_DENOMINATOR %
                LayoutProfile.F68.moduleCount != 0
        }
        assertTrue("no test case exercises a non-divisible pattern width", nonDivisible > 0)

        val layout = build(LayoutProfile.F68, 1080, 2400, 0)
        assertEquals(1036, layout.patternWidthPx)
        assertTrue("1036 must not be divisible by 68", 1036 % 68 != 0)
    }

    @Test
    fun matrixCentresTheRotatedCanvasAndAgreesWithTheTestRotationTable() {
        for ((widthPx, heightPx) in drawableCases) {
            for (rotationDeg in PatternLayoutBuilder.ROTATIONS_DEG) {
                val layout = build(LayoutProfile.C52, widthPx, heightPx, rotationDeg)
                val logical = LogicalView(layout)
                val matrix = layout.patternToDrawable
                val label = "${widthPx}x$heightPx rot=$rotationDeg"

                val mapped = listOf(
                    PointPx(0, 0),
                    PointPx(layout.patternWidthPx, 0),
                    PointPx(layout.patternWidthPx, layout.patternHeightPx),
                    PointPx(0, layout.patternHeightPx),
                ).map { logical.map(it) }
                assertEquals("$label left edge", logical.offsetX, mapped.minOf { it.x })
                assertEquals("$label top edge", logical.offsetY, mapped.minOf { it.y })
                assertEquals("$label right edge", logical.offsetX + logical.rotatedWidthPx, mapped.maxOf { it.x })
                assertEquals("$label bottom edge", logical.offsetY + logical.rotatedHeightPx, mapped.maxOf { it.y })

                for (point in listOf(PointPx(0, 0), PointPx(1, 7), PointPx(13, 29))) {
                    val fromMatrix = PointPx(
                        matrix[0] * point.x + matrix[1] * point.y + matrix[2],
                        matrix[3] * point.x + matrix[4] * point.y + matrix[5],
                    )
                    assertEquals("$label matrix vs rotation table for $point", logical.map(point), fromMatrix)
                }
            }
        }
    }

    @Test
    fun c52UsesTheSameDynamicAreaWithFiftyTwoBands() {
        val f68 = build(LayoutProfile.F68, 1080, 2400, 0)
        val c52 = build(LayoutProfile.C52, 1080, 2400, 0)
        assertEquals(68, f68.modules.size)
        assertEquals(52, c52.modules.size)
        // C52 keeps the first 52 module indices but is its own layout: the boundary
        // formula uses M = 52, so its bands are wider than the F68 bands.
        assertEquals(f68.dynamicRectPx, c52.dynamicRectPx)
        val logical = LogicalView(c52)
        c52.modules.forEachIndexed { index, module ->
            val start = index * c52.patternWidthPx / LayoutProfile.C52.moduleCount
            val end = (index + 1) * c52.patternWidthPx / LayoutProfile.C52.moduleCount
            val expected = logical.map(
                RectPx(start, logical.blockPx, end, c52.patternHeightPx - logical.blockPx),
            )
            assertEquals(expected, module.rectPx)
        }
        assertTrue(
            "C52 bands must be wider than F68 bands over the same area",
            c52.modules[0].rectPx.widthPx > f68.modules[0].rectPx.widthPx,
        )
    }

    @Test
    fun markersSitOnTheLogicalCornersAndKeepTheirOwnCornerOrder() {
        for ((widthPx, heightPx) in drawableCases) {
            for (rotationDeg in PatternLayoutBuilder.ROTATIONS_DEG) {
                val layout = build(LayoutProfile.F68, widthPx, heightPx, rotationDeg)
                val logical = LogicalView(layout)
                val label = "${widthPx}x$heightPx rot=$rotationDeg"

                assertEquals(listOf(0, 1, 2, 3), layout.markers.map { it.id })
                assertEquals(
                    listOf(0, 1, 2, 3).map { logicalMarkerOuter(layout, it) },
                    layout.markers.map { marker ->
                        val own = marker.cornersPx.map { logical.unmap(it) }
                        RectPx(
                            own.minOf { it.x },
                            own.minOf { it.y },
                            own.maxOf { it.x },
                            own.maxOf { it.y },
                        )
                    },
                )

                for (marker in layout.markers) {
                    val own = marker.cornersPx.map { logical.unmap(it) }
                    val left = own.minOf { it.x }
                    val top = own.minOf { it.y }
                    val right = own.maxOf { it.x }
                    val bottom = own.maxOf { it.y }
                    assertEquals(
                        "$label marker ${marker.id} corners must stay in the marker's own order",
                        listOf(
                            PointPx(left, top),
                            PointPx(right, top),
                            PointPx(right, bottom),
                            PointPx(left, bottom),
                        ),
                        own,
                    )
                }
            }
        }
    }

    @Test
    fun quietZonesAndCodeNeverOverlap() {
        for ((widthPx, heightPx) in drawableCases) {
            for (rotationDeg in PatternLayoutBuilder.ROTATIONS_DEG) {
                val layout = build(LayoutProfile.F68, widthPx, heightPx, rotationDeg)
                val cell = layout.markers.first().cellSizePx
                val label = "${widthPx}x$heightPx rot=$rotationDeg"

                for (marker in layout.markers) {
                    assertEquals("$label default id", ArUcoDict4x4_50.DICTIONARY, marker.dictionary)
                    assertEquals(4, marker.markerSizeBits)
                    assertEquals(1, marker.borderBits)
                    assertEquals(cell, marker.quietZonePx)
                    assertEquals(6 * cell, marker.outerRectPx.widthPx)
                    assertEquals(6 * cell, marker.outerRectPx.heightPx)
                    assertEquals(16, marker.cellRects.size)
                    assertEquals(16, marker.cellValues.size)
                    assertTrue(
                        "$label marker ${marker.id} quiet zone must surround the marker",
                        marker.quietRectPx.contains(marker.outerRectPx),
                    )
                    assertTrue(
                        "$label marker ${marker.id} quiet zone must not touch the code",
                        !marker.quietRectPx.intersects(layout.dynamicRectPx),
                    )
                    marker.cellRects.forEach { cellRect ->
                        assertEquals(cell, cellRect.widthPx)
                        assertEquals(cell, cellRect.heightPx)
                        assertTrue(
                            "$label marker cell must stay inside its black edge",
                            marker.outerRectPx.contains(cellRect),
                        )
                        assertTrue(!cellRect.intersects(layout.dynamicRectPx))
                    }
                }

                for (first in layout.markers.indices) {
                    for (second in first + 1 until layout.markers.size) {
                        val a = layout.markers[first]
                        val b = layout.markers[second]
                        assertTrue(
                            "$label markers ${a.id} and ${b.id} overlap",
                            !a.quietRectPx.intersects(b.quietRectPx),
                        )
                    }
                }

                layout.modules.forEach { module ->
                    layout.markers.forEach { marker ->
                        assertTrue(
                            "$label module ${module.index} overlaps marker ${marker.id}",
                            !module.rectPx.intersects(marker.quietRectPx) &&
                                !module.rectPx.intersects(marker.outerRectPx),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun markerSixBySixGridMatchesTheDictionaryInEveryRotation() {
        for ((widthPx, heightPx) in drawableCases) {
            for (rotationDeg in PatternLayoutBuilder.ROTATIONS_DEG) {
                val layout = build(LayoutProfile.F68, widthPx, heightPx, rotationDeg)
                val logical = LogicalView(layout)
                val pattern = PatternEncoder.encode(7uL, LayoutProfile.F68)
                val painted = paintRects(layout, pattern)
                val cell = layout.markers.first().cellSizePx
                val label = "${widthPx}x$heightPx rot=$rotationDeg"

                for (marker in layout.markers) {
                    // Stated in the marker's own coordinates: one black cell on each of the
                    // four sides, and the inner 4x4 equal to the DICT_4X4_50 bitmap.
                    val own = logicalMarkerOuter(layout, marker.id)
                    val bitmap = ArUcoDict4x4_50.bitmap(marker.id)
                    for (row in 0 until 6) {
                        for (column in 0 until 6) {
                            val point = logical.map(
                                PointPx(
                                    own.x0 + column * cell + cell / 2,
                                    own.y0 + row * cell + cell / 2,
                                ),
                            )
                            val level = painted.lastOrNull { it.rect.containsPoint(point) }?.level
                            val onBorder = row == 0 || row == 5 || column == 0 || column == 5
                            val expected = if (onBorder) {
                                MARKER_BLACK_LEVEL
                            } else if (bitmap[row - 1][column - 1] == 1) {
                                MARKER_WHITE_LEVEL
                            } else {
                                MARKER_BLACK_LEVEL
                            }
                            assertEquals(
                                "$label marker ${marker.id} own cell (row=$row,col=$column)",
                                expected,
                                level,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun layoutFieldsMatchTheContract() {
        val layout = build(LayoutProfile.F68, 1080, 2400, 90)
        assertEquals(PatternLayoutBuilder.STATIC_LAYOUT_ID, layout.layoutId)
        assertEquals("DRAWABLE_TOP_LEFT_PIXEL_EDGES", layout.coordinateSystem)
        assertEquals(9, layout.patternToDrawable.size)
        assertEquals(PatternEncoder.DEFAULT_LEVEL_LOW, layout.levelLow)
        assertEquals(PatternEncoder.DEFAULT_LEVEL_HIGH, layout.levelHigh)
        assertEquals(32, layout.levelLow)
        assertEquals(224, layout.levelHigh)
        assertEquals(1080, layout.drawableWidthPx)
        assertEquals(2400, layout.drawableHeightPx)
        assertEquals(90, layout.patternRotationDeg)
        // The 96%/68% canvas fills the drawable; a quarter turn swaps its extents.
        assertEquals(1632, layout.patternWidthPx)
        assertEquals(1036, layout.patternHeightPx)
    }

    @Test
    fun goldenGeometryFor1080x2400Upright() {
        // Numbers derived independently from the OPTC-1 rules and the documented layout
        // constants (96%/68% canvas, cell = shorter side / 48, quiet zone = one cell).
        val layout = build(LayoutProfile.F68, 1080, 2400, 0)

        assertEquals(1036, layout.patternWidthPx)
        assertEquals(1632, layout.patternHeightPx)
        assertEquals(listOf(1, 0, 22, 0, 1, 384, 0, 0, 1), layout.patternToDrawable)

        val cell = 21
        assertEquals(cell, layout.markers.first().cellSizePx)
        assertEquals(cell, layout.markers.first().quietZonePx)
        assertEquals(6 * cell, layout.markers.first().outerRectPx.widthPx)

        assertEquals(RectPx(22, 552, 1058, 1848), layout.dynamicRectPx)
        assertEquals(RectPx(22, 552, 37, 1848), layout.modules[0].rectPx)
        assertEquals(RectPx(37, 552, 52, 1848), layout.modules[1].rectPx)
        assertEquals(RectPx(1042, 552, 1058, 1848), layout.modules[67].rectPx)

        val markers = layout.markers.associateBy { it.id }
        assertEquals(RectPx(22, 384, 190, 552), markers.getValue(0).quietRectPx)
        assertEquals(RectPx(43, 405, 169, 531), markers.getValue(0).outerRectPx)
        assertEquals(
            listOf(PointPx(43, 405), PointPx(169, 405), PointPx(169, 531), PointPx(43, 531)),
            markers.getValue(0).cornersPx,
        )
        assertEquals(RectPx(911, 405, 1037, 531), markers.getValue(1).outerRectPx)
        assertEquals(RectPx(911, 1869, 1037, 1995), markers.getValue(2).outerRectPx)
        assertEquals(RectPx(43, 1869, 169, 1995), markers.getValue(3).outerRectPx)
    }

    @Test
    fun rejectsDrawablesThatCannotHoldTheModules() {
        val failure = runCatching { build(LayoutProfile.F68, 60, 400, 0) }.exceptionOrNull()
        assertNotNull("a 57 px wide pattern must be rejected", failure)
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun paintRectsMatchTheExportedLayoutAndLevels() {
        for ((widthPx, heightPx) in drawableCases) {
            for (rotationDeg in PatternLayoutBuilder.ROTATIONS_DEG) {
                val layout = build(LayoutProfile.F68, widthPx, heightPx, rotationDeg)
                val pattern = PatternEncoder.encode(7uL, LayoutProfile.F68)
                val rects = paintRects(layout, pattern)
                val label = "${widthPx}x$heightPx rot=$rotationDeg"

                assertEquals(
                    "$label painted rectangle count",
                    layout.modules.size + layout.markers.size * 18,
                    rects.size,
                )
                val drawable = RectPx(0, 0, widthPx, heightPx)
                rects.forEach { painted ->
                    assertTrue("$label $painted leaves the drawable", drawable.contains(painted.rect))
                    assertTrue(
                        "$label unexpected level ${painted.level}",
                        painted.level == MARKER_BLACK_LEVEL ||
                            painted.level == MARKER_WHITE_LEVEL ||
                            painted.level == PatternEncoder.DEFAULT_LEVEL_LOW ||
                            painted.level == PatternEncoder.DEFAULT_LEVEL_HIGH,
                    )
                }
                layout.modules.forEachIndexed { index, module ->
                    assertTrue(
                        "$label module $index is not painted with its code value",
                        rects.any { it.rect == module.rectPx && it.level == pattern.moduleCodes[index] },
                    )
                }
                layout.markers.forEach { marker ->
                    assertTrue(rects.any { it.rect == marker.quietRectPx && it.level == MARKER_WHITE_LEVEL })
                    assertTrue(rects.any { it.rect == marker.outerRectPx && it.level == MARKER_BLACK_LEVEL })
                }
            }
        }
    }

    private fun build(
        profile: LayoutProfile,
        widthPx: Int,
        heightPx: Int,
        rotationDeg: Int,
    ): PatternLayout = PatternLayoutBuilder.build(
        PatternLayoutBuilder.STATIC_LAYOUT_ID,
        profile,
        widthPx,
        heightPx,
        rotationDeg,
    )

    /**
     * Marker black outer edge in the logical canvas, re-derived from the corner rule
     * (id 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left, each inset by one cell).
     */
    private fun logicalMarkerOuter(layout: PatternLayout, id: Int): RectPx {
        val cell = layout.markers.first().cellSizePx
        val outer = cell * 6
        val width = layout.patternWidthPx
        val height = layout.patternHeightPx
        return when (id) {
            0 -> RectPx(cell, cell, cell + outer, cell + outer)
            1 -> RectPx(width - cell - outer, cell, width - cell, cell + outer)
            2 -> RectPx(width - cell - outer, height - cell - outer, width - cell, height - cell)
            3 -> RectPx(cell, height - cell - outer, cell + outer, height - cell)
            else -> throw IllegalArgumentException("unexpected marker id $id")
        }
    }

    private fun RectPx.containsPoint(point: PointPx): Boolean =
        point.x >= x0 && point.x < x1 && point.y >= y0 && point.y < y1

    private val RectPx.areaPx: Int get() = widthPx * heightPx

    /**
     * Re-derives the logical canvas geometry from the layout's own fields using the
     * OPTC-1 rotation table, so the rotation applied by the builder is checked against an
     * independent implementation.
     */
    private class LogicalView(val layout: PatternLayout) {
        val cellSizePx: Int = layout.markers.first().cellSizePx
        val blockPx: Int = (ArUcoDict4x4_50.MARKER_SIZE_BITS + 2 * ArUcoDict4x4_50.BORDER_BITS) * cellSizePx +
            2 * cellSizePx
        val rotatedWidthPx: Int
        val rotatedHeightPx: Int
        val offsetX: Int
        val offsetY: Int

        init {
            val quarterTurn = layout.patternRotationDeg % 180 != 0
            rotatedWidthPx = if (quarterTurn) layout.patternHeightPx else layout.patternWidthPx
            rotatedHeightPx = if (quarterTurn) layout.patternWidthPx else layout.patternHeightPx
            offsetX = (layout.drawableWidthPx - rotatedWidthPx) / 2
            offsetY = (layout.drawableHeightPx - rotatedHeightPx) / 2
        }

        fun map(point: PointPx): PointPx = when (layout.patternRotationDeg) {
            0 -> PointPx(point.x + offsetX, point.y + offsetY)
            90 -> PointPx(layout.patternHeightPx - point.y + offsetX, point.x + offsetY)
            180 -> PointPx(
                layout.patternWidthPx - point.x + offsetX,
                layout.patternHeightPx - point.y + offsetY,
            )
            270 -> PointPx(point.y + offsetX, layout.patternWidthPx - point.x + offsetY)
            else -> throw IllegalArgumentException("unexpected rotation ${layout.patternRotationDeg}")
        }

        fun unmap(point: PointPx): PointPx = when (layout.patternRotationDeg) {
            0 -> PointPx(point.x - offsetX, point.y - offsetY)
            90 -> PointPx(point.y - offsetY, layout.patternHeightPx + offsetX - point.x)
            180 -> PointPx(
                layout.patternWidthPx + offsetX - point.x,
                layout.patternHeightPx + offsetY - point.y,
            )
            270 -> PointPx(layout.patternWidthPx + offsetY - point.y, point.x - offsetX)
            else -> throw IllegalArgumentException("unexpected rotation ${layout.patternRotationDeg}")
        }

        fun map(rect: RectPx): RectPx {
            val corners = listOf(
                map(PointPx(rect.x0, rect.y0)),
                map(PointPx(rect.x1, rect.y0)),
                map(PointPx(rect.x1, rect.y1)),
                map(PointPx(rect.x0, rect.y1)),
            )
            return RectPx(
                corners.minOf { it.x },
                corners.minOf { it.y },
                corners.maxOf { it.x },
                corners.maxOf { it.y },
            )
        }
    }

    private companion object {
        val drawableCases = listOf(
            1080 to 2400,
            2400 to 1080,
            1000 to 700,
            640 to 640,
        )
    }
}
