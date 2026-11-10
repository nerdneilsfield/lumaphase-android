package dev.lumaphase.android.render

/**
 * The display mode that was in force when a dynamic run started.
 *
 * A granted `Surface.setFrameRate` request is not evidence that the panel actually runs at
 * that rate, so the mode the platform reports is recorded alongside the request.
 */
data class DisplayModeInfo(
    val modeId: Int,
    val refreshRateHz: Float,
    val physicalWidthPx: Int,
    val physicalHeightPx: Int,
    val supportedModeCount: Int,
) {
    val description: String
        get() = "mode=$modeId ${refreshRateHz}Hz ${physicalWidthPx}x$physicalHeightPx " +
            "supported_modes=$supportedModeCount"
}
