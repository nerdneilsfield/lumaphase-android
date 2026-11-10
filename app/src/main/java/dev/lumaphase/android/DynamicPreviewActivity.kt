package dev.lumaphase.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.JsonWriter
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import dev.lumaphase.android.export.SessionExporter
import dev.lumaphase.android.export.SessionJson
import dev.lumaphase.android.layout.LayoutChoice
import dev.lumaphase.android.layout.PatternLayoutBuilder
import dev.lumaphase.android.protocol.LayoutProfile
import dev.lumaphase.android.render.DynamicPatternSurface
import dev.lumaphase.android.render.DynamicRunState
import dev.lumaphase.android.session.ActiveSession
import dev.lumaphase.android.session.AndroidSessionEnvironment
import dev.lumaphase.android.session.ConfigurationEventRecord
import dev.lumaphase.android.session.DisplayEventRecord
import dev.lumaphase.android.session.PreviewRunHost
import dev.lumaphase.android.session.RunEffects
import dev.lumaphase.android.session.RunScheduler
import dev.lumaphase.android.session.SegmentEndReason
import dev.lumaphase.android.session.SegmentStartReason
import dev.lumaphase.android.session.SessionClosedReason
import dev.lumaphase.android.session.SessionConstants
import dev.lumaphase.android.session.SessionController
import dev.lumaphase.android.session.SessionFiles
import dev.lumaphase.android.session.SessionLog
import dev.lumaphase.android.session.SessionPhase
import dev.lumaphase.android.session.SessionRecord
import java.io.File
import java.io.StringWriter

/**
 * The optical display surface and, when asked, the session host.
 *
 * Nothing is drawn over the pattern: no text, no progress, no animation. The operator starts
 * the session from the settings screen, this activity runs the default plan
 * (`WARMUP`, `SESSION_CARD`, `RUNNING`, `END_CARD`), waits the drain window and seals. Back
 * ends the session early with `USER_STOP`.
 *
 * The plan fan-out lives in [PreviewRunHost], owned by [ActiveSession], not in this Activity:
 * the timer is armed in exactly one place, a recreated Activity continues the same run, and
 * side effects always go through whichever Activity is currently bound.
 */
class DynamicPreviewActivity : Activity() {

    private lateinit var surfaceView: DynamicPatternSurface
    private lateinit var layoutChoice: LayoutChoice
    private lateinit var runHost: PreviewRunHost

    private var patternRotationDeg = 0
    private var experimentProfile = SessionConstants.EXPERIMENT_PROFILE
    private var startRequested = false
    private val handler = Handler(Looper.getMainLooper())

    /**
     * The side effects of the schedule, routed to this Activity. The host is session-level, so
     * every effect reads the current [ActiveSession] and this Activity's own view: a completion
     * left over from an older Activity cannot act on a Surface the current one is using.
     */
    private val effects = object : RunEffects {
        override fun showPhase(phase: SessionPhase) {
            ActiveSession.controller?.advancePhase(phase)
        }

        override fun endSegment(reason: SegmentEndReason) {
            ActiveSession.controller?.endSegment(reason)
        }

        override fun expectSegmentReason(reason: SegmentStartReason) {
            ActiveSession.controller?.expectSegmentReason(reason)
        }

        override fun startProducingFrames() {
            surfaceView.resumeScheduling()
        }

        override fun stopProducingFrames() {
            surfaceView.pauseScheduling()
        }

        override fun requestDisplayTarget() {
            surfaceView.reportCurrentDisplay()
        }

        override fun beginDrain(onDrained: () -> Unit) {
            surfaceView.beginDrain(SessionConstants.PRESENTATION_DRAIN_TIMEOUT_NS, onDrained)
        }

        override fun seal(reason: SessionClosedReason) {
            this@DynamicPreviewActivity.seal(reason)
        }

        override fun refreshSegmentEnvironment() {
            ActiveSession.controller?.let { refreshSegmentEnvironment(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val legacyProfile = LayoutProfile.valueOf(
            intent.getStringExtra(EXTRA_PROFILE) ?: LayoutProfile.F68.name,
        )
        layoutChoice = LayoutChoice.forWire(intent.getStringExtra(EXTRA_LAYOUT_CHOICE))
            ?: LayoutChoice.legacy(legacyProfile)
        patternRotationDeg = intent.getIntExtra(EXTRA_ROTATION_DEG, 0)
        experimentProfile = intent.getStringExtra(EXTRA_EXPERIMENT_PROFILE)
            ?: SessionConstants.EXPERIMENT_PROFILE
        startRequested = intent.getBooleanExtra(EXTRA_START_SESSION, false)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The window must always fill the display, otherwise a transient system-bar reveal
        // would resize the Surface mid-session. The platform call is used directly rather
        // than adding an AndroidX compatibility layer.
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)

        val recreating = savedInstanceState != null
        val runState = ActiveSession.ensureRunState()
        // The controller always exists so the surface can be configured and the pattern shown,
        // but it records nothing until a session is explicitly started: the segment and every
        // event are gated on the stamp's recording flag, and sealing a never-started session
        // only clears it without writing a directory.
        val session = ActiveSession.controller ?: createSession(runState)
        refreshSegmentEnvironment(session)

        // One phase-timer owner for the whole session. It is created and bound before the
        // SurfaceView so the view can ask the owner, from its first callback, whether frames
        // may start. A recreated Activity adopts the running one and binds its own effects.
        runHost = ActiveSession.runHost
            ?: PreviewRunHost(
                MainLooperScheduler(),
                SystemClock::elapsedRealtime,
                finishWithoutHost = ::finishSessionWithoutHost,
            ).also { ActiveSession.adoptRunHost(it) }
        runHost.bind(effects)

        surfaceView = DynamicPatternSurface(this)
        surfaceView.configureDynamic(
            layoutChoice.layoutProfile,
            patternRotationDeg,
            runState,
            session,
            // Read at each VSync, so the current snapshot is what gets stamped.
            stampProvider = { session.stamp },
            // The session owner, not this view instance, decides whether a Surface may run.
            canStartRun = { runHost.canProduceFrames },
        )
        surfaceView.onSurfaceSize = { widthPx, heightPx ->
            try {
                val layout = PatternLayoutBuilder.build(
                    layoutIdFor(widthPx, heightPx),
                    layoutChoice,
                    widthPx,
                    heightPx,
                    patternRotationDeg,
                )
                session.registerLayout(layout)
                // The new geometry is a new segment: refresh the environment it will freeze
                // before the display start opens that segment.
                runHost.onGeometryChanged()
                surfaceView.showLayout(layout)
            } catch (error: IllegalArgumentException) {
                Log.e(TAG, "cannot build the pattern for ${widthPx}x$heightPx", error)
                finish()
            }
        }

        session.onLogFailure { reason ->
            // The log lost data or cannot be written: stop producing frames from the main
            // thread, latch the failure and let the host finish the session.
            handler.post { runHost.onLogFailure(reason) }
        }

        setContentView(surfaceView)

        // A recreated Activity must never turn a finished session into a new one: the old
        // instance's intent still says "start a session", so savedInstanceState is the only
        // local signal that this is not a fresh operator start.
        if (!recreating && startRequested && session.currentPhase() == SessionPhase.IDLE) {
            // An explicitly started session begins at sequence zero; nothing else resets it.
            runState.resetForNewSession()
            session.startSession()
            runHost.startNewRun()
        }
        Log.i(
            TAG,
            "display ${layoutChoice.layoutProfile.name} choice=${layoutChoice.wire} " +
                "pattern_rotation_deg=$patternRotationDeg " +
                "session=${session.sessionId} phase=${session.currentPhase()}",
        )
    }

    private fun createSession(runState: DynamicRunState): SessionController {
        val sessionId = java.util.UUID.randomUUID().toString()
        val directory = File(filesDir, SessionFiles.directoryName(sessionId))
        // Records are queued on the render thread and serialised by the log's own writer
        // thread, so no framing callback ever writes a file or formats JSON.
        val log = SessionLog(directory, serialize = { record -> serialize(record) })
        val session = SessionController(
            sessionId = sessionId,
            environment = AndroidSessionEnvironment.appEnvironment(this),
            eventLog = log,
        )
        ActiveSession.begin(session, directory, runState)
        return session
    }

    private fun serialize(record: SessionRecord): String {
        val buffer = StringWriter()
        val json = JsonWriter(buffer)
        when (record) {
            is DisplayEventRecord -> SessionJson.writeDisplayEventLine(json, record)
            is ConfigurationEventRecord -> SessionJson.writeConfigurationEventLine(json, record)
        }
        json.flush()
        return buffer.toString()
    }

    private fun refreshSegmentEnvironment(session: SessionController) {
        session.updateSegmentEnvironment(
            AndroidSessionEnvironment.segmentEnvironment(this, experimentProfile),
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = window.insetsController
        if (controller == null) {
            Log.w(TAG, "window has no insets controller; system bars stay visible")
            return
        }
        controller.hide(WindowInsets.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onStart() {
        super.onStart()
        // The host decides: it resumes drawing and the phase chain only when the run was
        // stopped and is still live, never during the drain or after a log failure.
        // "Already displayed" is read from the controller stamp; false before the first
        // segment, so a stop/resume in that window still produces SESSION_START.
        val hasVisibleTarget =
            ActiveSession.controller?.stamp?.segmentId?.isNotEmpty() == true
        runHost.onHostStarted(hasVisibleTarget)
    }

    override fun onStop() {
        // Backgrounding ends the segment, captures what is left of the phase and stops the
        // timer and the frames; the host owns all three.
        runHost.onHostStopped()
        super.onStop()
    }

    override fun finish() {
        runHost.onUserStop()
        super.finish()
    }

    override fun onDestroy() {
        // Unbind only this instance's effects; a recreated Activity may already have bound its
        // own, and this old instance must not detach the new host. A completion that arrives
        // after the unbind goes to the session-level fallback instead of a destroyed Activity.
        if (::runHost.isInitialized) runHost.unbind(effects)
        if (::surfaceView.isInitialized) surfaceView.stopAndRelease()
        super.onDestroy()
    }

    private fun seal(reason: SessionClosedReason) {
        val session = ActiveSession.controller ?: return
        if (session.closed) return
        // A session that was never started has nothing to seal and must not leave a directory.
        if (session.currentPhase() == SessionPhase.IDLE) {
            ActiveSession.clear()
            return
        }
        val directory = ActiveSession.directory ?: return
        // Stop scheduling first so no further frames are produced while the session seals.
        surfaceView.stopAndRelease()
        val sealed = session.stopSession(reason) ?: return
        // A failed file write is reported as a failed export, never as a complete package: the
        // directory is not handed back, so the export button stays unavailable.
        val failure = SessionExporter.writeSealedDocuments(directory, sealed) ?: sealed.writeFailure
        if (failure != null) {
            ActiveSession.clear()
            val text = "session=${sealed.manifest.sessionId}\nfiles=FAILED $failure"
            setResult(RESULT_OK, Intent().putExtra(RESULT_SUMMARY, text))
            Log.e(TAG, text)
            return
        }
        ActiveSession.clear()
        val text = buildString {
            append("session=").append(sealed.manifest.sessionId).append('\n')
            append("directory=").append(directory.absolutePath).append('\n')
            append("closed_reason=").append(reason.wire).append('\n')
            append("data_integrity=").append(sealed.summary.dataIntegrity.wire).append('\n')
            append("lost_event_count=").append(sealed.summary.lostEventCount).append('\n')
            for (segment in sealed.summary.segments) {
                append("segment ").append(segment.segmentId)
                append(" generated=").append(segment.generatedFrames)
                append(" submitted=").append(segment.submittedFrames)
                append(" presented=").append(segment.confirmedPresentedFrames)
                append(" unconfirmed=").append(segment.finalUnconfirmedFrames)
                append(" integrity=").append(segment.dataIntegrity.wire)
                append('\n')
            }
            append("files=ok")
        }
        setResult(
            RESULT_OK,
            Intent().putExtra(RESULT_SUMMARY, text).putExtra(RESULT_DIRECTORY, directory.absolutePath),
        )
        Log.i(TAG, text)
    }

    private fun layoutIdFor(widthPx: Int, heightPx: Int): String {
        // The drawable size, the pattern rotation and the requested geometry all decide the
        // immutable layout object. Any change is a new layout identity and a new segment; an
        // old layout is never overwritten by a new geometry.
        val key = "$widthPx:$heightPx:$patternRotationDeg:${layoutChoice.wire}"
        if (key != ActiveSession.currentLayoutKey) {
            if (ActiveSession.currentLayoutKey != null) {
                // The new segment itself is opened by the render thread's display-start report,
                // after the new layout has been registered.
                ActiveSession.controller?.endSegment(SegmentEndReason.CONFIGURATION_CHANGED)
                ActiveSession.controller?.expectSegmentReason(SegmentStartReason.CONFIGURATION_CHANGED)
            }
            ActiveSession.currentLayoutKey = key
            ActiveSession.currentLayoutId = "dynamic-layout-${ActiveSession.layoutCounter++}"
        }
        return ActiveSession.currentLayoutId
    }

    /**
     * The one implementation of [RunScheduler]: a main-thread `Handler` that keeps at most a
     * single pending phase callback, removing whatever it replaces.
     */
    private class MainLooperScheduler : RunScheduler {
        private val handler = Handler(Looper.getMainLooper())
        private var pending: Runnable? = null

        override fun schedulePhaseAdvance(delayMs: Long, action: () -> Unit) {
            cancelPhaseAdvance()
            val runnable = Runnable {
                pending = null
                action()
            }
            pending = runnable
            handler.postDelayed(runnable, delayMs)
        }

        override fun cancelPhaseAdvance() {
            pending?.let { handler.removeCallbacks(it) }
            pending = null
        }
    }

    companion object {
        private const val TAG = "OptcSessionHost"
        private const val EXTRA_PROFILE = "extra_profile"
        private const val EXTRA_LAYOUT_CHOICE = "extra_layout_choice"
        private const val EXTRA_ROTATION_DEG = "extra_rotation_deg"
        private const val EXTRA_EXPERIMENT_PROFILE = "extra_experiment_profile"
        private const val EXTRA_START_SESSION = "extra_start_session"

        /** The drain window the contract fixes after the end card. */
        const val DRAIN_MS = SessionConstants.PRESENTATION_DRAIN_TIMEOUT_NS / 1_000_000L

        const val RESULT_SUMMARY = "result_dynamic_summary"
        const val RESULT_DIRECTORY = "result_session_directory"

        fun intent(
            context: Context,
            choice: LayoutChoice,
            rotationDeg: Int,
            experimentProfile: String,
            startSession: Boolean,
        ): Intent = Intent(context, DynamicPreviewActivity::class.java)
            .putExtra(EXTRA_PROFILE, choice.layoutProfile.name)
            .putExtra(EXTRA_LAYOUT_CHOICE, choice.wire)
            .putExtra(EXTRA_ROTATION_DEG, rotationDeg)
            .putExtra(EXTRA_EXPERIMENT_PROFILE, experimentProfile)
            .putExtra(EXTRA_START_SESSION, startSession)

        fun summary(result: Intent?): String? = result?.getStringExtra(RESULT_SUMMARY)

        fun sessionDirectory(result: Intent?): String? = result?.getStringExtra(RESULT_DIRECTORY)
    }
}

/**
 * Closes a session when its drain completes (or a terminal log failure occurs) with no current
 * host. It deliberately touches no Activity or Surface: the old Activity may already be
 * destroyed. The package is still written with the same controller/exporter path; the result is
 * simply not delivered to a UI because no UI exists.
 */
private fun finishSessionWithoutHost(reason: SessionClosedReason) {
    val session = ActiveSession.controller ?: return
    val directory = ActiveSession.directory
    if (session.currentPhase() != SessionPhase.IDLE) {
        val sealed = session.stopSession(reason)
        if (sealed != null && directory != null) {
            val failure = SessionExporter.writeSealedDocuments(directory, sealed)
            if (failure != null) {
                Log.e("OptcSessionHost", "session-level finish failed: $failure")
            }
        }
    }
    ActiveSession.clear()
}
