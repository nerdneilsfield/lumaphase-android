package dev.lumaphase.android.camera

import dev.lumaphase.android.layout.BuiltInLayouts
import dev.lumaphase.android.layout.BuiltInSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInLiveSwitchTest {
    @Test
    fun samePresetAtDifferentRotationsHasDifferentIdsAndActualGeometry() {
        for (preset in BuiltInLayouts.PRESETS) {
            val r0 = BuiltInLayouts.build(BuiltInSelection(preset, 0))
            val r90 = BuiltInLayouts.build(BuiltInSelection(preset, 90))
            assertNotEquals("${preset.id} r0/r90 id", r0.layoutId, r90.layoutId)
            assertEquals("${preset.id}-r0", r0.layoutId)
            assertEquals("${preset.id}-r90", r90.layoutId)
            assertNotEquals("${preset.id} rotation", r0.patternRotationDeg, r90.patternRotationDeg)
            assertNotEquals("${preset.id} modules", r0.modules, r90.modules)
            assertNotEquals("${preset.id} dynamic rect", r0.dynamicRectPx, r90.dynamicRectPx)
        }
    }

    @Test
    fun differentBuiltInLayoutIdsRequireANewGenerationThatInvalidatesTheOldToken() {
        val gate = AnalysisGate()
        val token = gate.nextGeneration()
        assertTrue(gate.tryBegin(token))
        // CameraController.startLive calls nextGeneration() when the id changes; the old
        // analysis token must then stop being current.
        val next = gate.nextGeneration()
        assertFalse("old token must be invalidated", gate.isCurrent(token))
        assertTrue("new token is current", gate.isCurrent(next))
        gate.finish(token)
        assertTrue("new token may start after the old one finishes", gate.tryBegin(next))
        assertFalse("old token stays invalid", gate.isCurrent(token))
    }

    @Test
    fun everyBuiltInPresetRotationHasAUniqueImmutableLayoutId() {
        val ids = BuiltInLayouts.PRESETS.flatMap { preset ->
            BuiltInLayouts.ROTATIONS_DEG.map { rotation ->
                BuiltInLayouts.build(BuiltInSelection(preset, rotation)).layoutId
            }
        }
        assertEquals(BuiltInLayouts.PRESETS.size * BuiltInLayouts.ROTATIONS_DEG.size, ids.size)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun savedPresetIdStaysRotationFreeWhileTheBuiltLayoutIdNamesTheRotation() {
        for (preset in BuiltInLayouts.PRESETS) {
            val selection = BuiltInLayouts.selection(preset.id, 270)
            checkNotNull(selection)
            assertEquals(preset.id, selection.preset.id)
            assertEquals(270, selection.rotationDeg)
            assertEquals("${preset.id}-r270", BuiltInLayouts.build(selection).layoutId)
        }
    }
}
