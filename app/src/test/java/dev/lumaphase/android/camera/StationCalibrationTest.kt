package dev.lumaphase.android.camera

import dev.lumaphase.android.layout.BuiltInLayouts
import dev.lumaphase.android.layout.BuiltInSelection
import dev.lumaphase.android.layout.RectPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StationCalibrationTest {
    private val layout = BuiltInLayouts.build(BuiltInSelection(BuiltInLayouts.DEFAULT, 0))
    private val hardware = CameraHardware(
        cameraId = "0",
        sensorOrientationDeg = 90,
        previewWidthPx = 1920,
        previewHeightPx = 1080,
    )

    @Test
    fun createAndValidateUseActualSourceCameraAndFrameParameters() {
        val station = StationCalibrationRules.create(
            layout = layout,
            hardware = hardware,
            frame = frame(),
            targetCorners = targetCorners(),
            createdAtMillis = 7L,
        )
        assertTrue(StationCalibrationRules.validateLayout(station, layout).ok)
        assertTrue(StationCalibrationRules.validateCamera(station, hardware).ok)
        assertTrue(StationCalibrationRules.validateFrame(station, frame()).ok)
        assertTrue(StationCalibrationRules.validate(station, layout, hardware, frame()).ok)
        assertEquals(layout.layoutId, station.sourceLayoutId)
        assertEquals(4, station.targetCorners.size)
        assertEquals(StationCalibrationRules.sourceGeometryDigest(layout), station.sourceGeometryDigest)
    }

    @Test
    fun sourceGeometryDigestChangesWhenSameLayoutIdHasDifferentModules() {
        val changed = layout.copy(
            modules = layout.modules.mapIndexed { index, module ->
                if (index == 0) module.copy(rectPx = RectPx(0, 0, 1, 1)) else module
            },
        )
        assertEquals(layout.layoutId, changed.layoutId)
        assertFalse(
            StationCalibrationRules.sourceGeometryDigest(layout) ==
                StationCalibrationRules.sourceGeometryDigest(changed),
        )

        val station = StationCalibrationRules.create(layout, hardware, frame(), targetCorners(), 1L)
        val check = StationCalibrationRules.validate(station, changed, hardware, frame())
        assertFalse(check.ok)
        assertTrue(check.reason!!.contains("geometry"))
    }

    @Test
    fun layoutCameraFrameCropAndOrientationMismatchesAreRejected() {
        val station = StationCalibrationRules.create(layout, hardware, frame(), targetCorners(), 1L)
        assertFalse(StationCalibrationRules.validateLayout(station, layout.copy(layoutId = "other")).ok)
        assertFalse(StationCalibrationRules.validateCamera(station, hardware.copy(cameraId = "1")).ok)
        assertFalse(StationCalibrationRules.validateCamera(station, hardware.copy(previewWidthPx = 1280)).ok)
        assertFalse(StationCalibrationRules.validateFrame(station, frame(width = 120)).ok)
        assertFalse(StationCalibrationRules.validateFrame(station, frame(crop = RectPx(1, 0, 100, 100))).ok)
        assertFalse(StationCalibrationRules.validateFrame(station, frame(sensor = 0)).ok)
        assertFalse(StationCalibrationRules.validateFrame(station, frame(display = 90)).ok)
    }

    @Test
    fun targetOutsideTheCalibrationFrameIsRejectedInsteadOfStored() {
        assertThrows(IllegalArgumentException::class.java) {
            StationCalibrationRules.create(
                layout = layout,
                hardware = hardware,
                frame = frame(),
                targetCorners = listOf(
                    DoublePoint(0.0, 0.0),
                    DoublePoint(101.0, 0.0),
                    DoublePoint(101.0, 100.0),
                    DoublePoint(0.0, 100.0),
                ),
                createdAtMillis = 1L,
            )
        }
    }

    @Test
    fun notReadyIsAWaitStateNotAStationInvalidation() {
        val validLayout = StationCalibrationRules.validateLayout(
            StationCalibrationRules.create(layout, hardware, frame(), targetCorners(), 1L),
            layout,
        )
        val validCamera = StationCalibrationRules.validateCamera(
            StationCalibrationRules.create(layout, hardware, frame(), targetCorners(), 1L),
            hardware,
        )
        assertEquals(
            StationStartAction.WAIT_READY,
            stationStartAction(ready = false, hardware, validLayout, validCamera),
        )
        assertEquals(
            StationStartAction.WAIT_READY,
            stationStartAction(ready = false, hardware = null, layoutCheck = validLayout, cameraCheck = null),
        )
        assertEquals(
            StationStartAction.START,
            stationStartAction(ready = true, hardware, validLayout, validCamera),
        )
        assertEquals(
            StationStartAction.INVALID,
            stationStartAction(ready = true, hardware, StationCheck(false, "layout mismatch"), validCamera),
        )
        assertEquals(
            StationStartAction.INVALID,
            stationStartAction(ready = true, hardware, validLayout, StationCheck(false, "camera mismatch")),
        )
    }

    @Test
    fun switchingFromStationToAutoInvalidatesTheStationGeneration() {
        val gate = AnalysisGate()
        val stationToken = gate.beginNew()
        checkNotNull(stationToken)
        assertTrue(gate.isCurrent(stationToken))
        // CameraController.startLive/stopLive and station invalidation call nextGeneration.
        gate.nextGeneration()
        assertFalse("old station token must not survive a mode switch", gate.isCurrent(stationToken))
        gate.finish(stationToken)
        val next = gate.beginNew()
        checkNotNull(next)
        assertTrue(gate.isCurrent(next))
    }

    private fun targetCorners(): List<DoublePoint> = listOf(
        DoublePoint(10.0, 10.0),
        DoublePoint(80.0, 10.0),
        DoublePoint(80.0, 80.0),
        DoublePoint(10.0, 80.0),
    )

    private fun frame(
        width: Int = 100,
        crop: RectPx = RectPx(0, 0, 100, 100),
        sensor: Int = 90,
        display: Int = 0,
    ): GrayFrame {
        val height = 100
        return GrayFrame(
            width = width,
            height = height,
            pixels = ByteArray(width * height),
            rawWidth = 100,
            rawHeight = 100,
            rotationDeg = 90,
            sensorOrientationDeg = sensor,
            displayRotationDeg = display,
            cropPx = crop,
            timestampNanos = 42L,
        )
    }
}
