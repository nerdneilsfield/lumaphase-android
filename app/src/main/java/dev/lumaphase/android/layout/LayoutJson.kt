package dev.lumaphase.android.layout

import android.util.JsonWriter
import java.io.StringWriter
import java.io.Writer

/**
 * Writes the OPTC-1 `layout.json` container (`schema_version` 1.1) for one or more static
 * layouts, using Android's own [JsonWriter] rather than a hand-written JSON layer.
 *
 * This file is the layout collection only. It is not a session directory and does not
 * claim captured frames, timestamps or a complete session.
 *
 * Key names and types follow OPTC-1 sections 3.1, 3.3 and 4. Coordinates are final drawable
 * output pixels with the top-left origin and half-open rectangles. `pattern_to_drawable`
 * is the 3x3 row-major matrix written as three arrays of three. `pattern_width_px` and
 * `pattern_height_px` describe the complete inverse-quarter-turn logical canvas, and the
 * matrix is its pure rotation; the smaller internal design region is not exported as the
 * pattern canvas. Every layout, including the single-row F68/C52, carries the complete
 * geometry extension group; padding is a separate array and never mixed into `modules`.
 */
object LayoutJson {
    const val SCHEMA_VERSION = "1.1"

    const val KEY_SCHEMA_VERSION = "schema_version"
    const val KEY_LAYOUTS = "layouts"

    const val KEY_LAYOUT_ID = "layout_id"
    const val KEY_LAYOUT_PROFILE = "layout_profile"
    const val KEY_GEOMETRY_PROFILE = "geometry_profile"
    const val KEY_GRID_ROWS = "grid_rows"
    const val KEY_GRID_COLS = "grid_cols"
    const val KEY_MODULE_COUNT = "module_count"
    const val KEY_PADDING_COUNT = "padding_count"
    const val KEY_MODULE_ORDER = "module_order"
    const val KEY_SAMPLING_INNER_FRACTION = "sampling_inner_fraction"
    const val KEY_DRAWABLE_WIDTH_PX = "drawable_width_px"
    const val KEY_DRAWABLE_HEIGHT_PX = "drawable_height_px"
    const val KEY_COORDINATE_SYSTEM = "coordinate_system"
    const val KEY_PATTERN_ROTATION_DEG = "pattern_rotation_deg"
    const val KEY_PATTERN_WIDTH_PX = "pattern_width_px"
    const val KEY_PATTERN_HEIGHT_PX = "pattern_height_px"
    const val KEY_PATTERN_TO_DRAWABLE = "pattern_to_drawable"
    const val KEY_DYNAMIC_RECT_PX = "dynamic_rect_px"
    const val KEY_MODULES = "modules"
    const val KEY_PADDING = "padding"
    const val KEY_MARKERS = "markers"
    const val KEY_LEVEL_LOW = "level_low"
    const val KEY_LEVEL_HIGH = "level_high"

    const val KEY_INDEX = "index"
    const val KEY_SLOT_INDEX = "slot_index"
    const val KEY_ROLE = "role"
    const val KEY_RECT_PX = "rect_px"
    const val KEY_LEVEL = "level"

    const val KEY_ID = "id"
    const val KEY_DICTIONARY = "dictionary"
    const val KEY_MARKER_SIZE_BITS = "marker_size_bits"
    const val KEY_BORDER_BITS = "border_bits"
    const val KEY_CELL_SIZE_PX = "cell_size_px"
    const val KEY_QUIET_ZONE_PX = "quiet_zone_px"
    const val KEY_CORNERS_PX = "corners_px"

    val ROOT_KEYS: List<String> = listOf(KEY_SCHEMA_VERSION, KEY_LAYOUTS)

    val LAYOUT_KEYS: List<String> = listOf(
        KEY_LAYOUT_ID,
        KEY_LAYOUT_PROFILE,
        KEY_GEOMETRY_PROFILE,
        KEY_GRID_ROWS,
        KEY_GRID_COLS,
        KEY_MODULE_COUNT,
        KEY_PADDING_COUNT,
        KEY_MODULE_ORDER,
        KEY_SAMPLING_INNER_FRACTION,
        KEY_DRAWABLE_WIDTH_PX,
        KEY_DRAWABLE_HEIGHT_PX,
        KEY_COORDINATE_SYSTEM,
        KEY_PATTERN_ROTATION_DEG,
        KEY_PATTERN_WIDTH_PX,
        KEY_PATTERN_HEIGHT_PX,
        KEY_PATTERN_TO_DRAWABLE,
        KEY_DYNAMIC_RECT_PX,
        KEY_MODULES,
        KEY_PADDING,
        KEY_MARKERS,
        KEY_LEVEL_LOW,
        KEY_LEVEL_HIGH,
    )

    val MODULE_KEYS: List<String> = listOf(KEY_INDEX, KEY_RECT_PX)

    val PADDING_KEYS: List<String> = listOf(
        KEY_SLOT_INDEX,
        KEY_ROLE,
        KEY_RECT_PX,
        KEY_LEVEL,
    )

    val MARKER_KEYS: List<String> = listOf(
        KEY_ID,
        KEY_DICTIONARY,
        KEY_MARKER_SIZE_BITS,
        KEY_BORDER_BITS,
        KEY_CELL_SIZE_PX,
        KEY_QUIET_ZONE_PX,
        KEY_CORNERS_PX,
    )

    fun write(writer: Writer, layouts: List<PatternLayout>) {
        val json = JsonWriter(writer)
        json.setIndent("  ")
        json.beginObject()
        json.name(KEY_SCHEMA_VERSION).value(SCHEMA_VERSION)
        json.name(KEY_LAYOUTS).beginArray()
        for (layout in layouts) writeLayout(json, layout)
        json.endArray()
        json.endObject()
        json.flush()
    }

    /**
     * Serialises the layout collection to text, for handing the exact bytes that the
     * preview rendered to the export step.
     */
    fun toJson(layouts: List<PatternLayout>): String =
        StringWriter().also { write(it, layouts) }.toString()

    private fun writeLayout(json: JsonWriter, layout: PatternLayout) {
        json.beginObject()
        json.name(KEY_LAYOUT_ID).value(layout.layoutId)
        json.name(KEY_LAYOUT_PROFILE).value(layout.profile.name)
        json.name(KEY_GEOMETRY_PROFILE).value(layout.geometry.wire)
        json.name(KEY_GRID_ROWS).value(layout.geometry.gridRows.toLong())
        json.name(KEY_GRID_COLS).value(layout.geometry.gridCols.toLong())
        json.name(KEY_MODULE_COUNT).value(layout.geometry.moduleCount.toLong())
        json.name(KEY_PADDING_COUNT).value(layout.geometry.paddingCount.toLong())
        json.name(KEY_MODULE_ORDER).value(GridPlacement.MODULE_ORDER)
        json.name(KEY_SAMPLING_INNER_FRACTION).value(GridPlacement.SAMPLING_INNER_FRACTION)
        json.name(KEY_DRAWABLE_WIDTH_PX).value(layout.drawableWidthPx.toLong())
        json.name(KEY_DRAWABLE_HEIGHT_PX).value(layout.drawableHeightPx.toLong())
        json.name(KEY_COORDINATE_SYSTEM).value(layout.coordinateSystem)
        json.name(KEY_PATTERN_ROTATION_DEG).value(layout.patternRotationDeg.toLong())
        json.name(KEY_PATTERN_WIDTH_PX).value(layout.patternWidthPx.toLong())
        json.name(KEY_PATTERN_HEIGHT_PX).value(layout.patternHeightPx.toLong())

        json.name(KEY_PATTERN_TO_DRAWABLE).beginArray()
        for (row in 0 until 3) {
            json.beginArray()
            for (column in 0 until 3) {
                json.value(layout.patternToDrawable[row * 3 + column].toLong())
            }
            json.endArray()
        }
        json.endArray()

        json.name(KEY_DYNAMIC_RECT_PX)
        writeRect(json, layout.dynamicRectPx)

        json.name(KEY_MODULES).beginArray()
        for (module in layout.modules) {
            json.beginObject()
            json.name(KEY_INDEX).value(module.index.toLong())
            json.name(KEY_RECT_PX)
            writeRect(json, module.rectPx)
            json.endObject()
        }
        json.endArray()

        json.name(KEY_PADDING).beginArray()
        for (slot in layout.padding) {
            json.beginObject()
            json.name(KEY_SLOT_INDEX).value(slot.slotIndex.toLong())
            json.name(KEY_ROLE).value(ROLE_PADDING)
            json.name(KEY_RECT_PX)
            writeRect(json, slot.rectPx)
            json.name(KEY_LEVEL).value(slot.level.toLong())
            json.endObject()
        }
        json.endArray()

        json.name(KEY_MARKERS).beginArray()
        for (marker in layout.markers) {
            json.beginObject()
            json.name(KEY_ID).value(marker.id.toLong())
            json.name(KEY_DICTIONARY).value(marker.dictionary)
            json.name(KEY_MARKER_SIZE_BITS).value(marker.markerSizeBits.toLong())
            json.name(KEY_BORDER_BITS).value(marker.borderBits.toLong())
            json.name(KEY_CELL_SIZE_PX).value(marker.cellSizePx.toLong())
            json.name(KEY_QUIET_ZONE_PX).value(marker.quietZonePx.toLong())
            json.name(KEY_CORNERS_PX).beginArray()
            for (corner in marker.cornersPx) {
                json.beginArray()
                json.value(corner.x.toLong())
                json.value(corner.y.toLong())
                json.endArray()
            }
            json.endArray()
            json.endObject()
        }
        json.endArray()

        json.name(KEY_LEVEL_LOW).value(layout.levelLow.toLong())
        json.name(KEY_LEVEL_HIGH).value(layout.levelHigh.toLong())
        json.endObject()
    }

    private fun writeRect(json: JsonWriter, rect: RectPx) {
        json.beginArray()
        json.value(rect.x0.toLong())
        json.value(rect.y0.toLong())
        json.value(rect.x1.toLong())
        json.value(rect.y1.toLong())
        json.endArray()
    }

    /** The only role value a padding object may carry (contract section 3.3). */
    const val ROLE_PADDING = "padding"
}
