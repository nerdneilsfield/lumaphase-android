package dev.lumaphase.android.camera

import dev.lumaphase.android.layout.ImportedLayout
import java.security.MessageDigest

/**
 * The camera identity that a fixed-station calibration belongs to. It is filled from the
 * actual Camera2 configuration; no value is inferred from the device model.
 */
data class CameraHardware(
    val cameraId: String,
    val sensorOrientationDeg: Int,
    val previewWidthPx: Int,
    val previewHeightPx: Int,
)

/** App-private fixed-station calibration in upright GrayFrame pixels. */
data class StationCalibration(
    val sourceLayoutId: String,
    val sourceGeometryDigest: String,
    val sourceDrawableWidthPx: Int,
    val sourceDrawableHeightPx: Int,
    val sourcePatternRotationDeg: Int,
    val cameraId: String,
    val cameraSensorOrientationDeg: Int,
    val cameraPreviewWidthPx: Int,
    val cameraPreviewHeightPx: Int,
    val frameWidthPx: Int,
    val frameHeightPx: Int,
    val cropLeftPx: Int,
    val cropTopPx: Int,
    val cropRightPx: Int,
    val cropBottomPx: Int,
    val sensorOrientationDeg: Int,
    val displayRotationDeg: Int,
    val appliedRotationDeg: Int,
    val targetCorners: List<StationPoint>,
    val createdAtMillis: Long,
)

data class StationPoint(val x: Double, val y: Double)

data class StationCheck(val ok: Boolean, val reason: String?) {
    companion object {
        val VALID = StationCheck(true, null)
    }
}

/** Distinguishes "camera still opening" from a real saved-station mismatch. */
enum class StationStartAction { WAIT_READY, START, INVALID }

internal fun stationStartAction(
    ready: Boolean,
    hardware: CameraHardware?,
    layoutCheck: StationCheck,
    cameraCheck: StationCheck?,
): StationStartAction = when {
    !layoutCheck.ok -> StationStartAction.INVALID
    !ready || hardware == null -> StationStartAction.WAIT_READY
    cameraCheck == null || !cameraCheck.ok -> StationStartAction.INVALID
    else -> StationStartAction.START
}

/**
 * Pure fixed-station validation and source-geometry identity. This is deliberately a raw
 * pixel homography station for the Android camera test, not a KB4 calibrated projection.
 *
 * Movement of the physical station cannot be detected from these fields; the operator must
 * re-mark when the screen or camera moves. Only explicit effects are stored, never a
 * guessed/partial calibration.
 */
object StationCalibrationRules {
    fun create(
        layout: ImportedLayout,
        hardware: CameraHardware,
        frame: GrayFrame,
        targetCorners: List<DoublePoint>,
        createdAtMillis: Long,
    ): StationCalibration {
        require(targetCorners.size == 4) { "fixed station needs four target corners" }
        require(targetCorners.all { it.x >= 0.0 && it.y >= 0.0 }) {
            "fixed station target must be non-negative"
        }
        require(targetCorners.all { it.x < frame.width && it.y < frame.height }) {
            "fixed station target must stay inside the ${frame.width}x${frame.height} frame"
        }
        return StationCalibration(
            sourceLayoutId = layout.layoutId,
            sourceGeometryDigest = sourceGeometryDigest(layout),
            sourceDrawableWidthPx = layout.drawableWidthPx,
            sourceDrawableHeightPx = layout.drawableHeightPx,
            sourcePatternRotationDeg = layout.patternRotationDeg,
            cameraId = hardware.cameraId,
            cameraSensorOrientationDeg = hardware.sensorOrientationDeg,
            cameraPreviewWidthPx = hardware.previewWidthPx,
            cameraPreviewHeightPx = hardware.previewHeightPx,
            frameWidthPx = frame.width,
            frameHeightPx = frame.height,
            cropLeftPx = frame.cropPx.x0,
            cropTopPx = frame.cropPx.y0,
            cropRightPx = frame.cropPx.x1,
            cropBottomPx = frame.cropPx.y1,
            sensorOrientationDeg = frame.sensorOrientationDeg,
            displayRotationDeg = frame.displayRotationDeg,
            appliedRotationDeg = frame.rotationDeg,
            targetCorners = targetCorners.map { StationPoint(it.x, it.y) },
            createdAtMillis = createdAtMillis,
        )
    }

    fun validateLayout(station: StationCalibration, layout: ImportedLayout): StationCheck {
        if (layout.layoutId != station.sourceLayoutId) {
            return StationCheck(false, "source layout_id changed")
        }
        if (layout.drawableWidthPx != station.sourceDrawableWidthPx ||
            layout.drawableHeightPx != station.sourceDrawableHeightPx
        ) {
            return StationCheck(false, "source drawable changed")
        }
        if (layout.patternRotationDeg != station.sourcePatternRotationDeg) {
            return StationCheck(false, "source pattern rotation changed")
        }
        if (sourceGeometryDigest(layout) != station.sourceGeometryDigest) {
            return StationCheck(false, "source geometry digest changed")
        }
        return StationCheck.VALID
    }

    fun validateCamera(station: StationCalibration, hardware: CameraHardware): StationCheck {
        if (hardware.cameraId != station.cameraId) {
            return StationCheck(false, "camera id changed")
        }
        if (hardware.sensorOrientationDeg != station.cameraSensorOrientationDeg) {
            return StationCheck(false, "camera sensor orientation changed")
        }
        if (hardware.previewWidthPx != station.cameraPreviewWidthPx ||
            hardware.previewHeightPx != station.cameraPreviewHeightPx
        ) {
            return StationCheck(false, "camera preview size changed")
        }
        return StationCheck.VALID
    }

    /** Per-frame check; a mismatch must stop RUN and require an explicit re-mark. */
    fun validateFrame(station: StationCalibration, frame: GrayFrame): StationCheck {
        if (frame.width != station.frameWidthPx || frame.height != station.frameHeightPx) {
            return StationCheck(false, "frame size changed")
        }
        if (frame.cropPx.x0 != station.cropLeftPx || frame.cropPx.y0 != station.cropTopPx ||
            frame.cropPx.x1 != station.cropRightPx || frame.cropPx.y1 != station.cropBottomPx
        ) {
            return StationCheck(false, "camera crop changed")
        }
        if (frame.sensorOrientationDeg != station.sensorOrientationDeg ||
            frame.displayRotationDeg != station.displayRotationDeg ||
            frame.rotationDeg != station.appliedRotationDeg
        ) {
            return StationCheck(false, "frame orientation changed")
        }
        if (station.targetCorners.any {
                it.x < 0.0 || it.y < 0.0 || it.x >= frame.width || it.y >= frame.height
            }
        ) {
            return StationCheck(false, "station target corner outside frame")
        }
        return StationCheck.VALID
    }

    fun validate(
        station: StationCalibration,
        layout: ImportedLayout,
        hardware: CameraHardware,
        frame: GrayFrame? = null,
    ): StationCheck {
        val layoutCheck = validateLayout(station, layout)
        if (!layoutCheck.ok) return layoutCheck
        val cameraCheck = validateCamera(station, hardware)
        if (!cameraCheck.ok) return cameraCheck
        return frame?.let { validateFrame(station, it) } ?: StationCheck.VALID
    }

    /**
     * Stable digest of the actual imported layout geometry, not only its mutable-looking id.
     * It covers the drawable canvas, rotation, dynamic rectangle, every module/padding rect,
     * marker identity/corners, levels and sampling metadata.
     */
    fun sourceGeometryDigest(layout: ImportedLayout): String {
        val text = buildString {
            append(layout.schemaVersion).append('|')
            append(layout.layoutId).append('|')
            append(layout.profile.name).append('|')
            append(layout.geometry?.wire ?: "legacy").append('|')
            append(layout.geometry?.gridRows ?: 0).append('|')
            append(layout.geometry?.gridCols ?: 0).append('|')
            append(layout.drawableWidthPx).append('x').append(layout.drawableHeightPx).append('|')
            append(layout.patternRotationDeg).append('|')
            append(layout.patternWidthPx).append('x').append(layout.patternHeightPx).append('|')
            append(layout.dynamicRectPx.edges.joinToString(",")).append('|')
            layout.modules.forEach { module ->
                append(module.index).append(':').append(module.rectPx.edges.joinToString(",")).append(';')
            }
            append('|')
            layout.padding.forEach { slot ->
                append(slot.slotIndex).append(':').append(slot.rectPx.edges.joinToString(","))
                    .append(':').append(slot.level).append(';')
            }
            append('|')
            layout.markers.forEach { marker ->
                append(marker.id).append(':').append(marker.dictionary).append(':')
                    .append(marker.markerSizeBits).append(':').append(marker.borderBits).append(':')
                    .append(marker.cellSizePx).append(':').append(marker.quietZonePx).append(':')
                marker.cornersPx.forEach { corner ->
                    append(corner.x).append(',').append(corner.y).append(' ')
                }
                append(';')
            }
            append('|')
            append(layout.levelLow).append('/').append(layout.levelHigh).append('|')
            append(layout.samplingInnerFraction ?: "null")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }
}
