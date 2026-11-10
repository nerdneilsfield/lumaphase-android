package dev.lumaphase.android.recognition

import dev.lumaphase.android.camera.GrayFrame
import dev.lumaphase.android.layout.GeometryProfile
import dev.lumaphase.android.layout.ImportedLayout
import dev.lumaphase.android.layout.ModuleRect
import dev.lumaphase.android.layout.PaddingSlot
import dev.lumaphase.android.layout.RectPx
import dev.lumaphase.android.protocol.LayoutProfile
import dev.lumaphase.android.protocol.PatternEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic pixels with explicit expected normalized levels. The layout rectangles are built
 * by hand here; expectations are not produced by [FrameSampler].
 */
class FrameSamplerTest {

    @Test
    fun gridSamplesCentralWindowAndUsesReferenceModules() {
        val layout = gridLayout(800, 600, GeometryProfile.F68_G9X8)
        val frame = filledFrame(layout, payload = 0x1234)
        val result = FrameSampler.sample(frame, layout, IDENTITY)

        assertEquals(SampleMode.GRID, result.mode)
        assertEquals(1, result.bands.size)
        val band = result.bands.single()
        assertTrue(band.referenceMissing.not())
        assertTrue(band.missingModules.isEmpty())
        val levels = band.levels!!
        val codes = PatternEncoder.encode(0x1234uL, LayoutProfile.F68, 32, 224).moduleCodes
        for (index in codes.indices) {
            assertEquals("module $index", if (codes[index] == 32) 0.0 else 1.0, levels[index], 1e-9)
        }
        assertEquals(1, result.moduleBandCounts.distinct().single())
    }

    @Test
    fun missingModuleMakesTheBandExplicitlyNotDecodableWithout050Fill() {
        val base = gridLayout(800, 600, GeometryProfile.F68_G9X8)
        val missing = base.copy(
            modules = base.modules.map { module ->
                if (module.index == 5) ModuleRect(5, RectPx(900, 0, 1000, 50)) else module
            },
        )
        val result = FrameSampler.sample(filledFrame(base, 0x1234), missing, IDENTITY)
        val band = result.bands.single()
        assertNull(band.levels)
        assertEquals(listOf(5), band.missingModules)
        assertEquals("MISSING_MODULES", band.reason)
        assertTrue("no placeholder 0.5 may be present", band.levels == null)
    }

    @Test
    fun missingReferenceLevelsAreExplicitlyNotNormalized() {
        val layout = gridLayout(800, 600, GeometryProfile.F68_G9X8)
        val frame = constantFrame(layout, 100)
        val band = FrameSampler.sample(frame, layout, IDENTITY).bands.single()
        assertNull(band.levels)
        assertTrue(band.referenceMissing)
        assertEquals("REFERENCE_LEVELS_UNAVAILABLE", band.reason)
    }

    @Test
    fun legacyKeepsIndependentThinBands() {
        val layout = legacyLayout(800, 80)
        val result = FrameSampler.sample(filledFrame(layout, 0x1234), layout, IDENTITY)

        assertEquals(SampleMode.SINGLE_ROW, result.mode)
        assertTrue("single-row must keep multiple independent bands", result.bands.size > 1)
        assertTrue(result.moduleBandCounts.all { it == result.bands.size })
        result.bands.forEach { band ->
            assertTrue("band ${band.bandIndex} must be decodable", band.levels != null)
            assertEquals(68, band.levels!!.size)
        }
        // There is no synthetic per-module aggregation in the result: callers see one level
        // vector per band, not one averaged 68-vector.
        assertTrue(result.bands.size >= 2)
    }

    @Test
    fun legacyRotated90UsesHorizontalBandsAndTheShortYWindow() {
        val layout = legacyHorizontalLayout(800, 272, rotation = 90)
        val result = FrameSampler.sample(constantFrame(layout, 100), layout, IDENTITY)

        assertEquals(SampleMode.SINGLE_ROW, result.mode)
        // Projected long axis is drawable x (800 px), so the shared band count is 400.
        assertEquals(400, result.bands.size)
        assertEquals(400, result.moduleBandCounts.first())
        assertTrue(result.moduleBandCounts.all { it == 400 })
        result.bands.forEach { band ->
            val bounds = band.sampleBounds.getValue(0)
            assertTrue("90 short-axis window y0=${bounds.minY}", bounds.minY >= 0.8)
            assertTrue("90 short-axis window y1=${bounds.maxY}", bounds.maxY <= 3.2)
        }
    }

    @Test
    fun legacyRotated270UsesHorizontalBandsAndTheShortYWindow() {
        val layout = legacyHorizontalLayout(800, 272, rotation = 270)
        val result = FrameSampler.sample(constantFrame(layout, 100), layout, IDENTITY)
        assertEquals(400, result.bands.size)
        assertTrue(result.moduleBandCounts.all { it == 400 })
        result.bands.forEach { band ->
            val bounds = band.sampleBounds.getValue(0)
            assertTrue(bounds.minY >= 0.8 && bounds.maxY <= 3.2)
        }
    }

    @Test
    fun legacyBandIndexFollowsOriginalLogicalAxisForRotations() {
        val horizontal90 = legacyHorizontalLayout(800, 272, rotation = 90)
        val gradient90 = FrameSampler.sample(gradientXFrame(horizontal90), horizontal90, IDENTITY)
        assertTrue(
            "90 band 0 is the high logical-y end",
            gradient90.bands.first().rawMeans.getValue(0) >
                gradient90.bands.last().rawMeans.getValue(0),
        )

        val horizontal270 = legacyHorizontalLayout(800, 272, rotation = 270)
        val gradient270 = FrameSampler.sample(gradientXFrame(horizontal270), horizontal270, IDENTITY)
        assertTrue(
            "270 band 0 is the low logical-y end",
            gradient270.bands.first().rawMeans.getValue(0) <
                gradient270.bands.last().rawMeans.getValue(0),
        )

        val vertical180 = legacyLayout(272, 800, rotation = 180)
        val gradient180 = FrameSampler.sample(gradientYFrame(vertical180), vertical180, IDENTITY)
        assertTrue(
            "180 band 0 is the high logical-y end",
            gradient180.bands.first().rawMeans.getValue(0) >
                gradient180.bands.last().rawMeans.getValue(0),
        )
    }

    @Test
    fun legacyNonIdentityProjectionKeepsCommonExactBandCount() {
        val layout = legacyHorizontalLayout(800, 272, rotation = 90)
        val perspective = doubleArrayOf(0.75, 0.0, 10.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val result = FrameSampler.sample(constantFrame(layout, 100), layout, perspective)
        // Projected dynamic width is 600 px; shared band count is round(600/2)=300.
        assertEquals(300, result.bands.size)
        assertTrue(result.moduleBandCounts.all { it == 300 })
    }

    private fun gradientXFrame(layout: ImportedLayout): GrayFrame =
        grayFrame(layout) { x, _ -> (x.coerceAtMost(254)).toByte() }

    private fun gradientYFrame(layout: ImportedLayout): GrayFrame =
        grayFrame(layout) { _, y -> (y.coerceAtMost(254)).toByte() }

    private fun grayFrame(layout: ImportedLayout, value: (Int, Int) -> Byte): GrayFrame {
        val w = layout.drawableWidthPx
        val h = layout.drawableHeightPx
        val pixels = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) pixels[y * w + x] = value(x, y)
        return GrayFrame(w, h, pixels, w, h, 0, 0, 0, RectPx(0, 0, w, h))
    }

    private fun filledFrame(layout: ImportedLayout, payload: Int): GrayFrame {
        val width = layout.drawableWidthPx
        val height = layout.drawableHeightPx
        val pixels = ByteArray(width * height) { 100.toByte() }
        val codes = PatternEncoder.encode(payload.toULong(), layout.profile, 32, 224).moduleCodes
        layout.modules.forEach { module ->
            val rect = module.rectPx
            for (y in rect.y0 until rect.y1) {
                for (x in rect.x0 until rect.x1) {
                    if (x in 0 until width && y in 0 until height) {
                        pixels[y * width + x] = codes[module.index].toByte()
                    }
                }
            }
        }
        return GrayFrame(
            width = width,
            height = height,
            pixels = pixels,
            rawWidth = width,
            rawHeight = height,
            rotationDeg = 0,
            sensorOrientationDeg = 0,
            displayRotationDeg = 0,
            cropPx = RectPx(0, 0, width, height),
        )
    }

    private fun constantFrame(layout: ImportedLayout, value: Int): GrayFrame =
        GrayFrame(
            layout.drawableWidthPx,
            layout.drawableHeightPx,
            ByteArray(layout.drawableWidthPx * layout.drawableHeightPx) { value.toByte() },
            layout.drawableWidthPx,
            layout.drawableHeightPx,
            0,
            0,
            0,
            RectPx(0, 0, layout.drawableWidthPx, layout.drawableHeightPx),
        )

    private fun gridLayout(width: Int, height: Int, geometry: GeometryProfile): ImportedLayout {
        val dynamic = RectPx(0, 0, width, height)
        val slots = List(geometry.gridRows * geometry.gridCols) { slot ->
            val row = slot / geometry.gridCols
            val column = slot % geometry.gridCols
            RectPx(
                column * width / geometry.gridCols,
                row * height / geometry.gridRows,
                (column + 1) * width / geometry.gridCols,
                (row + 1) * height / geometry.gridRows,
            )
        }
        val modules = List(geometry.moduleCount) { ModuleRect(it, slots[it]) }
        val padding = List(geometry.paddingCount) { offset ->
            PaddingSlot(
                slotIndex = geometry.moduleCount + offset,
                rectPx = slots[geometry.moduleCount + offset],
                level = if (offset % 2 == 0) 32 else 224,
            )
        }
        return ImportedLayout(
            schemaVersion = "1.1",
            layoutId = "test-grid",
            profile = geometry.layoutProfile,
            geometry = geometry,
            drawableWidthPx = width,
            drawableHeightPx = height,
            patternRotationDeg = 0,
            patternWidthPx = width,
            patternHeightPx = height,
            dynamicRectPx = dynamic,
            modules = modules,
            padding = padding,
            markers = emptyList(),
            levelLow = 32,
            levelHigh = 224,
            samplingInnerFraction = 0.6,
        )
    }

    private fun legacyHorizontalLayout(
        width: Int,
        height: Int,
        rotation: Int = 90,
    ): ImportedLayout {
        val moduleHeight = height / LayoutProfile.F68.moduleCount
        val modules = List(LayoutProfile.F68.moduleCount) { index ->
            ModuleRect(index, RectPx(0, index * moduleHeight, width, (index + 1) * moduleHeight))
        }
        return ImportedLayout(
            schemaVersion = "1.1",
            layoutId = "test-legacy-rotated",
            profile = LayoutProfile.F68,
            geometry = null,
            drawableWidthPx = width,
            drawableHeightPx = height,
            patternRotationDeg = rotation,
            patternWidthPx = height,
            patternHeightPx = width,
            dynamicRectPx = RectPx(0, 0, width, height),
            modules = modules,
            padding = emptyList(),
            markers = emptyList(),
            levelLow = 32,
            levelHigh = 224,
            samplingInnerFraction = null,
        )
    }

    private fun legacyLayout(width: Int, height: Int, rotation: Int = 0): ImportedLayout {
        val moduleWidth = width / LayoutProfile.F68.moduleCount
        val modules = List(LayoutProfile.F68.moduleCount) { index ->
            ModuleRect(index, RectPx(index * moduleWidth, 0, (index + 1) * moduleWidth, height))
        }
        return ImportedLayout(
            schemaVersion = "1.0",
            layoutId = "test-legacy",
            profile = LayoutProfile.F68,
            geometry = null,
            drawableWidthPx = width,
            drawableHeightPx = height,
            patternRotationDeg = rotation,
            patternWidthPx = width,
            patternHeightPx = height,
            dynamicRectPx = RectPx(0, 0, width, height),
            modules = modules,
            padding = emptyList(),
            markers = emptyList(),
            levelLow = 32,
            levelHigh = 224,
            samplingInnerFraction = null,
        )
    }

    private companion object {
        val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    }
}
