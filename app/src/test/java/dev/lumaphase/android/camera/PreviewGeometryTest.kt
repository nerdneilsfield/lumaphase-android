package dev.lumaphase.android.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Explicit orientation/aspect expectations, not values derived by calling the same helpers.
 * Raw buffers are landscape, portrait and square; the expected upright extents cover every
 * sensor/display quarter-turn combination.
 */
class PreviewGeometryTest {

    @Test
    fun landscapeBufferUsesTheUprightExtentsForEveryQuarterTurn() {
        val rawWidth = 1280
        val rawHeight = 720

        // Natural TextureView content: sensor 90 turns the landscape buffer into 720x1280.
        assertEquals(720, CameraPreviewGeometry.naturalSize(rawWidth, rawHeight, 90).width)
        assertEquals(1280, CameraPreviewGeometry.naturalSize(rawWidth, rawHeight, 90).height)
        assertEquals(1280, CameraPreviewGeometry.naturalSize(rawWidth, rawHeight, 0).width)
        assertEquals(720, CameraPreviewGeometry.naturalSize(rawWidth, rawHeight, 0).height)

        assertUpright(rawWidth, rawHeight, sensor = 90, display = 0, expectedW = 720, expectedH = 1280)
        assertUpright(rawWidth, rawHeight, sensor = 90, display = 90, expectedW = 1280, expectedH = 720)
        assertUpright(rawWidth, rawHeight, sensor = 90, display = 180, expectedW = 720, expectedH = 1280)
        assertUpright(rawWidth, rawHeight, sensor = 90, display = 270, expectedW = 1280, expectedH = 720)

        assertUpright(rawWidth, rawHeight, sensor = 0, display = 0, expectedW = 1280, expectedH = 720)
        assertUpright(rawWidth, rawHeight, sensor = 0, display = 90, expectedW = 720, expectedH = 1280)
        assertUpright(rawWidth, rawHeight, sensor = 0, display = 180, expectedW = 1280, expectedH = 720)
        assertUpright(rawWidth, rawHeight, sensor = 0, display = 270, expectedW = 720, expectedH = 1280)

        // The relative rotation and the upright extents agree for the portrait phone case.
        assertEquals(90, CameraPreviewInfo(1280, 720, 90, 0).uprightRotationDeg)
        assertEquals(0, CameraPreviewInfo(1280, 720, 90, 90).uprightRotationDeg)
        assertEquals(270, CameraPreviewInfo(1280, 720, 90, 180).uprightRotationDeg)
        assertEquals(180, CameraPreviewInfo(1280, 720, 90, 270).uprightRotationDeg)
    }

    @Test
    fun squareBufferStaysSquareForAllFourRotations() {
        for (sensor in listOf(0, 90, 180, 270)) {
            for (display in listOf(0, 90, 180, 270)) {
                val size = CameraPreviewGeometry.uprightSize(720, 720, sensor, display)
                assertEquals("sensor=$sensor display=$display", 720, size.width)
                assertEquals("sensor=$sensor display=$display", 720, size.height)
            }
        }
    }

    @Test
    fun fitUsesOneScaleAndCentresLetterboxBars() {
        val portrait = CameraPreviewGeometry.fit(
            viewWidth = 1000f,
            viewHeight = 800f,
            contentWidth = 720f,
            contentHeight = 1280f,
        )
        assertEquals(0.625f, portrait.scale, 1e-6f)
        assertEquals(450f, portrait.contentWidth, 1e-4f)
        assertEquals(800f, portrait.contentHeight, 1e-4f)
        assertEquals(275f, portrait.offsetX, 1e-4f)
        assertEquals(0f, portrait.offsetY, 1e-4f)
        assertEquals(
            "portrait content must keep its aspect",
            720f / 1280f,
            portrait.contentWidth / portrait.contentHeight,
            1e-6f,
        )

        val landscape = CameraPreviewGeometry.fit(
            viewWidth = 1000f,
            viewHeight = 800f,
            contentWidth = 1280f,
            contentHeight = 720f,
        )
        assertEquals(0.78125f, landscape.scale, 1e-6f)
        assertEquals(1000f, landscape.contentWidth, 1e-4f)
        assertEquals(562.5f, landscape.contentHeight, 1e-4f)
        assertEquals(0f, landscape.offsetX, 1e-4f)
        assertEquals(118.75f, landscape.offsetY, 1e-4f)
        assertEquals(
            "landscape content must keep its aspect",
            1280f / 720f,
            landscape.contentWidth / landscape.contentHeight,
            1e-6f,
        )

        val square = CameraPreviewGeometry.fit(1000f, 800f, 720f, 720f)
        assertEquals(800f / 720f, square.scale, 1e-6f)
        assertEquals(800f, square.contentWidth, 1e-4f)
        assertEquals(800f, square.contentHeight, 1e-4f)
        assertEquals(100f, square.offsetX, 1e-4f)
        assertEquals(0f, square.offsetY, 1e-4f)
        assertEquals(1f, square.contentWidth / square.contentHeight, 1e-6f)
    }

    private fun assertUpright(
        rawWidth: Int,
        rawHeight: Int,
        sensor: Int,
        display: Int,
        expectedW: Int,
        expectedH: Int,
    ) {
        val size = CameraPreviewGeometry.uprightSize(rawWidth, rawHeight, sensor, display)
        assertEquals("sensor=$sensor display=$display width", expectedW, size.width)
        assertEquals("sensor=$sensor display=$display height", expectedH, size.height)
    }
}
