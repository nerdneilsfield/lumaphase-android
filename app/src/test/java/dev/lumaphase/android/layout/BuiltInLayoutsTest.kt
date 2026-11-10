package dev.lumaphase.android.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInLayoutsTest {
    @Test
    fun catalogNamesSixExplicitSourceReferencesAndDefaultsToPortraitF68Grid() {
        assertEquals(6, BuiltInLayouts.PRESETS.size)
        assertEquals(
            BuiltInLayouts.PRESETS.size,
            BuiltInLayouts.PRESETS.map { it.id }.toSet().size,
        )
        assertEquals("builtin-android-portrait-f68-g9x8", BuiltInLayouts.DEFAULT.id)
        assertEquals(1080, BuiltInLayouts.DEFAULT.referenceWidthPx)
        assertEquals(2400, BuiltInLayouts.DEFAULT.referenceHeightPx)
        assertEquals(GeometryProfile.F68_G9X8, BuiltInLayouts.DEFAULT.geometry)

        val expected = listOf(
            "builtin-android-portrait-f68-g9x8" to GeometryProfile.F68_G9X8,
            "builtin-android-portrait-c52-g9x6" to GeometryProfile.C52_G9X6,
            "builtin-android-portrait-f68-single" to GeometryProfile.F68,
            "builtin-android-portrait-c52-single" to GeometryProfile.C52,
            "builtin-android-landscape-f68-g6x12" to GeometryProfile.F68_G6X12,
            "builtin-android-landscape-c52-g5x12" to GeometryProfile.C52_G5X12,
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
        BuiltInLayouts.PRESETS.drop(4).forEach {
            assertEquals(2400, it.referenceWidthPx)
            assertEquals(1080, it.referenceHeightPx)
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
