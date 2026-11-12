package dev.lumaphase.android.layout

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.lumaphase.android.protocol.EncodedPattern
import dev.lumaphase.android.protocol.LayoutProfile
import dev.lumaphase.android.protocol.PatternEncoder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Produces the evidence files the coordinator can inspect without a device:
 * a real-builder layout collection, the payload values used for the images, and
 * old/new PNGs. The JSON is a test serialiser that mirrors the production LayoutJson key
 * sets; Android's `android.util.JsonWriter` cannot execute in a host JVM, so this test
 * does not claim production writer bytes. The PNG writer is a plain RGB pixel encoder
 * built on java.util.zip, not an image-generation service.
 */
class LargeTagEvidenceTest {

    @Test
    fun generatesLayoutJsonPayloadAndPixelEvidence() {
        val directory = File("build/evidence/large-tags").apply { mkdirs() }
        val samples = buildSamples()
        val gson = GsonBuilder().setPrettyPrinting().create()

        val root = JsonObject()
        root.addProperty(LayoutJson.KEY_SCHEMA_VERSION, LayoutJson.SCHEMA_VERSION)
        val layouts = JsonArray()
        samples.forEach { sample -> layouts.add(jsonLayout(sample.layout)) }
        root.add(LayoutJson.KEY_LAYOUTS, layouts)
        File(directory, "lumaphase-android-run-large-tags-layouts.json")
            .writeText(gson.toJson(root) + "\n")

        val metadata = JsonObject()
        metadata.addProperty("schema_version", "evidence")
        metadata.addProperty("builder", "dev.lumaphase.android.layout.PatternLayoutBuilder")
        metadata.addProperty(
            "serializer",
            "test-only Gson writer mirroring the production LayoutJson keys; " +
                "NOT android.util.JsonWriter bytes",
        )
        metadata.addProperty("render_seq", RENDER_SEQ)
        val payload = JsonObject()
        for (profile in listOf(LayoutProfile.F68, LayoutProfile.C52)) {
            val encoded = PatternEncoder.encode(RENDER_SEQ.toULong(), profile)
            payload.add(profile.name, jsonPayload(encoded))
        }
        metadata.add("payload", payload)
        val sampleArray = JsonArray()
        samples.forEach { sample ->
            sampleArray.add(JsonObject().apply {
                addProperty("layout_id", sample.layout.layoutId)
                addProperty("kind", sample.kind)
                addProperty("profile", sample.layout.profile.name)
                addProperty("geometry_profile", sample.layout.geometry.wire)
                addProperty("drawable_width_px", sample.layout.drawableWidthPx)
                addProperty("drawable_height_px", sample.layout.drawableHeightPx)
                addProperty("pattern_rotation_deg", sample.layout.patternRotationDeg)
                addProperty("png", sample.pngName)
                addProperty("dynamic_rect_px", sample.layout.dynamicRectPx.edges.joinToString(","))
                addProperty("marker_cell_px", sample.layout.markers.first().cellSizePx)
            })
        }
        metadata.add("samples", sampleArray)
        File(directory, "lumaphase-android-run-large-tags-payload.json")
            .writeText(gson.toJson(metadata) + "\n")

        for (sample in samples) {
            val image = render(
                sample.layout,
                PatternEncoder.encode(RENDER_SEQ.toULong(), sample.layout.profile),
            )
            writePng(File(directory, sample.pngName), image)
        }
        for (profile in listOf(LayoutProfile.F68, LayoutProfile.C52)) {
            for (rotation in PatternLayoutBuilder.ROTATIONS_DEG) {
                val old = samples.single {
                    it.kind == "old" && it.layout.profile == profile &&
                        it.layout.patternRotationDeg == rotation &&
                        it.layout.drawableWidthPx == 1080
                }
                val large = samples.single {
                    it.kind == "large" && it.layout.profile == profile &&
                        it.layout.patternRotationDeg == rotation &&
                        it.layout.drawableWidthPx == 1080
                }
                val comparison = renderComparison(old.layout, large.layout, profile)
                writePng(
                    File(directory, "compare-${profile.name.lowercase()}-1080x2400-r$rotation.png"),
                    comparison,
                )
            }
            for (rotation in listOf(0, 90)) {
                val old = samples.single {
                    it.kind == "old" && it.layout.profile == profile &&
                        it.layout.patternRotationDeg == rotation &&
                        it.layout.drawableWidthPx == 2400
                }
                val large = samples.single {
                    it.kind == "large" && it.layout.profile == profile &&
                        it.layout.patternRotationDeg == rotation &&
                        it.layout.drawableWidthPx == 2400
                }
                val comparison = renderComparison(old.layout, large.layout, profile)
                writePng(
                    File(directory, "compare-${profile.name.lowercase()}-2400x1080-r$rotation.png"),
                    comparison,
                )
            }
        }

        // The JSON has exactly the production root, layout, marker and padding keys.
        val parsed = com.google.gson.JsonParser.parseString(
            File(directory, "lumaphase-android-run-large-tags-layouts.json").readText(),
        ).asJsonObject
        assertEquals(LayoutJson.ROOT_KEYS.toSet(), parsed.keySet())
        val first = parsed.getAsJsonArray(LayoutJson.KEY_LAYOUTS).first().asJsonObject
        assertEquals(LayoutJson.LAYOUT_KEYS.toSet(), first.keySet())
        assertEquals(
            LayoutJson.MARKER_KEYS.toSet(),
            first.getAsJsonArray(LayoutJson.KEY_MARKERS).first().asJsonObject.keySet(),
        )
        assertEquals(
            LayoutJson.PADDING_KEYS.toSet(),
            first.getAsJsonArray(LayoutJson.KEY_PADDING).first().asJsonObject.keySet(),
        )
        assertTrue(File(directory, "lumaphase-android-run-large-tags-payload.json").isFile)
        // A PNG signature and IHDR are enough to reject a mislabelled or empty file here;
        // the report tells the coordinator to open the images for visual inspection.
        val png = File(directory, "large-f68-1080x2400-r0.png")
        assertTrue("missing rendered PNG", png.isFile)
        val head = png.inputStream().use { it.readNBytes(24) }
        assertEquals(0x89.toByte(), head[0])
        assertEquals('P'.code.toByte(), head[1])
        assertEquals('N'.code.toByte(), head[2])
        assertEquals('G'.code.toByte(), head[3])
    }

    private data class Sample(
        val kind: String,
        val layout: PatternLayout,
        val pngName: String,
    )

    private fun buildSamples(): List<Sample> {
        val samples = ArrayList<Sample>()
        for (profile in listOf(LayoutProfile.F68, LayoutProfile.C52)) {
            val oldChoice = if (profile == LayoutProfile.F68) LayoutChoice.F68_GRID else LayoutChoice.C52_GRID
            val largeChoice = if (profile == LayoutProfile.F68) {
                LayoutChoice.F68_GRID_LARGE
            } else {
                LayoutChoice.C52_GRID_LARGE
            }
            for (rotation in PatternLayoutBuilder.ROTATIONS_DEG) {
                val old = PatternLayoutBuilder.build(
                    "sample-old-${profile.name.lowercase()}-1080x2400-r$rotation",
                    oldChoice,
                    1080,
                    2400,
                    rotation,
                )
                val large = PatternLayoutBuilder.build(
                    "sample-large-${profile.name.lowercase()}-1080x2400-r$rotation",
                    largeChoice,
                    1080,
                    2400,
                    rotation,
                )
                require(old.dynamicRectPx == large.dynamicRectPx)
                require(old.modules == large.modules)
                require(old.padding == large.padding)
                samples += Sample(
                    "old",
                    old,
                    "old-${profile.name.lowercase()}-1080x2400-r$rotation.png",
                )
                samples += Sample(
                    "large",
                    large,
                    "large-${profile.name.lowercase()}-1080x2400-r$rotation.png",
                )
            }
            for (rotation in listOf(0, 90)) {
                val old = PatternLayoutBuilder.build(
                    "sample-old-${profile.name.lowercase()}-2400x1080-r$rotation",
                    oldChoice,
                    2400,
                    1080,
                    rotation,
                )
                val large = PatternLayoutBuilder.build(
                    "sample-large-${profile.name.lowercase()}-2400x1080-r$rotation",
                    largeChoice,
                    2400,
                    1080,
                    rotation,
                )
                require(old.dynamicRectPx == large.dynamicRectPx)
                samples += Sample(
                    "old",
                    old,
                    "old-${profile.name.lowercase()}-2400x1080-r$rotation.png",
                )
                samples += Sample(
                    "large",
                    large,
                    "large-${profile.name.lowercase()}-2400x1080-r$rotation.png",
                )
            }
        }
        return samples
    }

    private fun jsonLayout(layout: PatternLayout): JsonObject = JsonObject().apply {
        addProperty(LayoutJson.KEY_LAYOUT_ID, layout.layoutId)
        addProperty(LayoutJson.KEY_LAYOUT_PROFILE, layout.profile.name)
        addProperty(LayoutJson.KEY_GEOMETRY_PROFILE, layout.geometry.wire)
        addProperty(LayoutJson.KEY_GRID_ROWS, layout.geometry.gridRows)
        addProperty(LayoutJson.KEY_GRID_COLS, layout.geometry.gridCols)
        addProperty(LayoutJson.KEY_MODULE_COUNT, layout.geometry.moduleCount)
        addProperty(LayoutJson.KEY_PADDING_COUNT, layout.geometry.paddingCount)
        addProperty(LayoutJson.KEY_MODULE_ORDER, GridPlacement.MODULE_ORDER)
        addProperty(LayoutJson.KEY_SAMPLING_INNER_FRACTION, GridPlacement.SAMPLING_INNER_FRACTION)
        addProperty(LayoutJson.KEY_DRAWABLE_WIDTH_PX, layout.drawableWidthPx)
        addProperty(LayoutJson.KEY_DRAWABLE_HEIGHT_PX, layout.drawableHeightPx)
        addProperty(LayoutJson.KEY_COORDINATE_SYSTEM, layout.coordinateSystem)
        addProperty(LayoutJson.KEY_PATTERN_ROTATION_DEG, layout.patternRotationDeg)
        addProperty(LayoutJson.KEY_PATTERN_WIDTH_PX, layout.patternWidthPx)
        addProperty(LayoutJson.KEY_PATTERN_HEIGHT_PX, layout.patternHeightPx)
        add(LayoutJson.KEY_PATTERN_TO_DRAWABLE, JsonArray().apply {
            for (row in 0 until 3) {
                add(JsonArray().apply {
                    for (column in 0 until 3) add(layout.patternToDrawable[row * 3 + column])
                })
            }
        })
        add(LayoutJson.KEY_DYNAMIC_RECT_PX, jsonRect(layout.dynamicRectPx))
        add(LayoutJson.KEY_MODULES, JsonArray().apply {
            layout.modules.forEach { module ->
                add(JsonObject().apply {
                    addProperty(LayoutJson.KEY_INDEX, module.index)
                    add(LayoutJson.KEY_RECT_PX, jsonRect(module.rectPx))
                })
            }
        })
        add(LayoutJson.KEY_PADDING, JsonArray().apply {
            layout.padding.forEach { slot ->
                add(JsonObject().apply {
                    addProperty(LayoutJson.KEY_SLOT_INDEX, slot.slotIndex)
                    addProperty(LayoutJson.KEY_ROLE, LayoutJson.ROLE_PADDING)
                    add(LayoutJson.KEY_RECT_PX, jsonRect(slot.rectPx))
                    addProperty(LayoutJson.KEY_LEVEL, slot.level)
                })
            }
        })
        add(LayoutJson.KEY_MARKERS, JsonArray().apply {
            layout.markers.forEach { marker ->
                add(JsonObject().apply {
                    addProperty(LayoutJson.KEY_ID, marker.id)
                    addProperty(LayoutJson.KEY_DICTIONARY, marker.dictionary)
                    addProperty(LayoutJson.KEY_MARKER_SIZE_BITS, marker.markerSizeBits)
                    addProperty(LayoutJson.KEY_BORDER_BITS, marker.borderBits)
                    addProperty(LayoutJson.KEY_CELL_SIZE_PX, marker.cellSizePx)
                    addProperty(LayoutJson.KEY_QUIET_ZONE_PX, marker.quietZonePx)
                    add(LayoutJson.KEY_CORNERS_PX, JsonArray().apply {
                        marker.cornersPx.forEach { corner ->
                            add(JsonArray().apply {
                                add(corner.x)
                                add(corner.y)
                            })
                        }
                    })
                })
            }
        })
        addProperty(LayoutJson.KEY_LEVEL_LOW, layout.levelLow)
        addProperty(LayoutJson.KEY_LEVEL_HIGH, layout.levelHigh)
    }

    private fun jsonPayload(encoded: EncodedPattern): JsonObject = JsonObject().apply {
        addProperty("render_seq", encoded.renderSeq.toString())
        addProperty("payload_n", encoded.payloadN)
        addProperty("gray", encoded.gray)
        addProperty("crc8", encoded.crc8)
        addProperty("module_count", encoded.moduleCodes.size)
        add("module_codes", JsonArray().apply { encoded.moduleCodes.forEach { add(it) } })
    }

    private fun jsonRect(rect: RectPx): JsonArray = JsonArray().apply {
        rect.edges.forEach { add(it) }
    }

    private class RgbCanvas(val width: Int, val height: Int) {
        val pixels = IntArray(width * height) { BLACK }
        private val rgb = ByteArray(height * (1 + width * 3))

        fun fill(rect: RectPx, level: Int) {
            val gray = (level shl 16) or (level shl 8) or level
            val x0 = rect.x0.coerceIn(0, width)
            val x1 = rect.x1.coerceIn(0, width)
            val y0 = rect.y0.coerceIn(0, height)
            val y1 = rect.y1.coerceIn(0, height)
            for (y in y0 until y1) {
                val row = y * width
                for (x in x0 until x1) pixels[row + x] = gray
            }
        }

        fun copyScaledFrom(source: RgbCanvas, targetX: Int, targetY: Int, scale: Int) {
            for (y in 0 until source.height / scale) {
                val sourceRow = y * scale * source.width
                val targetRow = (targetY + y) * width + targetX
                for (x in 0 until source.width / scale) {
                    // Nearest-neighbour keeps the actual rectangle edges visible.
                    pixels[targetRow + x] = source.pixels[sourceRow + x * scale]
                }
            }
        }

        fun encodePng(file: File) {
            var cursor = 0
            for (y in 0 until height) {
                rgb[cursor++] = 0 // filter: none
                val row = y * width
                for (x in 0 until width) {
                    val value = pixels[row + x]
                    rgb[cursor++] = ((value shr 16) and 0xFF).toByte()
                    rgb[cursor++] = ((value shr 8) and 0xFF).toByte()
                    rgb[cursor++] = (value and 0xFF).toByte()
                }
            }
            val compressed = ByteArrayOutputStream()
            val deflater = Deflater(Deflater.BEST_SPEED)
            deflater.setInput(rgb)
            deflater.finish()
            val buffer = ByteArray(16_384)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                compressed.write(buffer, 0, count)
            }
            deflater.end()

            val out = ByteArrayOutputStream()
            val data = DataOutputStream(out)
            data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
            val ihdr = ByteArray(13)
            writeInt(ihdr, 0, width)
            writeInt(ihdr, 4, height)
            ihdr[8] = 8
            ihdr[9] = 2
            ihdr[10] = 0
            ihdr[11] = 0
            ihdr[12] = 0
            writeChunk(data, "IHDR", ihdr)
            writeChunk(data, "IDAT", compressed.toByteArray())
            writeChunk(data, "IEND", ByteArray(0))
            data.flush()
            file.writeBytes(out.toByteArray())
        }

        private fun writeChunk(out: DataOutputStream, type: String, payload: ByteArray) {
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            out.writeInt(payload.size)
            out.write(typeBytes)
            out.write(payload)
            val crc = CRC32()
            crc.update(typeBytes)
            crc.update(payload)
            out.writeInt(crc.value.toInt())
        }

        private fun writeInt(target: ByteArray, offset: Int, value: Int) {
            target[offset] = ((value shr 24) and 0xFF).toByte()
            target[offset + 1] = ((value shr 16) and 0xFF).toByte()
            target[offset + 2] = ((value shr 8) and 0xFF).toByte()
            target[offset + 3] = (value and 0xFF).toByte()
        }

        companion object {
            const val BLACK = 0
        }
    }

    private fun render(layout: PatternLayout, pattern: EncodedPattern): RgbCanvas {
        val canvas = RgbCanvas(layout.drawableWidthPx, layout.drawableHeightPx)
        for (painted in paintRects(layout, pattern)) {
            canvas.fill(painted.rect, painted.level)
        }
        return canvas
    }

    private fun renderComparison(
        old: PatternLayout,
        large: PatternLayout,
        profile: LayoutProfile,
    ): RgbCanvas {
        val oldCanvas = render(old, PatternEncoder.encode(RENDER_SEQ.toULong(), profile))
        val largeCanvas = render(large, PatternEncoder.encode(RENDER_SEQ.toULong(), profile))
        require(oldCanvas.width == largeCanvas.width && oldCanvas.height == largeCanvas.height)
        val halfWidth = oldCanvas.width / 2
        val halfHeight = oldCanvas.height / 2
        val comparison = RgbCanvas(halfWidth * 2 + 2, halfHeight)
        comparison.copyScaledFrom(oldCanvas, 0, 0, 2)
        for (y in 0 until halfHeight) {
            comparison.fill(RectPx(halfWidth, y, halfWidth + 2, y + 1), 255)
        }
        comparison.copyScaledFrom(largeCanvas, halfWidth + 2, 0, 2)
        return comparison
    }

    private fun writePng(file: File, canvas: RgbCanvas) {
        canvas.encodePng(file)
    }

    private companion object {
        const val RENDER_SEQ = 7L
    }
}
