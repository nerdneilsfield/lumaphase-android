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
                        val start = index * logical.localWidthPx / profile.moduleCount
                        val end = (index + 1) * logical.localWidthPx / profile.moduleCount
                        val expected = logical.map(
                            RectPx(
                                start,
                                logical.blockPx,
                                end,
                                logical.localHeightPx - logical.blockPx,
                            ),
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
        assertEquals(1036, LogicalView(layout).localWidthPx)
        assertEquals(1080, layout.patternWidthPx)
        assertTrue("1036 must not be divisible by 68", 1036 % 68 != 0)
    }

    @Test
    fun publicCanvasIsTheFullDrawableInverseRotationAndTheMatrixIsPureRotation() {
        for ((widthPx, heightPx) in drawableCases) {
            for (rotationDeg in PatternLayoutBuilder.ROTATIONS_DEG) {
                val layout = build(LayoutProfile.C52, widthPx, heightPx, rotationDeg)
                val quarterTurn = rotationDeg % 180 != 0
                val expectedWidth = if (quarterTurn) heightPx else widthPx
                val expectedHeight = if (quarterTurn) widthPx else heightPx
                val label = "${widthPx}x$heightPx rot=$rotationDeg"

                assertEquals("$label full pattern width", expectedWidth, layout.patternWidthPx)
                assertEquals("$label full pattern height", expectedHeight, layout.patternHeightPx)
                assertEquals(
                    "$label pure rotation matrix",
                    patternToDrawableMatrix(rotationDeg, expectedWidth, expectedHeight, 0, 0).toList(),
                    layout.patternToDrawable,
                )

                val matrix = layout.patternToDrawable
                val corners = listOf(
                    PointPx(0, 0),
                    PointPx(expectedWidth, 0),
                    PointPx(expectedWidth, expectedHeight),
                    PointPx(0, expectedHeight),
                ).map { point ->
                    PointPx(
                        matrix[0] * point.x + matrix[1] * point.y + matrix[2],
                        matrix[3] * point.x + matrix[4] * point.y + matrix[5],
                    )
                }
                assertEquals("$label full canvas left", 0, corners.minOf { it.x })
                assertEquals("$label full canvas top", 0, corners.minOf { it.y })
                assertEquals("$label full canvas right", widthPx, corners.maxOf { it.x })
                assertEquals("$label full canvas bottom", heightPx, corners.maxOf { it.y })

                // The actual design region is still the smaller 96%/68% area, centred in the
                // drawable. Its local mapping must be unaffected by the public full canvas.
                val logical = LogicalView(layout)
                val localCorners = listOf(
                    PointPx(0, 0),
                    PointPx(logical.localWidthPx, 0),
                    PointPx(logical.localWidthPx, logical.localHeightPx),
                    PointPx(0, logical.localHeightPx),
                ).map { logical.map(it) }
                assertEquals("$label local left", logical.offsetX, localCorners.minOf { it.x })
                assertEquals("$label local top", logical.offsetY, localCorners.minOf { it.y })
                assertEquals(
                    "$label local right",
                    logical.offsetX + logical.rotatedWidthPx,
                    localCorners.maxOf { it.x },
                )
                assertEquals(
                    "$label local bottom",
                    logical.offsetY + logical.rotatedHeightPx,
                    localCorners.maxOf { it.y },
                )
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
            val start = index * logical.localWidthPx / LayoutProfile.C52.moduleCount
            val end = (index + 1) * logical.localWidthPx / LayoutProfile.C52.moduleCount
            val expected = logical.map(
                RectPx(
                    start,
                    logical.blockPx,
                    end,
                    logical.localHeightPx - logical.blockPx,
                ),
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
        // The public canvas is the complete inverse-quarter-turn drawable, not the internal
        // 96%/68% design region; a quarter turn swaps the drawable's extents.
        assertEquals(2400, layout.patternWidthPx)
        assertEquals(1080, layout.patternHeightPx)
    }

    @Test
    fun goldenGeometryFor1080x2400Upright() {
        // Numbers derived independently from the OPTC-1 rules and the documented layout
        // constants (96%/68% canvas, cell = shorter side / 48, quiet zone = one cell).
        val layout = build(LayoutProfile.F68, 1080, 2400, 0)

        assertEquals(1080, layout.patternWidthPx)
        assertEquals(2400, layout.patternHeightPx)
        assertEquals(listOf(1, 0, 0, 0, 1, 0, 0, 0, 1), layout.patternToDrawable)

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
                    layout.modules.size + layout.padding.size + layout.markers.size * 18,
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

    @Test
    fun gridChoiceFollowsTheSurfaceOrientationForEveryRotation() {
        // Portrait targets, including a square; 1536x2048 keeps a 3:4 aspect that is not the
        // Pixel default. Every rotation must keep the same surface-based choice.
        val portraitSurfaces = listOf(
            1080 to 1920,
            1440 to 2560,
            1536 to 2048,
            1080 to 1080,
        )
        val landscapeSurfaces = listOf(
            1920 to 1080,
            2560 to 1440,
            2048 to 1536,
        )
        for ((widthPx, heightPx) in portraitSurfaces) {
            for (rotationDeg in PatternLayoutBuilder.ROTATIONS_DEG) {
                val label = "portrait ${widthPx}x$heightPx rot=$rotationDeg"
                assertEquals(
                    label,
                    GeometryProfile.F68_G9X8,
                    buildChoice(LayoutChoice.F68_GRID, widthPx, heightPx, rotationDeg).geometry,
                )
                assertEquals(
                    label,
                    GeometryProfile.C52_G9X6,
                    buildChoice(LayoutChoice.C52_GRID, widthPx, heightPx, rotationDeg).geometry,
                )
            }
        }
        for ((widthPx, heightPx) in landscapeSurfaces) {
            for (rotationDeg in PatternLayoutBuilder.ROTATIONS_DEG) {
                val label = "landscape ${widthPx}x$heightPx rot=$rotationDeg"
                assertEquals(
                    label,
                    GeometryProfile.F68_G6X12,
                    buildChoice(LayoutChoice.F68_GRID, widthPx, heightPx, rotationDeg).geometry,
                )
                assertEquals(
                    label,
                    GeometryProfile.C52_G5X12,
                    buildChoice(LayoutChoice.C52_GRID, widthPx, heightPx, rotationDeg).geometry,
                )
            }
        }

        // The single-row choices are unaffected by orientation and rotation.
        assertEquals(
            GeometryProfile.F68,
            buildChoice(LayoutChoice.F68_SINGLE, 1080, 2400, 0).geometry,
        )
        assertEquals(
            GeometryProfile.C52,
            buildChoice(LayoutChoice.C52_SINGLE, 2400, 1080, 90).geometry,
        )
    }

    @Test
    fun gridLayoutsTileTheDynamicAreaAndKeepPairsInOneLogicalRow() {
        for ((widthPx, heightPx) in drawableCases) {
            for (rotationDeg in PatternLayoutBuilder.ROTATIONS_DEG) {
                for (choice in listOf(LayoutChoice.F68_GRID, LayoutChoice.C52_GRID)) {
                    val layout = buildChoice(choice, widthPx, heightPx, rotationDeg)
                    val logical = LogicalView(layout)
                    val label = "${choice.wire} ${widthPx}x$heightPx rot=$rotationDeg"
                    assertEquals(label, choice.geometryFor(widthPx, heightPx), layout.geometry)

                    val geometry = layout.geometry
                    assertEquals(label, geometry.moduleCount, layout.modules.size)
                    assertEquals(label, geometry.paddingCount, layout.padding.size)
                    val allRects = layout.modules.map { it.rectPx } + layout.padding.map { it.rectPx }
                    assertEquals(
                        "$label slots must cover exactly the dynamic area",
                        layout.dynamicRectPx,
                        allRects.reduce { left, right -> left.union(right) },
                    )
                    assertEquals(
                        "$label slots must not overlap",
                        layout.dynamicRectPx.widthPx * layout.dynamicRectPx.heightPx,
                        allRects.sumOf { it.widthPx * it.heightPx },
                    )
                    allRects.forEach { rect ->
                        assertTrue("$label $rect leaves the dynamic area", layout.dynamicRectPx.contains(rect))
                    }

                    // Complementary protocol pairs must stay adjacent inside one logical row.
                    val logicalModules = layout.modules.map { unmapRect(logical, it.rectPx) }
                    var index = 0
                    while (index < logicalModules.size) {
                        val first = logicalModules[index]
                        val second = logicalModules[index + 1]
                        assertEquals("$label pair $index row", first.y0, second.y0)
                        assertEquals("$label pair $index row", first.y1, second.y1)
                        assertEquals("$label pair $index adjacency", first.x1, second.x0)
                        index += 2
                    }
                }
            }
        }
    }

    @Test
    fun gridPaddingIsIndependentAndPaintedWithItsActualLevel() {
        val layout = buildChoice(LayoutChoice.F68_GRID, 1080, 2400, 0)
        assertEquals(4, layout.padding.size)
        layout.padding.forEachIndexed { index, slot ->
            assertEquals(layout.geometry.moduleCount + index, slot.slotIndex)
            assertEquals(if (index % 2 == 0) 32 else 224, slot.level)
            assertTrue(
                "padding must not overlap a protocol module",
                layout.modules.none { it.rectPx.intersects(slot.rectPx) },
            )
        }
        val pattern = PatternEncoder.encode(7uL, layout.profile)
        val painted = paintRects(layout, pattern)
        layout.padding.forEach { slot ->
            assertTrue(
                "padding slot ${slot.slotIndex} must be drawn with its level",
                painted.any { it.rect == slot.rectPx && it.level == slot.level },
            )
        }
        assertEquals(68, layout.modules.size)
        assertEquals(72, layout.modules.size + layout.padding.size)
    }

    private fun buildChoice(
        choice: LayoutChoice,
        widthPx: Int,
        heightPx: Int,
        rotationDeg: Int,
    ): PatternLayout = PatternLayoutBuilder.build(
        "layout-grid",
        choice,
        widthPx,
        heightPx,
        rotationDeg,
    )

    /** Independent inverse of the builder's rotation, used only to inspect logical rows. */
    private fun unmapRect(logical: LogicalView, rect: RectPx): RectPx {
        val corners = listOf(
            logical.unmap(PointPx(rect.x0, rect.y0)),
            logical.unmap(PointPx(rect.x1, rect.y0)),
            logical.unmap(PointPx(rect.x1, rect.y1)),
            logical.unmap(PointPx(rect.x0, rect.y1)),
        )
        return RectPx(
            corners.minOf { it.x },
            corners.minOf { it.y },
            corners.maxOf { it.x },
            corners.maxOf { it.y },
        )
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
        val logical = LogicalView(layout)
        val width = logical.localWidthPx
        val height = logical.localHeightPx
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
        /** The builder's internal 96%/68% design region, not the public full canvas. */
        val localWidthPx: Int
        val localHeightPx: Int
        val rotatedWidthPx: Int
        val rotatedHeightPx: Int
        val offsetX: Int
        val offsetY: Int

        init {
            val quarterTurn = layout.patternRotationDeg % 180 != 0
            localWidthPx = if (quarterTurn) {
                layout.drawableHeightPx * PatternLayoutBuilder.HEIGHT_FRACTION_NUMERATOR /
                    PatternLayoutBuilder.HEIGHT_FRACTION_DENOMINATOR
            } else {
                layout.drawableWidthPx * PatternLayoutBuilder.WIDTH_FRACTION_NUMERATOR /
                    PatternLayoutBuilder.WIDTH_FRACTION_DENOMINATOR
            }
            localHeightPx = if (quarterTurn) {
                layout.drawableWidthPx * PatternLayoutBuilder.WIDTH_FRACTION_NUMERATOR /
                    PatternLayoutBuilder.WIDTH_FRACTION_DENOMINATOR
            } else {
                layout.drawableHeightPx * PatternLayoutBuilder.HEIGHT_FRACTION_NUMERATOR /
                    PatternLayoutBuilder.HEIGHT_FRACTION_DENOMINATOR
            }
            rotatedWidthPx = if (quarterTurn) localHeightPx else localWidthPx
            rotatedHeightPx = if (quarterTurn) localWidthPx else localHeightPx
            offsetX = (layout.drawableWidthPx - rotatedWidthPx) / 2
            offsetY = (layout.drawableHeightPx - rotatedHeightPx) / 2
        }

        fun map(point: PointPx): PointPx = when (layout.patternRotationDeg) {
            0 -> PointPx(point.x + offsetX, point.y + offsetY)
            90 -> PointPx(localHeightPx - point.y + offsetX, point.x + offsetY)
            180 -> PointPx(
                localWidthPx - point.x + offsetX,
                localHeightPx - point.y + offsetY,
            )
            270 -> PointPx(point.y + offsetX, localWidthPx - point.x + offsetY)
            else -> throw IllegalArgumentException("unexpected rotation ${layout.patternRotationDeg}")
        }

        fun unmap(point: PointPx): PointPx = when (layout.patternRotationDeg) {
            0 -> PointPx(point.x - offsetX, point.y - offsetY)
            90 -> PointPx(point.y - offsetY, localHeightPx + offsetX - point.x)
            180 -> PointPx(
                localWidthPx + offsetX - point.x,
                localHeightPx + offsetY - point.y,
            )
            270 -> PointPx(localWidthPx + offsetY - point.y, point.x - offsetX)
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
