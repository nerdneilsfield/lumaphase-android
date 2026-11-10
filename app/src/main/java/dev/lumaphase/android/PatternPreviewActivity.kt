package dev.lumaphase.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import dev.lumaphase.android.layout.GeometryProfile
import dev.lumaphase.android.layout.LayoutChoice
import dev.lumaphase.android.layout.LayoutExport
import dev.lumaphase.android.layout.LayoutJson
import dev.lumaphase.android.layout.PatternLayout
import dev.lumaphase.android.layout.PatternLayoutBuilder
import dev.lumaphase.android.layout.PreviewedLayout
import dev.lumaphase.android.protocol.LayoutProfile
import dev.lumaphase.android.protocol.PatternEncoder
import dev.lumaphase.android.render.StaticPatternSurface

/**
 * Full-screen static optical target.
 *
 * Nothing is drawn over the pattern: the window is immersive, portrait-locked and kept
 * awake, and the system back key finishes this activity and returns to the settings
 * screen. The pattern geometry is built from the actual surface size reported by
 * `surfaceChanged`, and that exact [PatternLayout] instance is what gets drawn.
 *
 * After the layout has really been drawn, its serialised `layout.json` and identity are
 * returned as the activity result, so the settings screen can export the pixels that were
 * rendered instead of estimating a size of its own.
 *
 * `pattern_rotation_deg` is the operator's choice and is independent of the display
 * rotation, which this app never derives from the sensor.
 */
class PatternPreviewActivity : Activity() {

    private lateinit var surfaceView: StaticPatternSurface

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val profileName = intent.getStringExtra(EXTRA_PROFILE) ?: LayoutProfile.F68.name
        val choice = LayoutChoice.forWire(intent.getStringExtra(EXTRA_LAYOUT_CHOICE))
            ?: LayoutChoice.legacy(LayoutProfile.valueOf(profileName))
        val rotationDeg = intent.getIntExtra(EXTRA_ROTATION_DEG, 0)
        val renderSeq = intent.getStringExtra(EXTRA_RENDER_SEQ)?.toULongOrNull() ?: 0uL
        val encoded = PatternEncoder.encode(renderSeq, choice.layoutProfile)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The window must always fill the display, otherwise a transient system-bar reveal
        // would resize the Surface and produce a different layout. The platform call is used
        // directly rather than adding an AndroidX compatibility layer; it is deprecated but
        // still functional and is the only platform API that does this.
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)

        surfaceView = StaticPatternSurface(this)
        surfaceView.onSurfaceSize = { widthPx, heightPx ->
            try {
                val layout = PatternLayoutBuilder.build(
                    PatternLayoutBuilder.STATIC_LAYOUT_ID,
                    choice,
                    widthPx,
                    heightPx,
                    rotationDeg,
                )
                surfaceView.show(layout, encoded)
            } catch (error: IllegalArgumentException) {
                Log.e(TAG, "cannot build the pattern for ${widthPx}x$heightPx", error)
                finish()
            }
        }
        surfaceView.onLayoutRendered = { layout -> reportRenderedLayout(layout) }
        setContentView(surfaceView)
        Log.i(
            TAG,
            "preview ${choice.layoutProfile.name} choice=${choice.wire} " +
                "pattern_rotation_deg=$rotationDeg render_seq=$renderSeq",
        )
    }

    /** Publishes the drawn layout as the activity result; the most recent render wins. */
    private fun reportRenderedLayout(layout: PatternLayout) {
        val snapshot = LayoutExport.capture(layout) { LayoutJson.toJson(it) }
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(RESULT_LAYOUT_JSON, snapshot.json)
                .putExtra(RESULT_DRAWABLE_WIDTH_PX, snapshot.drawableWidthPx)
                .putExtra(RESULT_DRAWABLE_HEIGHT_PX, snapshot.drawableHeightPx)
                .putExtra(RESULT_PROFILE, snapshot.profile.name)
                .putExtra(RESULT_GEOMETRY_PROFILE, snapshot.geometry.wire)
                .putExtra(RESULT_PATTERN_ROTATION_DEG, snapshot.patternRotationDeg),
        )
        Log.i(TAG, "reported rendered layout ${snapshot.description}")
    }

    /**
     * Hides the system bars once the window is attached and focused, where
     * `window.insetsController` is guaranteed to exist; doing it before `setContentView`
     * can silently do nothing because the controller may still be null.
     */
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

    override fun onDestroy() {
        surfaceView.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OptcPreview"
        private const val EXTRA_PROFILE = "extra_profile"
        private const val EXTRA_LAYOUT_CHOICE = "extra_layout_choice"
        private const val EXTRA_ROTATION_DEG = "extra_rotation_deg"
        private const val EXTRA_RENDER_SEQ = "extra_render_seq"

        const val RESULT_LAYOUT_JSON = "result_layout_json"
        const val RESULT_DRAWABLE_WIDTH_PX = "result_drawable_width_px"
        const val RESULT_DRAWABLE_HEIGHT_PX = "result_drawable_height_px"
        const val RESULT_PROFILE = "result_profile"
        const val RESULT_GEOMETRY_PROFILE = "result_geometry_profile"
        const val RESULT_PATTERN_ROTATION_DEG = "result_pattern_rotation_deg"

        fun intent(
            context: Context,
            choice: LayoutChoice,
            rotationDeg: Int,
            renderSeq: ULong,
        ): Intent = Intent(context, PatternPreviewActivity::class.java)
            .putExtra(EXTRA_PROFILE, choice.layoutProfile.name)
            .putExtra(EXTRA_LAYOUT_CHOICE, choice.wire)
            .putExtra(EXTRA_ROTATION_DEG, rotationDeg)
            .putExtra(EXTRA_RENDER_SEQ, renderSeq.toString())

        /**
         * Reads the rendered-layout snapshot from a preview result. Returns null when the
         * preview never drew anything, so the caller must not export.
         */
        fun previewedLayout(result: Intent?): PreviewedLayout? {
            if (result == null) return null
            val json = result.getStringExtra(RESULT_LAYOUT_JSON) ?: return null
            val widthPx = result.getIntExtra(RESULT_DRAWABLE_WIDTH_PX, -1)
            val heightPx = result.getIntExtra(RESULT_DRAWABLE_HEIGHT_PX, -1)
            val rotationDeg = result.getIntExtra(RESULT_PATTERN_ROTATION_DEG, Int.MIN_VALUE)
            val profileName = result.getStringExtra(RESULT_PROFILE) ?: return null
            val geometryName = result.getStringExtra(RESULT_GEOMETRY_PROFILE) ?: return null
            if (json.isEmpty() || widthPx <= 0 || heightPx <= 0 || rotationDeg == Int.MIN_VALUE) {
                return null
            }
            val profile = runCatching { LayoutProfile.valueOf(profileName) }.getOrNull() ?: return null
            val geometry = GeometryProfile.forWire(geometryName) ?: return null
            if (geometry.layoutProfile != profile) return null
            return PreviewedLayout(json, widthPx, heightPx, profile, geometry, rotationDeg)
        }
    }
}
