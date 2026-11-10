package dev.lumaphase.android.layout

import dev.lumaphase.android.protocol.LayoutProfile
import java.io.Writer

/**
 * The immutable layout that the preview actually rendered, together with its serialised
 * `layout.json` text.
 *
 * This is the only geometry source for the export: [json] was produced from the same
 * [PatternLayout] instance that was drawn to the Surface, whose size came from
 * `surfaceChanged`. Nothing in the export path consults window or display metrics, so an
 * exported file can never describe pixels other than the ones that were displayed.
 *
 * The snapshot is deliberately small and travels as Activity result extras. There is no
 * registry, database or cache.
 */
data class PreviewedLayout(
    val json: String,
    val drawableWidthPx: Int,
    val drawableHeightPx: Int,
    val profile: LayoutProfile,
    val patternRotationDeg: Int,
) {
    /** Short human-readable identity of the rendered layout. */
    val description: String
        get() = "${profile.name}, pattern_rotation_deg=$patternRotationDeg, " +
            "${drawableWidthPx}x$drawableHeightPx px"
}

/**
 * Writes a previewed layout snapshot verbatim.
 *
 * There is no geometry code here on purpose: the function cannot rebuild a layout because
 * it is never given a size, a profile or a rotation.
 */
object LayoutExport {
    /** Returns false and writes nothing when no layout has been rendered yet. */
    fun write(writer: Writer, snapshot: PreviewedLayout?): Boolean {
        if (snapshot == null) return false
        writer.write(snapshot.json)
        return true
    }

    /**
     * Captures a layout that has just been drawn to the surface. [serialise] is the JSON
     * serialiser (`LayoutJson::toJson` in the app); keeping it a parameter means this data
     * flow can be exercised without Android's `JsonWriter`.
     */
    fun capture(
        layout: PatternLayout,
        serialise: (List<PatternLayout>) -> String,
    ): PreviewedLayout = PreviewedLayout(
        json = serialise(listOf(layout)),
        drawableWidthPx = layout.drawableWidthPx,
        drawableHeightPx = layout.drawableHeightPx,
        profile = layout.profile,
        patternRotationDeg = layout.patternRotationDeg,
    )
}
