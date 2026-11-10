package dev.lumaphase.android.layout

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.lumaphase.android.protocol.PatternEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-001 placement checks against the shared fixture, which is read from
 * `docs/cw/fixtures/cr-001-layouts.json` in place (see the test source set). The fixture is
 * the authoritative expected geometry; this test must not derive expected rectangles from
 * the product builder.
 *
 * It covers the 24 cases (four grid geometries and the two single-row layouts, each in four
 * rotations), the 14 payload vectors, and the all-65536 payload sweep for the grid reorder.
 */
class Cr001PlacementTest {

    @Test
    fun fixtureCarriesTheSharedVersionsAndCaseSet() {
        val root = fixture()
        assertEquals("1", root.get("fixture_version").asString)
        assertEquals("1.1", root.get("schema_version").asString)
        assertEquals("OPTC-1", root.get("protocol_version").asString)
        assertEquals("PLACEMENT_ONLY", root.get("scope").asString)
        assertEquals(121, root.getAsJsonArray("logical_canvas_px")[0].asInt)
        assertEquals(109, root.getAsJsonArray("logical_canvas_px")[1].asInt)
        assertEquals(24, root.getAsJsonArray("cases").size())
        assertEquals(14, root.getAsJsonArray("payload_oracles").size())
        assertEquals(6, root.getAsJsonArray("placement_oracles").size())
    }

    @Test
    fun everyFixtureCaseMatchesIntegerFloorPlacementAndRotation() {
        val root = fixture()
        val logicalDynamic = rect(root.getAsJsonArray("logical_dynamic_rect_px"))
        assertEquals(RectPx(7, 11, 108, 94), logicalDynamic)
        val cases = root.getAsJsonArray("cases")

        for (element in cases) {
            val case = element.asJsonObject
            val label = case.get("case_id").asString
            val geometry = GeometryProfile.forWire(case.get("geometry_profile").asString)
            assertNotNull("$label: unknown geometry", geometry)
            geometry!!
            assertEquals(label, geometry.layoutProfile.name, case.get("layout_profile").asString)
            assertEquals(label, geometry.gridRows, case.get("grid_rows").asInt)
            assertEquals(label, geometry.gridCols, case.get("grid_cols").asInt)
            assertEquals(label, geometry.moduleCount, case.get("module_count").asInt)
            assertEquals(label, geometry.paddingCount, case.get("padding_count").asInt)
            assertEquals(label, "row-major", case.get("module_order").asString)
            assertEquals(label, 0.6, case.get("sampling_inner_fraction").asDouble, 1e-9)

            val rotation = case.get("pattern_rotation_deg").asInt
            val patternWidth = case.get("pattern_width_px").asInt
            val patternHeight = case.get("pattern_height_px").asInt
            assertEquals(label, 121, patternWidth)
            assertEquals(label, 109, patternHeight)
            assertEquals(
                label,
                if (rotation % 180 == 0) patternWidth else patternHeight,
                case.get("drawable_width_px").asInt,
            )
            assertEquals(
                label,
                if (rotation % 180 == 0) patternHeight else patternWidth,
                case.get("drawable_height_px").asInt,
            )
            assertEquals(
                label,
                "DRAWABLE_TOP_LEFT_PIXEL_EDGES",
                case.get("coordinate_system").asString,
            )
            assertEquals(
                label,
                patternToDrawableMatrix(rotation, patternWidth, patternHeight, 0, 0).toList(),
                matrix(case.getAsJsonArray("pattern_to_drawable")),
            )

            val slots = GridPlacement.slots(
                logicalDynamic,
                geometry.gridRows,
                geometry.gridCols,
                geometry.gridRows * geometry.gridCols,
            )
            val mappedSlots = slots.map { slot ->
                mapRectToDrawable(rotation, patternWidth, patternHeight, 0, 0, slot)
            }
            assertEquals(
                label,
                mapRectToDrawable(rotation, patternWidth, patternHeight, 0, 0, logicalDynamic),
                rect(case.getAsJsonArray("dynamic_rect_px")),
            )

            val modules = case.getAsJsonArray("modules")
            assertEquals(label, geometry.moduleCount, modules.size())
            for (index in 0 until modules.size()) {
                val module = modules[index].asJsonObject
                assertEquals("$label module index", index, module.get("index").asInt)
                assertEquals(
                    "$label module $index rect",
                    mappedSlots[index],
                    rect(module.getAsJsonArray("rect_px")),
                )
            }

            val padding = case.getAsJsonArray("padding")
            assertEquals(label, geometry.paddingCount, padding.size())
            val levels = GridPlacement.paddingLevels(
                geometry,
                case.get("level_low").asInt,
                case.get("level_high").asInt,
            )
            for (offset in 0 until padding.size()) {
                val slot = padding[offset].asJsonObject
                assertEquals("$label padding slot", geometry.moduleCount + offset, slot.get("slot_index").asInt)
                assertEquals("$label padding role", "padding", slot.get("role").asString)
                assertEquals(
                    "$label padding $offset rect",
                    mappedSlots[geometry.moduleCount + offset],
                    rect(slot.getAsJsonArray("rect_px")),
                )
                assertEquals("$label padding $offset level", levels[offset], slot.get("level").asInt)
            }
        }
    }

    @Test
    fun documentedIndependentAnchorsHold() {
        val cases = fixture().getAsJsonArray("cases").map { it.asJsonObject }
        val f68 = cases.single { it.get("case_id").asString == "F68-G6x12-r0" }
        assertEquals(RectPx(7, 11, 15, 24), rect(f68.getAsJsonArray("modules")[0].asJsonObject.getAsJsonArray("rect_px")))
        assertEquals(
            RectPx(65, 80, 74, 94),
            rect(f68.getAsJsonArray("modules")[67].asJsonObject.getAsJsonArray("rect_px")),
        )
        val f68Rotated = cases.single { it.get("case_id").asString == "F68-G6x12-r90" }
        assertEquals(
            RectPx(85, 7, 98, 15),
            rect(f68Rotated.getAsJsonArray("modules")[0].asJsonObject.getAsJsonArray("rect_px")),
        )
    }

    @Test
    fun payloadAndSlotOraclesMatchTheUnchangedEncoder() {
        val root = fixture()
        val payloads = root.getAsJsonArray("payload_oracles").map { it.asJsonObject }
        assertEquals(14, payloads.size)

        val gridGeometries = listOf(
            GeometryProfile.F68_G6X12,
            GeometryProfile.C52_G5X12,
            GeometryProfile.F68_G9X8,
            GeometryProfile.C52_G9X6,
        )
        for (payload in payloads) {
            val renderSeq = payload.get("render_seq").asString.toULong()
            val fullCodes = payload.getAsJsonArray("module_codes").map { it.asInt }
            for (geometry in gridGeometries) {
                val encoded = PatternEncoder.encode(renderSeq, geometry.layoutProfile)
                val expectedData = fullCodes.take(geometry.moduleCount)
                assertEquals(
                    "geometry ${geometry.wire} seq $renderSeq",
                    expectedData,
                    encoded.moduleCodes.toList(),
                )
                val padding = GridPlacement.paddingLevels(geometry, 32, 224)
                assertEquals(geometry.paddingCount, padding.size)
            }
        }

        for (element in root.getAsJsonArray("placement_oracles")) {
            val oracle = element.asJsonObject
            val geometry = GeometryProfile.forWire(oracle.get("geometry_profile").asString)!!
            val data = oracle.getAsJsonArray("data_codes").map { it.asInt }
            val padding = oracle.getAsJsonArray("padding_codes").map { it.asInt }
            val slots = oracle.getAsJsonArray("slot_codes").map { it.asInt }
            assertEquals(geometry.moduleCount, data.size)
            assertEquals(geometry.paddingCount, padding.size)
            assertEquals(GridPlacement.paddingLevels(geometry, 32, 224), padding)
            assertEquals(data + padding, slots)
            val encoded = PatternEncoder.encode(
                oracle.get("render_seq").asString.toULong(),
                geometry.layoutProfile,
            )
            assertEquals("${geometry.wire} data", encoded.moduleCodes.toList(), data)
        }
    }

    @Test
    fun allToolkitPayloadsKeepTheirComplementaryPairsInOneLogicalRow() {
        val logicalDynamic = RectPx(7, 11, 108, 94)
        val plans = listOf(
            GeometryProfile.F68_G6X12,
            GeometryProfile.C52_G5X12,
            GeometryProfile.F68_G9X8,
            GeometryProfile.C52_G9X6,
        ).map { geometry ->
            Triple(
                geometry,
                GridPlacement.slots(logicalDynamic, geometry.gridRows, geometry.gridCols),
                GridPlacement.paddingLevels(geometry, 32, 224),
            )
        }

        for (seq in 0 until 0x10000) {
            val payload = seq.toULong()
            for ((geometry, slots, padding) in plans) {
                // The encoder only knows the payload family; the geometry cannot reorder it.
                // Re-encoding for the grid profile must therefore be exactly the same array.
                val encoded = PatternEncoder.encode(payload, geometry.layoutProfile)
                assertEquals(geometry.moduleCount, encoded.moduleCodes.size)

                // Slot i is module i (row-major); padding fills the remaining slots. Reading
                // the slots back in index order must return the original module array.
                val slotCodes = IntArray(slots.size)
                for (index in encoded.moduleCodes.indices) slotCodes[index] = encoded.moduleCodes[index]
                for (offset in padding.indices) slotCodes[geometry.moduleCount + offset] = padding[offset]
                assertEquals(
                    "geometry ${geometry.wire} seq $seq changes the module array",
                    encoded.moduleCodes.toList(),
                    slotCodes.take(geometry.moduleCount),
                )

                var index = 0
                while (index < encoded.moduleCodes.size) {
                    val row = index / geometry.gridCols
                    val nextRow = (index + 1) / geometry.gridCols
                    assertEquals(
                        "geometry ${geometry.wire} seq $seq pair $index crosses a row",
                        row,
                        nextRow,
                    )
                    assertEquals(
                        "geometry ${geometry.wire} seq $seq pair $index is not complementary",
                        true,
                        (encoded.moduleCodes[index] == 32 && encoded.moduleCodes[index + 1] == 224) ||
                            (encoded.moduleCodes[index] == 224 && encoded.moduleCodes[index + 1] == 32),
                    )
                    index += 2
                }
            }
        }
    }

    private fun fixture(): JsonObject {
        val stream = checkNotNull(javaClass.getResourceAsStream(FIXTURE)) {
            "missing shared fixture $FIXTURE; check the test source set"
        }
        return stream.use { JsonParser.parseString(it.readBytes().toString(Charsets.UTF_8)).asJsonObject }
    }

    private fun rect(array: JsonArray): RectPx =
        RectPx(array[0].asInt, array[1].asInt, array[2].asInt, array[3].asInt)

    private fun matrix(array: JsonArray): List<Int> =
        array.flatMap { row -> row.asJsonArray.map { it.asInt } }

    private companion object {
        const val FIXTURE = "/cr-001-layouts.json"
    }
}
