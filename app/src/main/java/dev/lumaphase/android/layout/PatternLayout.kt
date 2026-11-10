package dev.lumaphase.android.layout

import dev.lumaphase.android.protocol.LayoutProfile

/**
 * Half-open integer rectangle in final drawable/Surface output pixels:
 * `[x0, x1) x [y0, y1)` with the origin at the top-left, x to the right and y down.
 * This is the only coordinate system used by the exported layout (OPTC-1 section 3.1).
 */
data class RectPx(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    init {
        require(x1 >= x0) { "rectangle x1 < x0: $this" }
        require(y1 >= y0) { "rectangle y1 < y0: $this" }
    }

    val widthPx: Int get() = x1 - x0
    val heightPx: Int get() = y1 - y0

    /** `[x0, y0, x1, y1]` as written to `layout.json`. */
    val edges: IntArray get() = intArrayOf(x0, y0, x1, y1)

    /** Half-open overlap test; touching edges do not count as an overlap. */
    fun intersects(other: RectPx): Boolean =
        x0 < other.x1 && other.x0 < x1 && y0 < other.y1 && other.y0 < y1

    fun contains(other: RectPx): Boolean =
        x0 <= other.x0 && y0 <= other.y0 && other.x1 <= x1 && other.y1 <= y1

    fun union(other: RectPx): RectPx = RectPx(
        minOf(x0, other.x0),
        minOf(y0, other.y0),
        maxOf(x1, other.x1),
        maxOf(y1, other.y1),
    )
}

data class PointPx(val x: Int, val y: Int)

/** One module of the dynamic code at its final drawable rectangle. */
data class ModuleRect(val index: Int, val rectPx: RectPx)

/**
 * One padding slot of a grid layout. It fills the last row but carries no protocol bit;
 * [level] is the actual 8-bit code value drawn there, alternating low/high.
 */
data class PaddingSlot(val slotIndex: Int, val rectPx: RectPx, val level: Int)

/**
 * One ArUco locating marker.
 *
 * [cornersPx] holds the four boundary vertices of the marker's black outer edge in the
 * marker's own order (top-left, top-right, bottom-right, bottom-left) after the pattern
 * rotation; the order is not re-sorted for the rotated image position.
 *
 * [quietRectPx], [outerRectPx], [cellRects] and [cellValues] describe the same marker for
 * rendering. They are not part of the exported contract fields.
 */
data class MarkerLayout(
    val id: Int,
    val dictionary: String,
    val markerSizeBits: Int,
    val borderBits: Int,
    val cellSizePx: Int,
    val quietZonePx: Int,
    val cornersPx: List<PointPx>,
    val quietRectPx: RectPx,
    val outerRectPx: RectPx,
    val cellRects: List<RectPx>,
    val cellValues: List<Int>,
)

/**
 * Immutable geometry of one OPTC-1 pattern layout. All [modules] and [markers] rectangles
 * are already expressed in final drawable output pixels, so a reader samples them
 * directly and must not apply the rotation again (OPTC-1 section 3.1).
 *
 * [patternWidthPx] and [patternHeightPx] describe the **complete unrotated logical canvas**:
 * for a 0/180 layout they equal the drawable width/height, and for 90/270 they are the
 * drawable height/width. [patternToDrawable] is the pure rotation of that complete canvas;
 * it has no centring translation. The smaller 96%/68% design region used to lay out the
 * visible modules and markers is internal to the builder and is not this public canvas.
 */
data class PatternLayout(
    val layoutId: String,
    val profile: LayoutProfile,
    val geometry: GeometryProfile,
    val drawableWidthPx: Int,
    val drawableHeightPx: Int,
    val coordinateSystem: String,
    val patternRotationDeg: Int,
    /** Width of the complete unrotated logical canvas, not the internal design region. */
    val patternWidthPx: Int,
    /** Height of the complete unrotated logical canvas, not the internal design region. */
    val patternHeightPx: Int,
    /** 3x3 complete-canvas-to-drawable rotation, row-major, integers; no offset. */
    val patternToDrawable: List<Int>,
    val dynamicRectPx: RectPx,
    val modules: List<ModuleRect>,
    val padding: List<PaddingSlot>,
    val markers: List<MarkerLayout>,
    val levelLow: Int,
    val levelHigh: Int,
) {
    init {
        require(patternRotationDeg in setOf(0, 90, 180, 270)) {
            "unsupported pattern rotation $patternRotationDeg"
        }
        val quarterTurn = patternRotationDeg % 180 != 0
        val expectedPatternWidth = if (quarterTurn) drawableHeightPx else drawableWidthPx
        val expectedPatternHeight = if (quarterTurn) drawableWidthPx else drawableHeightPx
        require(patternWidthPx == expectedPatternWidth && patternHeightPx == expectedPatternHeight) {
            "pattern canvas ${patternWidthPx}x$patternHeightPx is not the inverse-quarter-turn " +
                "drawable ${drawableWidthPx}x$drawableHeightPx at $patternRotationDeg deg"
        }
        require(
            patternToDrawable == patternToDrawableMatrix(
                patternRotationDeg,
                expectedPatternWidth,
                expectedPatternHeight,
                0,
                0,
            ).toList(),
        ) {
            "pattern_to_drawable must be the pure rotation of the complete logical canvas"
        }
        require(profile == geometry.layoutProfile) {
            "layout profile ${profile.name} does not match geometry ${geometry.wire}"
        }
        require(modules.size == geometry.moduleCount) {
            "geometry ${geometry.wire} needs ${geometry.moduleCount} modules, got ${modules.size}"
        }
        require(padding.size == geometry.paddingCount) {
            "geometry ${geometry.wire} needs ${geometry.paddingCount} padding slots, got ${padding.size}"
        }
        modules.forEachIndexed { index, module ->
            require(module.index == index) { "module index ${module.index} at position $index" }
        }
        padding.forEachIndexed { index, slot ->
            require(slot.slotIndex == geometry.moduleCount + index) {
                "padding slot ${slot.slotIndex} at position $index"
            }
            require(slot.level == levelLow || slot.level == levelHigh) {
                "padding level ${slot.level} is not one of $levelLow/$levelHigh"
            }
        }
    }
}
