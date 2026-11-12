package dev.lumaphase.android.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayLayoutTest {
    @Test
    fun calibrationTargetUsesRealThreeTimesRunTagAndStaticReferences() {
        val run = PatternLayoutBuilder.build(
            "run",
            GeometryProfile.F68_G6X12,
            1512,
            982,
            0,
        )
        val target = CalibrationTargetBuilder.build("tag", run)
        assertNotEquals(run.layoutId, target.layoutId)
        assertEquals(LayoutType.CALIBRATION_TARGET, target.layoutType)
        assertEquals(1512, target.drawableWidthPx)
        assertEquals(982, target.drawableHeightPx)
        assertEquals(listOf(0, 1, 2, 3), target.markers.map { it.id })
        val runCell = run.markers.first().cellSizePx
        assertTrue(target.markers.all { it.cellSizePx == 3 * runCell })
        assertTrue(target.markers.all { it.quietZonePx >= it.cellSizePx })
        assertEquals(listOf(0, 128, 255), target.references.map { it.level })
        assertTrue(target.references.all { it.rectPx.widthPx > 0 && it.rectPx.heightPx > 0 })
    }

    @Test
    fun calibrationTargetRefusesARunLayoutThatCannotFitThreeTimesTag() {
        val run = PatternLayoutBuilder.build(
            "tiny",
            GeometryProfile.F68_G6X12,
            140,
            100,
            0,
        )
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationTargetBuilder.build("tag", run)
        }
    }

    @Test
    fun landscapeTAGFitsWhilePortrait1080x2400DoesNot() {
        val landscape = PatternLayoutBuilder.build(
            "landscape",
            GeometryProfile.F68_G6X12,
            2400,
            1080,
            0,
        )
        // 1080*68/100=734 -> c_run=734/48=15 -> c_cal=45 -> margin=9*45=405.
        // 2400-2*405=1590 and 1080-2*405=270, so the fixed 3x target fits.
        assertEquals(15, landscape.markers.first().cellSizePx)
        val target = CalibrationTargetBuilder.build("tag-landscape", landscape)
        assertTrue(target.markers.all { it.cellSizePx == 45 })
        val rotated = PatternLayoutBuilder.build(
            "landscape-r90",
            GeometryProfile.F68_G6X12,
            2400,
            1080,
            90,
        )
        assertEquals(15, rotated.markers.first().cellSizePx)
        val rotatedTarget = CalibrationTargetBuilder.build("tag-landscape-r90", rotated)
        assertTrue(rotatedTarget.markers.all { it.cellSizePx == 45 })
        val fullHdLandscape = PatternLayoutBuilder.build(
            "hd-landscape",
            GeometryProfile.F68_G6X12,
            1920,
            1080,
            0,
        )
        CalibrationTargetBuilder.build("tag-hd", fullHdLandscape)
        val portrait = PatternLayoutBuilder.build(
            "portrait",
            GeometryProfile.F68_G9X8,
            1080,
            2400,
            0,
        )
        // 1080-2*405 < 3, so the fixed 3x target cannot fit and is reported, never scaled.
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationTargetBuilder.build("tag-portrait", portrait)
        }
    }

    @Test
    fun fullscreenDiagnosticHasNoMarkersAndUsesAllocatedNativeFrameParity() {
        val white = FullscreenDiagnosticBuilder.build(
            "white",
            120,
            80,
            0,
            DiagnosticPattern.WHITE,
        )
        assertEquals(LayoutType.FULLSCREEN_DIAGNOSTIC, white.layoutType)
        assertEquals(255, fullscreenDiagnosticRects(white, 3).single().level)
        val black = FullscreenDiagnosticBuilder.build(
            "black",
            120,
            80,
            90,
            DiagnosticPattern.BLACK,
        )
        assertEquals(0, fullscreenDiagnosticRects(black, 4).single().level)
        val bw = FullscreenDiagnosticBuilder.build(
            "bw",
            120,
            80,
            180,
            DiagnosticPattern.VSYNC_BLACK_WHITE,
        )
        assertEquals(255, fullscreenDiagnosticRects(bw, 2).single().level)
        assertEquals(0, fullscreenDiagnosticRects(bw, 3).single().level)
        val checker = FullscreenDiagnosticBuilder.build(
            "checker",
            120,
            80,
            0,
            DiagnosticPattern.CHECKERBOARD_2X2,
        )
        assertEquals(2, fullscreenDiagnosticRects(checker, 0).size)
        assertTrue(fullscreenDiagnosticRects(checker, 0).all { it.level == 255 })
    }
}
