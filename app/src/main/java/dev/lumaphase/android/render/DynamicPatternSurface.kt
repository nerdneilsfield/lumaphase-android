package dev.lumaphase.android.render

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.lumaphase.android.layout.PatternLayout
import dev.lumaphase.android.protocol.LayoutProfile
import dev.lumaphase.android.timing.DisplayStamp
import dev.lumaphase.android.timing.FrameTimingSink
import dev.lumaphase.android.timing.ProbeCapabilities
import dev.lumaphase.android.timing.RenderStats

/**
 * SurfaceView that runs the dynamic OPTC-1 pattern.
 *
 * The view only forwards surface callbacks and surface size to [DynamicRenderThread]; all
 * EGL and VSync work happens on that thread. A new Surface gets a new
 * [DynamicRenderThread] and therefore a new `surface_instance_id`, but it keeps the
 * [DynamicRunState] it was configured with, so the `run_id` and the render sequence continue
 * across a Surface rebuild; results of frames submitted on an older Surface keep their
 * original identity.
 *
 * `surfaceDestroyed` blocks until the render thread has drained its pending frames,
 * released GL and EGL and quit its Looper; nothing quits the Looper before that cleanup.
 */
class DynamicPatternSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    /** Invoked on the main thread with a valid surface size, and again when it changes. */
    var onSurfaceSize: ((widthPx: Int, heightPx: Int) -> Unit)? = null

    private var engine: DynamicRenderThread? = null
    private var sink: FrameTimingSink? = null
    private var stampProvider: (() -> DisplayStamp)? = null
    private var runState: DynamicRunState? = null
    private var profile: LayoutProfile = LayoutProfile.F68
    private var patternRotationDeg = 0
    private var surfaceWidthPx = 0
    private var surfaceHeightPx = 0
    private var runStarted = false
    private var stopped = false
    private var halting = false
    private var lastLayout: PatternLayout? = null
    private var canStartRun: () -> Boolean = { true }

    private var lastCapabilities: ProbeCapabilities? = null
    private var lastDisplayMode: String = "unknown"

    init {
        holder.addCallback(this)
    }

    /**
     * Sets which pattern the dynamic run displays and which run it belongs to.
     *
     * [runState] is owned by the preview host and outlives Surface rebuilds, so recreating
     * the Surface keeps the run id and the render sequence and only allocates a new
     * `surface_instance_id`.
     */
    fun configureDynamic(
        profile: LayoutProfile,
        patternRotationDeg: Int,
        runState: DynamicRunState,
        sink: FrameTimingSink,
        stampProvider: () -> DisplayStamp,
        canStartRun: () -> Boolean = { true },
    ) {
        this.profile = profile
        this.patternRotationDeg = patternRotationDeg
        this.runState = runState
        this.sink = sink
        this.stampProvider = stampProvider
        this.canStartRun = canStartRun
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        ensureEngine()
    }

    /**
     * Creates the render thread only when the session owner currently allows frame production.
     * A Surface that appears while the app is stopped, draining or log-failed stays without an
     * engine; a later [showLayout] when the host allows it creates one. This is what makes a
     * new Activity's new Surface obey the same permission as the old one.
     */
    private fun ensureEngine(): DynamicRenderThread? {
        engine?.let { return it }
        if (stopped || halting || !canStartRun()) return null
        val state = runState ?: return null
        val timingSink = sink ?: return null
        val provider = stampProvider ?: return null
        if (!holder.surface.isValid) return null
        return DynamicRenderThread(profile, patternRotationDeg, timingSink, state, provider)
            .also { it.start(); engine = it }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidthPx = width
        surfaceHeightPx = height
        onSurfaceSize?.invoke(width, height)
    }

    /**
     * A Surface that goes away without the preview ending only releases this Surface's EGL
     * resources; the run survives and the next Surface continues it.
     */
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseEngine()
    }

    /** Ends this preview: releases the render thread and refuses to start another one. */
    fun stopAndRelease() {
        stopped = true
        releaseEngine()
    }

    private fun releaseEngine() {
        val running = engine ?: return
        engine = null
        runStarted = false
        // The release drains the frames still pending and publishes the final counts.
        running.onSurfaceDestroyed()
        lastCapabilities = running.capabilities()
        lastDisplayMode = running.displayMode()
    }

    /**
     * Starts the run for this Surface, or updates the geometry when the size changed without
     * the Surface being recreated.
     */
    fun showLayout(layout: PatternLayout) {
        // Keep the most recent geometry even when the host currently forbids frames, so a
        // later resume can request the display target without waiting for another size callback.
        lastLayout = layout
        if (stopped || halting || !canStartRun()) return
        if (surfaceWidthPx <= 0 || surfaceHeightPx <= 0) return
        val running = ensureEngine() ?: return
        val surface = holder.surface
        if (!surface.isValid) return
        if (runStarted) {
            running.onSurfaceSizeChanged(surface, surfaceWidthPx, surfaceHeightPx, layout)
            return
        }
        runStarted = true
        running.startRun(
            surface = surface,
            widthPx = surfaceWidthPx,
            heightPx = surfaceHeightPx,
            layout = layout,
            displayMode = currentDisplayMode() ?: DisplayModeInfo(0, 0f, 0, 0, 0),
        )
    }

    /**
     * Re-publishes the current display target, which the session records as a new segment
     * (used when the app returns to the foreground on a Surface that survived).
     */
    fun reportCurrentDisplay() {
        lastLayout?.let { showLayout(it) }
    }

    /**
     * Stops producing frames and keeps querying the frames already submitted for up to
     * [timeoutNs]; [onDrained] is invoked on the main thread when the window ends or every
     * tracked frame has settled, whichever comes first.
     *
     * Opening the drain also halts this preview: a Surface rebuild inside the window must not
     * start a new run, a new segment or new frames while the session is finishing.
     */
    fun beginDrain(timeoutNs: Long, onDrained: () -> Unit) {
        halting = true
        val running = engine
        if (running == null) {
            onDrained()
            return
        }
        running.beginDrain(timeoutNs, onDrained)
    }

    /** Stops VSync scheduling while the app is in the background. */
    fun pauseScheduling() {
        engine?.pauseScheduling()
    }

    fun resumeScheduling() {
        if (stopped || halting || !canStartRun()) return
        // A Surface that was created while the host forbade frames had no run yet. Establish
        // the display target first; the run schedules itself. Never start the vsync callback
        // before a layout exists, because the one-shot callback would then stand down.
        if (!runStarted) {
            lastLayout?.let { showLayout(it) }
            return
        }
        engine?.resumeScheduling()
    }

    fun stats(): RenderStats = engine?.stats() ?: runState?.carriedStats ?: RenderStats.EMPTY

    fun capabilities(): ProbeCapabilities? = engine?.capabilities() ?: lastCapabilities

    fun displayModeText(): String = engine?.displayMode() ?: lastDisplayMode

    private fun currentDisplayMode(): DisplayModeInfo? {
        val current = display ?: return null
        val mode = current.mode ?: return null
        Log.i(TAG, "display mode ${mode.modeId} ${mode.refreshRate}Hz")
        return DisplayModeInfo(
            modeId = mode.modeId,
            refreshRateHz = mode.refreshRate,
            physicalWidthPx = mode.physicalWidth,
            physicalHeightPx = mode.physicalHeight,
            supportedModeCount = current.supportedModes.size,
        )
    }

    private companion object {
        const val TAG = "OptcDynamicView"
    }
}
