package dev.lumaphase.android.camera

import dev.lumaphase.android.layout.RectPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualGeometryTest {
    @Test
    fun dynamicCornersAreTlTrBrBl() {
        val corners = ManualGeometry.dynamicCorners(RectPx(10, 20, 110, 80))
        assertEquals(10.0, corners[0].x, 0.0)
        assertEquals(20.0, corners[0].y, 0.0)
        assertEquals(110.0, corners[1].x, 0.0)
        assertEquals(20.0, corners[1].y, 0.0)
        assertEquals(110.0, corners[2].x, 0.0)
        assertEquals(80.0, corners[2].y, 0.0)
        assertEquals(10.0, corners[3].x, 0.0)
        assertEquals(80.0, corners[3].y, 0.0)
    }

    @Test
    fun fitInverseRejectsLetterboxAndMapsContent() {
        val fit = CameraPreviewGeometry.fit(1000f, 800f, 720f, 1280f)
        val topLeft = CameraPreviewGeometry.viewToFrame(fit, 275f, 0f, 720, 1280)!!
        assertEquals(0.0, topLeft.x, 1e-6)
        assertEquals(0.0, topLeft.y, 1e-6)
        val bottomRight = CameraPreviewGeometry.viewToFrame(fit, 724f, 799f, 720, 1280)!!
        assertEquals(718.4, bottomRight.x, 1e-4)
        assertEquals(1278.4, bottomRight.y, 1e-4)
        assertNull("left letterbox must not become a frame coordinate", CameraPreviewGeometry.viewToFrame(fit, 0f, 0f, 720, 1280))
    }
}
