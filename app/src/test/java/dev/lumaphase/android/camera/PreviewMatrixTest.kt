package dev.lumaphase.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

/**
 * Explicit matrix expectations for natural (sensor-rotated) content. These values were computed
 * from the contract of uniform fit plus display rotation, not by calling PreviewMatrix to make
 * the expected output.
 */
class PreviewMatrixTest {

    @Test
    fun landscapeContentIsUniformInAllFourDisplayRotations() {
        assertBounds(
            raw = 1280 to 720,
            sensor = 90,
            display = 0,
            view = 1080 to 1920,
            expected = RectF(0f, 0f, 1080f, 1920f),
        )
        assertBounds(
            raw = 1280 to 720,
            sensor = 90,
            display = 90,
            view = 1920 to 1080,
            expected = RectF(0f, 0f, 1920f, 1080f),
        )
        assertBounds(
            raw = 1280 to 720,
            sensor = 90,
            display = 180,
            view = 1080 to 1920,
            expected = RectF(0f, 0f, 1080f, 1920f),
        )
        assertBounds(
            raw = 1280 to 720,
            sensor = 90,
            display = 270,
            view = 1920 to 1080,
            expected = RectF(0f, 0f, 1920f, 1080f),
        )
        // A view whose aspect is not the upright aspect keeps letterbox, never anisotropic
        // stretching: 1280x720 upright in 1080x1920 gets 1080x607.5, centred at y=656.25.
        assertBounds(
            raw = 1280 to 720,
            sensor = 90,
            display = 90,
            view = 1080 to 1920,
            expected = RectF(0f, 656.25f, 1080f, 1263.75f),
        )
    }

    @Test
    fun textureViewAdditiveMatrixComposedWithDefaultFillEqualsTheIdealNaturalMap() {
        val cases = listOf(
            Triple(1280 to 720, 0, 1080 to 1920),
            Triple(1280 to 720, 0, 1008 to 1792), // 12dp padding at density 3
            Triple(1280 to 720, 90, 1920 to 1080),
            Triple(1280 to 720, 90, 1080 to 1920), // non-upright view / letterbox
            Triple(1280 to 720, 180, 1080 to 1920),
            Triple(720 to 720, 0, 1080 to 1080),
            Triple(720 to 720, 270, 1080 to 1080),
        )
        for ((raw, display, view) in cases) {
            val preview = CameraPreviewInfo(raw.first, raw.second, 90, display)
            val n = PreviewMatrix.compose(preview, view.first, view.second)
            val additive = PreviewMatrix.viewAdditive(preview, view.first, view.second)
            val natural = preview.naturalSize
            val d0 = view.first.toFloat() / natural.width
            val d1 = view.second.toFloat() / natural.height
            val composed = Affine2D(
                m00 = additive.m00 * d0,
                m01 = additive.m01 * d1,
                m02 = additive.m02,
                m10 = additive.m10 * d0,
                m11 = additive.m11 * d1,
                m12 = additive.m12,
            )
            assertAffineEquals("display=$display view=$view", n, composed)
            if (display == 0 && view.first * natural.height == view.second * natural.width) {
                assertAffineEquals("same-aspect display0 must be identity", Affine2D(1f,0f,0f,0f,1f,0f), additive)
            }
        }
    }

    @Test
    fun squareContentRemainsSquareAndUsesOneSingularValue() {
        for (display in listOf(0, 90, 180, 270)) {
            val preview = CameraPreviewInfo(720, 720, 0, display)
            val matrix = PreviewMatrix.compose(preview, 1080, 1080)
            val (sx, sy) = singularValues(matrix)
            assertEquals("display=$display singular x", 1.5f, sx, 1e-5f)
            assertEquals("display=$display singular y", 1.5f, sy, 1e-5f)
            val bounds = mapBounds(matrix, 720, 720)
            assertEquals("display=$display left", 0f, bounds.left, 1e-3f)
            assertEquals("display=$display top", 0f, bounds.top, 1e-3f)
            assertEquals("display=$display right", 1080f, bounds.right, 1e-3f)
            assertEquals("display=$display bottom", 1080f, bounds.bottom, 1e-3f)
        }
    }

    @Test
    fun overlayFitInverseMatchesTheSameUprightContentBox() {
        val preview = CameraPreviewInfo(1280, 720, 90, 90)
        val viewW = 1080f
        val viewH = 1920f
        val upright = preview.uprightSize
        val fit = CameraPreviewGeometry.fit(viewW, viewH, upright.width.toFloat(), upright.height.toFloat())
        assertEquals(1080f, fit.contentWidth, 1e-3f)
        assertEquals(607.5f, fit.contentHeight, 1e-3f)
        assertEquals(656.25f, fit.offsetY, 1e-3f)
        val topLeft = CameraPreviewGeometry.viewToFrame(fit, 0f, 656.25f, upright.width, upright.height)!!
        assertEquals(0.0, topLeft.x, 1e-6)
        assertEquals(0.0, topLeft.y, 1e-6)
        assertNull(
            "letterbox above content is not a frame point",
            CameraPreviewGeometry.viewToFrame(fit, 0f, 0f, upright.width, upright.height),
        )
    }

    private fun assertBounds(
        raw: Pair<Int, Int>,
        sensor: Int,
        display: Int,
        view: Pair<Int, Int>,
        expected: RectF,
    ) {
        val preview = CameraPreviewInfo(raw.first, raw.second, sensor, display)
        val matrix = PreviewMatrix.compose(preview, view.first, view.second)
        val (sx, sy) = singularValues(matrix)
        assertEquals("sensor=$sensor display=$display singular x", sx, sy, 1e-5f)
        val bounds = mapBounds(matrix, preview.naturalSize.width, preview.naturalSize.height)
        assertEquals("sensor=$sensor display=$display left", expected.left, bounds.left, 1e-3f)
        assertEquals("sensor=$sensor display=$display top", expected.top, bounds.top, 1e-3f)
        assertEquals("sensor=$sensor display=$display right", expected.right, bounds.right, 1e-3f)
        assertEquals("sensor=$sensor display=$display bottom", expected.bottom, bounds.bottom, 1e-3f)
    }

    private fun assertAffineEquals(label: String, expected: Affine2D, actual: Affine2D) {
        assertEquals("$label m00", expected.m00, actual.m00, 1e-4f)
        assertEquals("$label m01", expected.m01, actual.m01, 1e-4f)
        assertEquals("$label m02", expected.m02, actual.m02, 1e-3f)
        assertEquals("$label m10", expected.m10, actual.m10, 1e-4f)
        assertEquals("$label m11", expected.m11, actual.m11, 1e-4f)
        assertEquals("$label m12", expected.m12, actual.m12, 1e-3f)
    }

    private fun singularValues(m: Affine2D): Pair<Float, Float> {
        val x = sqrt(m.m00 * m.m00 + m.m10 * m.m10)
        val y = sqrt(m.m01 * m.m01 + m.m11 * m.m11)
        return x to y
    }

    private fun mapBounds(m: Affine2D, naturalW: Int, naturalH: Int): RectF {
        val points = listOf(
            m.map(0f, 0f),
            m.map(naturalW.toFloat(), 0f),
            m.map(naturalW.toFloat(), naturalH.toFloat()),
            m.map(0f, naturalH.toFloat()),
        )
        return RectF(
            points.minOf { it.first },
            points.minOf { it.second },
            points.maxOf { it.first },
            points.maxOf { it.second },
        )
    }

    private data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float)
}
