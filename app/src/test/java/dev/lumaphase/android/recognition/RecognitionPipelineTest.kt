package dev.lumaphase.android.recognition

import dev.lumaphase.android.camera.CornerMatch
import dev.lumaphase.android.camera.FrameLocator
import dev.lumaphase.android.camera.GrayFrame
import dev.lumaphase.android.camera.LocateFailure
import dev.lumaphase.android.camera.LocatedScreen
import dev.lumaphase.android.camera.ManualGeometry
import dev.lumaphase.android.layout.GeometryProfile
import dev.lumaphase.android.layout.ImportedLayout
import dev.lumaphase.android.layout.ModuleRect
import dev.lumaphase.android.layout.PaddingSlot
import dev.lumaphase.android.layout.RectPx
import com.google.gson.JsonParser
import dev.lumaphase.android.protocol.LayoutProfile
import dev.lumaphase.android.protocol.PatternEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionPipelineTest {

    @Test
    fun locatedGridDecodesOneIndependentBand() {
        val layout = gridLayout()
        val reading = RecognitionPipeline(FakeLocator(located())).analyze(filledFrame(layout, 0x1234), layout)
        assertTrue(reading.located)
        assertEquals(RecognitionStatus.DECODED, reading.status)
        assertEquals(1, reading.bands.size)
        val decode = reading.bands.single().decode!!
        assertEquals(listOf(0x1234), decode.payloadCandidates)
        assertEquals(DecodeStatus.PASS, decode.crcStatus)
    }

    @Test
    fun missingModuleNeverBecomesADecoded056HardPass() {
        val base = gridLayout()
        val layout = base.copy(
            modules = base.modules.map { module ->
                if (module.index == 5) ModuleRect(5, RectPx(900, 0, 1000, 50)) else module
            },
        )
        val reading = RecognitionPipeline(FakeLocator(located())).analyze(filledFrame(base, 0x1234), layout)
        assertEquals(RecognitionStatus.NO_DECODE, reading.status)
        val band = reading.bands.single()
        assertNull(band.decode)
        assertEquals(listOf(5), band.missingModules)
        assertEquals("MISSING_MODULES", band.reason)
    }

    @Test
    fun twoLegacyBandsDecodeTheirOwnExternalPayloadVectors() {
        val layout = legacyTwoBandLayout()
        val vectors = protocolVectors(2)
        val frame = twoBandFrame(layout, vectors[0].second, vectors[1].second)
        val reading = RecognitionPipeline(FakeLocator(located())).analyze(frame, layout)
        assertTrue(reading.located)
        assertEquals(2, reading.bands.size)
        val first = reading.bands[0].decode!!
        val second = reading.bands[1].decode!!
        assertEquals(listOf(vectors[0].first), first.payloadCandidates)
        assertEquals(listOf(vectors[1].first), second.payloadCandidates)
        assertEquals(DecodeStatus.PASS, first.crcStatus)
        assertEquals(DecodeStatus.PASS, second.crcStatus)
    }

    @Test
    fun manualDynamicCornersUseTheSameSamplerAndPayloadOracle() {
        val layout = gridLayout()
        val points = ManualGeometry.dynamicCorners(layout.dynamicRectPx)
        val reading = RecognitionPipeline(
            FakeLocator(
                LocateFailure(emptyList(), listOf(0, 1, 2, 3), "no markers for manual"),
            ),
            homographyEstimator = { _, _ -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0) },
        ).analyzeManual(filledFrame(layout, 0x1234), layout, points)
        assertTrue(reading.located)
        assertTrue(reading.manual)
        assertEquals(RecognitionStatus.DECODED, reading.status)
        assertEquals(listOf(0x1234), reading.bands.single().decode!!.payloadCandidates)
        assertEquals(4, reading.markerFrameCorners.single().size)
    }

    @Test
    fun notLocatedHasNoPayloadAndNoBands() {
        val reading = RecognitionPipeline(
            FakeLocator(
                LocateFailure(
                    detectedMarkerIds = listOf(0, 1, 3),
                    missingMarkerIds = listOf(2),
                    reason = "MISSING_MARKERS",
                ),
            ),
        ).analyze(filledFrame(gridLayout(), 0x1234), gridLayout())
        assertEquals(RecognitionStatus.NOT_LOCATED, reading.status)
        assertTrue(reading.bands.isEmpty())
        assertNull(reading.sample)
        assertEquals(listOf(2), reading.missingMarkerIds)
    }

    private fun legacyTwoBandLayout(): ImportedLayout {
        val width = 800
        val height = 4
        val moduleWidth = width / LayoutProfile.F68.moduleCount
        val modules = List(LayoutProfile.F68.moduleCount) { index ->
            ModuleRect(index, RectPx(index * moduleWidth, 0, (index + 1) * moduleWidth, height))
        }
        return ImportedLayout(
            schemaVersion = "1.0",
            layoutId = "pipeline-legacy-two-bands",
            profile = LayoutProfile.F68,
            geometry = null,
            drawableWidthPx = width,
            drawableHeightPx = height,
            patternRotationDeg = 0,
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

    private fun twoBandFrame(
        layout: ImportedLayout,
        topCodes: IntArray,
        bottomCodes: IntArray,
    ): GrayFrame {
        val w = layout.drawableWidthPx
        val h = layout.drawableHeightPx
        val pixels = ByteArray(w * h) { 100.toByte() }
        layout.modules.forEach { module ->
            val rect = module.rectPx
            for (y in rect.y0 until rect.y1) for (x in rect.x0 until rect.x1) {
                pixels[y * w + x] = (if (y < 2) topCodes[module.index] else bottomCodes[module.index]).toByte()
            }
        }
        return GrayFrame(w, h, pixels, w, h, 0, 0, 0, RectPx(0, 0, w, h))
    }

    private fun protocolVectors(count: Int): List<Pair<Int, IntArray>> {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("optc/protocol_test_vectors.json")).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        val root = JsonParser.parseString(text).asJsonObject
        return root.getAsJsonArray("vectors").take(count).map { element ->
            val vector = element.asJsonObject
            vector.get("payload_n").asInt to
                vector.getAsJsonArray("module_codes").map { it.asInt }.take(68).toIntArray()
        }
    }

    private fun located(): LocatedScreen = LocatedScreen(
        homography = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
        detectedMarkerIds = listOf(0, 1, 2, 3),
        missingMarkerIds = emptyList(),
        match = CornerMatch(emptyList(), emptyList(), listOf(0, 1, 2, 3), emptyList()),
        reprojectionRmsPx = 0.0,
    )

    private fun gridLayout(): ImportedLayout {
        val geometry = GeometryProfile.F68_G9X8
        val width = 800
        val height = 600
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
        return ImportedLayout(
            schemaVersion = "1.1",
            layoutId = "pipeline-grid",
            profile = LayoutProfile.F68,
            geometry = geometry,
            drawableWidthPx = width,
            drawableHeightPx = height,
            patternRotationDeg = 0,
            patternWidthPx = width,
            patternHeightPx = height,
            dynamicRectPx = RectPx(0, 0, width, height),
            modules = List(geometry.moduleCount) { ModuleRect(it, slots[it]) },
            padding = List(geometry.paddingCount) { offset ->
                PaddingSlot(
                    slotIndex = geometry.moduleCount + offset,
                    rectPx = slots[geometry.moduleCount + offset],
                    level = if (offset % 2 == 0) 32 else 224,
                )
            },
            markers = emptyList(),
            levelLow = 32,
            levelHigh = 224,
            samplingInnerFraction = 0.6,
        )
    }

    private fun filledFrame(layout: ImportedLayout, payload: Int): GrayFrame {
        val bytes = ByteArray(layout.drawableWidthPx * layout.drawableHeightPx) { 100.toByte() }
        val codes = PatternEncoder.encode(payload.toULong(), layout.profile, 32, 224).moduleCodes
        layout.modules.forEach { module ->
            val rect = module.rectPx
            for (y in rect.y0 until rect.y1) {
                for (x in rect.x0 until rect.x1) {
                    bytes[y * layout.drawableWidthPx + x] = codes[module.index].toByte()
                }
            }
        }
        return GrayFrame(
            layout.drawableWidthPx,
            layout.drawableHeightPx,
            bytes,
            layout.drawableWidthPx,
            layout.drawableHeightPx,
            0,
            0,
            0,
            RectPx(0, 0, layout.drawableWidthPx, layout.drawableHeightPx),
        )
    }

    private class FakeLocator(private val result: dev.lumaphase.android.camera.LocateResult) : FrameLocator {
        override fun locate(
            frame: GrayFrame,
            markers: List<dev.lumaphase.android.layout.ImportedMarker>,
        ): dev.lumaphase.android.camera.LocateResult = result
    }
}
