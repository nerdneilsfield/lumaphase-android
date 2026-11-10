package dev.lumaphase.android.render

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import dev.lumaphase.android.layout.LevelRect
import dev.lumaphase.android.layout.PatternLayout
import dev.lumaphase.android.layout.RectPx
import dev.lumaphase.android.layout.moduleRects
import dev.lumaphase.android.layout.staticMarkerRects
import dev.lumaphase.android.protocol.LayoutProfile
import dev.lumaphase.android.protocol.PatternEncoder
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
) {
    private val thread = HandlerThread(THREAD_NAME)
    private lateinit var handler: Handler

    private var choreographer: Choreographer? = null
    private var egl: EglCore? = null
    private var renderer: RectRenderer? = null
    private var probe: PresentationProbe? = null
    private var layout: PatternLayout? = null
    private var staticRects: List<LevelRect> = emptyList()
    private var dynamicRects: List<RectPx> = emptyList()
    private var surfaceInstanceId: String? = null
    private var nextRenderSeq: ULong = 0uL
    private var nextNativeFrameId: Long = 0L
    private var viewportWidthPx = 0
    private var viewportHeightPx = 0
    private var lastExpectedPresentNanos: Long? = null
    private var lastVsyncIntervalNanos: Long? = null
    private var scheduled = false
    private var released = false
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
        layout: PatternLayout,
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
        Log.i(
            TAG,
            "run started ${runState.runId} $surfaceInstanceId ${layout.profile.name} " +
                "pattern_rotation_deg=$patternRotationDeg drawable=${widthPx}x$heightPx " +
                "display ${displayMode.description}",
        )
        publishStats()
        startScheduling()
    }

    /** A configuration change keeps the run, the pending identities and the sequence. */
    fun onSurfaceSizeChanged(surface: Surface, widthPx: Int, heightPx: Int, layout: PatternLayout) = post {
        if (released) return@post
        updateGeometry(widthPx, heightPx, layout)
        requestFrameRate(surface)
        Log.i(TAG, "geometry changed to ${widthPx}x$heightPx, layout ${layout.layoutId}")
    }

    fun pauseScheduling() = post { stopScheduling("paused") }

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

    private fun updateGeometry(widthPx: Int, heightPx: Int, layout: PatternLayout) {
        this.layout = layout
        this.viewportWidthPx = widthPx
        this.viewportHeightPx = heightPx
        // Marker rectangles and module rectangles do not change between dynamic states.
        staticRects = staticMarkerRects(layout)
        dynamicRects = moduleRects(layout)
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
        val currentLayout = layout ?: return
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

        // A new dynamic state gets a new sequence; it is consumed even if the swap fails.
        val renderSeq = nextRenderSeq
        nextRenderSeq += 1uL
        val encoded = PatternEncoder.encode(renderSeq, profile)
        val identity = FrameIdentity(
            runId = currentRun,
            surfaceInstanceId = surfaceId,
            nativeFrameId = (nextNativeFrameId++).toString(),
            renderSeq = renderSeq,
            layoutId = currentLayout.layoutId,
        )

        rects.beginFrame(viewportWidthPx, viewportHeightPx, staticRects.size + dynamicRects.size)
        for (rect in staticRects) rects.addRect(rect.rect, rect.level)
        dynamicRects.forEachIndexed { index, rect -> rects.addRect(rect, encoded.moduleCodes[index]) }
        rects.endFrame()

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
        val failure = if (swapped) {
            framesSubmitted++
            null
        } else {
            framesFailed++
            "eglSwapBuffers failed 0x${Integer.toHexString(core.lastError())}"
        }
        sink.onSubmitted(
            FrameSubmission(
                identity = identity,
                timeline = vsync,
                submitNanos = submitNanos,
                eglFrameId = frameId?.let { java.lang.Long.toUnsignedString(it) },
                presentationRequestNanos = requestedNanos,
                swapAccepted = swapped,
                failure = failure,
            ),
        )
        if (!swapped) {
            // No frame reached the compositor, so there is no presentation evidence to
            // collect for it; the consumed sequence is reported by the failed submission.
            Log.e(TAG, "swap failed for render_seq=$renderSeq: $failure")
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

    private fun releaseOnRenderThread() {
        if (released) return
        stopScheduling(UnconfirmedReason.SURFACE_DESTROYED)
        drainPending(UnconfirmedReason.SURFACE_DESTROYED)
        // Published before the identity is dropped, so the final stats keep the run id.
        publishStats()
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
        private const val TAG = "OptcDynamic"
        private const val THREAD_NAME = "optc-dynamic-render"
        private const val RELEASE_TIMEOUT_SECONDS = 2L
        private const val SUMMARY_LOG_INTERVAL_FRAMES = 600

        /** The requested refresh rate. `Surface.setFrameRate` does not guarantee it. */
        const val REQUESTED_REFRESH_HZ = 120f
    }
}
