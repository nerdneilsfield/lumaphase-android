package dev.lumaphase.android.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInLayoutsTest {
    @Test
    fun catalogNamesEightExplicitSourceReferencesAndDefaultsToPortraitF68LargeTag() {
        assertEquals(8, BuiltInLayouts.PRESETS.size)
        assertEquals(
            BuiltInLayouts.PRESETS.size,
            BuiltInLayouts.PRESETS.map { it.id }.toSet().size,
        )
        assertEquals(BuiltInLayouts.LARGE_TAG_PRESET_ID, BuiltInLayouts.DEFAULT.id)
        assertEquals(1080, BuiltInLayouts.DEFAULT.referenceWidthPx)
        assertEquals(2400, BuiltInLayouts.DEFAULT.referenceHeightPx)
        assertEquals(GeometryProfile.F68_G9X8, BuiltInLayouts.DEFAULT.geometry)
        assertTrue(BuiltInLayouts.DEFAULT.largeTags)

        val expected = listOf(
            "builtin-android-portrait-f68-g9x8" to GeometryProfile.F68_G9X8,
            "builtin-android-portrait-c52-g9x6" to GeometryProfile.C52_G9X6,
            "builtin-android-portrait-f68-single" to GeometryProfile.F68,
            "builtin-android-portrait-c52-single" to GeometryProfile.C52,
            "builtin-android-landscape-f68-g6x12" to GeometryProfile.F68_G6X12,
            "builtin-android-landscape-c52-g5x12" to GeometryProfile.C52_G5X12,
            BuiltInLayouts.LARGE_TAG_PRESET_ID to GeometryProfile.F68_G9X8,
            BuiltInLayouts.C52_LARGE_TAG_PRESET_ID to GeometryProfile.C52_G9X6,
        )
        expected.forEachIndexed { index, (id, geometry) ->
            val preset = BuiltInLayouts.PRESETS[index]
            assertEquals(id, preset.id)
            assertEquals(geometry, preset.geometry)
            assertTrue(preset.label.contains(preset.sourceReference))
        }
        BuiltInLayouts.PRESETS.take(4).forEach {
            assertEquals(1080, it.referenceWidthPx)
            assertEquals(2400, it.referenceHeightPx)
        }
        BuiltInLayouts.PRESETS.subList(4, 6).forEach {
            assertEquals(2400, it.referenceWidthPx)
            assertEquals(1080, it.referenceHeightPx)
        }
        BuiltInLayouts.PRESETS.subList(6, 8).forEach {
            assertEquals(1080, it.referenceWidthPx)
            assertEquals(2400, it.referenceHeightPx)
            assertTrue(it.largeTags)
        }
        assertEquals(GeometryProfile.F68_G9X8, BuiltInLayouts.PRESETS[6].geometry)
        assertEquals(GeometryProfile.C52_G9X6, BuiltInLayouts.PRESETS[7].geometry)
    }

    @Test
    fun c52LargeTagPresetMatchesTheC52GridLargeChoice() {
        val preset = BuiltInLayouts.PRESETS.single {
            it.id == BuiltInLayouts.C52_LARGE_TAG_PRESET_ID
        }
        assertEquals(GeometryProfile.C52_G9X6, preset.geometry)
        assertTrue(preset.largeTags)
        for (rotation in BuiltInLayouts.ROTATIONS_DEG) {
            val imported = BuiltInLayouts.build(BuiltInSelection(preset, rotation))
            val expected = PatternLayoutBuilder.build(
                "${preset.id}-r$rotation",
                LayoutChoice.C52_GRID_LARGE,
                1080,
                2400,
                rotation,
            )
            val label = "C52 large preset r$rotation"
            assertEquals(label, expected.layoutId, imported.layoutId)
            assertEquals(label, expected.geometry, imported.geometry)
            assertEquals(label, expected.dynamicRectPx, imported.dynamicRectPx)
            assertEquals(label, expected.modules, imported.modules)
            assertEquals(label, expected.padding, imported.padding)
            assertEquals(label, expected.markers.map { it.id }, imported.markers.map { it.id })
            assertEquals(label, expected.markers.map { it.cellSizePx }, imported.markers.map { it.cellSizePx })
            assertEquals(label, expected.markers.map { it.quietZonePx }, imported.markers.map { it.quietZonePx })
            assertEquals(label, expected.markers.map { it.cornersPx }, imported.markers.map { it.cornersPx })
        }
    }

    @Test
    fun everyPresetKeepsTheBuildersModulesPaddingAndMarkersAtEachRotation() {
        for (preset in BuiltInLayouts.PRESETS) {
            for (rotation in BuiltInLayouts.ROTATIONS_DEG) {
                val selection = BuiltInSelection(preset, rotation)
                val imported = BuiltInLayouts.build(selection)
                val layoutId = "${preset.id}-r$rotation"
                val expected = PatternLayoutBuilder.build(
                    layoutId = layoutId,
                    geometry = preset.geometry,
                    drawableWidthPx = preset.referenceWidthPx,
                    drawableHeightPx = preset.referenceHeightPx,
                    patternRotationDeg = rotation,
                    largeTags = preset.largeTags,
                )
                val label = "${preset.id} at $rotation"
                assertEquals(label, layoutId, imported.layoutId)
                assertEquals(label, expected.drawableWidthPx, imported.drawableWidthPx)
                assertEquals(label, expected.drawableHeightPx, imported.drawableHeightPx)
                assertEquals(label, expected.patternRotationDeg, imported.patternRotationDeg)
                assertEquals(label, expected.patternWidthPx, imported.patternWidthPx)
                assertEquals(label, expected.patternHeightPx, imported.patternHeightPx)
                assertEquals(label, expected.dynamicRectPx, imported.dynamicRectPx)
                assertEquals(label, expected.modules, imported.modules)
                assertEquals(label, expected.padding, imported.padding)
                assertEquals(label, expected.levelLow, imported.levelLow)
                assertEquals(label, expected.levelHigh, imported.levelHigh)
                assertEquals(label, preset.geometry, imported.geometry)
                assertEquals(label, expected.markers.size, imported.markers.size)
                assertEquals(label, listOf(0, 1, 2, 3), imported.markers.map { it.id })
                expected.markers.forEachIndexed { index, marker ->
                    val actual = imported.markers[index]
                    assertEquals("$label marker $index id", marker.id, actual.id)
                    assertEquals("$label marker $index dictionary", marker.dictionary, actual.dictionary)
                    assertEquals("$label marker $index size", marker.markerSizeBits, actual.markerSizeBits)
                    assertEquals("$label marker $index border", marker.borderBits, actual.borderBits)
                    assertEquals("$label marker $index cell", marker.cellSizePx, actual.cellSizePx)
                    assertEquals("$label marker $index quiet", marker.quietZonePx, actual.quietZonePx)
                    assertEquals("$label marker $index corners", marker.cornersPx, actual.cornersPx)
                }
                assertEquals(
                    label,
                    GridPlacement.SAMPLING_INNER_FRACTION,
                    imported.samplingInnerFraction!!,
                    0.0,
                )
            }
        }
    }

    @Test
    fun savedPresetSelectionRejectsUnknownIdAndRotation() {
        assertNull(BuiltInLayouts.selection("builtin-missing", 0))
        assertNull(BuiltInLayouts.selection(BuiltInLayouts.DEFAULT.id, 45))
        assertEquals(
            BuiltInLayouts.DEFAULT,
            BuiltInLayouts.selection(BuiltInLayouts.DEFAULT.id, 270)?.preset,
        )
        assertEquals(
            270,
            BuiltInLayouts.selection(BuiltInLayouts.DEFAULT.id, 270)?.rotationDeg,
        )
    }
}
