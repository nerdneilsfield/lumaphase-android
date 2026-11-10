package dev.lumaphase.android.layout

/**
 * The integer placement rule of OPTC-1 section 3.3, shared by the single-row layouts and the
 * CR-001 grids.
 *
 * Slot `i` has `row = i / cols` and `column = i % cols` in the unrotated logical dynamic
 * rectangle `[x0,y0,x1,y1)`. Both axes use floor boundaries:
 *
 * `x0 + column * (x1-x0) / cols` and `y0 + row * (y1-y0) / rows`.
 *
 * The rectangle is tiled exactly: the first and last boundaries are the dynamic edges, every
 * cell has the same width and height up to the unavoidable one-pixel remainder, and no two
 * cells overlap. Because every layout has an even column count, an original complementary
 * module pair (two adjacent protocol modules) never crosses a logical row.
 */
object GridPlacement {
    const val MODULE_ORDER = "row-major"
    const val SAMPLING_INNER_FRACTION = 0.6

    /**
     * Returns all `rows * cols` slot rectangles in row-major slot order. [slotCount] may be
     * smaller than the full grid for callers that only need the data slots; padding slots are
     * the remaining indexes.
     */
    fun slots(
        dynamicRectPx: RectPx,
        rows: Int,
        cols: Int,
        slotCount: Int = rows * cols,
    ): List<RectPx> {
        require(rows > 0 && cols > 0) { "grid must be positive, got ${rows}x$cols" }
        require(cols % 2 == 0) { "grid columns must be even, got $cols" }
        require(slotCount in 0..rows * cols) {
            "slot count $slotCount does not fit a ${rows}x$cols grid"
        }
        require(dynamicRectPx.widthPx >= cols) {
            "dynamic width ${dynamicRectPx.widthPx} px cannot hold $cols columns"
        }
        require(dynamicRectPx.heightPx >= rows) {
            "dynamic height ${dynamicRectPx.heightPx} px cannot hold $rows rows"
        }
        val widthPx = dynamicRectPx.widthPx
        val heightPx = dynamicRectPx.heightPx
        return List(slotCount) { slot ->
            val row = slot / cols
            val column = slot % cols
            RectPx(
                dynamicRectPx.x0 + column * widthPx / cols,
                dynamicRectPx.y0 + row * heightPx / rows,
                dynamicRectPx.x0 + (column + 1) * widthPx / cols,
                dynamicRectPx.y0 + (row + 1) * heightPx / rows,
            )
        }
    }

    /**
     * Padding levels alternate from the layout's low level: first low, then high, repeating.
     * They are actual 8-bit code values, not an "L"/"H" string enum.
     */
    fun paddingLevels(geometry: GeometryProfile, levelLow: Int, levelHigh: Int): List<Int> =
        List(geometry.paddingCount) { index ->
            if (index % 2 == 0) levelLow else levelHigh
        }
}
