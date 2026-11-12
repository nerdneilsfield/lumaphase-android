package dev.lumaphase.android.layout

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import dev.lumaphase.android.protocol.LayoutProfile

/**
 * Thrown when an imported `layout.json` is not a complete, valid OPTC-1 layout document.
 * The camera test never falls back to a guessed geometry when this happens.
 */
class LayoutImportException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Reads the layout collection written by `layout.json` (schema 1.0 or 1.1).
 *
 * This is the first real import caller in this repository, so it implements the contract's
 * old 1.0 rule here: a layout with the whole geometry extension group absent is a legacy
 * single-row layout; a partially present group, an unknown geometry, a count/index mismatch,
 * or a malformed padding entry is rejected. 1.0 files that carry the complete extension group
 * are accepted with the same validation as 1.1. There is no lenient fallback for a damaged
 * file and no parser for session files that are never imported.
 *
 * Every accepted layout also has to satisfy section 3.2: `pattern_width_px`/`pattern_height_px`
 * are the complete inverse-quarter-turn drawable canvas, `pattern_to_drawable` is that canvas's
 * pure rotation, and the exported module/padding rectangles are re-derived from the drawable
 * dynamic rectangle by the inverse rotation plus the integer grid placement. This is a
 * structural check, not a percentage-based guess.
 *
 * Gson is used only as a JSON reader. The model and all validation are independent of the
 * writer's API, so this code runs in a host JVM test.
 */
object LayoutJsonImport {
    private val EXTENSION_KEYS = listOf(
        LayoutJson.KEY_GEOMETRY_PROFILE,
        LayoutJson.KEY_GRID_ROWS,
        LayoutJson.KEY_GRID_COLS,
        LayoutJson.KEY_MODULE_COUNT,
        LayoutJson.KEY_PADDING_COUNT,
        LayoutJson.KEY_MODULE_ORDER,
        LayoutJson.KEY_SAMPLING_INNER_FRACTION,
        LayoutJson.KEY_PADDING,
    )

    private val SCHEMA_VERSIONS = setOf("1.0", "1.1", "1.2")
    private val ROTATIONS = setOf(0, 90, 180, 270)
    private const val EXPECTED_SAMPLING = 0.6

    fun parse(text: String): List<ImportedLayout> {
        val root = try {
            JsonParser.parseString(text)
        } catch (error: JsonParseException) {
            throw LayoutImportException("invalid JSON: ${error.message}", error)
        }
        if (!root.isJsonObject) {
            throw LayoutImportException("layout.json root must be an object")
        }
        val document = root.asJsonObject
        val schemaVersion = document.string(LayoutJson.KEY_SCHEMA_VERSION)
        if (schemaVersion !in SCHEMA_VERSIONS) {
            throw LayoutImportException("unsupported schema_version '$schemaVersion'")
        }
        val layouts = document.array(LayoutJson.KEY_LAYOUTS)
        if (layouts.size() == 0) {
            throw LayoutImportException("layouts must not be empty")
        }

        val ids = HashSet<String>()
        val parsed = ArrayList<ImportedLayout>(layouts.size())
        for (index in 0 until layouts.size()) {
            val element = layouts[index]
            if (!element.isJsonObject) {
                throw LayoutImportException("layout[$index] must be an object")
            }
            val layout = try {
                parseLayout(schemaVersion, element.asJsonObject)
            } catch (error: LayoutImportException) {
                throw LayoutImportException("layout[$index]: ${error.message}", error)
            }
            if (!ids.add(layout.layoutId)) {
                throw LayoutImportException("duplicate layout_id '${layout.layoutId}'")
            }
            parsed += layout
        }
        return parsed
    }

    private fun parseLayout(schemaVersion: String, json: JsonObject): ImportedLayout {
        val layoutId = json.string(LayoutJson.KEY_LAYOUT_ID)
        if (layoutId.isBlank()) throw LayoutImportException("layout_id must not be blank")
        val layoutType = if (schemaVersion == "1.2") {
            val wire = json.string(LayoutJson.KEY_LAYOUT_TYPE)
            LayoutType.entries.firstOrNull { it.wire == wire }
                ?: throw LayoutImportException("unknown layout_type '$wire'")
        } else {
            LayoutType.OPTC
        }
        if (layoutType != LayoutType.OPTC) {
            throw LayoutImportException(
                "layout '$layoutId' is ${layoutType.wire}, not an OPTC payload layout",
            )
        }

        val profile = when (val value = json.string(LayoutJson.KEY_LAYOUT_PROFILE)) {
            LayoutProfile.F68.name -> LayoutProfile.F68
            LayoutProfile.C52.name -> LayoutProfile.C52
            else -> throw LayoutImportException("unknown layout_profile '$value'")
        }

        val presentExtensionCount = EXTENSION_KEYS.count { json.has(it) }
        val hasExtension = presentExtensionCount > 0
        val completeExtension = presentExtensionCount == EXTENSION_KEYS.size
        if (hasExtension && !completeExtension) {
            throw LayoutImportException(
                "partial geometry extension group; all of $EXTENSION_KEYS are required",
            )
        }
        if (schemaVersion == "1.1" && !hasExtension) {
            throw LayoutImportException("schema 1.1 layout has no geometry extension group")
        }

        val drawableWidth = json.positiveInt(LayoutJson.KEY_DRAWABLE_WIDTH_PX)
        val drawableHeight = json.positiveInt(LayoutJson.KEY_DRAWABLE_HEIGHT_PX)
        val drawable = RectPx(0, 0, drawableWidth, drawableHeight)
        val coordinateSystem = json.string(LayoutJson.KEY_COORDINATE_SYSTEM)
        if (coordinateSystem != COORDINATE_SYSTEM) {
            throw LayoutImportException("unsupported coordinate_system '$coordinateSystem'")
        }
        val rotation = json.int(LayoutJson.KEY_PATTERN_ROTATION_DEG)
        if (rotation !in ROTATIONS) {
            throw LayoutImportException("unsupported pattern_rotation_deg $rotation")
        }
        val patternWidth = json.positiveInt(LayoutJson.KEY_PATTERN_WIDTH_PX)
        val patternHeight = json.positiveInt(LayoutJson.KEY_PATTERN_HEIGHT_PX)
        val quarterTurn = rotation % 180 != 0
        val expectedPatternWidth = if (quarterTurn) drawableHeight else drawableWidth
        val expectedPatternHeight = if (quarterTurn) drawableWidth else drawableHeight
        if (patternWidth != expectedPatternWidth || patternHeight != expectedPatternHeight) {
            throw LayoutImportException(
                "pattern canvas ${patternWidth}x$patternHeight is not the inverse-quarter-turn " +
                    "drawable ${drawableWidth}x$drawableHeight at $rotation deg",
            )
        }
        val dynamicRect = json.rect(LayoutJson.KEY_DYNAMIC_RECT_PX, "dynamic_rect_px")
        if (dynamicRect.widthPx <= 0 || dynamicRect.heightPx <= 0) {
            throw LayoutImportException("dynamic_rect_px must be a non-empty rectangle")
        }
        if (!drawable.contains(dynamicRect)) {
            throw LayoutImportException("dynamic_rect_px leaves the drawable")
        }
        val matrix = json.matrix(LayoutJson.KEY_PATTERN_TO_DRAWABLE)
        val expectedMatrix = patternToDrawableMatrix(
            rotation,
            expectedPatternWidth,
            expectedPatternHeight,
            0,
            0,
        ).toList()
        if (matrix != expectedMatrix) {
            throw LayoutImportException(
                "pattern_to_drawable must be the pure rotation of the complete logical canvas",
            )
        }

        val levelLow = json.int(LayoutJson.KEY_LEVEL_LOW)
        val levelHigh = json.int(LayoutJson.KEY_LEVEL_HIGH)
        if (levelLow !in 0..255 || levelHigh !in 0..255 || levelLow >= levelHigh) {
            throw LayoutImportException("level_low/level_high must satisfy 0 <= low < high <= 255")
        }

        val geometry: GeometryProfile?
        val padding: List<PaddingSlot>
        val samplingInnerFraction: Double?
        if (!hasExtension) {
            geometry = null
            padding = emptyList()
            samplingInnerFraction = null
        } else {
            val geometryName = json.string(LayoutJson.KEY_GEOMETRY_PROFILE)
            geometry = GeometryProfile.forWire(geometryName)
                ?: throw LayoutImportException("unknown geometry_profile '$geometryName'")
            if (geometry.layoutProfile != profile) {
                throw LayoutImportException(
                    "geometry '$geometryName' does not belong to layout_profile '${profile.name}'",
                )
            }
            if (json.int(LayoutJson.KEY_GRID_ROWS) != geometry.gridRows ||
                json.int(LayoutJson.KEY_GRID_COLS) != geometry.gridCols
            ) {
                throw LayoutImportException("grid_rows/grid_cols do not match '$geometryName'")
            }
            if (json.int(LayoutJson.KEY_MODULE_COUNT) != profile.moduleCount) {
                throw LayoutImportException("module_count does not match '${profile.name}'")
            }
            if (json.int(LayoutJson.KEY_PADDING_COUNT) != geometry.paddingCount) {
                throw LayoutImportException("padding_count does not match '$geometryName'")
            }
            if (json.string(LayoutJson.KEY_MODULE_ORDER) != GridPlacement.MODULE_ORDER) {
                throw LayoutImportException("module_order must be '${GridPlacement.MODULE_ORDER}'")
            }
            val sampling = json.number(LayoutJson.KEY_SAMPLING_INNER_FRACTION)
            if (kotlin.math.abs(sampling - EXPECTED_SAMPLING) > 1e-9) {
                throw LayoutImportException(
                    "sampling_inner_fraction must be $EXPECTED_SAMPLING",
                )
            }
            samplingInnerFraction = sampling
            padding = parsePadding(
                json.array(LayoutJson.KEY_PADDING),
                geometry.paddingCount,
                profile.moduleCount,
                dynamicRect,
                levelLow,
                levelHigh,
            )
        }

        val modules = parseModules(
            json.array(LayoutJson.KEY_MODULES),
            profile.moduleCount,
            dynamicRect,
        )
        val markers = parseMarkers(json.array(LayoutJson.KEY_MARKERS), drawable, dynamicRect)
        try {
            validatePlacement(
                geometry = geometry ?: GeometryProfile.legacy(profile),
                dynamicRect = dynamicRect,
                modules = modules,
                padding = padding,
                rotation = rotation,
                patternWidth = expectedPatternWidth,
                patternHeight = expectedPatternHeight,
            )
        } catch (error: LayoutImportException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw LayoutImportException("invalid integer placement: ${error.message}", error)
        }

        return ImportedLayout(
            schemaVersion = schemaVersion,
            layoutId = layoutId,
            profile = profile,
            geometry = geometry,
            drawableWidthPx = drawableWidth,
            drawableHeightPx = drawableHeight,
            patternRotationDeg = rotation,
            patternWidthPx = patternWidth,
            patternHeightPx = patternHeight,
            dynamicRectPx = dynamicRect,
            modules = modules,
            padding = padding,
            markers = markers,
            levelLow = levelLow,
            levelHigh = levelHigh,
            samplingInnerFraction = samplingInnerFraction,
            layoutType = layoutType,
        )
    }

    private fun parseModules(
        array: JsonArray,
        expectedCount: Int,
        dynamicRect: RectPx,
    ): List<ModuleRect> {
        if (array.size() != expectedCount) {
            throw LayoutImportException(
                "modules must contain exactly $expectedCount entries, got ${array.size()}",
            )
        }
        val modules = ArrayList<ModuleRect>(expectedCount)
        for (position in 0 until array.size()) {
            val element = array[position]
            if (!element.isJsonObject) throw LayoutImportException("module[$position] must be an object")
            val module = element.asJsonObject
            val index = module.int(LayoutJson.KEY_INDEX)
            if (index != position) {
                throw LayoutImportException("module index $index at position $position")
            }
            val rect = module.rect(LayoutJson.KEY_RECT_PX, "module[$index].rect_px")
            if (rect.widthPx <= 0 || rect.heightPx <= 0) {
                throw LayoutImportException("module[$index] rect must be non-empty")
            }
            if (!dynamicRect.contains(rect)) {
                throw LayoutImportException("module[$index] leaves dynamic_rect_px")
            }
            modules += ModuleRect(index, rect)
        }
        return modules
    }

    private fun parsePadding(
        array: JsonArray,
        expectedCount: Int,
        moduleCount: Int,
        dynamicRect: RectPx,
        levelLow: Int,
        levelHigh: Int,
    ): List<PaddingSlot> {
        if (array.size() != expectedCount) {
            throw LayoutImportException(
                "padding must contain exactly $expectedCount entries, got ${array.size()}",
            )
        }
        val padding = ArrayList<PaddingSlot>(expectedCount)
        for (position in 0 until array.size()) {
            val element = array[position]
            if (!element.isJsonObject) throw LayoutImportException("padding[$position] must be an object")
            val slot = element.asJsonObject
            val expectedSlot = moduleCount + position
            val slotIndex = slot.int(LayoutJson.KEY_SLOT_INDEX)
            if (slotIndex != expectedSlot) {
                throw LayoutImportException(
                    "padding slot_index $slotIndex at position $position, expected $expectedSlot",
                )
            }
            val role = slot.string(LayoutJson.KEY_ROLE)
            if (role != LayoutJson.ROLE_PADDING) {
                throw LayoutImportException("padding[$position] role must be '${LayoutJson.ROLE_PADDING}'")
            }
            val rect = slot.rect(LayoutJson.KEY_RECT_PX, "padding[$slotIndex].rect_px")
            if (rect.widthPx <= 0 || rect.heightPx <= 0) {
                throw LayoutImportException("padding[$slotIndex] rect must be non-empty")
            }
            if (!dynamicRect.contains(rect)) {
                throw LayoutImportException("padding[$slotIndex] leaves dynamic_rect_px")
            }
            val level = slot.int(LayoutJson.KEY_LEVEL)
            val expectedLevel = if (position % 2 == 0) levelLow else levelHigh
            if (level != expectedLevel) {
                throw LayoutImportException(
                    "padding[$slotIndex] level $level, expected $expectedLevel",
                )
            }
            padding += PaddingSlot(slotIndex, rect, level)
        }
        return padding
    }

    private fun parseMarkers(
        array: JsonArray,
        drawable: RectPx,
        dynamicRect: RectPx,
    ): List<ImportedMarker> {
        if (array.size() != 4) {
            throw LayoutImportException("markers must contain exactly 4 entries")
        }
        val ids = HashSet<Int>()
        val markers = ArrayList<ImportedMarker>(4)
        for (position in 0 until array.size()) {
            val element = array[position]
            if (!element.isJsonObject) throw LayoutImportException("marker[$position] must be an object")
            val marker = element.asJsonObject
            val id = marker.int(LayoutJson.KEY_ID)
            if (id !in 0..3 || !ids.add(id)) {
                throw LayoutImportException("marker id $id must be unique in 0..3")
            }
            val dictionary = marker.string(LayoutJson.KEY_DICTIONARY)
            if (dictionary != ArUcoDict4x4_50.DICTIONARY) {
                throw LayoutImportException("marker[$id] dictionary must be '${ArUcoDict4x4_50.DICTIONARY}'")
            }
            val markerSizeBits = marker.int(LayoutJson.KEY_MARKER_SIZE_BITS)
            if (markerSizeBits != ArUcoDict4x4_50.MARKER_SIZE_BITS) {
                throw LayoutImportException("marker[$id] marker_size_bits must be 4")
            }
            val borderBits = marker.int(LayoutJson.KEY_BORDER_BITS)
            if (borderBits != ArUcoDict4x4_50.BORDER_BITS) {
                throw LayoutImportException("marker[$id] border_bits must be 1")
            }
            val cellSizePx = marker.positiveInt(LayoutJson.KEY_CELL_SIZE_PX)
            val quietZonePx = marker.positiveInt(LayoutJson.KEY_QUIET_ZONE_PX)
            val cornersElement = marker.array(LayoutJson.KEY_CORNERS_PX)
            if (cornersElement.size() != 4) {
                throw LayoutImportException("marker[$id] corners_px must contain 4 points")
            }
            val corners = List(4) { index ->
                cornersElement[index].point("marker[$id].corners_px[$index]").also { point ->
                    if (point.x !in 0..drawable.widthPx || point.y !in 0..drawable.heightPx) {
                        throw LayoutImportException("marker[$id] corner $point leaves the drawable")
                    }
                }
            }
            val xs = corners.map { it.x }
            val ys = corners.map { it.y }
            val outer = RectPx(xs.min(), ys.min(), xs.max(), ys.max())
            if (outer.widthPx <= 0 || outer.heightPx <= 0) {
                throw LayoutImportException("marker[$id] corners do not describe a rectangle")
            }
            if (outer.intersects(dynamicRect)) {
                throw LayoutImportException("marker[$id] overlaps dynamic_rect_px")
            }
            markers += ImportedMarker(
                id = id,
                dictionary = dictionary,
                markerSizeBits = markerSizeBits,
                borderBits = borderBits,
                cellSizePx = cellSizePx,
                quietZonePx = quietZonePx,
                cornersPx = corners,
            )
        }
        return markers.sortedBy { it.id }
    }

    private fun JsonObject.string(name: String): String {
        val element = required(name)
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw LayoutImportException("$name must be a string")
        }
        return element.asString
    }

    private fun JsonObject.int(name: String): Int {
        val element = required(name)
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            throw LayoutImportException("$name must be an integer")
        }
        val value = element.asDouble
        if (!value.isFinite() || value % 1.0 != 0.0 || value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            throw LayoutImportException("$name must be an integer")
        }
        return value.toInt()
    }

    private fun JsonObject.positiveInt(name: String): Int {
        val value = int(name)
        if (value <= 0) throw LayoutImportException("$name must be positive")
        return value
    }

    private fun JsonObject.number(name: String): Double {
        val element = required(name)
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            throw LayoutImportException("$name must be a number")
        }
        val value = element.asDouble
        if (!value.isFinite()) throw LayoutImportException("$name must be finite")
        return value
    }

    private fun JsonObject.array(name: String): JsonArray {
        val element = required(name)
        if (!element.isJsonArray) throw LayoutImportException("$name must be an array")
        return element.asJsonArray
    }

    private fun JsonObject.rect(name: String, label: String): RectPx {
        val array = array(name)
        if (array.size() != 4) throw LayoutImportException("$label must contain 4 integers")
        val values = IntArray(4) { index ->
            val element = array[index]
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
                throw LayoutImportException("$label[$index] must be an integer")
            }
            val value = element.asDouble
            if (!value.isFinite() || value % 1.0 != 0.0 ||
                value < Int.MIN_VALUE || value > Int.MAX_VALUE
            ) {
                throw LayoutImportException("$label[$index] must be an integer")
            }
            value.toInt()
        }
        return try {
            RectPx(values[0], values[1], values[2], values[3])
        } catch (error: IllegalArgumentException) {
            throw LayoutImportException("$label is not a valid rectangle: ${error.message}", error)
        }
    }

    private fun JsonObject.matrix(name: String): List<Int> {
        val rows = array(name)
        if (rows.size() != 3) throw LayoutImportException("$name must contain 3 rows")
        val values = ArrayList<Int>(9)
        for (rowIndex in 0 until 3) {
            val row = rows[rowIndex]
            if (!row.isJsonArray || row.asJsonArray.size() != 3) {
                throw LayoutImportException("$name[$rowIndex] must contain 3 integers")
            }
            for (columnIndex in 0 until 3) {
                val element = row.asJsonArray[columnIndex]
                if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
                    throw LayoutImportException("$name[$rowIndex][$columnIndex] must be an integer")
                }
                val value = element.asDouble
                if (!value.isFinite() || value % 1.0 != 0.0 ||
                    value < Int.MIN_VALUE || value > Int.MAX_VALUE
                ) {
                    throw LayoutImportException("$name[$rowIndex][$columnIndex] must be an integer")
                }
                values += value.toInt()
            }
        }
        return values
    }

    /**
     * Re-derives the logical dynamic rectangle from the exported drawable rectangle by the
     * inverse of the contract rotation, then re-applies the integer grid placement and the
     * forward rotation. This rejects a module/padding list whose rectangles overlap, are
     * shifted, or were laid out against a different canvas. It never uses a percentage of the
     * drawable to guess the logical range.
     */
    private fun validatePlacement(
        geometry: GeometryProfile,
        dynamicRect: RectPx,
        modules: List<ModuleRect>,
        padding: List<PaddingSlot>,
        rotation: Int,
        patternWidth: Int,
        patternHeight: Int,
    ) {
        val logicalDynamic = inverseRotateRect(
            dynamicRect,
            rotation,
            patternWidth,
            patternHeight,
        )
        val slots = GridPlacement.slots(
            logicalDynamic,
            geometry.gridRows,
            geometry.gridCols,
            geometry.gridRows * geometry.gridCols,
        )
        val expectedDynamic = mapRectToDrawable(
            rotation,
            patternWidth,
            patternHeight,
            0,
            0,
            logicalDynamic,
        )
        if (expectedDynamic != dynamicRect) {
            throw LayoutImportException("dynamic_rect_px does not match the inverse-rotated placement")
        }
        for (index in modules.indices) {
            val expected = mapRectToDrawable(
                rotation,
                patternWidth,
                patternHeight,
                0,
                0,
                slots[index],
            )
            if (modules[index].rectPx != expected) {
                throw LayoutImportException("module[$index] is not the integer grid slot for this geometry")
            }
        }
        for (offset in padding.indices) {
            val expected = mapRectToDrawable(
                rotation,
                patternWidth,
                patternHeight,
                0,
                0,
                slots[geometry.moduleCount + offset],
            )
            if (padding[offset].rectPx != expected) {
                throw LayoutImportException(
                    "padding[${padding[offset].slotIndex}] is not the integer grid slot for this geometry",
                )
            }
        }
    }

    private fun inverseRotateRect(
        rect: RectPx,
        rotation: Int,
        patternWidth: Int,
        patternHeight: Int,
    ): RectPx {
        val corners = listOf(
            inverseRotatePoint(rect.x0, rect.y0, rotation, patternWidth, patternHeight),
            inverseRotatePoint(rect.x1, rect.y0, rotation, patternWidth, patternHeight),
            inverseRotatePoint(rect.x1, rect.y1, rotation, patternWidth, patternHeight),
            inverseRotatePoint(rect.x0, rect.y1, rotation, patternWidth, patternHeight),
        )
        return RectPx(
            corners.minOf { it.x },
            corners.minOf { it.y },
            corners.maxOf { it.x },
            corners.maxOf { it.y },
        )
    }

    private fun inverseRotatePoint(
        x: Int,
        y: Int,
        rotation: Int,
        patternWidth: Int,
        patternHeight: Int,
    ): PointPx = when (rotation) {
        0 -> PointPx(x, y)
        90 -> PointPx(y, patternHeight - x)
        180 -> PointPx(patternWidth - x, patternHeight - y)
        270 -> PointPx(patternWidth - y, x)
        else -> throw LayoutImportException("unsupported pattern_rotation_deg $rotation")
    }

    private fun JsonElement.point(label: String): PointPx {
        if (!isJsonArray || asJsonArray.size() != 2) {
            throw LayoutImportException("$label must contain [x, y]")
        }
        val x = asJsonArray[0]
        val y = asJsonArray[1]
        if (!x.isJsonPrimitive || !x.asJsonPrimitive.isNumber ||
            !y.isJsonPrimitive || !y.asJsonPrimitive.isNumber
        ) {
            throw LayoutImportException("$label must contain two integers")
        }
        val xd = x.asDouble
        val yd = y.asDouble
        if (!xd.isFinite() || !yd.isFinite() || xd % 1.0 != 0.0 || yd % 1.0 != 0.0 ||
            xd < Int.MIN_VALUE || xd > Int.MAX_VALUE || yd < Int.MIN_VALUE || yd > Int.MAX_VALUE
        ) {
            throw LayoutImportException("$label must contain two integers")
        }
        return PointPx(xd.toInt(), yd.toInt())
    }

    private fun JsonObject.required(name: String): JsonElement =
        get(name) ?: throw LayoutImportException("missing required field '$name'")

    private const val COORDINATE_SYSTEM = "DRAWABLE_TOP_LEFT_PIXEL_EDGES"
}
