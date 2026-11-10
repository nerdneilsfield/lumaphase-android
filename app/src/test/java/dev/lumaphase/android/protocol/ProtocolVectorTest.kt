package dev.lumaphase.android.protocol

import com.google.gson.JsonParser
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks the encoder against the authoritative OPTC-1 vector fixture copied from the
 * upstream delivery. The fixture is read from a repository test resource, so the test
 * has no dependency on any absolute path outside this repository.
 */
class ProtocolVectorTest {

    @Test
    fun fixtureIsTheAuthoritativeCopy() {
        val digest = MessageDigest.getInstance("SHA-256").digest(fixtureBytes())
        assertEquals(FIXTURE_SHA256, digest.joinToString("") { byte ->
            String.format(Locale.ROOT, "%02x", byte)
        })
    }

    @Test
    fun everyVectorMatchesTheEncoder() {
        val root = JsonParser.parseString(String(fixtureBytes(), Charsets.UTF_8)).asJsonObject
        assertEquals("OPTC-1", root.get("protocol").asString)
        assertEquals("F68", root.get("layout").asString)
        assertEquals("F4", root.get("crc_check_123456789_hex").asString)

        val vectors = root.getAsJsonArray("vectors")
        assertEquals(14, vectors.size())

        for (element in vectors) {
            val vector = element.asJsonObject
            val renderSeq = vector.get("render_seq").asLong
            val expectedPayload = vector.get("payload_n").asInt
            val expectedGray = vector.get("gray_hex").asString
            val expectedCrc = vector.get("crc_hex").asString
            val expectedRunner = vector.get("runner_index").asInt
            val expectedBits = vector.get("modules_01").asString
            val expectedCodes = vector.getAsJsonArray("module_codes").map { it.asInt }.toIntArray()

            val encoded = PatternEncoder.encode(renderSeq.toULong(), LayoutProfile.F68)
            val label = "render_seq=$renderSeq"

            assertEquals(label, expectedPayload, encoded.payloadN)
            assertEquals(label, expectedGray, String.format(Locale.ROOT, "%04X", encoded.gray))
            assertEquals(label, expectedCrc, String.format(Locale.ROOT, "%02X", encoded.crc8))
            assertEquals(label, expectedRunner, encoded.payloadN % 8)
            assertArrayEquals(label, expectedCodes, encoded.moduleCodes)
            assertEquals(
                label,
                expectedBits,
                encoded.moduleCodes.joinToString("") {
                    if (it == PatternEncoder.DEFAULT_LEVEL_HIGH) "1" else "0"
                },
            )
        }
    }

    @Test
    fun crcCheckValueIsF4() {
        assertEquals(0xF4, Crc8.compute("123456789".toByteArray(Charsets.US_ASCII)))
    }

    private fun fixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(FIXTURE)) { "missing test resource $FIXTURE" }
            .use { it.readBytes() }

    private companion object {
        const val FIXTURE = "/optc/protocol_test_vectors.json"
        const val FIXTURE_SHA256 =
            "cfe36b72a606edee18315b9eb841910bacade176b491e80b15a32b61dc2742a9"
    }
}
