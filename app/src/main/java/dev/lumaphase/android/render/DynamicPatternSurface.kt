package dev.lumaphase.android.render

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.lumaphase.android.layout.PatternLayout
import dev.lumaphase.android.protocol.LayoutProfile
import dev.lumaphase.android.timing.BoundedFrameEvidenceSink
import dev.lumaphase.android.timing.ProbeCapabilities
import dev.lumaphase.android.timing.RenderStats

/**
 * SurfaceView that runs the dynamic OPTC-1 pattern.
 *
 * The view only forwards surface callbacks and surface size to [DynamicRenderThread]; all
 * EGL and VSync work happens on that thread. A new Surface gets a new
 * [DynamicRenderThread], and therefore a new `surface_instance_id` and `run_id`, while
 * results of frames submitted on an older Surface keep their original identity.
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

    private val evidence = BoundedFrameEvidenceSink()
    private var engine: DynamicRenderThread? = null
    private var runState: DynamicRunState? = null
    private var profile: LayoutProfile = LayoutProfile.F68
    private var patternRotationDeg = 0
    private var surfaceWidthPx = 0
    private var surfaceHeightPx = 0
    private var runStarted = false
    private var stopped = false

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
    ) {
        this.profile = profile
        this.patternRotationDeg = patternRotationDeg
        this.runState = runState
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (stopped || engine != null) return
        val state = runState ?: return
        engine = DynamicRenderThread(profile, patternRotationDeg, evidence, state).also { it.start() }
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
        val running = engine ?: return
        if (surfaceWidthPx <= 0 || surfaceHeightPx <= 0) return
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

    /** Stops VSync scheduling while the app is in the background. */
    fun pauseScheduling() {
        engine?.pauseScheduling()
    }

    fun resumeScheduling() {
        engine?.resumeScheduling()
    }

    fun stats(): RenderStats = engine?.stats() ?: runState?.carriedStats ?: RenderStats.EMPTY

    fun capabilities(): ProbeCapabilities? = engine?.capabilities() ?: lastCapabilities

    fun displayModeText(): String = engine?.displayMode() ?: lastDisplayMode

    fun recentSubmissions(): List<String> = evidence.recentSubmissions()

    fun recentResults(): List<String> = evidence.recentResults()

    fun submissionCount(): Int = evidence.submissionCount()

    fun resultCount(): Int = evidence.resultCount()

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
