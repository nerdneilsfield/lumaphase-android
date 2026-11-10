package dev.lumaphase.android.camera

import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-owner analysis admission. It carries the generation token and the one in-flight token,
 * so an old generation's release can never clear a newer request. New frames are dropped while
 * the executor is busy; nothing is queued here.
 */
class AnalysisGate {
    private val generation = AtomicInteger(0)
    private val inFlight = AtomicInteger(NO_ANALYSIS)

    fun nextGeneration(): Int = generation.incrementAndGet()

    /**
     * Reserves the single slot and starts a new generation atomically. Returns null when an
     * analysis is already in flight; the caller must not change live/manual mode in that case.
     */
    @Synchronized
    fun beginNew(): Int? {
        if (inFlight.get() != NO_ANALYSIS) return null
        val next = generation.incrementAndGet()
        inFlight.set(next)
        return next
    }

    fun currentGeneration(): Int = generation.get()

    fun tryBegin(analysisId: Int): Boolean = inFlight.compareAndSet(NO_ANALYSIS, analysisId)

    fun finish(analysisId: Int) {
        inFlight.compareAndSet(analysisId, NO_ANALYSIS)
    }

    fun isCurrent(analysisId: Int): Boolean = generation.get() == analysisId

    companion object {
        const val NO_ANALYSIS = -1
    }
}
