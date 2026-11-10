package dev.lumaphase.android.layout

import dev.lumaphase.android.protocol.EncodedPattern

/** Marker grey levels are fixed black and white (OPTC-1 section 4). */
const val MARKER_WHITE_LEVEL = 255
const val MARKER_BLACK_LEVEL = 0

/** One solid rectangle to paint, with its 8-bit SDR code value. */
data class LevelRect(val rect: RectPx, val level: Int)

/**
 * The complete set of rectangles that make up the rendered static target, in final
 * drawable output pixels. The GL renderer draws exactly this list and the tests check it
 * against the exported layout, so the displayed geometry and `layout.json` cannot drift
 * apart.
 *
 * Order is quiet zone (white), marker black edge, marker data cells, then the dynamic
 * modules. Within one marker the later rectangles are deliberately drawn on top of the
 * earlier ones: the white quiet zone is under the black edge, and the data cells are under
 * nothing but cover the middle of the black edge. Later entries therefore win where they
 * overlap. Different markers never overlap each other or the dynamic modules.
 */
fun paintRects(layout: PatternLayout, pattern: EncodedPattern): List<LevelRect> {
    require(pattern.moduleCodes.size == layout.modules.size) {
        "encoded ${pattern.profile.name} pattern has ${pattern.moduleCodes.size} modules " +
            "but the layout has ${layout.modules.size}"
    }
    val rects = ArrayList<LevelRect>(layout.modules.size + markerRectCount(layout))
    rects += staticMarkerRects(layout)
    val moduleRects = moduleRects(layout)
    moduleRects.forEachIndexed { index, moduleRect ->
        rects += LevelRect(moduleRect, pattern.moduleCodes[index])
    }
    return rects
}

/**
 * Marker rectangles in draw order. They depend only on the layout, so a per-frame renderer
 * can build them once and reuse them for every dynamic state.
 */
fun staticMarkerRects(layout: PatternLayout): List<LevelRect> {
    val rects = ArrayList<LevelRect>(markerRectCount(layout))
    for (marker in layout.markers) {
        rects += LevelRect(marker.quietRectPx, MARKER_WHITE_LEVEL)
        rects += LevelRect(marker.outerRectPx, MARKER_BLACK_LEVEL)
        marker.cellRects.forEachIndexed { index, cell ->
            val level = if (marker.cellValues[index] == 1) MARKER_WHITE_LEVEL else MARKER_BLACK_LEVEL
            rects += LevelRect(cell, level)
        }
    }
    return rects
}

/**
 * Module rectangles in module-index order. Only their level changes between dynamic states,
 * so the geometry itself is also built once per layout.
 */
fun moduleRects(layout: PatternLayout): List<RectPx> = layout.modules.map { it.rectPx }

private fun markerRectCount(layout: PatternLayout): Int =
    layout.markers.size *
        (2 + ArUcoDict4x4_50.MARKER_SIZE_BITS * ArUcoDict4x4_50.MARKER_SIZE_BITS)
