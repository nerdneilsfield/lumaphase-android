package dev.lumaphase.android.render

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.lumaphase.android.layout.PatternLayout
import dev.lumaphase.android.layout.paintRects
import dev.lumaphase.android.protocol.EncodedPattern
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SurfaceView that renders one static OPTC-1 pattern with EGL/GLES on a dedicated render
 * thread.
 *
 * There is no frame scheduling here: the pattern is drawn once per surface size change and
 * left on screen. VSync callbacks, FrameTimeline, EGL frame ids, presentation timestamps
 * and any per-frame state belong to T3 and are deliberately absent.
 *
 * The pattern geometry is always taken from a [PatternLayout] built for exactly this
 * surface size, and [paintRects] is the same list the layout export describes.
 */
class StaticPatternSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    /** Invoked on the main thread with a valid surface size, and again when it changes. */
    var onSurfaceSize: ((widthPx: Int, heightPx: Int) -> Unit)? = null

    /**
     * Invoked on the main thread after [layout] has actually been drawn to the surface.
     * This is the only signal that the layout is the one on screen, so it is what the
     * preview reports back to the settings screen for export.
     */
    var onLayoutRendered: ((PatternLayout) -> Unit)? = null

    private val renderThread = HandlerThread("optc-static-render")
    private val renderHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var layout: PatternLayout? = null
    @Volatile private var pattern: EncodedPattern? = null
    @Volatile private var surfaceWidthPx = 0
    @Volatile private var surfaceHeightPx = 0

    private var egl: EglCore? = null
    private var rectRenderer: RectRenderer? = null
    private var shutDown = false

    init {
        renderThread.start()
        renderHandler = Handler(renderThread.looper)
        holder.addCallback(this)
    }

    /** Sets the pattern to display; the draw happens on the render thread. */
    fun show(layout: PatternLayout, pattern: EncodedPattern) {
        this.layout = layout
        this.pattern = pattern
        renderHandler.post { drawCurrent() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidthPx = width
        surfaceHeightPx = height
        onSurfaceSize?.invoke(width, height)
        renderHandler.post { drawCurrent() }
    }

    /**
     * Releases this Surface's GL and EGL resources on the owning render thread. A Surface
     * rebuild is not the end of the view: the thread stays alive, and a later Surface
     * recreates the EGL resources on the next size change.
     */
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (shutDown) return
        runOnRenderThread { releaseGl() }
    }

    /**
     * Releases GL and EGL on the owning render thread and only then stops that thread, so a
     * later `surfaceDestroyed` cannot post to a dead Looper. Idempotent, and safe whether it
     * runs before or after `surfaceDestroyed`. Call from the host's `onDestroy`.
     */
    fun shutdown() {
        if (shutDown) return
        shutDown = true
        runOnRenderThread {
            releaseGl()
            Looper.myLooper()?.quit()
        }
    }

    /** Runs [block] on the render thread and waits for it, with a bounded wait. */
    private fun runOnRenderThread(block: () -> Unit) {
        val finished = CountDownLatch(1)
        val accepted = renderHandler.post {
            block()
            finished.countDown()
        }
        if (!accepted) {
            Log.w(TAG, "render thread is already gone")
            return
        }
        if (!finished.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Log.w(TAG, "render thread did not finish within ${RELEASE_TIMEOUT_SECONDS}s")
        }
    }

    private fun drawCurrent() {
        val layout = layout ?: return
        val pattern = pattern ?: return
        val widthPx = surfaceWidthPx
        val heightPx = surfaceHeightPx
        if (widthPx <= 0 || heightPx <= 0) return
        if (layout.drawableWidthPx != widthPx || layout.drawableHeightPx != heightPx) {
            Log.w(
                TAG,
                "surface ${widthPx}x$heightPx does not match layout " +
                    "${layout.drawableWidthPx}x${layout.drawableHeightPx}; not drawing",
            )
            return
        }
        val surface = holder.surface
        if (!surface.isValid) return

        val core = egl ?: EglCore().also { created ->
            if (!created.init(surface)) {
                created.release()
                Log.e(TAG, "EGL initialisation failed")
                return
            }
            egl = created
        }
        val program = rectRenderer ?: RectRenderer().also { created ->
            if (!created.create()) {
                created.release()
                Log.e(TAG, "GL program creation failed")
                return
            }
            rectRenderer = created
        }

        program.draw(paintRects(layout, pattern), widthPx, heightPx)
        if (!core.swapBuffers()) {
            Log.e(TAG, "eglSwapBuffers failed, error 0x${Integer.toHexString(core.lastError())}")
            return
        }
        Log.i(
            TAG,
            "drew ${layout.profile.name} pattern_rotation_deg=${layout.patternRotationDeg} " +
                "render_seq=${pattern.renderSeq} drawable=${widthPx}x$heightPx",
        )
        val rendered = onLayoutRendered
        if (rendered != null) {
            mainHandler.post { rendered(layout) }
        }
    }

    private fun releaseGl() {
        rectRenderer?.release()
        rectRenderer = null
        egl?.release()
        egl = null
    }

    private companion object {
        const val TAG = "OptcStaticSurface"
        const val RELEASE_TIMEOUT_SECONDS = 2L
    }
}
