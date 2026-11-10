package dev.lumaphase.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import dev.lumaphase.android.layout.PatternLayoutBuilder
import dev.lumaphase.android.protocol.LayoutProfile
import dev.lumaphase.android.render.DynamicPatternSurface
import dev.lumaphase.android.render.DynamicRenderThread
import dev.lumaphase.android.render.DynamicRunState
import dev.lumaphase.android.timing.TimestampPrefix

/**
 * Full-screen dynamic OPTC-1 pattern driven by `Choreographer.VsyncCallback`.
 *
 * Nothing is drawn over the pattern. The activity is portrait-locked, immersive and kept
 * awake; the system back key returns to the settings screen, which then shows a summary of
 * what actually ran. Whether the display really ran at the requested rate, and whether the
 * EGL timestamp extension works on the device, cannot be established here: the summary
 * reports what the platform answered, not a measurement of the panel.
 */
class DynamicPreviewActivity : Activity() {

    private lateinit var surfaceView: DynamicPatternSurface
    private lateinit var profile: LayoutProfile
    private var patternRotationDeg = 0
    private var layoutCounter = 0
    private var currentGeometry: Long? = null
    private var currentLayoutId = ""
    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val profileName = intent.getStringExtra(EXTRA_PROFILE) ?: LayoutProfile.F68.name
        profile = LayoutProfile.valueOf(profileName)
        patternRotationDeg = intent.getIntExtra(EXTRA_ROTATION_DEG, 0)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The window must always fill the display, otherwise a transient system-bar reveal
        // would resize the Surface mid-run. The platform call is used directly rather than
        // adding an AndroidX compatibility layer.
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)

        surfaceView = DynamicPatternSurface(this)
        surfaceView.configureDynamic(profile, patternRotationDeg, DynamicRunState())
        surfaceView.onSurfaceSize = { widthPx, heightPx ->
            try {
                val layout = PatternLayoutBuilder.build(
                    layoutIdFor(widthPx, heightPx),
                    profile,
                    widthPx,
                    heightPx,
                    patternRotationDeg,
                )
                surfaceView.showLayout(layout)
            } catch (error: IllegalArgumentException) {
                Log.e(TAG, "cannot build the pattern for ${widthPx}x$heightPx", error)
                finish()
            }
        }
        setContentView(surfaceView)
        Log.i(TAG, "dynamic preview ${profile.name} pattern_rotation_deg=$patternRotationDeg")
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
        surfaceView.resumeScheduling()
    }

    override fun onStop() {
        surfaceView.pauseScheduling()
        super.onStop()
    }

    override fun finish() {
        if (finishing) {
            super.finish()
            return
        }
        finishing = true
        // Stop scheduling, drain the pending frames and release GL/EGL before reading the
        // numbers, so the summary handed back is complete. Then report and finish.
        surfaceView.stopAndRelease()
        setResult(RESULT_OK, Intent().putExtra(RESULT_SUMMARY, buildSummary()))
        super.finish()
    }

    override fun onDestroy() {
        // Idempotent safety net for a destroy that did not go through finish().
        surfaceView.stopAndRelease()
        super.onDestroy()
    }

    /**
     * A new geometry needs a new immutable layout identity; the same geometry keeps its id.
     * Submissions freeze the id they were made with, so a frame submitted earlier keeps its
     * own layout id even after the geometry changed.
     */
    private fun layoutIdFor(widthPx: Int, heightPx: Int): String {
        val geometry = (widthPx.toLong() shl 32) or (heightPx.toLong() and 0xFFFFFFFFL)
        if (geometry != currentGeometry) {
            currentGeometry = geometry
            currentLayoutId = "dynamic-layout-${layoutCounter++}"
        }
        return currentLayoutId
    }

    private fun buildSummary(): String {
        val stats = surfaceView.stats()
        val capabilities = surfaceView.capabilities()
        val text = buildString {
            append("run=").append(stats.runId.ifEmpty { "none" })
            append(" profile=").append(profile.name)
            append(" pattern_rotation_deg=").append(patternRotationDeg).append('\n')
            append("display ").append(surfaceView.displayModeText()).append('\n')
            append("requested ").append(DynamicRenderThread.REQUESTED_REFRESH_HZ)
            append("Hz with CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS (a request, not a guarantee)\n")
            if (capabilities == null) {
                append("capabilities: not queried (the run never started)\n")
            } else {
                append("capabilities: extension=").append(capabilities.extensionAvailable)
                append(" timestamps_enabled=").append(capabilities.timestampsEnabled)
                append(" presentation_time=").append(capabilities.presentationTimeSupported)
                for (field in EGL_FIELDS) {
                    append(' ').append(field.key).append('=')
                    append(capabilities.supportedTypes[field] == true)
                }
                append('\n')
            }
            append("stats ").append(stats).append('\n')
            append("submissions=").append(surfaceView.submissionCount())
            append(" results=").append(surfaceView.resultCount()).append('\n')
            surfaceView.recentSubmissions().forEach { append("  submitted: ").append(it).append('\n') }
            surfaceView.recentResults().forEach { append("  result: ").append(it).append('\n') }
            append(
                "note: this is runtime evidence only; the actual refresh rate and the EGL " +
                    "timestamp support are not verified without a device run",
            )
        }
        return text
    }

    companion object {
        private const val TAG = "OptcDynamicPreview"
        private const val EXTRA_PROFILE = "extra_profile"
        private const val EXTRA_ROTATION_DEG = "extra_rotation_deg"
        const val RESULT_SUMMARY = "result_dynamic_summary"

        private val EGL_FIELDS = listOf(
            TimestampPrefix.SYSTEM_PRESENT,
            TimestampPrefix.RENDER_COMPLETE,
            TimestampPrefix.LATCH,
        )

        fun intent(
            context: Context,
            profile: LayoutProfile,
            rotationDeg: Int,
        ): Intent = Intent(context, DynamicPreviewActivity::class.java)
            .putExtra(EXTRA_PROFILE, profile.name)
            .putExtra(EXTRA_ROTATION_DEG, rotationDeg)

        fun summary(result: Intent?): String? = result?.getStringExtra(RESULT_SUMMARY)
    }
}
