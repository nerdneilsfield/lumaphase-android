package dev.lumaphase.android.layout

import dev.lumaphase.android.protocol.LayoutProfile
import dev.lumaphase.android.protocol.PatternEncoder

/**
 * Builds the static OPTC-1 pattern geometry in final drawable/Surface output pixels.
 *
 * The pattern is designed on an unrotated logical canvas of `patternWidthPx x
 * patternHeightPx`; `patternRotationDeg` maps it onto the drawable clockwise per
 * OPTC-1 section 3.2 and the result is centred. Every exported rectangle is produced by
 * transforming its four boundary corners with that same map, so rectangles stay aligned
 * to integer pixel edges and the half-open convention is preserved.
 *
 * Layout of the logical canvas:
 *  - one ArUco marker block in each of the four corners, id 0 top-left, 1 top-right,
 *    2 bottom-right, 3 bottom-left, each surrounded by a one-cell white quiet zone that
 *    is flush with the canvas edge;
 *  - the dynamic area between the two marker bands. The fixed module array is placed there
 *    either as one row or as an integer grid; slot `i` sits at `row = i / cols`,
 *    `column = i % cols` with floor boundaries on both axes (OPTC-1 section 3.3). Grid
 *    padding slots fill the remaining positions and carry actual low/high levels.
 * The quiet zones, the marker blocks and the dynamic area never overlap.
 *
 * The canvas covers 96% of the drawable width and 68% of its height (the proportions the
 * original design suggested); the remaining margin is cleared to black.
 */
object PatternLayoutBuilder {
    const val COORDINATE_SYSTEM = "DRAWABLE_TOP_LEFT_PIXEL_EDGES"

    /** Layout object identity used by the static preview and the layout export. */
    const val STATIC_LAYOUT_ID = "layout-0"

    const val WIDTH_FRACTION_NUMERATOR = 96
    const val WIDTH_FRACTION_DENOMINATOR = 100
    const val HEIGHT_FRACTION_NUMERATOR = 68
    const val HEIGHT_FRACTION_DENOMINATOR = 100

    /** The marker's black outer edge is about 1/8 of the pattern's shorter side. */
    const val MARKER_SHORT_SIDE_DIVISOR = 48
    const val MIN_CELL_SIZE_PX = 2

    val ROTATIONS_DEG = intArrayOf(0, 90, 180, 270)

    /**
     * Builds the layout for a settings choice. A grid choice is resolved from the actual
     * Surface/drawable width and height, so the same selection becomes 9x8 (portrait) or 6x12
     * (landscape) without the caller knowing the concrete geometry name. The pattern rotation
     * is applied afterwards and does not alter the choice.
     */
    fun build(
        layoutId: String,
        choice: LayoutChoice,
        drawableWidthPx: Int,
        drawableHeightPx: Int,
        patternRotationDeg: Int,
        levelLow: Int = PatternEncoder.DEFAULT_LEVEL_LOW,
        levelHigh: Int = PatternEncoder.DEFAULT_LEVEL_HIGH,
    ): PatternLayout {
        val frame = canvasFrame(drawableWidthPx, drawableHeightPx, patternRotationDeg)
        return buildWithGeometry(
            layoutId,
            choice.geometryFor(drawableWidthPx, drawableHeightPx),
            frame,
            levelLow,
            levelHigh,
        )
    }

    /** Builds an explicitly named geometry; used by tests and by the choice resolver above. */
    fun build(
        layoutId: String,
        geometry: GeometryProfile,
        drawableWidthPx: Int,
        drawableHeightPx: Int,
        patternRotationDeg: Int,
        levelLow: Int = PatternEncoder.DEFAULT_LEVEL_LOW,
        levelHigh: Int = PatternEncoder.DEFAULT_LEVEL_HIGH,
    ): PatternLayout = buildWithGeometry(
        layoutId,
        geometry,
        canvasFrame(drawableWidthPx, drawableHeightPx, patternRotationDeg),
        levelLow,
        levelHigh,
    )

    /** The original single-row API, kept for the legacy F68/C52 layouts and their tests. */
    fun build(
        layoutId: String,
        profile: LayoutProfile,
        drawableWidthPx: Int,
        drawableHeightPx: Int,
        patternRotationDeg: Int,
        levelLow: Int = PatternEncoder.DEFAULT_LEVEL_LOW,
        levelHigh: Int = PatternEncoder.DEFAULT_LEVEL_HIGH,
    ): PatternLayout = build(
        layoutId,
        GeometryProfile.legacy(profile),
        drawableWidthPx,
        drawableHeightPx,
        patternRotationDeg,
        levelLow,
        levelHigh,
    )

    private fun buildWithGeometry(
        layoutId: String,
        geometry: GeometryProfile,
        frame: CanvasFrame,
        levelLow: Int,
        levelHigh: Int,
    ): PatternLayout {
        val slotCount = geometry.gridRows * geometry.gridCols
        val logicalSlots = GridPlacement.slots(
            frame.dynamicLogical,
            geometry.gridRows,
            geometry.gridCols,
            slotCount,
        )
        val logicalModules = List(geometry.moduleCount) { index ->
            ModuleRect(index, logicalSlots[index])
        }
        val paddingLevels = GridPlacement.paddingLevels(geometry, levelLow, levelHigh)
        val logicalPadding = List(geometry.paddingCount) { index ->
            PaddingSlot(
                slotIndex = geometry.moduleCount + index,
                rectPx = logicalSlots[geometry.moduleCount + index],
                level = paddingLevels[index],
            )
        }

        val mapRect: (RectPx) -> RectPx = { rect ->
            mapRectToDrawable(
                frame.patternRotationDeg,
                frame.patternWidthPx,
                frame.patternHeightPx,
                frame.offsetX,
                frame.offsetY,
                rect,
            )
        }

        val markers = listOf(
            0 to frame.quietTopLeft,
            1 to frame.quietTopRight,
            2 to frame.quietBottomRight,
            3 to frame.quietBottomLeft,
        ).map { (id, quiet) ->
            val outer = RectPx(
                quiet.x0 + frame.quietZonePx,
                quiet.y0 + frame.quietZonePx,
                quiet.x0 + frame.quietZonePx + frame.markerOuterPx,
                quiet.y0 + frame.quietZonePx + frame.markerOuterPx,
            )
            val bitmap = ArUcoDict4x4_50.bitmap(id)
            // The black edge is one BORDER_BITS cell wide on every side, so the 4x4 data
            // cells are inset from the black outer edge by that border, not flush with it.
            val borderPx = ArUcoDict4x4_50.BORDER_BITS * frame.cellSizePx
            val cellRects = ArrayList<RectPx>(bitmap.size * bitmap.size)
            val cellValues = ArrayList<Int>(bitmap.size * bitmap.size)
            for (row in 0 until ArUcoDict4x4_50.MARKER_SIZE_BITS) {
                for (col in 0 until ArUcoDict4x4_50.MARKER_SIZE_BITS) {
                    cellRects += mapRect(
                        RectPx(
                            outer.x0 + borderPx + col * frame.cellSizePx,
                            outer.y0 + borderPx + row * frame.cellSizePx,
                            outer.x0 + borderPx + (col + 1) * frame.cellSizePx,
                            outer.y0 + borderPx + (row + 1) * frame.cellSizePx,
                        ),
                    )
                    cellValues += bitmap[row][col]
                }
            }
            // The marker's own boundary vertices in its own order: top-left, top-right,
            // bottom-right, bottom-left, then transformed with the pattern.
            val ownCorners = listOf(
                PointPx(outer.x0, outer.y0),
                PointPx(outer.x1, outer.y0),
                PointPx(outer.x1, outer.y1),
                PointPx(outer.x0, outer.y1),
            ).map { point ->
                mapPointToDrawable(
                    frame.patternRotationDeg, frame.patternWidthPx, frame.patternHeightPx,
                    frame.offsetX, frame.offsetY, point.x, point.y,
                )
            }
            MarkerLayout(
                id = id,
                dictionary = ArUcoDict4x4_50.DICTIONARY,
                markerSizeBits = ArUcoDict4x4_50.MARKER_SIZE_BITS,
                borderBits = ArUcoDict4x4_50.BORDER_BITS,
                cellSizePx = frame.cellSizePx,
                quietZonePx = frame.quietZonePx,
                cornersPx = ownCorners,
                quietRectPx = mapRect(quiet),
                outerRectPx = mapRect(outer),
                cellRects = cellRects,
                cellValues = cellValues,
            )
        }

        val layout = PatternLayout(
            layoutId = layoutId,
            profile = geometry.layoutProfile,
            geometry = geometry,
            drawableWidthPx = frame.drawableWidthPx,
            drawableHeightPx = frame.drawableHeightPx,
            coordinateSystem = COORDINATE_SYSTEM,
            patternRotationDeg = frame.patternRotationDeg,
            patternWidthPx = frame.patternWidthPx,
            patternHeightPx = frame.patternHeightPx,
            patternToDrawable = patternToDrawableMatrix(
                frame.patternRotationDeg,
                frame.patternWidthPx,
                frame.patternHeightPx,
                frame.offsetX,
                frame.offsetY,
            ).toList(),
            dynamicRectPx = mapRect(frame.dynamicLogical),
            modules = logicalModules.map { ModuleRect(it.index, mapRect(it.rectPx)) },
            padding = logicalPadding.map { PaddingSlot(it.slotIndex, mapRect(it.rectPx), it.level) },
            markers = markers,
            levelLow = levelLow,
            levelHigh = levelHigh,
        )

        val drawableRect = RectPx(0, 0, frame.drawableWidthPx, frame.drawableHeightPx)
        require(drawableRect.contains(layout.dynamicRectPx)) {
            "dynamic area ${layout.dynamicRectPx} leaves the drawable $drawableRect"
        }
        layout.markers.forEach { marker ->
            require(drawableRect.contains(marker.quietRectPx)) {
                "marker ${marker.id} quiet zone ${marker.quietRectPx} leaves the drawable $drawableRect"
            }
        }
        return layout
    }

    /**
     * The parts of the geometry that depend only on the drawable size and rotation: the
     * logical canvas, marker blocks, dynamic rectangle and centring offsets. The module grid
     * itself is placed later, from the geometry profile.
     */
    private class CanvasFrame(
        val drawableWidthPx: Int,
        val drawableHeightPx: Int,
        val patternRotationDeg: Int,
        val patternWidthPx: Int,
        val patternHeightPx: Int,
        val offsetX: Int,
        val offsetY: Int,
        val cellSizePx: Int,
        val quietZonePx: Int,
        val markerOuterPx: Int,
        val quietTopLeft: RectPx,
        val quietTopRight: RectPx,
        val quietBottomRight: RectPx,
        val quietBottomLeft: RectPx,
        val dynamicLogical: RectPx,
    )

    private fun canvasFrame(
        drawableWidthPx: Int,
        drawableHeightPx: Int,
        patternRotationDeg: Int,
    ): CanvasFrame {
        require(drawableWidthPx > 0 && drawableHeightPx > 0) {
            "drawable size must be positive, got ${drawableWidthPx}x$drawableHeightPx"
        }
        require(patternRotationDeg in ROTATIONS_DEG) {
            "unsupported pattern rotation $patternRotationDeg, expected one of ${ROTATIONS_DEG.toList()}"
        }

        val rotatedWidthPx = drawableWidthPx * WIDTH_FRACTION_NUMERATOR / WIDTH_FRACTION_DENOMINATOR
        val rotatedHeightPx = drawableHeightPx * HEIGHT_FRACTION_NUMERATOR / HEIGHT_FRACTION_DENOMINATOR
        val quarterTurn = patternRotationDeg % 180 != 0
        val patternWidthPx = if (quarterTurn) rotatedHeightPx else rotatedWidthPx
        val patternHeightPx = if (quarterTurn) rotatedWidthPx else rotatedHeightPx

        val cellSizePx = maxOf(
            MIN_CELL_SIZE_PX,
            minOf(patternWidthPx, patternHeightPx) / MARKER_SHORT_SIDE_DIVISOR,
        )
        val markerOuterPx =
            (ArUcoDict4x4_50.MARKER_SIZE_BITS + 2 * ArUcoDict4x4_50.BORDER_BITS) * cellSizePx
        val quietZonePx = cellSizePx
        // The block reserves a quiet-zone margin on all four sides of the marker's black edge.
        val markerBlockPx = markerOuterPx + 2 * quietZonePx
        require(2 * markerBlockPx < patternHeightPx) {
            "pattern height $patternHeightPx px is too small for $markerBlockPx px marker blocks"
        }

        val quietTopLeft = RectPx(0, 0, markerBlockPx, markerBlockPx)
        val quietTopRight = RectPx(patternWidthPx - markerBlockPx, 0, patternWidthPx, markerBlockPx)
        val quietBottomRight = RectPx(
            patternWidthPx - markerBlockPx,
            patternHeightPx - markerBlockPx,
            patternWidthPx,
            patternHeightPx,
        )
        val quietBottomLeft = RectPx(0, patternHeightPx - markerBlockPx, markerBlockPx, patternHeightPx)

        val offsetX = (drawableWidthPx - rotatedWidthPx) / 2
        val offsetY = (drawableHeightPx - rotatedHeightPx) / 2

        return CanvasFrame(
            drawableWidthPx = drawableWidthPx,
            drawableHeightPx = drawableHeightPx,
            patternRotationDeg = patternRotationDeg,
            patternWidthPx = patternWidthPx,
            patternHeightPx = patternHeightPx,
            offsetX = offsetX,
            offsetY = offsetY,
            cellSizePx = cellSizePx,
            quietZonePx = quietZonePx,
            markerOuterPx = markerOuterPx,
            quietTopLeft = quietTopLeft,
            quietTopRight = quietTopRight,
            quietBottomRight = quietBottomRight,
            quietBottomLeft = quietBottomLeft,
            dynamicLogical = RectPx(
                0,
                markerBlockPx,
                patternWidthPx,
                patternHeightPx - markerBlockPx,
            ),
        )
    }
}

/**
 * Logical pattern boundary point `(x, y)` to drawable boundary point, clockwise per
 * OPTC-1 section 3.2, then offset by the centring translation.
 */
internal fun mapPointToDrawable(
    rotationDeg: Int,
    patternWidthPx: Int,
    patternHeightPx: Int,
    offsetX: Int,
    offsetY: Int,
    x: Int,
    y: Int,
): PointPx = when (rotationDeg) {
    0 -> PointPx(x + offsetX, y + offsetY)
    90 -> PointPx(patternHeightPx - y + offsetX, x + offsetY)
    180 -> PointPx(patternWidthPx - x + offsetX, patternHeightPx - y + offsetY)
    270 -> PointPx(y + offsetX, patternWidthPx - x + offsetY)
    else -> throw IllegalArgumentException("unsupported pattern rotation $rotationDeg")
}

/** Transforms a rectangle by transforming its four boundary corners and taking the extent. */
internal fun mapRectToDrawable(
    rotationDeg: Int,
    patternWidthPx: Int,
    patternHeightPx: Int,
    offsetX: Int,
    offsetY: Int,
    rect: RectPx,
): RectPx {
    val corners = listOf(
        mapPointToDrawable(rotationDeg, patternWidthPx, patternHeightPx, offsetX, offsetY, rect.x0, rect.y0),
        mapPointToDrawable(rotationDeg, patternWidthPx, patternHeightPx, offsetX, offsetY, rect.x1, rect.y0),
        mapPointToDrawable(rotationDeg, patternWidthPx, patternHeightPx, offsetX, offsetY, rect.x1, rect.y1),
        mapPointToDrawable(rotationDeg, patternWidthPx, patternHeightPx, offsetX, offsetY, rect.x0, rect.y1),
    )
    return RectPx(
        corners.minOf { it.x },
        corners.minOf { it.y },
        corners.maxOf { it.x },
        corners.maxOf { it.y },
    )
}

/**
 * The 3x3 logical-to-drawable matrix, row-major, with the centring translation applied.
 * It matches [mapPointToDrawable] exactly.
 */
internal fun patternToDrawableMatrix(
    rotationDeg: Int,
    patternWidthPx: Int,
    patternHeightPx: Int,
    offsetX: Int,
    offsetY: Int,
): IntArray = when (rotationDeg) {
    0 -> intArrayOf(
        1, 0, offsetX,
        0, 1, offsetY,
        0, 0, 1,
    )
    90 -> intArrayOf(
        0, -1, patternHeightPx + offsetX,
        1, 0, offsetY,
        0, 0, 1,
    )
    180 -> intArrayOf(
        -1, 0, patternWidthPx + offsetX,
        0, -1, patternHeightPx + offsetY,
        0, 0, 1,
    )
    270 -> intArrayOf(
        0, 1, offsetX,
        -1, 0, patternWidthPx + offsetY,
        0, 0, 1,
    )
    else -> throw IllegalArgumentException("unsupported pattern rotation $rotationDeg")
}
