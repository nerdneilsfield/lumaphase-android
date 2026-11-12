package dev.lumaphase.android

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
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
import dev.lumaphase.android.layout.CalibrationTarget
import dev.lumaphase.android.layout.CalibrationTargetBuilder
import dev.lumaphase.android.layout.DiagnosticPattern
import dev.lumaphase.android.layout.DisplayLayout
import dev.lumaphase.android.layout.FullscreenDiagnosticBuilder
import dev.lumaphase.android.layout.FullscreenDiagnosticLayout
import dev.lumaphase.android.layout.LayoutChoice
import dev.lumaphase.android.layout.PatternLayout
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
 * CR-002 typed display host. It uses the same real DynamicPatternSurface/EGL surface as the
 * ordinary preview, registers the actual typed layouts, and exports through the existing
 * six-file path. TAG waits for an explicit tap; A and B use fixed planned durations.
 */
class CR002DisplayActivity : Activity() {

    enum class Mode(val wire: String) {
        TAG("TAG"),
        A("A"),
        B("B"),
    }

    private lateinit var surfaceView: DynamicPatternSurface
    private lateinit var layoutChoice: LayoutChoice
    private lateinit var runHost: PreviewRunHost
    private var mode: Mode = Mode.TAG
    private var patternRotationDeg = 0
    private var runLayout: PatternLayout? = null
    private var calibrationTarget: CalibrationTarget? = null
    private var diagnosticLayouts: Map<DiagnosticPattern, FullscreenDiagnosticLayout> = emptyMap()
    private var currentScene: DisplayLayout? = null
    private var started = false
    private var surfaceSceneShown = false
    private var surfaceSizeKnown = false
    private var hostStartPending = false
    private var lastGeometryKey: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val experimentProfileByStep = mutableMapOf<Int, String>()

    private val effects = object : RunEffects {
        override fun showPhase(phase: SessionPhase) {
            val step = runHost.phaseIndex - 1
            val session = ActiveSession.controller ?: return
            val profile = profileForStep(step, phase)
            experimentProfileByStep[step] = profile
            session.updateSegmentEnvironment(
                AndroidSessionEnvironment.segmentEnvironment(this@CR002DisplayActivity, profile),
            )
            if (step > 0) {
                session.endSegment(SegmentEndReason.CONFIGURATION_CHANGED)
                session.expectSegmentReason(SegmentStartReason.CONFIGURATION_CHANGED)
            }
            session.advancePhase(
                phase,
                force = true,
                plannedDurationNs = runHost.activationDurationNs,
            )
            showScene(step, phase)
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
            sealSession(reason)
        }

        override fun refreshSegmentEnvironment() {
            ActiveSession.controller?.updateSegmentEnvironment(
                AndroidSessionEnvironment.segmentEnvironment(
                    this@CR002DisplayActivity,
                    experimentProfileByStep[runHost.phaseIndex - 1] ?: profileForStep(
                        runHost.phaseIndex - 1,
                        SessionPhase.CALIBRATION,
                    ),
                ),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = Mode.entries.firstOrNull {
            it.wire == intent.getStringExtra(EXTRA_MODE)
        } ?: Mode.TAG
        if (mode == Mode.TAG) {
            // Lock the whole TAG session to landscape, including when it starts landscape:
            // assigning the request is what keeps a later rotation from leaving the confirmed
            // target geometry. The fixed 3x target does not fit portrait 1080x2400/1080x1920,
            // so the real landscape Surface must exist before creating the session.
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
                return
            }
        }
        layoutChoice = LayoutChoice.forWire(intent.getStringExtra(EXTRA_LAYOUT_CHOICE))
            ?: LayoutChoice.F68_GRID
        patternRotationDeg = intent.getIntExtra(EXTRA_ROTATION_DEG, 0)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)

        val runState = ActiveSession.ensureRunState()
        val session = ActiveSession.controller ?: createSession(runState)
        val plan = when (mode) {
            Mode.TAG -> listOf(
                SessionPhase.CALIBRATION,
                SessionPhase.WARMUP,
                SessionPhase.SESSION_CARD,
                SessionPhase.RUNNING,
                SessionPhase.END_CARD,
            )
            Mode.A -> List(6) { SessionPhase.CALIBRATION }
            Mode.B -> listOf(SessionPhase.CALIBRATION)
        }
        val durations = when (mode) {
            Mode.TAG -> listOf<Long?>(
                null,
                SessionPhase.WARMUP.plannedDurationNs,
                SessionPhase.SESSION_CARD.plannedDurationNs,
                SessionPhase.RUNNING.plannedDurationNs,
                SessionPhase.END_CARD.plannedDurationNs,
            )
            Mode.A -> listOf(
                1_000_000_000L, 3_000_000_000L,
                1_000_000_000L, 3_000_000_000L,
                1_000_000_000L, 3_000_000_000L,
            )
            Mode.B -> listOf(60_000_000_000L)
        }
        runHost = ActiveSession.runHost ?: PreviewRunHost(
            MainLooperScheduler(),
            SystemClock::elapsedRealtime,
            plan = plan,
            planDurationsNs = durations,
            finishWithoutHost = ::finishSessionWithoutHost,
        ).also { ActiveSession.adoptRunHost(it) }
        runHost.bind(effects)

        surfaceView = DynamicPatternSurface(this)
        surfaceView.configureDynamic(
            layoutChoice.layoutProfile,
            patternRotationDeg,
            runState,
            session,
            stampProvider = { session.stamp },
            canStartRun = { runHost.canProduceFrames },
        )
        surfaceView.onSurfaceSize = sizeCallback@ { widthPx, heightPx ->
            try {
                runLayout = PatternLayoutBuilder.build(
                    "run-${widthPx}x${heightPx}-r$patternRotationDeg-${layoutChoice.wire}",
                    layoutChoice,
                    widthPx,
                    heightPx,
                    patternRotationDeg,
                )
                calibrationTarget = if (mode == Mode.TAG) {
                    runLayout?.let {
                        CalibrationTargetBuilder.build(
                            "calibration-${widthPx}x$heightPx-r$patternRotationDeg",
                            it,
                        )
                    }
                } else {
                    null
                }
                diagnosticLayouts = DiagnosticPattern.entries.associateWith { pattern ->
                    FullscreenDiagnosticBuilder.build(
                        "diagnostic-${pattern.wire}-${widthPx}x$heightPx-r$patternRotationDeg",
                        widthPx,
                        heightPx,
                        patternRotationDeg,
                        pattern,
                    )
                }
            } catch (error: Throwable) {
                Log.e(TAG, "CR-002 target unavailable: ${error.message}", error)
                setResult(RESULT_CANCELED, Intent().putExtra(
                    RESULT_SUMMARY,
                    "CR-002 target unavailable: ${error.message}",
                ))
                finish()
                return@sizeCallback
            }
            surfaceSizeKnown = true
            // Contract 1.2 section 4: size or direction change invalidates the confirmed
            // mapping. The display rotation is part of "direction" and is not implied by the
            // drawable width/height alone.
            val displayRotation = surfaceView.display?.rotation ?: -1
            val geometryKey = "$widthPx:$heightPx:$patternRotationDeg:$displayRotation"
            if (hostStartPending) {
                hostStartPending = false
                // Resume only once the real Surface size is known, so a resumed phase timer
                // is not armed while there is no display target.
                runHost.onHostStarted(
                    ActiveSession.controller?.stamp?.segmentId?.isNotEmpty() == true,
                )
            }
            if (!surfaceSceneShown) {
                surfaceSceneShown = true
                if (mode == Mode.TAG && TagCalibration.confirmedGeometryKey != null &&
                    TagCalibration.confirmedGeometryKey != geometryKey
                ) {
                    // A recreated Activity sees the confirmation of the previous Surface. The
                    // confirmed mapping does not carry over to a different real geometry.
                    TagCalibration.confirmedGeometryKey = null
                    lastGeometryKey = geometryKey
                    runHost.onGeometryChanged(requireTagReconfirmation = true)
                    return@sizeCallback
                }
                if (!started && session.currentPhase() == SessionPhase.IDLE) {
                    started = true
                    TagCalibration.confirmedGeometryKey = null
                    runState.resetForNewSession()
                    // Start only after the real drawable exists and the fixed 3x TAG was
                    // built, so an unusable size never opens an empty session.
                    session.startSession()
                }
                if (runHost.started) showCurrentScene() else runHost.startNewRun()
                lastGeometryKey = geometryKey
                return@sizeCallback
            }
            val tagConfirmedBefore = mode == Mode.TAG &&
                TagCalibration.confirmedGeometryKey == lastGeometryKey
            if (mode == Mode.TAG && tagConfirmedBefore && lastGeometryKey != geometryKey) {
                // A confirmed TAG mapping does not survive a real Surface geometry change.
                // Keep the same session, render sequence and remaining RUN schedule; only
                // require the operator to confirm TAG again on the new geometry.
                TagCalibration.confirmedGeometryKey = null
                lastGeometryKey = geometryKey
                runHost.onGeometryChanged(requireTagReconfirmation = true)
                return@sizeCallback
            }
            lastGeometryKey = geometryKey
            showCurrentScene()
        }
        surfaceView.setOnClickListener {
            if (runHost.manualWait) {
                TagCalibration.confirmedGeometryKey = lastGeometryKey
                runHost.advanceManually()
            }
        }
        setContentView(surfaceView)
    }

    private fun profileForStep(step: Int, phase: SessionPhase): String = when (mode) {
        Mode.TAG -> if (phase == SessionPhase.CALIBRATION) "CR002-TAG" else experimentProfileForRun()
        Mode.A -> when (step / 2) {
            0 -> if (step % 2 == 0) "CR002-A-WHITE-SETTLE" else "CR002-A-WHITE"
            1 -> if (step % 2 == 0) "CR002-A-BLACK-SETTLE" else "CR002-A-BLACK"
            else -> if (step % 2 == 0) "CR002-A-GRID-SETTLE" else "CR002-A-GRID"
        }
        Mode.B -> "CR002-B-BW"
    }

    private fun experimentProfileForRun(): String = SessionConstants.EXPERIMENT_PROFILE


    private fun showScene(step: Int, phase: SessionPhase) {
        val scene = when (mode) {
            Mode.TAG -> if (phase == SessionPhase.CALIBRATION) calibrationTarget else runLayout?.let {
                DisplayLayout.Optc(it)
            }
            Mode.A -> {
                if (phase != SessionPhase.CALIBRATION) runLayout?.let { DisplayLayout.Optc(it) }
                else {
                    val pattern = when (step / 2) {
                        0 -> DiagnosticPattern.WHITE
                        1 -> DiagnosticPattern.BLACK
                        else -> DiagnosticPattern.CHECKERBOARD_2X2
                    }
                    diagnosticLayouts[pattern]
                }
            }
            Mode.B -> if (phase == SessionPhase.CALIBRATION) {
                diagnosticLayouts[DiagnosticPattern.VSYNC_BLACK_WHITE]
            } else {
                runLayout?.let { DisplayLayout.Optc(it) }
            }
        } ?: return
        currentScene = scene
        ActiveSession.controller?.registerLayout(scene)
        surfaceView.showDisplayLayout(scene)
    }

    private fun showCurrentScene() {
        val phase = ActiveSession.controller?.currentPhase() ?: SessionPhase.IDLE
        val step = (runHost.phaseIndex - 1).coerceAtLeast(0)
        showScene(step, phase)
    }

    private fun createSession(runState: DynamicRunState): SessionController {
        val sessionId = java.util.UUID.randomUUID().toString()
        val directory = File(filesDir, SessionFiles.directoryName(sessionId))
        val log = SessionLog(directory, serialize = { record -> serialize(record) })
        val environment = AndroidSessionEnvironment.appEnvironment(this)
        val session = SessionController(
            sessionId = sessionId,
            environment = environment,
            eventLog = log,
            schemaVersion = "1.2",
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

    private fun hideSystemBars() {
        val controller = window.insetsController ?: return
        controller.hide(WindowInsets.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        if (::runHost.isInitialized && surfaceSizeKnown && surfaceView.holder.surface.isValid) {
            runHost.onHostStarted(ActiveSession.controller?.stamp?.segmentId?.isNotEmpty() == true)
        } else {
            // The Surface size callback arrives after onStart, the Surface was destroyed while
            // backgrounded, or this is the short-lived portrait orientation bootstrap instance.
            // Defer the resume so the phase timer starts only when a real display target can be
            // established.
            hostStartPending = true
        }
    }

    override fun onStop() {
        // The orientation bootstrap instance returns from onCreate before runHost exists; it
        // must not stop or end the session owned by the later landscape instance.
        if (::runHost.isInitialized) runHost.onHostStopped()
        super.onStop()
    }

    override fun finish() {
        // Same bootstrap rule: an instance without a host must not seal the shared session
        // that a later landscape instance created.
        if (::runHost.isInitialized) runHost.onUserStop()
        super.finish()
    }

    override fun onDestroy() {
        if (::runHost.isInitialized) runHost.unbind(effects)
        if (::surfaceView.isInitialized) surfaceView.stopAndRelease()
        super.onDestroy()
    }

    private fun sealSession(reason: SessionClosedReason) {
        TagCalibration.confirmedGeometryKey = null
        val session = ActiveSession.controller ?: return
        if (session.closed) return
        if (session.currentPhase() == SessionPhase.IDLE) {
            ActiveSession.clear()
            return
        }
        val directory = ActiveSession.directory ?: return
        surfaceView.stopAndRelease()
        val sealed = session.stopSession(reason) ?: return
        val failure = SessionExporter.writeSealedDocuments(directory, sealed) ?: sealed.writeFailure
        if (failure != null) {
            ActiveSession.clear()
            setResult(RESULT_OK, Intent().putExtra(RESULT_SUMMARY, "files=FAILED $failure"))
            return
        }
        ActiveSession.clear()
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(RESULT_SUMMARY, "session=${sealed.manifest.sessionId}\nfiles=ok")
                .putExtra(RESULT_DIRECTORY, directory.absolutePath),
        )
    }

    private class MainLooperScheduler : RunScheduler {
        private val handler = Handler(Looper.getMainLooper())
        private var pending: Runnable? = null
        override fun schedulePhaseAdvance(delayMs: Long, action: () -> Unit) {
            cancelPhaseAdvance()
            val runnable = Runnable { pending = null; action() }
            pending = runnable
            handler.postDelayed(runnable, delayMs)
        }
        override fun cancelPhaseAdvance() {
            pending?.let { handler.removeCallbacks(it) }
            pending = null
        }
    }

    companion object {
        private const val TAG = "CR002Display"
        private const val EXTRA_MODE = "extra_cr002_mode"
        private const val EXTRA_LAYOUT_CHOICE = "extra_layout_choice"
        private const val EXTRA_ROTATION_DEG = "extra_rotation_deg"
        const val RESULT_SUMMARY = "result_cr002_summary"
        const val RESULT_DIRECTORY = "result_cr002_directory"

        fun intent(
            context: Context,
            mode: Mode,
            choice: LayoutChoice,
            rotationDeg: Int,
        ): Intent = Intent(context, CR002DisplayActivity::class.java)
            .putExtra(EXTRA_MODE, mode.wire)
            .putExtra(EXTRA_LAYOUT_CHOICE, choice.wire)
            .putExtra(EXTRA_ROTATION_DEG, rotationDeg)

        fun summary(result: Intent?): String? = result?.getStringExtra(RESULT_SUMMARY)
        fun sessionDirectory(result: Intent?): String? = result?.getStringExtra(RESULT_DIRECTORY)
    }
}

/** The geometry key of the TAG mapping the operator last confirmed, across Activity rebuilds. */
private object TagCalibration {
    var confirmedGeometryKey: String? = null
}

private fun finishSessionWithoutHost(reason: SessionClosedReason) {
    TagCalibration.confirmedGeometryKey = null
    val session = ActiveSession.controller ?: return
    val directory = ActiveSession.directory
    if (session.currentPhase() != SessionPhase.IDLE) {
        val sealed = session.stopSession(reason)
        if (sealed != null && directory != null) {
            SessionExporter.writeSealedDocuments(directory, sealed)
        }
    }
    ActiveSession.clear()
}
