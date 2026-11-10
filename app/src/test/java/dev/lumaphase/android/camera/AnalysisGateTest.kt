package dev.lumaphase.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Old-generation release must not clear a newer in-flight token, and nothing queues. */
class AnalysisGateTest {
    @Test
    fun manualBeginFailsWithoutChangingTheCurrentRequestWhenBusy() {
        val gate = AnalysisGate()
        val auto = gate.nextGeneration()
        assertTrue(gate.tryBegin(auto))
        val before = gate.currentGeneration()
        assertEquals(null, gate.beginNew())
        assertEquals("busy admission must not invalidate current generation", before, gate.currentGeneration())
        gate.finish(auto)
        val manual = gate.beginNew()
        assertTrue(manual != null)
        assertTrue(gate.isCurrent(manual!!))
        gate.finish(manual)
    }

    @Test
    fun oldGenerationCannotClearNewInflight() {
        val gate = AnalysisGate()
        val first = gate.nextGeneration()
        assertTrue(gate.tryBegin(first))
        val second = gate.nextGeneration()
        assertFalse("only one analysis may run", gate.tryBegin(second))
        gate.finish(second)
        assertFalse("old release must not clear first", gate.tryBegin(second))
        gate.finish(first)
        assertTrue(gate.tryBegin(second))
        val third = gate.nextGeneration()
        assertFalse(gate.isCurrent(second))
        gate.finish(second)
        assertTrue(gate.tryBegin(third))
        gate.finish(third)
    }
}
