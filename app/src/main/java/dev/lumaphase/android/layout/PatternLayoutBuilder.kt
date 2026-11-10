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
 *  - the dynamic module strip between the two marker bands, spanning the canvas width and
 *    split into `moduleCount` vertical bands with boundaries `a + floor(i * (b - a) / M)`
 *    (OPTC-1 section 3.3).
 * The quiet zones, the marker blocks and the dynamic strip never overlap.
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

    fun build(
        layoutId: String,
        profile: LayoutProfile,
        drawableWidthPx: Int,
        drawableHeightPx: Int,
        patternRotationDeg: Int,
        levelLow: Int = PatternEncoder.DEFAULT_LEVEL_LOW,
        levelHigh: Int = PatternEncoder.DEFAULT_LEVEL_HIGH,
    ): PatternLayout {
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
        require(patternWidthPx >= profile.moduleCount) {
            "pattern width $patternWidthPx px cannot hold ${profile.moduleCount} ${profile.name} modules"
        }

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

        val dynamicLogical = RectPx(0, markerBlockPx, patternWidthPx, patternHeightPx - markerBlockPx)

        val moduleCount = profile.moduleCount
        val boundaries = IntArray(moduleCount + 1) { index ->
            dynamicLogical.x0 + index * dynamicLogical.widthPx / moduleCount
        }
        val logicalModules = List(moduleCount) { index ->
            ModuleRect(
                index,
                RectPx(boundaries[index], dynamicLogical.y0, boundaries[index + 1], dynamicLogical.y1),
            )
        }

        val offsetX = (drawableWidthPx - rotatedWidthPx) / 2
        val offsetY = (drawableHeightPx - rotatedHeightPx) / 2
        val mapRect: (RectPx) -> RectPx = { rect ->
            mapRectToDrawable(
                patternRotationDeg, patternWidthPx, patternHeightPx, offsetX, offsetY, rect,
            )
        }

        val markers = listOf(
            0 to quietTopLeft,
            1 to quietTopRight,
            2 to quietBottomRight,
            3 to quietBottomLeft,
        ).map { (id, quiet) ->
            val outer = RectPx(
                quiet.x0 + quietZonePx,
                quiet.y0 + quietZonePx,
                quiet.x0 + quietZonePx + markerOuterPx,
                quiet.y0 + quietZonePx + markerOuterPx,
            )
            val bitmap = ArUcoDict4x4_50.bitmap(id)
            // The black edge is one BORDER_BITS cell wide on every side, so the 4x4 data
            // cells are inset from the black outer edge by that border, not flush with it.
            val borderPx = ArUcoDict4x4_50.BORDER_BITS * cellSizePx
            val cellRects = ArrayList<RectPx>(bitmap.size * bitmap.size)
            val cellValues = ArrayList<Int>(bitmap.size * bitmap.size)
            for (row in 0 until ArUcoDict4x4_50.MARKER_SIZE_BITS) {
                for (col in 0 until ArUcoDict4x4_50.MARKER_SIZE_BITS) {
                    cellRects += mapRect(
                        RectPx(
                            outer.x0 + borderPx + col * cellSizePx,
                            outer.y0 + borderPx + row * cellSizePx,
                            outer.x0 + borderPx + (col + 1) * cellSizePx,
                            outer.y0 + borderPx + (row + 1) * cellSizePx,
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
                    patternRotationDeg, patternWidthPx, patternHeightPx,
                    offsetX, offsetY, point.x, point.y,
                )
            }
            MarkerLayout(
                id = id,
                dictionary = ArUcoDict4x4_50.DICTIONARY,
                markerSizeBits = ArUcoDict4x4_50.MARKER_SIZE_BITS,
                borderBits = ArUcoDict4x4_50.BORDER_BITS,
                cellSizePx = cellSizePx,
                quietZonePx = quietZonePx,
                cornersPx = ownCorners,
                quietRectPx = mapRect(quiet),
                outerRectPx = mapRect(outer),
                cellRects = cellRects,
                cellValues = cellValues,
            )
        }

        val layout = PatternLayout(
            layoutId = layoutId,
            profile = profile,
            drawableWidthPx = drawableWidthPx,
            drawableHeightPx = drawableHeightPx,
            coordinateSystem = COORDINATE_SYSTEM,
            patternRotationDeg = patternRotationDeg,
            patternWidthPx = patternWidthPx,
            patternHeightPx = patternHeightPx,
            patternToDrawable = patternToDrawableMatrix(
                patternRotationDeg, patternWidthPx, patternHeightPx, offsetX, offsetY,
            ).toList(),
            dynamicRectPx = mapRect(dynamicLogical),
            modules = logicalModules.map { ModuleRect(it.index, mapRect(it.rectPx)) },
            markers = markers,
            levelLow = levelLow,
            levelHigh = levelHigh,
        )

        val drawableRect = RectPx(0, 0, drawableWidthPx, drawableHeightPx)
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
