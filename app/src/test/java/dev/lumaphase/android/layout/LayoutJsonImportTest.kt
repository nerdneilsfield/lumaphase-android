package dev.lumaphase.android.layout

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.lumaphase.android.protocol.LayoutProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layout import checks against the shared placement fixture and complete JSON documents.
 *
 * The placement fixture is the independent placement/module oracle. It has no markers and no
 * `layouts` root, so it is also the negative test for a placement-only file. The complete
 * files add marker objects with contract-valid metadata; their module and padding values are
 * copied from the fixture, not from the parser.
 */
class LayoutJsonImportTest {

    @Test
    fun schema11LayoutMatchesTheSharedFixture() {
        val imported = LayoutJsonImport.parse(resource("camera/valid-1.1.json"))
        assertEquals(1, imported.size)
        val layout = imported.single()
        val fixture = fixtureCase("F68-G6x12-r0")

        assertEquals("camera-test-f68-grid", layout.layoutId)
        assertEquals("1.1", layout.schemaVersion)
        assertEquals(LayoutProfile.F68, layout.profile)
        assertEquals(GeometryProfile.F68_G6X12, layout.geometry)
        assertEquals(fixture.get("drawable_width_px").asInt, layout.drawableWidthPx)
        assertEquals(fixture.get("drawable_height_px").asInt, layout.drawableHeightPx)
        assertEquals(fixture.get("pattern_rotation_deg").asInt, layout.patternRotationDeg)
        assertEquals(rect(fixture.getAsJsonArray("dynamic_rect_px")), layout.dynamicRectPx)
        assertEquals(68, layout.modules.size)
        assertEquals(4, layout.padding.size)
        assertEquals(4, layout.markers.size)
        assertEquals(listOf(0, 1, 2, 3), layout.markers.map { it.id })
        assertTrue(layout.markers.all { it.dictionary == "DICT_4X4_50" })
        assertTrue(layout.markers.all { it.markerSizeBits == 4 && it.borderBits == 1 })

        val fixtureModules = fixture.getAsJsonArray("modules")
        for (index in 0 until fixtureModules.size()) {
            val expected = fixtureModules[index].asJsonObject
            assertEquals(index, layout.modules[index].index)
            assertEquals(rect(expected.getAsJsonArray("rect_px")), layout.modules[index].rectPx)
        }
        val fixturePadding = fixture.getAsJsonArray("padding")
        for (index in 0 until fixturePadding.size()) {
            val expected = fixturePadding[index].asJsonObject
            assertEquals(expected.get("slot_index").asInt, layout.padding[index].slotIndex)
            assertEquals(rect(expected.getAsJsonArray("rect_px")), layout.padding[index].rectPx)
            assertEquals(expected.get("level").asInt, layout.padding[index].level)
        }
    }

    @Test
    fun schema12OptcIsAcceptedAndTypedNonOptcIsRefusedByCameraImport() {
        val document = validObject("camera/valid-1.1.json")
        document.addProperty("schema_version", "1.2")
        layout(document).addProperty("layout_type", "OPTC")
        val imported = LayoutJsonImport.parse(document.toString()).single()
        assertEquals("1.2", imported.schemaVersion)
        assertEquals(LayoutType.OPTC, imported.layoutType)

        layout(document).addProperty("layout_type", "CALIBRATION_TARGET")
        assertThrows(LayoutImportException::class.java) {
            LayoutJsonImport.parse(document.toString())
        }
    }

    @Test
    fun schema10LayoutWithoutExtensionIsStillSingleRow() {
        val imported = LayoutJsonImport.parse(resource("camera/valid-1.0.json"))
        val layout = imported.single()
        val fixture = fixtureCase("F68-r0")

        assertEquals("1.0", layout.schemaVersion)
        assertEquals(LayoutProfile.F68, layout.profile)
        assertNull(layout.geometry)
        assertTrue(layout.isLegacySingleRow)
        assertEquals(0, layout.padding.size)
        assertNull(layout.samplingInnerFraction)
        assertEquals(68, layout.modules.size)
        val fixtureModules = fixture.getAsJsonArray("modules")
        for (index in 0 until fixtureModules.size()) {
            assertEquals(
                rect(fixtureModules[index].asJsonObject.getAsJsonArray("rect_px")),
                layout.modules[index].rectPx,
            )
        }
    }

    @Test
    fun macStyleDoubleMatrixIsAccepted() {
        // The Mac writer stores the homogeneous matrix as JSON doubles (0.0/1.0), while the
        // Android fixture test resources use integers. Both are integral values and must be
        // accepted without rewriting the imported values.
        val document = validObject("camera/valid-1.1.json")
        layout(document).add(
            "pattern_to_drawable",
            JsonParser.parseString(
                "[[1.0,0.0,0.0],[0.0,1.0,0.0],[0.0,0.0,1.0]]",
            ),
        )
        val imported = LayoutJsonImport.parse(document.toString()).single()
        assertEquals(GeometryProfile.F68_G6X12, imported.geometry)
        assertEquals(68, imported.modules.size)
    }

    @Test
    fun wrongFullCanvasMatrixSizeAndModulePlacementAreRejected() {
        val wrongMatrix = validObject("camera/valid-1.1.json")
        layout(wrongMatrix).add(
            "pattern_to_drawable",
            JsonParser.parseString("[[1,0,1],[0,1,0],[0,0,1]]"),
        )
        assertThrows(LayoutImportException::class.java) {
            LayoutJsonImport.parse(wrongMatrix.toString())
        }

        val wrongCanvas = validObject("camera/valid-1.1.json")
        layout(wrongCanvas).addProperty("pattern_width_px", 120)
        assertThrows(LayoutImportException::class.java) {
            LayoutJsonImport.parse(wrongCanvas.toString())
        }

        val misaligned = validObject("camera/valid-1.1.json")
        val firstModule = layout(misaligned).getAsJsonArray("modules")[0].asJsonObject
        firstModule.add(
            "rect_px",
            JsonParser.parseString("[8,11,16,24]"),
        )
        assertThrows(LayoutImportException::class.java) {
            LayoutJsonImport.parse(misaligned.toString())
        }

        val overlapping = validObject("camera/valid-1.1.json")
        val modules = layout(overlapping).getAsJsonArray("modules")
        modules[1].asJsonObject.add(
            "rect_px",
            modules[0].asJsonObject.getAsJsonArray("rect_px").deepCopy(),
        )
        assertThrows(LayoutImportException::class.java) {
            LayoutJsonImport.parse(overlapping.toString())
        }
    }

    @Test
    fun realMacLayoutsAreAccepted() {
        val imported = LayoutJsonImport.parse(resource("camera/real-mac-1.1.json"))
        assertEquals(2, imported.size)
        assertEquals(GeometryProfile.F68_G6X12, imported[0].geometry)
        assertEquals(GeometryProfile.C52_G5X12, imported[1].geometry)
        assertEquals(68, imported[0].modules.size)
        assertEquals(52, imported[1].modules.size)
        assertEquals(4, imported[0].markers.size)
        assertEquals(4, imported[1].markers.size)
    }

    @Test
    fun oldAndroidLocalCanvasLayoutsAreRejectedNotRewritten() {
        // These are the reviewer's actual-writer reconstructions of the old Android output.
        // Their pattern_width/height are the former 96%/68% local region and their matrix has
        // the centring translation, so they are not contract-compliant under section 3.2.
        // They remain negative fixtures; expected files are not edited to pretend otherwise.
        for (name in listOf("camera/real-android-1.0.json", "camera/real-android-1.1.json")) {
            val error = assertThrows(LayoutImportException::class.java) {
                LayoutJsonImport.parse(resource(name))
            }
            assertTrue(
                "$name should fail on the complete-canvas rule: ${error.message}",
                error.message?.contains("inverse-quarter-turn") == true,
            )
        }
    }

    @Test
    fun newBuilderOutputIsAcceptedForEveryGeometryAndRotation() {
        for (geometry in GeometryProfile.entries) {
            for (rotation in PatternLayoutBuilder.ROTATIONS_DEG) {
                val layout = PatternLayoutBuilder.build(
                    "builder-${geometry.wire}-r$rotation",
                    geometry,
                    1080,
                    2400,
                    rotation,
                )
                val imported = LayoutJsonImport.parse(builderDocument(layout)).single()
                assertEquals(layout.layoutId, imported.layoutId)
                assertEquals(geometry, imported.geometry)
                assertEquals(layout.profile, imported.profile)
                assertEquals(layout.patternWidthPx, imported.patternWidthPx)
                assertEquals(layout.patternHeightPx, imported.patternHeightPx)
                assertEquals(layout.dynamicRectPx, imported.dynamicRectPx)
                assertEquals(layout.modules, imported.modules)
                assertEquals(layout.padding, imported.padding)
                assertEquals(layout.markers.map { it.id }, imported.markers.map { it.id })
                assertEquals(4, imported.markers.size)
            }
        }
    }

    @Test
    fun multipleLayoutsAreReturnedInFileOrderForExplicitSelection() {
        val imported = LayoutJsonImport.parse(resource("camera/multi-1.1.json"))
        assertEquals(listOf("camera-test-f68-grid", "camera-test-c52-grid"), imported.map { it.layoutId })
        assertEquals(GeometryProfile.F68_G6X12, imported[0].geometry)
        assertEquals(GeometryProfile.C52_G9X6, imported[1].geometry)
        assertEquals(LayoutProfile.C52, imported[1].profile)
    }

    @Test
    fun partialExtensionAndSchema11WithoutExtensionAreRejected() {
        val partial = validObject("camera/valid-1.1.json")
        layout(partial).remove("padding")
        assertThrows(LayoutImportException::class.java) {
            LayoutJsonImport.parse(partial.toString())
        }

        val missing = validObject("camera/valid-1.1.json")
        val source = layout(missing)
        EXTENSIONS.forEach { source.remove(it) }
        assertThrows(LayoutImportException::class.java) {
            LayoutJsonImport.parse(missing.toString())
        }
    }

    @Test
    fun partialExtensionOnA10FileIsRejected() {
        val partial = validObject("camera/valid-1.0.json")
        layout(partial).addProperty("geometry_profile", "F68")
        assertThrows(LayoutImportException::class.java) {
            LayoutJsonImport.parse(partial.toString())
        }
    }

    @Test
    fun unknownGeometryAndCountMismatchesAreRejected() {
        val unknown = validObject("camera/valid-1.1.json")
        layout(unknown).addProperty("geometry_profile", "F68-G4x18")
        assertThrows(LayoutImportException::class.java) {
            LayoutJsonImport.parse(unknown.toString())
        }

        val wrongCount = validObject("camera/valid-1.1.json")
        layout(wrongCount).addProperty("module_count", 52)
        assertThrows(LayoutImportException::class.java) {
            LayoutJsonImport.parse(wrongCount.toString())
        }

        val wrongPadding = validObject("camera/valid-1.1.json")
        layout(wrongPadding).getAsJsonArray("padding").remove(0)
        assertThrows(LayoutImportException::class.java) {
            LayoutJsonImport.parse(wrongPadding.toString())
        }

        val wrongLevel = validObject("camera/valid-1.1.json")
        val firstPadding = layout(wrongLevel).getAsJsonArray("padding")[0].asJsonObject
        firstPadding.addProperty("level", 224)
        assertThrows(LayoutImportException::class.java) {
            LayoutJsonImport.parse(wrongLevel.toString())
        }
    }

    @Test
    fun duplicateLayoutIdAndMalformedMarkersAreRejected() {
        val duplicate = validObject("camera/valid-1.1.json")
        val layouts = duplicate.getAsJsonArray("layouts")
        val copy = JsonParser.parseString(layouts[0].toString()).asJsonObject
        layouts.add(copy)
        assertThrows(LayoutImportException::class.java) {
            LayoutJsonImport.parse(duplicate.toString())
        }

        val missingMarker = validObject("camera/valid-1.1.json")
        layout(missingMarker).getAsJsonArray("markers").remove(0)
        assertThrows(LayoutImportException::class.java) {
            LayoutJsonImport.parse(missingMarker.toString())
        }

        val duplicateMarker = validObject("camera/valid-1.1.json")
        val markers = layout(duplicateMarker).getAsJsonArray("markers")
        markers[1].asJsonObject.addProperty("id", 0)
        assertThrows(LayoutImportException::class.java) {
            LayoutJsonImport.parse(duplicateMarker.toString())
        }

        val markerOverlap = validObject("camera/valid-1.1.json")
        val firstMarker = layout(markerOverlap).getAsJsonArray("markers")[0].asJsonObject
        firstMarker.add(
            "corners_px",
            JsonParser.parseString("[[7,11],[13,11],[13,17],[7,17]]").asJsonArray,
        )
        assertThrows(LayoutImportException::class.java) {
            LayoutJsonImport.parse(markerOverlap.toString())
        }
    }

    @Test
    fun aPlacementOnlyFixtureIsNotAcceptedAsALayoutDocument() {
        assertThrows(LayoutImportException::class.java) {
            LayoutJsonImport.parse(resource("cr-001-layouts.json"))
        }
    }

    @Test
    fun aLayoutWithoutMarkersIsNotSilentlyAccepted() {
        val noMarkers = validObject("camera/valid-1.1.json")
        layout(noMarkers).remove("markers")
        assertThrows(LayoutImportException::class.java) {
            LayoutJsonImport.parse(noMarkers.toString())
        }
    }

    /** Test-only feed for the parser; it is not claimed to be production JsonWriter bytes. */
    private fun builderDocument(layout: PatternLayout): String {
        val root = JsonObject()
        root.addProperty("schema_version", "1.1")
        val layouts = JsonArray()
        val objectJson = JsonObject()
        objectJson.addProperty("layout_id", layout.layoutId)
        objectJson.addProperty("layout_profile", layout.profile.name)
        objectJson.addProperty("geometry_profile", layout.geometry.wire)
        objectJson.addProperty("grid_rows", layout.geometry.gridRows)
        objectJson.addProperty("grid_cols", layout.geometry.gridCols)
        objectJson.addProperty("module_count", layout.geometry.moduleCount)
        objectJson.addProperty("padding_count", layout.geometry.paddingCount)
        objectJson.addProperty("module_order", "row-major")
        objectJson.addProperty("sampling_inner_fraction", 0.6)
        objectJson.addProperty("drawable_width_px", layout.drawableWidthPx)
        objectJson.addProperty("drawable_height_px", layout.drawableHeightPx)
        objectJson.addProperty("coordinate_system", layout.coordinateSystem)
        objectJson.addProperty("pattern_rotation_deg", layout.patternRotationDeg)
        objectJson.addProperty("pattern_width_px", layout.patternWidthPx)
        objectJson.addProperty("pattern_height_px", layout.patternHeightPx)
        objectJson.add("pattern_to_drawable", intRows(layout.patternToDrawable))
        objectJson.add("dynamic_rect_px", rectJson(layout.dynamicRectPx))
        val modules = JsonArray()
        layout.modules.forEach { module ->
            val item = JsonObject()
            item.addProperty("index", module.index)
            item.add("rect_px", rectJson(module.rectPx))
            modules.add(item)
        }
        objectJson.add("modules", modules)
        val padding = JsonArray()
        layout.padding.forEach { slot ->
            val item = JsonObject()
            item.addProperty("slot_index", slot.slotIndex)
            item.addProperty("role", LayoutJson.ROLE_PADDING)
            item.add("rect_px", rectJson(slot.rectPx))
            item.addProperty("level", slot.level)
            padding.add(item)
        }
        objectJson.add("padding", padding)
        val markers = JsonArray()
        layout.markers.forEach { marker ->
            val item = JsonObject()
            item.addProperty("id", marker.id)
            item.addProperty("dictionary", marker.dictionary)
            item.addProperty("marker_size_bits", marker.markerSizeBits)
            item.addProperty("border_bits", marker.borderBits)
            item.addProperty("cell_size_px", marker.cellSizePx)
            item.addProperty("quiet_zone_px", marker.quietZonePx)
            val corners = JsonArray()
            marker.cornersPx.forEach { point ->
                corners.add(JsonArray().apply {
                    add(point.x)
                    add(point.y)
                })
            }
            item.add("corners_px", corners)
            markers.add(item)
        }
        objectJson.add("markers", markers)
        objectJson.addProperty("level_low", layout.levelLow)
        objectJson.addProperty("level_high", layout.levelHigh)
        layouts.add(objectJson)
        root.add("layouts", layouts)
        return root.toString()
    }

    private fun rectJson(rect: RectPx): JsonArray = JsonArray().apply {
        rect.edges.forEach { add(it) }
    }

    private fun intRows(values: List<Int>): JsonArray = JsonArray().apply {
        for (row in 0 until 3) {
            add(JsonArray().apply {
                for (column in 0 until 3) add(values[row * 3 + column])
            })
        }
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "missing test resource $name"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun fixtureCase(caseId: String): JsonObject {
        val root = JsonParser.parseString(resource("cr-001-layouts.json")).asJsonObject
        val cases = root.getAsJsonArray("cases")
        for (index in 0 until cases.size()) {
            val candidate = cases[index].asJsonObject
            if (candidate.get("case_id").asString == caseId) return candidate
        }
        error("fixture case $caseId not found")
    }

    private fun validObject(name: String): JsonObject =
        JsonParser.parseString(resource(name)).asJsonObject

    private fun layout(document: JsonObject): JsonObject =
        document.getAsJsonArray("layouts")[0].asJsonObject

    private fun rect(array: JsonArray): RectPx =
        RectPx(array[0].asInt, array[1].asInt, array[2].asInt, array[3].asInt)

    private companion object {
        val EXTENSIONS = listOf(
            "geometry_profile",
            "grid_rows",
            "grid_cols",
            "module_count",
            "padding_count",
            "module_order",
            "sampling_inner_fraction",
            "padding",
        )
    }
}
