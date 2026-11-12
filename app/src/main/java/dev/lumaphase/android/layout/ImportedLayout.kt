package dev.lumaphase.android.layout

import dev.lumaphase.android.protocol.LayoutProfile

/**
 * Read-only description of one layout imported from a `layout.json` file.
 *
 * This deliberately is not [PatternLayout]: the imported file describes another display's
 * immutable output geometry, and the camera test must use its rectangles exactly as written.
 * Rebuilding a [PatternLayout] from the capture phone's drawable would silently replace the
 * measured geometry with a different one.
 *
 * [modules] and [padding] are already in final drawable output coordinates. They have been
 * rotated by the source display and must not be rotated again (OPTC-1 sections 3.1 and 3.2).
 * [geometry] is null only for a 1.0 layout whose complete geometry extension group is absent;
 * such a layout keeps the original single-row sampling behaviour.
 */
data class ImportedLayout(
    val schemaVersion: String,
    val layoutId: String,
    val profile: LayoutProfile,
    val geometry: GeometryProfile?,
    val drawableWidthPx: Int,
    val drawableHeightPx: Int,
    val patternRotationDeg: Int,
    val patternWidthPx: Int,
    val patternHeightPx: Int,
    val dynamicRectPx: RectPx,
    val modules: List<ModuleRect>,
    val padding: List<PaddingSlot>,
    val markers: List<ImportedMarker>,
    val levelLow: Int,
    val levelHigh: Int,
    val samplingInnerFraction: Double?,
    /** 1.2 type; legacy 1.0/1.1 layouts are OPTC. */
    val layoutType: LayoutType = LayoutType.OPTC,
) {
    /** Geometry name for display; a missing extension is the legacy single-row layout. */
    val geometryLabel: String get() = geometry?.wire ?: "legacy single-row"

    /** True only when the file carried no geometry extension at all. */
    val isLegacySingleRow: Boolean get() = geometry == null
}

/**
 * One locating marker read from `layout.json`. [cornersPx] keeps the file's order: the
 * marker's own top-left, top-right, bottom-right, bottom-left, already rotated by the source
 * pattern. It is not re-sorted by position in the captured image (contract section 4).
 */
data class ImportedMarker(
    val id: Int,
    val dictionary: String,
    val markerSizeBits: Int,
    val borderBits: Int,
    val cellSizePx: Int,
    val quietZonePx: Int,
    val cornersPx: List<PointPx>,
)
