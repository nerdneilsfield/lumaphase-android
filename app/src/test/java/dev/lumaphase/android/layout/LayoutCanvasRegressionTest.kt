package dev.lumaphase.android.layout

import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Frozen external regression oracle for the geometry that must not change.
 *
 * The digest covers every actual drawable rectangle and marker cell produced by
 * [PatternLayoutBuilder], for all six geometry profiles, all four pattern rotations and
 * non-square/odd drawable sizes. It deliberately excludes `pattern_width_px`,
 * `pattern_height_px` and `pattern_to_drawable`: those public fields are being corrected to
 * describe the complete logical canvas, while the rendered modules, padding and markers must
 * remain byte-for-byte identical.
 *
 * The expected hash was produced from the pre-fix builder and then copied here. A later
 * change that moves a module, padding slot, marker or dynamic rectangle for any of these
 * cases fails this test even if every other test still passes.
 */
class LayoutCanvasRegressionTest {

    @Test
    fun actualDrawableGeometryIsUnchangedForAllProfilesAndRotations() {
        assertEquals(EXPECTED_SHA256, geometryDigest())
    }

    private fun geometryDigest(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (geometry in GeometryProfile.entries) {
            for ((widthPx, heightPx) in drawableCases) {
                for (rotationDeg in PatternLayoutBuilder.ROTATIONS_DEG) {
                    val layout = PatternLayoutBuilder.build(
                        "regression-${geometry.wire}",
                        geometry,
                        widthPx,
                        heightPx,
                        rotationDeg,
                    )
                    val quarterTurn = rotationDeg % 180 != 0
                    val expectedCanvasWidth = if (quarterTurn) heightPx else widthPx
                    val expectedCanvasHeight = if (quarterTurn) widthPx else heightPx
                    assertEquals(expectedCanvasWidth, layout.patternWidthPx)
                    assertEquals(expectedCanvasHeight, layout.patternHeightPx)
                    assertEquals(
                        patternToDrawableMatrix(
                            rotationDeg,
                            expectedCanvasWidth,
                            expectedCanvasHeight,
                            0,
                            0,
                        ).toList(),
                        layout.patternToDrawable,
                    )
                    digest.update(canonical(layout).toByteArray(Charsets.UTF_8))
                    digest.update('\n'.code.toByte())
                }
            }
        }
        return digest.digest().joinToString("") { byte ->
            String.format(Locale.ROOT, "%02x", byte)
        }
    }

    private fun canonical(layout: PatternLayout): String = buildString {
        append(layout.profile.name).append('|')
        append(layout.geometry.wire).append('|')
        append(layout.drawableWidthPx).append('x').append(layout.drawableHeightPx).append('|')
        append(layout.patternRotationDeg).append('|')
        append(layout.dynamicRectPx.edges.joinToString(",")).append('|')
        append(layout.levelLow).append('/').append(layout.levelHigh).append('|')
        layout.modules.forEach { module ->
            append("m").append(module.index).append('=')
            append(module.rectPx.edges.joinToString(",")).append(';')
        }
        layout.padding.forEach { slot ->
            append("p").append(slot.slotIndex).append('=')
            append(slot.rectPx.edges.joinToString(",")).append(':').append(slot.level).append(';')
        }
        layout.markers.forEach { marker ->
            append("k").append(marker.id).append('=')
            append(marker.cornersPx.joinToString(";") { "${it.x},${it.y}" }).append('|')
            append(marker.quietRectPx.edges.joinToString(",")).append('|')
            append(marker.outerRectPx.edges.joinToString(",")).append('|')
            append(marker.cellSizePx).append(':').append(marker.quietZonePx).append('|')
            marker.cellRects.forEach { rect -> append(rect.edges.joinToString(",")).append(';') }
            marker.cellValues.forEach { value -> append(value).append(',') }
        }
    }

    private companion object {
        val drawableCases = listOf(
            1081 to 2401,
            799 to 601,
            2400 to 1081,
        )

        // Filled from the pre-fix builder output before changing the public canvas fields.
        const val EXPECTED_SHA256 = "432469ea9118dc8f7c4a45c68f771f030cdb96b22a73a8ed8e3d288683557cad"
    }
}
