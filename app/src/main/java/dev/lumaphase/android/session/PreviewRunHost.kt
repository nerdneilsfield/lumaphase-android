package dev.lumaphase.android.session

/**
 * The single phase-timer entry point.
 *
 * [schedulePhaseAdvance] *replaces* whatever is pending, so there is never more than one
 * armed phase callback: two competing chains are impossible by construction rather than
 * guarded against.
 */
interface RunScheduler {
    fun schedulePhaseAdvance(delayMs: Long, action: () -> Unit)
    fun cancelPhaseAdvance()
}

/**
 * What the host currently in charge must do when the schedule changes. Every effect goes
 * through the host bound most recently, so a callback that was queued by an older Activity
 * still acts on the current one and can never seal a Surface the current host is using.
 */
interface RunEffects {
    fun showPhase(phase: SessionPhase)
    fun endSegment(reason: SegmentEndReason)
    fun expectSegmentReason(reason: SegmentStartReason)
    fun startProducingFrames()
    fun stopProducingFrames()
    fun requestDisplayTarget()
    fun beginDrain(onDrained: () -> Unit)
    fun seal(reason: SessionClosedReason)
    fun refreshSegmentEnvironment()
}

/**
 * The explicit scheduling state of one session preview: which phase is running, how much of
 * it is left, whether the drain window is open, and whether the session is sealed or its log
 * has failed.
 *
 * It is plain Kotlin with an injected scheduler and clock, so the whole event order is
 * exercised on the JVM instead of being argued from the Activity's code. It lives in
 * [ActiveSession] so an Activity recreation continues the same run. The side effects go
 * through the [RunEffects] bound most recently, so a completion posted by an older Activity
 * still finishes the run on the current host instead of only stopping the old view.
 *
 * [finishWithoutHost] is the session-level fallback: when a completion or a terminal log
 * failure arrives with no host bound, the host must still close the session. It is a plain
 * callback (the Activity passes a function that does not capture the Activity) so the host
 * never calls a destroyed Activity.
 */
class PreviewRunHost(
    private val scheduler: RunScheduler,
    private val nowMs: () -> Long,
    val plan: List<SessionPhase> = DEFAULT_SESSION_PLAN,
    /** Optional per-step durations; null means wait for an explicit operator advance. */
    private val planDurationsNs: List<Long?>? = null,
    private val finishWithoutHost: (SessionClosedReason) -> Unit = {},
) {
    private var effects: RunEffects? = null
    private var deadlineMs = 0L
    private var closeReason: SessionClosedReason = SessionClosedReason.NORMAL_END

    var phaseIndex = 0
        private set

    /** True while a phase callback is armed. */
    var phaseRunning = false
        private set

    /** A null-duration step (CR-002 TAG calibration) waits for the operator. */
    var manualWait = false
        private set

    /**
     * The duration this activation of the current phase was planned for, including the
     * remaining time when a recalibration resumes. The session writes it into the phase event,
     * so the event and the armed timer agree.
     */
    var activationDurationNs: Long? = null
        private set

    var phaseRemainingMs = 0L
        private set

    var draining = false
        private set

    var sealed = false
        private set

    /** How many times the drain reported completion; the schedule seals at most once. */
    var drainCompletions = 0
        private set

    var started = false
        private set

    /** True while a geometry change is waiting for the operator to confirm TAG again. */
    var recalibrating = false
        private set

    private data class RecalibrationResume(
        val phase: SessionPhase,
        val phaseIndex: Int,
        val remainingMs: Long,
        val wasRunning: Boolean,
    )

    private var recalibrationResume: RecalibrationResume? = null

    /** Latched once the session log can no longer be trusted: no new frames, no new segment. */
    var logFailed = false
        private set

    /** True while a host is bound in the foreground. */
    private var foreground = false

    /**
     * Whether a Surface may start (or resume) producing frames right now.
     *
     * The view asks this before creating a render engine or starting a run, so a Surface that
     * appears while the app is stopped, draining, log-failed or sealed cannot start drawing
     * just because it is a new view instance. A plain preview (never started) is allowed
     * while it is in the foreground, so it can still show the pattern.
     */
    val canProduceFrames: Boolean
        get() = effects != null && foreground && !draining && !logFailed && !sealed

    /** The host that is currently in charge; replaced whenever an Activity enters. */
    fun bind(next: RunEffects) {
        effects = next
        // A new host must be started by its own onStart before it may draw; the old host's
        // foreground state must not leak into the new one.
        foreground = false
    }

    /**
     * Drops the current host only if it is still the one passed in. A recreated Activity's
     * bind cannot be undone by the old Activity's onDestroy.
     *
     * A running phase owned by the departing host is paused, not advanced: a recreated host
     * resumes from the captured remaining time. A draining session is left alone; its
     * completion will either reach the next host or the session-level fallback.
     */
    fun unbind(candidate: RunEffects) {
        if (effects !== candidate) return
        effects = null
        foreground = false
        if (started && phaseRunning && !draining && !sealed && !logFailed) {
            phaseRemainingMs = (deadlineMs - nowMs()).coerceAtLeast(1L)
            phaseRunning = false
            scheduler.cancelPhaseAdvance()
        }
    }

    /** The first start of a new session: the only place the phase timer is first armed. */
    fun startNewRun() {
        if (started || sealed || logFailed) return
        if (effects == null) return
        started = true
        phaseIndex = 0
        enterNextPhase()
    }

    /**
     * Foreground. Resumes drawing, but never arms a second timer, never resumes during the
     * drain, and never resumes after a log failure.
     *
     * [hasVisibleTarget] is true when the session has already had a display target/segment
     * (the Activity reads it from the controller's stamp). A session stopped before its first
     * display must not claim RESUME: its first segment is still SESSION_START.
     */
    fun onHostStarted(hasVisibleTarget: Boolean) {
        foreground = true
        val host = effects ?: return
        if (draining || sealed || logFailed) return
        if (!started) {
            // Plain preview: there is no session phase, only the pattern to show.
            host.requestDisplayTarget()
            host.startProducingFrames()
            return
        }
        if (phaseRunning) {
            // The live phase chain owns the timer; only make sure frames can run again.
            host.startProducingFrames()
            return
        }
        if (manualWait) {
            host.requestDisplayTarget()
            host.startProducingFrames()
            return
        }
        host.refreshSegmentEnvironment()
        if (hasVisibleTarget) host.expectSegmentReason(SegmentStartReason.RESUME)
        // Establish the display target/segment before allowing frames to run, so a resumed
        // frame cannot be attributed to the segment that was just ended.
        host.requestDisplayTarget()
        host.startProducingFrames()
        arm(phaseRemainingMs.coerceAtLeast(1L))
    }

    /** Background: capture what is left of the phase and stop producing frames. */
    fun onHostStopped() {
        foreground = false
        if (draining || sealed) return
        if (started && phaseRunning) {
            phaseRemainingMs = (deadlineMs - nowMs()).coerceAtLeast(1L)
            phaseRunning = false
            scheduler.cancelPhaseAdvance()
        }
        effects?.endSegment(SegmentEndReason.BACKGROUND)
        effects?.stopProducingFrames()
    }

    /** A geometry or configuration change: the next segment must read the new environment. */
    /**
     * A geometry/configuration change. For TAG, [requireTagReconfirmation] pauses the current
     * plan and shows the calibration target again; the original phase index and remaining
     * time are retained, so confirmation resumes the same RUN rather than restarting it.
     */
    fun onGeometryChanged(requireTagReconfirmation: Boolean = false) {
        if (draining || sealed || !started) return
        if (requireTagReconfirmation && !manualWait) {
            val currentPhase = plan.getOrNull((phaseIndex - 1).coerceAtLeast(0)) ?: return
            val wasRunning = phaseRunning
            if (wasRunning) {
                phaseRemainingMs = (deadlineMs - nowMs()).coerceAtLeast(1L)
            }
            scheduler.cancelPhaseAdvance()
            phaseRunning = false
            recalibrationResume = RecalibrationResume(
                phase = currentPhase,
                phaseIndex = phaseIndex,
                remainingMs = phaseRemainingMs,
                wasRunning = wasRunning,
            )
            recalibrating = true
            manualWait = true
            activationDurationNs = null
            effects?.showPhase(SessionPhase.CALIBRATION)
            return
        }
        effects?.refreshSegmentEnvironment()
    }

    /**
     * The log lost data or cannot be written. It is latched for the rest of the run: nothing
     * resumes on its own and no new segment may open. The only remaining move is to finish,
     * so the pending frames are drained and the session seals with the failure's own reason.
     */
    fun onLogFailure(reason: SessionClosedReason) {
        if (logFailed || sealed) return
        logFailed = true
        closeReason = reason
        if (draining) return
        phaseRunning = false
        scheduler.cancelPhaseAdvance()
        effects?.stopProducingFrames()
        if (effects == null) {
            // Nobody can show the result; the session-level fallback still closes the files.
            sealed = true
            finishWithoutHost(reason)
            return
        }
        beginDrain()
    }

    /** The operator left: seal once, without claiming the drain window. */
    fun onUserStop() {
        if (sealed) return
        sealed = true
        phaseRunning = false
        foreground = false
        scheduler.cancelPhaseAdvance()
        val host = effects
        if (host != null) host.seal(SessionClosedReason.USER_STOP) else finishWithoutHost(SessionClosedReason.USER_STOP)
    }

    /** The drain window closed, early or at its deadline. Seals at most once. */
    fun onDrained() {
        if (sealed) return
        sealed = true
        draining = false
        drainCompletions++
        val host = effects
        if (host != null) host.seal(closeReason) else finishWithoutHost(closeReason)
    }

    /** Explicit operator continue for a null-duration calibration step. */
    fun advanceManually() {
        if (!manualWait || sealed || draining || logFailed) return
        manualWait = false
        val resume = recalibrationResume
        recalibrationResume = null
        recalibrating = false
        if (resume != null) {
            phaseIndex = resume.phaseIndex
            phaseRemainingMs = resume.remainingMs
            activationDurationNs = if (resume.wasRunning) resume.remainingMs * 1_000_000L else null
            effects?.showPhase(resume.phase)
            if (resume.wasRunning) {
                arm(phaseRemainingMs.coerceAtLeast(1L))
            }
            return
        }
        enterNextPhase()
    }

    // ---- internals -----------------------------------------------------------------------

    private fun enterNextPhase() {
        val host = effects ?: return
        val phase = plan.getOrNull(phaseIndex)
        if (phase == null) {
            beginDrain()
            return
        }
        phaseIndex++
        val durationNs = planDurationsNs?.let { it.getOrNull(phaseIndex - 1) }
            ?: phase.plannedDurationNs
        activationDurationNs = durationNs
        host.showPhase(phase)
        if (durationNs == null) {
            phaseRunning = false
            manualWait = true
            phaseRemainingMs = 0L
            scheduler.cancelPhaseAdvance()
        } else {
            manualWait = false
            val durationMs = durationNs / 1_000_000L
            arm(durationMs)
        }
    }

    private fun arm(delayMs: Long) {
        phaseRemainingMs = delayMs
        deadlineMs = nowMs() + delayMs
        phaseRunning = true
        scheduler.schedulePhaseAdvance(delayMs, advanceAction)
    }

    private val advanceAction: () -> Unit = {
        // Only the live chain may advance: a stale callback is inert.
        if (phaseRunning && !draining && !sealed && !logFailed) enterNextPhase()
    }

    private fun beginDrain() {
        val host = effects ?: return
        phaseRunning = false
        phaseRemainingMs = 0L
        draining = true
        scheduler.cancelPhaseAdvance()
        // The affected segment has already been ended with its specific reason when the failure
        // caused the drain; on a normal end this closes the last segment.
        host.endSegment(SegmentEndReason.NORMAL_END)
        // The drain keeps querying the frames already submitted and hands back a completion;
        // the pending timer chain is gone, so no new frame can be produced meanwhile.
        host.beginDrain { onDrained() }
    }
}
