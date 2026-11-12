package dev.lumaphase.android.render

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import dev.lumaphase.android.layout.CalibrationTarget
import dev.lumaphase.android.layout.DisplayLayout
import dev.lumaphase.android.layout.DiagnosticPattern
import dev.lumaphase.android.layout.FullscreenDiagnosticLayout
import dev.lumaphase.android.layout.LevelRect
import dev.lumaphase.android.layout.PatternLayout
import dev.lumaphase.android.layout.calibrationPaintRects
import dev.lumaphase.android.layout.fullscreenDiagnosticRects
import dev.lumaphase.android.layout.RectPx
import dev.lumaphase.android.layout.moduleRects
import dev.lumaphase.android.layout.paddingRects
import dev.lumaphase.android.layout.staticMarkerRects
import dev.lumaphase.android.protocol.EncodedPattern
import dev.lumaphase.android.protocol.LayoutProfile
import dev.lumaphase.android.protocol.PatternEncoder
import dev.lumaphase.android.timing.DisplayStamp
import dev.lumaphase.android.timing.DisplayStart
import dev.lumaphase.android.timing.FrameIdentity
import dev.lumaphase.android.timing.FrameResult
import dev.lumaphase.android.timing.FrameSubmission
import dev.lumaphase.android.timing.FrameTimingSink
import dev.lumaphase.android.timing.ProbeCapabilities
import dev.lumaphase.android.timing.RenderStats
import dev.lumaphase.android.timing.UnconfirmedReason
import dev.lumaphase.android.timing.VsyncTimeline
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives the dynamic OPTC-1 pattern from `Choreographer.VsyncCallback` on a private Looper
 * thread that also owns the EGL context.
 *
 * Per callback it copies the preferred `FrameTimeline` scalars, allocates a fresh
 * `render_seq` for the new dynamic state, draws the full pattern, takes the EGL frame id of
 * the next swap, requests the presentation time and swaps. Afterwards it queries frames
 * submitted earlier and re-registers, because the callback is one-shot and must not be
 * assumed to continue on its own.
 *
 * `render_seq` is never reused, including for a frame whose swap failed: the state was
 * generated, so its sequence is consumed. `vsync_id`, the EGL frame id and `render_seq`
 * identify the schedule timeline, the swap and the pattern respectively and are kept apart.
 *
 * Everything below runs on the render thread unless stated otherwise.
 */
class DynamicRenderThread(
    private val profile: LayoutProfile,
    private val patternRotationDeg: Int,
    private val sink: FrameTimingSink,
    private val runState: DynamicRunState,
    private val stampProvider: () -> DisplayStamp,
) {
    private val thread = HandlerThread(THREAD_NAME)
    private lateinit var handler: Handler

    /** Used only to hand the drain completion back to the host's thread. */
    private val mainHandler = Handler(Looper.getMainLooper())

    private var choreographer: Choreographer? = null
    private var egl: EglCore? = null
    private var renderer: RectRenderer? = null
    private var probe: PresentationProbe? = null
    private var displayLayout: DisplayLayout? = null
    private var layout: PatternLayout? = null
    private var patternProfile: LayoutProfile = LayoutProfile.F68
    private var staticRects: List<LevelRect> = emptyList()
    private var dynamicRects: List<RectPx> = emptyList()
    private var paddingLevelRects: List<LevelRect> = emptyList()
    private var surfaceInstanceId: String? = null
    private var nextRenderSeq: ULong = 0uL
    private var nextNativeFrameId: Long = 0L
    private var viewportWidthPx = 0
    private var viewportHeightPx = 0
    private var lastExpectedPresentNanos: Long? = null
    private var lastVsyncIntervalNanos: Long? = null
    private var scheduled = false
    private var released = false
    private var drainDeadlineNanos = 0L
    private var drainCompletion: (() -> Unit)? = null
    private var cardTextureId = 0
    private var cardTextureLabel: String? = null
    private var cardTextureSession: String? = null
    private val pending = PendingFrameQueue()

    private var framesSubmitted = 0
    private var framesFailed = 0
    private var resultsSettled = 0
    private var resultsUnconfirmed = 0
    private var maxPendingSeen = 0
    private var droppedQueueFull = 0
    private var droppedAtStop = 0

    @Volatile private var statsSnapshot: RenderStats = RenderStats.EMPTY
    @Volatile private var capabilitiesSnapshot: ProbeCapabilities? = null
    @Volatile private var displayModeText: String = "unknown"

    private val vsyncCallback = Choreographer.VsyncCallback { frameData -> onVsync(frameData) }

    fun start() {
        thread.start()
        handler = Handler(thread.looper)
    }

    // ---- commands posted from other threads ------------------------------------------------

    fun startRun(
        surface: Surface,
        widthPx: Int,
        heightPx: Int,
        layout: DisplayLayout,
        displayMode: DisplayModeInfo,
    ) = post {
        if (released) return@post
        updateGeometry(widthPx, heightPx, layout)

        surfaceInstanceId = "surface-${UUID.randomUUID()}"
        displayModeText = displayMode.description
        // The run continues across Surface rebuilds: identity, sequence and counters come from
        // the run state, so only the surface instance id is new here.
        nextRenderSeq = runState.nextRenderSeq
        nextNativeFrameId = runState.nextNativeFrameId
        val carried = runState.carriedStats
        framesSubmitted = carried.framesSubmitted
        framesFailed = carried.framesFailed
        resultsSettled = carried.resultsSettled
        resultsUnconfirmed = carried.resultsUnconfirmed
        maxPendingSeen = carried.maxPendingSeen
        droppedQueueFull = carried.droppedQueueFull
        droppedAtStop = carried.droppedAtStop
        lastVsyncIntervalNanos = carried.lastVsyncIntervalNanos
        lastExpectedPresentNanos = null
        pending.clear()

        val core = EglCore()
        if (!core.init(surface)) {
            core.release()
            Log.e(TAG, "EGL initialisation failed; the dynamic pattern will not run")
            return@post
        }
        egl = core

        val rectRenderer = RectRenderer()
        if (!rectRenderer.create()) {
            rectRenderer.release()
            core.release()
            egl = null
            Log.e(TAG, "GL program creation failed; the dynamic pattern will not run")
            return@post
        }
        renderer = rectRenderer

        val presentationProbe = PresentationProbe(core)
        capabilitiesSnapshot = presentationProbe.initialize()
        probe = presentationProbe

        requestFrameRate(surface)
        val optcProfile = layout.optcLayout?.profile
        Log.i(
            TAG,
            "run started ${runState.runId} $surfaceInstanceId ${optcProfile?.name ?: "typed"} " +
                "layout_type=${layout.layoutType.wire} layout=${layout.layoutId} " +
                "pattern_rotation_deg=$patternRotationDeg drawable=${widthPx}x$heightPx " +
                "display ${displayMode.description}",
        )
        // The session freezes its segment snapshot from these facts, exactly once per target.
        reportDisplayStarted(surface, layout, widthPx, heightPx)
        publishStats()
        startScheduling()
    }

    /** A configuration change keeps the run, the pending identities and the sequence. */
    fun onSurfaceSizeChanged(surface: Surface, widthPx: Int, heightPx: Int, layout: DisplayLayout) = post {
        if (released) return@post
        updateGeometry(widthPx, heightPx, layout)
        requestFrameRate(surface)
        Log.i(TAG, "geometry changed to ${widthPx}x$heightPx, layout ${layout.layoutId}")
        // The new geometry is a new display target: the session must freeze a new segment for
        // it, otherwise later frames would still be attributed to the old segment and layout.
        reportDisplayStarted(surface, layout, widthPx, heightPx)
    }

    fun pauseScheduling() = post { stopScheduling("paused") }

    /**
     * Stops producing new frames and keeps querying the frames already submitted, on this
     * thread's own Looper, until they settle or the window expires. Results that settle are
     * reported normally; whatever is still pending at the deadline is reported as
     * `PENDING_AT_SESSION_CLOSE`, which is the session-close reason, not a Surface loss.
     *
     * Reuses the existing Looper with a bounded chain of delayed ticks: no sleeping and no
     * busy polling, and no work at all once every tracked frame has settled. The completion is
     * delivered on the host's thread even when this thread is already gone, so the session can
     * always seal.
     */
    fun beginDrain(timeoutNs: Long, onDrained: () -> Unit) {
        if (released || !this::handler.isInitialized) {
            mainHandler.post(onDrained)
            return
        }
        if (!handler.post { startDrainOnRenderThread(timeoutNs, onDrained) }) {
            Log.w(TAG, "render thread is gone; the drain completes on the host thread")
            mainHandler.post(onDrained)
        }
    }

    private fun startDrainOnRenderThread(timeoutNs: Long, onDrained: () -> Unit) {
        if (released) {
            mainHandler.post(onDrained)
            return
        }
        stopScheduling("drain")
        // One drain chain at a time: a stale tick cannot complete a newer window.
        handler.removeCallbacks(drainTickRunnable)
        drainCompletion = onDrained
        drainDeadlineNanos = System.nanoTime() + timeoutNs
        drainTick()
    }

    fun resumeScheduling() = post {
        if (released) return@post
        if (egl == null) return@post
        startScheduling()
    }

    /**
     * Stops scheduling, reports every still-pending frame as `SURFACE_DESTROYED`, releases
     * GL and EGL on this thread and finally quits the Looper. Called from
     * `SurfaceHolder.Callback.surfaceDestroyed` and waited on, so the EGL resources are
     * always freed before the Looper goes away.
     */
    fun onSurfaceDestroyed() {
        val finished = CountDownLatch(1)
        val accepted = handler.post {
            releaseOnRenderThread()
            finished.countDown()
        }
        if (!accepted) {
            Log.w(TAG, "render thread was already gone; nothing left to release")
            return
        }
        if (!finished.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Log.w(TAG, "render thread did not finish releasing within ${RELEASE_TIMEOUT_SECONDS}s")
        }
    }

    fun stats(): RenderStats = statsSnapshot

    fun capabilities(): ProbeCapabilities? = capabilitiesSnapshot

    fun displayMode(): String = displayModeText

    // ---- render thread ---------------------------------------------------------------------

    private fun drainTick() {
        if (released) {
            completeDrain()
            return
        }
        queryPendingFrames()
        publishStats()
        if (pending.size == 0 || System.nanoTime() >= drainDeadlineNanos) {
            completeDrain()
            return
        }
        handler.postDelayed(drainTickRunnable, DRAIN_TICK_MS)
    }

    private fun completeDrain() {
        // Anything still tracked when the window closes is reported with the session-close
        // reason, and the queue is emptied so a later Surface teardown cannot relabel these
        // frames as a Surface loss.
        drainPending(UnconfirmedReason.PENDING_AT_SESSION_CLOSE)
        publishStats()
        val completion = drainCompletion
        drainCompletion = null
        completion?.let { mainHandler.post(it) }
    }

    private val drainTickRunnable = Runnable { drainTick() }

    private fun updateGeometry(widthPx: Int, heightPx: Int, layout: DisplayLayout) {
        this.displayLayout = layout
        this.viewportWidthPx = widthPx
        this.viewportHeightPx = heightPx
        when (layout) {
            is DisplayLayout.Optc -> {
                this.layout = layout.layout
                this.patternProfile = layout.layout.profile
                staticRects = staticMarkerRects(layout.layout)
                dynamicRects = moduleRects(layout.layout)
                paddingLevelRects = paddingRects(layout.layout)
            }
            is CalibrationTarget -> {
                this.layout = null
                staticRects = calibrationPaintRects(layout)
                dynamicRects = emptyList()
                paddingLevelRects = emptyList()
            }
            is FullscreenDiagnosticLayout -> {
                this.layout = null
                staticRects = emptyList()
                dynamicRects = emptyList()
                paddingLevelRects = emptyList()
            }
        }
    }

    private fun requestFrameRate(surface: Surface) {
        try {
            surface.setFrameRate(
                REQUESTED_REFRESH_HZ,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
            )
            Log.i(TAG, "requested ${REQUESTED_REFRESH_HZ}Hz, seamless only; not a guarantee")
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "setFrameRate(${REQUESTED_REFRESH_HZ}) rejected: ${error.message}")
        }
    }

    private fun startScheduling() {
        if (scheduled || released) return
        val created = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        scheduled = true
        created.postVsyncCallback(vsyncCallback)
    }

    /**
     * The callback is one-shot, so stopping is "do not register again". A callback that is
     * already queued returns immediately because [scheduled] is false.
     */
    private fun stopScheduling(reason: String) {
        if (!scheduled) return
        scheduled = false
        Log.i(TAG, "stopped scheduling ($reason)")
    }

    private fun onVsync(frameData: Choreographer.FrameData) {
        if (released || !scheduled) return
        val core = egl ?: return
        val rects = renderer ?: return
        val currentDisplay = displayLayout ?: return
        val currentOptc = currentDisplay.optcLayout
        val currentLayout = currentOptc
        val surfaceId = surfaceInstanceId ?: return
        val currentRun = runState.runId
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return

        val timeline = frameData.preferredFrameTimeline
        val vsync = VsyncTimeline(
            vsyncId = timeline.vsyncId,
            expectedPresentTimeNanos = timeline.expectedPresentationTimeNanos,
            deadlineNanos = timeline.deadlineNanos,
        )
        lastVsyncIntervalNanos = lastExpectedPresentNanos?.let {
            vsync.expectedPresentTimeNanos - it
        }
        lastExpectedPresentNanos = vsync.expectedPresentTimeNanos

        // One read per VSync: the same snapshot decides what is drawn and what identity the
        // frame carries, so the picture can never disagree with its recorded phase.
        val currentStamp = stampProvider()
        val cardLabel = currentStamp.cardLabel
        // The frame identity is allocated before any failable draw or swap, and it is consumed
        // even when that draw or swap fails. The diagnostic parity below and the recorded
        // identity use this same value, so a failed frame cannot silently reuse the old parity.
        val frameKind = currentStamp.frameKind
        val allocatedNativeFrameId: Long? = if (frameKind != null) {
            val id = nextNativeFrameId
            nextNativeFrameId += 1
            id
        } else {
            null
        }
        // A new dynamic state gets a new sequence; it is consumed even if the swap fails.
        // Stationary phases carry no dynamic state and consume no sequence (contract section 1).
        val encoded: EncodedPattern?
        val renderSeq: ULong?
        if (currentOptc != null && cardLabel == null) {
            val nextSeq = nextRenderSeq
            nextRenderSeq += 1uL
            renderSeq = nextSeq
            encoded = PatternEncoder.encode(nextSeq, currentOptc.profile)
        } else {
            renderSeq = null
            encoded = null
        }

        rects.beginFrame(
            viewportWidthPx,
            viewportHeightPx,
            staticRects.size + dynamicRects.size + paddingLevelRects.size + 1,
        )
        if (currentOptc == null) {
            val diagnosticRects = when (currentDisplay) {
                is CalibrationTarget -> staticRects
                is FullscreenDiagnosticLayout ->
                    fullscreenDiagnosticRects(
                        currentDisplay,
                        allocatedNativeFrameId ?: nextNativeFrameId,
                    )
                else -> emptyList()
            }
            diagnosticRects.forEach { rects.addRect(it.rect, it.level) }
        } else {
            for (rect in staticRects) rects.addRect(rect.rect, rect.level)
            if (encoded != null) {
                dynamicRects.forEachIndexed { index, rect ->
                    rects.addRect(rect, encoded.moduleCodes[index])
                }
                paddingLevelRects.forEach { padding -> rects.addRect(padding.rect, padding.level) }
            } else {
                // Stationary target: the dynamic area becomes a uniform mid grey (contract section 5).
                rects.addRect(currentLayout!!.dynamicRectPx, CardBitmap.MID_GREY_LEVEL)
            }
        }
        rects.endFrame()
        if (cardLabel != null && currentLayout != null) {
            val textureId = cardTexture(rects, currentLayout, cardLabel, currentStamp.sessionId)
            if (textureId != 0) {
                rects.drawTexture(textureId, currentLayout.dynamicRectPx, viewportWidthPx, viewportHeightPx)
            }
        }

        // Frame id of the swap that is about to happen, taken before it happens. Its error is
        // captured inside the probe right after the call, not after the request or the swap.
        val frameIdResult = probe?.nextFrameId()
        val frameId = frameIdResult?.frameId

        // Presentation time request uses the same expected value the standard field records.
        val requestedNanos = if (capabilitiesSnapshot?.presentationTimeSupported == true) {
            if (core.requestPresentationTime(vsync.expectedPresentTimeNanos)) {
                vsync.expectedPresentTimeNanos
            } else {
                null
            }
        } else {
            null
        }

        val submitNanos = System.nanoTime()
        val swapped = core.swapBuffers()
        // eglGetError() clears the error, so it is read exactly once: a second read would
        // return EGL_SUCCESS (0x3000) and contradict the message. The same value feeds both.
        var swapError = 0
        val failure = if (swapped) {
            framesSubmitted++
            null
        } else {
            framesFailed++
            swapError = core.lastError()
            "eglSwapBuffers failed 0x${Integer.toHexString(swapError)}"
        }
        if (frameKind == null) {
            // No session is recording: the pattern is shown, but nothing is logged.
            queryPendingFrames()
            publishStats()
            if (scheduled && !released) choreographer?.postVsyncCallback(vsyncCallback)
            return
        }
        val identity = FrameIdentity(
            runId = currentRun,
            sessionId = currentStamp.sessionId,
            segmentId = currentStamp.segmentId,
            surfaceInstanceId = surfaceId,
            nativeFrameId = allocatedNativeFrameId!!.toString(),
            frameKind = frameKind,
            phase = currentStamp.phase,
            renderSeq = renderSeq,
            layoutId = currentStamp.layoutId.ifEmpty { currentDisplay.layoutId },
        )
        sink.onSubmitted(
            FrameSubmission(
                identity = identity,
                timeline = vsync,
                submitNanos = submitNanos,
                eglFrameId = frameId?.let { java.lang.Long.toUnsignedString(it) },
                presentationRequestNanos = requestedNanos,
                payloadN = encoded?.payloadN,
                crc8 = encoded?.crc8,
                swapAccepted = swapped,
                failure = failure,
                failureErrorCode = if (swapped) null else "0x" + Integer.toHexString(swapError),
            ),
        )
        if (!swapped) {
            // No frame reached the compositor, so there is no presentation evidence to
            // collect for it; the consumed sequence is reported by the failed submission.
            Log.e(TAG, "swap failed for render_seq=${renderSeq ?: "none"}: $failure")
        } else if (frameId == null) {
            // No frame id means this submission can never be queried, so a supported type is
            // QUERY_FAILED rather than PENDING. A null error code means no call was attempted
            // because the extension is unusable, which is an unsupported capability.
            val errorCode = frameIdResult?.errorCode
            resultsUnconfirmed++
            sink.onResult(
                FrameResult(
                    identity = identity,
                    fields = probe?.unqueryable()?.fields.orEmpty(),
                    unconfirmedReason = if (errorCode == null) {
                        UnconfirmedReason.TIMESTAMP_UNSUPPORTED
                    } else {
                        UnconfirmedReason.QUERY_FAILED
                    },
                    queryErrorCode = errorCode,
                ),
            )
        } else {
            track(frameId, identity, vsync, submitNanos)
        }

        queryPendingFrames()
        publishStats()
        if (framesSubmitted % SUMMARY_LOG_INTERVAL_FRAMES == 0 && framesSubmitted > 0) {
            Log.i(TAG, "run $currentRun ${statsSnapshot}")
        }

        if (scheduled && !released) {
            choreographer?.postVsyncCallback(vsyncCallback)
        }
    }

    private fun track(
        frameId: Long,
        identity: FrameIdentity,
        timeline: VsyncTimeline,
        submitNanos: Long,
    ) {
        val frame = PendingFrame(
            identity = identity,
            eglFrameId = frameId,
            timeline = timeline,
            submittedNanos = submitNanos,
            lastFields = probe?.neverQueried()?.fields.orEmpty(),
        )
        if (pending.add(frame)) {
            maxPendingSeen = maxOf(maxPendingSeen, pending.size)
            return
        }
        // The queue is full, so this submission can no longer be tracked. It is reported,
        // never dropped silently.
        droppedQueueFull++
        resultsUnconfirmed++
        sink.onResult(
            FrameResult(
                identity = identity,
                fields = frame.lastFields,
                unconfirmedReason = UnconfirmedReason.PENDING_QUEUE_FULL,
                queryErrorCode = null,
            ),
        )
    }

    /** Queries every frame still waiting; a frame leaves the queue once its presentation settles. */
    private fun queryPendingFrames() {
        val currentProbe = probe ?: return
        for (frame in pending.frames()) {
            val outcome = currentProbe.query(frame.eglFrameId)
            frame.lastFields = outcome.fields
            if (outcome.systemPresentPending) continue
            pending.remove(frame)
            resultsSettled++
            sink.onResult(
                FrameResult(
                    identity = frame.identity,
                    fields = outcome.fields,
                    unconfirmedReason = outcome.unconfirmedReason,
                    queryErrorCode = outcome.queryErrorCode,
                ),
            )
        }
    }

    /**
     * The identity block is rebuilt only when the session id or the phase label changes, so a
     * card costs one texture upload, not one per frame.
     */
    private fun reportDisplayStarted(
        surface: Surface,
        layout: DisplayLayout,
        widthPx: Int,
        heightPx: Int,
    ) {
        val core = egl ?: return
        sink.onDisplayStarted(
            DisplayStart(
                surfaceInstanceId = surfaceInstanceId.orEmpty(),
                layoutId = layout.layoutId,
                drawableWidthPx = widthPx,
                drawableHeightPx = heightPx,
                colorSpace = core.colorSpaceName(),
                pixelFormat = core.pixelFormatName(),
            ),
        )
    }

    private fun cardTexture(
        rects: RectRenderer,
        layout: PatternLayout,
        label: String,
        sessionId: String,
    ): Int {
        if (cardTextureId != 0 && cardTextureLabel == label && cardTextureSession == sessionId) {
            return cardTextureId
        }
        if (cardTextureId != 0) {
            rects.deleteTexture(cardTextureId)
            cardTextureId = 0
        }
        val bitmap = CardBitmap.buildIdentityBitmap(layout, sessionId, PLATFORM_NAME, label)
        cardTextureId = rects.createTexture(bitmap)
        bitmap.recycle()
        cardTextureLabel = label
        cardTextureSession = sessionId
        return cardTextureId
    }

    private fun releaseOnRenderThread() {
        if (released) return
        stopScheduling(UnconfirmedReason.SURFACE_DESTROYED)
        // A destroy during the drain window closes that window out and delivers its
        // completion: the frames the window was still waiting for are reported at session
        // close, because the window was already the session's end. A destroy with no drain in
        // progress is a genuine Surface loss and keeps that reason.
        if (drainCompletion != null) {
            completeDrain()
        }
        drainPending(UnconfirmedReason.SURFACE_DESTROYED)
        // Published before the identity is dropped, so the final stats keep the run id.
        publishStats()
        cardTextureId = 0
        cardTextureLabel = null
        cardTextureSession = null
        renderer?.release()
        renderer = null
        egl?.release()
        egl = null
        probe = null
        surfaceInstanceId = null
        released = true
        Log.i(TAG, "released EGL and stopped the render thread; last stats ${statsSnapshot}")
        Looper.myLooper()?.quit()
    }

    private fun drainPending(reason: String) {
        for (frame in pending.clear()) {
            droppedAtStop++
            resultsUnconfirmed++
            sink.onResult(
                FrameResult(
                    identity = frame.identity,
                    fields = frame.lastFields,
                    unconfirmedReason = reason,
                    queryErrorCode = null,
                ),
            )
        }
    }

    private fun publishStats() {
        val stats = RenderStats(
            runId = runState.runId,
            framesSubmitted = framesSubmitted,
            framesFailed = framesFailed,
            resultsSettled = resultsSettled,
            resultsUnconfirmed = resultsUnconfirmed,
            pendingNow = pending.size,
            maxPendingSeen = maxPendingSeen,
            droppedQueueFull = droppedQueueFull,
            droppedAtStop = droppedAtStop,
            lastVsyncIntervalNanos = lastVsyncIntervalNanos,
        )
        statsSnapshot = stats
        // Hand the continuation to the owner so a Surface rebuild resumes this run.
        runState.publish(nextRenderSeq, nextNativeFrameId, stats)
    }

    private fun post(block: () -> Unit) {
        if (released) return
        if (!handler.post(block)) {
            Log.w(TAG, "render thread is gone; command dropped")
        }
    }

    companion object {
        const val PLATFORM_NAME = "ANDROID"
        private const val TAG = "OptcDynamic"
        private const val THREAD_NAME = "optc-dynamic-render"
        private const val RELEASE_TIMEOUT_SECONDS = 2L
        private const val SUMMARY_LOG_INTERVAL_FRAMES = 600
        /** How often the drain window re-queries while frames are still pending. */
        private const val DRAIN_TICK_MS = 16L

        /** The requested refresh rate. `Surface.setFrameRate` does not guarantee it. */
        const val REQUESTED_REFRESH_HZ = 120f
    }
}
