package dev.lumaphase.android.camera

import kotlin.math.hypot

/**
 * Per-marker size diagnostic from one upright GrayFrame. It is a measurement proxy, not a
 * PASS/FAIL gate and not a calibrated true minimum cell:
 *
 * - [sideLengthsPx] are the four detected outer-corner side lengths in upright-frame pixels.
 * - [minEdgePx] is the shortest of those raw pixel lengths.
 * - [edgeMinDiv6Px] is `minEdgePx / 6`, the shortest outer edge divided by the six
 *   DICT_4X4_50 border+data cells. This is an **edge-derived proxy**, not the true per-cell
 *   minimum under perspective.
 *
 * A marker that was not detected has `detected=false` and all size fields null; nothing is
 * invented. OpenCV confidence is not exposed by this detector and is never fabricated.
 */
data class TagObservation(
    val markerId: Int,
    val detected: Boolean,
    val corners: List<DoublePoint>?,
    val sideLengthsPx: List<Double>?,
    val minEdgePx: Double?,
    val edgeMinDiv6Px: Double?,
    val frameWidthPx: Int,
    val frameHeightPx: Int,
    val frameTimestampNanos: Long,
)

object TagDiagnostics {
    /** Text-only engineering targets from CR-002; not used as a detector or PASS gate. */
    const val TARGET_MIN_OUTER_EDGE_PX = 60.0
    const val TARGET_MIN_CELL_PX = 8.0

    /** One observation per expected layout marker, including explicit missing entries. */
    fun observe(
        expectedMarkerIds: List<Int>,
        detected: Map<Int, DoubleArray>,
        frame: GrayFrame,
    ): List<TagObservation> = expectedMarkerIds.map { id ->
        measure(id, detected[id], frame)
    }

    fun measure(
        markerId: Int,
        detectedCorners: DoubleArray?,
        frame: GrayFrame,
    ): TagObservation {
        if (detectedCorners == null || detectedCorners.size != 8) {
            return TagObservation(
                markerId = markerId,
                detected = false,
                corners = null,
                sideLengthsPx = null,
                minEdgePx = null,
                edgeMinDiv6Px = null,
                frameWidthPx = frame.width,
                frameHeightPx = frame.height,
                frameTimestampNanos = frame.timestampNanos,
            )
        }
        val corners = (0 until 4).map { index ->
            DoublePoint(
                detectedCorners[2 * index],
                detectedCorners[2 * index + 1],
            )
        }
        val sides = (0 until 4).map { index ->
            val a = corners[index]
            val b = corners[(index + 1) % 4]
            hypot(b.x - a.x, b.y - a.y)
        }
        val minEdge = sides.min()
        return TagObservation(
            markerId = markerId,
            detected = true,
            corners = corners,
            sideLengthsPx = sides,
            minEdgePx = minEdge,
            edgeMinDiv6Px = minEdge / 6.0,
            frameWidthPx = frame.width,
            frameHeightPx = frame.height,
            frameTimestampNanos = frame.timestampNanos,
        )
    }
}
