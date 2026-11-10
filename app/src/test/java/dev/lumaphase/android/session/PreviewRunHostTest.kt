package dev.lumaphase.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole event order of one preview, driven on the host with a fake scheduler and a fake
 * clock. The scheduling state lives in [PreviewRunHost] precisely so this can run here: the
 * Android pieces behind [RunEffects] (a `Handler`, a `SurfaceView`, EGL) cannot run in a JVM
 * unit test, so this asserts what the host decides — how many timers are armed, which phase
 * is current, whether frames may be produced, and how many times the drain completes.
 */
class PreviewRunHostTest {

    /** A [RunScheduler] that keeps the single pending callback and lets the test fire it. */
    private class FakeScheduler : RunScheduler {
        var scheduleCount = 0
            private set
        var pendingDelayMs: Long? = null
            private set
        private var pendingAction: (() -> Unit)? = null

        override fun schedulePhaseAdvance(delayMs: Long, action: () -> Unit) {
            scheduleCount++
            pendingDelayMs = delayMs
            pendingAction = action
        }

        override fun cancelPhaseAdvance() {
            pendingDelayMs = null
            pendingAction = null
        }

        val hasPending: Boolean get() = pendingAction != null

        /** Runs the pending callback as the Looper would, if one is armed. */
        fun fire() {
            val action = pendingAction!!
            pendingDelayMs = null
            pendingAction = null
            action()
        }
    }

    /** Records every side effect instead of touching a Surface or a session. */
    private class Recorder : RunEffects {
        val phases = ArrayList<SessionPhase>()
        val segmentEnds = ArrayList<SegmentEndReason>()
        val expectedReasons = ArrayList<SegmentStartReason>()
        val seals = ArrayList<SessionClosedReason>()
        var framesStarted = 0
        var framesStopped = 0
        var displayRequests = 0
        var environmentRefreshes = 0
        var beginDrainCount = 0
        var drainCall: (() -> Unit)? = null
        val events = ArrayList<String>()

        override fun showPhase(phase: SessionPhase) { phases += phase; events += "phase" }
        override fun endSegment(reason: SegmentEndReason) { segmentEnds += reason; events += "end" }
        override fun expectSegmentReason(reason: SegmentStartReason) { expectedReasons += reason; events += "expect" }
        override fun startProducingFrames() { framesStarted++; events += "start" }
        override fun stopProducingFrames() { framesStopped++; events += "stop" }
        override fun requestDisplayTarget() { displayRequests++; events += "request" }
        override fun refreshSegmentEnvironment() { environmentRefreshes++; events += "refresh" }
        override fun seal(reason: SessionClosedReason) { seals += reason; events += "seal" }
        override fun beginDrain(onDrained: () -> Unit) {
            beginDrainCount++
            drainCall = onDrained
            events += "drain"
        }
    }

    private fun host(
        scheduler: FakeScheduler = FakeScheduler(),
        clock: () -> Long = { 0L },
        effects: Recorder = Recorder(),
        finishWithoutHost: (SessionClosedReason) -> Unit = {},
    ): Triple<PreviewRunHost, FakeScheduler, Recorder> {
        val runHost = PreviewRunHost(
            scheduler,
            clock,
            finishWithoutHost = finishWithoutHost,
        )
        runHost.bind(effects)
        return Triple(runHost, scheduler, effects)
    }

    private fun reachDrain(host: PreviewRunHost, scheduler: FakeScheduler) {
        host.startNewRun()
        repeat(4) { scheduler.fire() }
        assertTrue("the plan should have ended in the drain", host.draining)
    }

    @Test
    fun firstStartAndOnStartArmExactlyOneTimer() {
        val (runHost, scheduler, effects) = host()

        runHost.startNewRun()
        assertEquals(1, scheduler.scheduleCount)
        assertEquals(10_000L, scheduler.pendingDelayMs)
        assertEquals(listOf(SessionPhase.WARMUP), effects.phases)

        // onStart runs right after onCreate; it must not arm a second chain.
        runHost.onHostStarted(hasVisibleTarget = false)
        assertEquals(1, scheduler.scheduleCount)
        assertTrue(runHost.phaseRunning)
        // The first phase is not a resume, and no display target is re-reported.
        assertTrue(effects.expectedReasons.isEmpty())
        assertEquals(0, effects.displayRequests)
        assertTrue(runHost.canProduceFrames)
    }

    @Test
    fun theWholePlanRunsInOrderThenOpensOneDrain() {
        val (runHost, scheduler, effects) = host()
        runHost.startNewRun()

        val delays = ArrayList<Long>()
        repeat(4) {
            delays += scheduler.pendingDelayMs!!
            scheduler.fire()
        }

        assertEquals(listOf(10_000L, 2_000L, 90_000L, 2_000L), delays)
        assertEquals(
            listOf(
                SessionPhase.WARMUP,
                SessionPhase.SESSION_CARD,
                SessionPhase.RUNNING,
                SessionPhase.END_CARD,
            ),
            effects.phases,
        )
        assertTrue(runHost.draining)
        assertFalse(runHost.phaseRunning)
        assertEquals(1, effects.beginDrainCount)
    }

    @Test
    fun recreationKeepsTheRemainingPhaseAndResumesOnce() {
        var now = 0L
        val (runHost, scheduler, first) = host(clock = { now })
        runHost.startNewRun()
        val schedulesAtStart = scheduler.scheduleCount

        now = 4_000
        runHost.onHostStopped()
        assertEquals("background time must not be charged to the phase", 6_000L, runHost.phaseRemainingMs)
        assertTrue(first.segmentEnds.contains(SegmentEndReason.BACKGROUND))
        assertTrue(first.framesStopped >= 1)
        assertFalse(runHost.phaseRunning)

        // A new Activity adopts the same host and binds its own effects.
        val second = Recorder()
        runHost.bind(second)
        runHost.onHostStarted(hasVisibleTarget = true)

        assertEquals(schedulesAtStart + 1, scheduler.scheduleCount)
        assertEquals(6_000L, scheduler.pendingDelayMs)
        assertEquals(listOf(SegmentStartReason.RESUME), second.expectedReasons)
        assertEquals(1, second.displayRequests)
        assertTrue(second.framesStarted >= 1)
        assertTrue(
            "the display target must be requested before frames are allowed to run",
            second.events.indexOf("request") in 0 until second.events.indexOf("start"),
        )
    }

    @Test
    fun duringDrainNothingResumesOrProducesFrames() {
        val (runHost, scheduler, effects) = host()
        reachDrain(runHost, scheduler)
        val framesStarted = effects.framesStarted
        val displayRequests = effects.displayRequests
        val phaseCount = effects.phases.size

        val rebuilt = Recorder()
        runHost.bind(rebuilt)      // a Surface rebuild / recreated Activity inside the window
        runHost.onHostStarted(hasVisibleTarget = true) // an onStart inside the window
        runHost.onGeometryChanged() // a resize inside the window

        assertEquals(framesStarted, effects.framesStarted)
        assertEquals(0, rebuilt.framesStarted)
        assertEquals(displayRequests, effects.displayRequests)
        assertEquals(0, rebuilt.displayRequests)
        assertTrue(rebuilt.expectedReasons.isEmpty())
        assertEquals(phaseCount, effects.phases.size)
        assertTrue(runHost.draining)
        assertEquals(0, runHost.drainCompletions)
    }

    @Test
    fun theDrainCompletionSealsAtMostOnce() {
        val (runHost, scheduler, effects) = host()
        reachDrain(runHost, scheduler)
        val drain = effects.drainCall!!

        drain()
        drain() // a late second completion must be inert

        assertEquals(1, runHost.drainCompletions)
        assertTrue(runHost.sealed)
        assertEquals(listOf(SessionClosedReason.NORMAL_END), effects.seals)
    }

    @Test
    fun aCompletionFromAnOlderActivitySealsTheCurrentHost() {
        val (runHost, scheduler, first) = host()
        reachDrain(runHost, scheduler)
        val drain = first.drainCall!!

        // The Activity is recreated before the queued completion is delivered.
        val second = Recorder()
        runHost.bind(second)
        drain()

        assertEquals(listOf(SessionClosedReason.NORMAL_END), second.seals)
        assertTrue(first.seals.isEmpty())
    }

    @Test
    fun aLogFailureLatchesAndOnlyFinishes() {
        val (runHost, scheduler, effects) = host()
        runHost.startNewRun()
        val schedulesBefore = scheduler.scheduleCount

        runHost.onLogFailure(SessionClosedReason.RENDER_FAILURE)

        assertTrue(runHost.logFailed)
        assertFalse(runHost.phaseRunning)
        assertTrue(effects.framesStopped >= 1)
        assertTrue(runHost.draining)
        assertEquals(1, effects.beginDrainCount)

        // Nothing resumes on its own, and no new timer is armed.
        val framesStarted = effects.framesStarted
        runHost.onHostStarted(hasVisibleTarget = true)
        runHost.startNewRun()
        assertEquals(framesStarted, effects.framesStarted)
        assertEquals(schedulesBefore, scheduler.scheduleCount)

        effects.drainCall!!()
        assertEquals(listOf(SessionClosedReason.RENDER_FAILURE), effects.seals)
        assertEquals(1, runHost.drainCompletions)
    }

    @Test
    fun anUnboundCompletionFinishesAtSessionLevelOnce() {
        val closed = ArrayList<SessionClosedReason>()
        val (runHost, scheduler, effects) = host(finishWithoutHost = { closed += it })
        reachDrain(runHost, scheduler)
        val drain = effects.drainCall!!

        runHost.unbind(effects)
        drain() // no host: the session-level fallback must close, not an old Activity
        drain() // late second completion is inert

        assertEquals(listOf(SessionClosedReason.NORMAL_END), closed)
        assertTrue(runHost.sealed)
        assertTrue(effects.seals.isEmpty())
        assertEquals(1, runHost.drainCompletions)
        assertFalse(runHost.canProduceFrames)
    }

    @Test
    fun anOldHostCannotUnbindTheCurrentHost() {
        val (runHost, scheduler, first) = host()
        reachDrain(runHost, scheduler)
        val drain = first.drainCall!!

        val second = Recorder()
        runHost.bind(second)
        runHost.unbind(first) // the old Activity's onDestroy must not detach the new host
        drain()

        assertEquals(listOf(SessionClosedReason.NORMAL_END), second.seals)
        assertTrue(first.seals.isEmpty())
    }

    @Test
    fun aRunningHostUnboundPausesForTheNextHost() {
        var now = 0L
        val (runHost, scheduler, first) = host(clock = { now })
        runHost.startNewRun()
        now = 4_000

        runHost.unbind(first)
        assertEquals(6_000L, runHost.phaseRemainingMs)
        assertFalse(runHost.phaseRunning)
        assertFalse(runHost.canProduceFrames)

        val second = Recorder()
        runHost.bind(second)
        runHost.onHostStarted(hasVisibleTarget = true)

        assertEquals(listOf(SegmentStartReason.RESUME), second.expectedReasons)
        assertEquals(1, second.displayRequests)
        assertEquals(1, second.framesStarted)
        assertEquals(6_000L, scheduler.pendingDelayMs)
        assertTrue(
            second.events.indexOf("request") < second.events.indexOf("start"),
        )
    }

    @Test
    fun aFirstResumeBeforeAnyDisplayKeepsSessionStart() {
        val (runHost, scheduler, first) = host()
        runHost.startNewRun()
        runHost.onHostStopped() // stopped before the first display target exists

        val second = Recorder()
        runHost.bind(second)
        runHost.onHostStarted(hasVisibleTarget = false)

        assertTrue("no RESUME before the first segment", second.expectedReasons.isEmpty())
        assertEquals(1, second.displayRequests)
        assertEquals(1, second.framesStarted)
        assertTrue(runHost.phaseRunning)
    }

    @Test
    fun canProduceFramesFollowsForegroundDrainAndLogFailure() {
        val (runHost, scheduler, effects) = host()
        assertFalse(runHost.canProduceFrames) // no foreground yet
        runHost.startNewRun()
        assertFalse(runHost.canProduceFrames)

        runHost.onHostStarted(hasVisibleTarget = false)
        assertTrue(runHost.canProduceFrames)
        runHost.onHostStopped()
        assertFalse(runHost.canProduceFrames)
        runHost.onHostStarted(hasVisibleTarget = false)
        assertTrue(runHost.canProduceFrames)

        runHost.onLogFailure(SessionClosedReason.RENDER_FAILURE)
        assertTrue(runHost.draining)
        assertFalse(runHost.canProduceFrames)
        assertEquals(1, effects.beginDrainCount)
    }

    @Test
    fun aLogFailureWithoutAHostFinishesAtSessionLevel() {
        val closed = ArrayList<SessionClosedReason>()
        val (runHost, scheduler, effects) = host(finishWithoutHost = { closed += it })
        runHost.startNewRun()
        runHost.unbind(effects)

        runHost.onLogFailure(SessionClosedReason.RENDER_FAILURE)

        assertEquals(listOf(SessionClosedReason.RENDER_FAILURE), closed)
        assertTrue(effects.seals.isEmpty())
        assertTrue(runHost.sealed)
        assertFalse(runHost.canProduceFrames)
    }

    @Test
    fun noSessionPreviewCanDrawWhileForeground() {
        val (runHost, scheduler, effects) = host()
        assertFalse(runHost.canProduceFrames)

        runHost.onHostStarted(hasVisibleTarget = false)

        assertTrue(runHost.canProduceFrames)
        assertEquals(1, effects.displayRequests)
        assertEquals(1, effects.framesStarted)
        assertEquals(0, scheduler.scheduleCount)
        runHost.onHostStopped()
        assertFalse(runHost.canProduceFrames)
    }

    @Test
    fun aUserStopSealsOnceWithoutClaimingTheDrain() {
        val (runHost, scheduler, effects) = host()
        runHost.startNewRun()

        runHost.onUserStop()
        runHost.onUserStop()

        assertEquals(listOf(SessionClosedReason.USER_STOP), effects.seals)
        assertTrue(runHost.sealed)
        assertEquals(0, runHost.drainCompletions)
        assertFalse(scheduler.hasPending)
    }
}
