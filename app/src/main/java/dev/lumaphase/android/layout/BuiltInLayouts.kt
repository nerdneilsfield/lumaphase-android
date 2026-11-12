package dev.lumaphase.android.layout

/**
 * Named source-reference presets that can be used before importing a real `layout.json`.
 *
 * Each preset rebuilds its geometry from the existing [PatternLayoutBuilder] at a fixed,
 * explicitly named source reference size. Nothing here is measured from the capture phone or
 * inferred from the device under test: a target whose real display geometry differs still needs
 * its own `layout.json`. The reduced public fields are the builder's actual drawable rectangles,
 * mapped to [ImportedLayout] without re-rotation.
 */
data class BuiltInLayoutPreset(
    val id: String,
    val label: String,
    val referenceWidthPx: Int,
    val referenceHeightPx: Int,
    val geometry: GeometryProfile,
    /** Keep the preset's payload grid and enlarge only its four locating tags. */
    val largeTags: Boolean = false,
) {
    /** Reference display size shown next to the preset; not the tested device's size. */
    val sourceReference: String get() = "${referenceWidthPx}×${referenceHeightPx}"
}

/** One built-in preset plus the operator-selected pattern rotation. */
data class BuiltInSelection(
    val preset: BuiltInLayoutPreset,
    val rotationDeg: Int,
) {
    init {
        require(rotationDeg in BuiltInLayouts.ROTATIONS_DEG) {
            "unsupported pattern rotation $rotationDeg"
        }
    }
}

object BuiltInLayouts {
    const val SCHEMA_VERSION = "builtin"

    val ROTATIONS_DEG: IntArray = intArrayOf(0, 90, 180, 270)

    val PRESETS: List<BuiltInLayoutPreset> = listOf(
        preset(
            "builtin-android-portrait-f68-g9x8",
            "Android 竖屏 1080×2400 · F68-G9x8",
            1080,
            2400,
            GeometryProfile.F68_G9X8,
        ),
        preset(
            "builtin-android-portrait-c52-g9x6",
            "Android 竖屏 1080×2400 · C52-G9x6",
            1080,
            2400,
            GeometryProfile.C52_G9X6,
        ),
        preset(
            "builtin-android-portrait-f68-single",
            "Android 竖屏 1080×2400 · F68 单排",
            1080,
            2400,
            GeometryProfile.F68,
        ),
        preset(
            "builtin-android-portrait-c52-single",
            "Android 竖屏 1080×2400 · C52 单排",
            1080,
            2400,
            GeometryProfile.C52,
        ),
        preset(
            "builtin-android-landscape-f68-g6x12",
            "Android 横屏 2400×1080 · F68-G6x12",
            2400,
            1080,
            GeometryProfile.F68_G6X12,
        ),
        preset(
            "builtin-android-landscape-c52-g5x12",
            "Android 横屏 2400×1080 · C52-G5x12",
            2400,
            1080,
            GeometryProfile.C52_G5X12,
        ),
        preset(
            LARGE_TAG_PRESET_ID,
            "Android 竖屏 1080×2400 · F68-G9x8 大Tag",
            1080,
            2400,
            GeometryProfile.F68_G9X8,
            largeTags = true,
        ),
        preset(
            C52_LARGE_TAG_PRESET_ID,
            "Android 竖屏 1080×2400 · C52-G9x6 大Tag",
            1080,
            2400,
            GeometryProfile.C52_G9X6,
            largeTags = true,
        ),
    )

    /**
     * The immutable id of the new large-tag preset. It is a separate source-reference identity;
     * the six previous preset ids stay untouched.
     */
    const val LARGE_TAG_PRESET_ID = "builtin-android-portrait-f68-g9x8-large-tag"

    /** The C52 counterpart; it keeps the old six ids and the F68 default untouched. */
    const val C52_LARGE_TAG_PRESET_ID = "builtin-android-portrait-c52-g9x6-large-tag"

    /** First-launch default: the new Android portrait F68 large-tag preset. */
    val DEFAULT: BuiltInLayoutPreset get() =
        PRESETS.first { it.id == LARGE_TAG_PRESET_ID }

    fun find(presetId: String?): BuiltInLayoutPreset? =
        PRESETS.firstOrNull { it.id == presetId }

    /** Resolves a saved preset id/rotation; an unknown id or rotation is rejected, not guessed. */
    fun selection(presetId: String?, rotationDeg: Int): BuiltInSelection? {
        val preset = find(presetId) ?: return null
        if (rotationDeg !in ROTATIONS_DEG) return null
        return BuiltInSelection(preset, rotationDeg)
    }

    /** Builds the source layout through the real builder, then maps its fields. */
    fun build(selection: BuiltInSelection): ImportedLayout {
        val preset = selection.preset
        val layout = PatternLayoutBuilder.build(
            // Pattern rotation changes the actual geometry, so the immutable-layout contract
            // requires a distinct layout id. The saved preset id itself stays rotation-free.
            layoutId = "${preset.id}-r${selection.rotationDeg}",
            geometry = preset.geometry,
            drawableWidthPx = preset.referenceWidthPx,
            drawableHeightPx = preset.referenceHeightPx,
            patternRotationDeg = selection.rotationDeg,
            largeTags = preset.largeTags,
        )
        return layout.toImported()
    }

    private fun preset(
        id: String,
        label: String,
        widthPx: Int,
        heightPx: Int,
        geometry: GeometryProfile,
        largeTags: Boolean = false,
    ): BuiltInLayoutPreset = BuiltInLayoutPreset(id, label, widthPx, heightPx, geometry, largeTags)

    private fun PatternLayout.toImported(): ImportedLayout = ImportedLayout(
        schemaVersion = SCHEMA_VERSION,
        layoutId = layoutId,
        profile = profile,
        geometry = geometry,
        drawableWidthPx = drawableWidthPx,
        drawableHeightPx = drawableHeightPx,
        patternRotationDeg = patternRotationDeg,
        patternWidthPx = patternWidthPx,
        patternHeightPx = patternHeightPx,
        dynamicRectPx = dynamicRectPx,
        modules = modules,
        padding = padding,
        markers = markers.map { marker ->
            ImportedMarker(
                id = marker.id,
                dictionary = marker.dictionary,
                markerSizeBits = marker.markerSizeBits,
                borderBits = marker.borderBits,
                cellSizePx = marker.cellSizePx,
                quietZonePx = marker.quietZonePx,
                cornersPx = marker.cornersPx,
            )
        },
        levelLow = levelLow,
        levelHigh = levelHigh,
        samplingInnerFraction = GridPlacement.SAMPLING_INNER_FRACTION,
    )
}
