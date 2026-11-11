package dev.lumaphase.android.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerIdAndFreezeOrderTest {
    @Test
    fun idsFlattenKeepsTheSameOrderForNx1And1xNShapes() {
        val values = intArrayOf(3, 1, 2, 0)
        assertArrayEquals(values, markerIdsInFlatOrder(4, 1, values))
        assertArrayEquals(values, markerIdsInFlatOrder(1, 4, values))
    }

    @Test
    fun idsFlattenRejectsAnInconsistentShape() {
        assertThrows(IllegalArgumentException::class.java) {
            markerIdsInFlatOrder(2, 2, intArrayOf(1, 3, 2))
        }
    }

    @Test
    fun pendingFreezeWaitsForBusyLiveThenInvalidatesTheOldToken() {
        val gate = AnalysisGate()
        val live = gate.nextGeneration()
        assertTrue(gate.tryBegin(live))
        // A freeze request cannot start while live is in flight; the controller keeps it
        // pending instead of dropping it.
        assertNull(gate.beginNew())
        gate.finish(live)
        val frozen = gate.beginNew()
        assertTrue(frozen != null)
        assertTrue(gate.isCurrent(frozen!!))
        assertFalse("the live token is no longer current", gate.isCurrent(live))

        // Restoring live while the frozen analysis is running invalidates its token too.
        gate.nextGeneration()
        assertFalse(gate.isCurrent(frozen))
        gate.finish(frozen)
    }
}
