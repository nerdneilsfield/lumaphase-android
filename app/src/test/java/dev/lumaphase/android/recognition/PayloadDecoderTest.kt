package dev.lumaphase.android.recognition

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.lumaphase.android.protocol.LayoutProfile
import dev.lumaphase.android.protocol.PatternEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cross-language oracle for every expected value here is the frozen Python core
 * `lumaphase_sync/protocol.py` (`decode_payload`), run once from a `/tmp` script; the inputs
 * are the delivered 14 vectors and a few explicit mutations. Nothing in this file builds an
 * expectation from [PayloadDecoder] itself.
 */
class PayloadDecoderTest {

    /** Clean normalised levels of one state: `0.0` = low, `1.0` = high, original index order. */
    private fun clean(payloadN: Int, profile: LayoutProfile): DoubleArray =
        PatternEncoder.encode(payloadN.toULong(), profile, 0, 1).moduleCodes
            .map { it.toDouble() }
            .toDoubleArray()

    private fun swap(levels: DoubleArray, index: Int) {
        levels[index] = 1.0 - levels[index]
    }

    @Test
    fun externalVectorsDecodeToTheirPayloadN() {
        val root = vectors()
        assertEquals("OPTC-1", root.get("protocol").asString)
        for (element in root.getAsJsonArray("vectors")) {
            val vector = element.asJsonObject
            val payloadN = vector.get("payload_n").asInt
            val codes = vector.getAsJsonArray("module_codes").map { it.asInt }
            assertEquals(68, codes.size)
            // The delivered vector is the F68 layout: 32 -> low, 224 -> high.
            val f68 = DoubleArray(68) { if (codes[it] == 32) 0.0 else 1.0 }
            val decodedF68 = PayloadDecoder.decode(f68, LayoutProfile.F68)
            assertEquals(listOf(payloadN), decodedF68.payloadCandidates)
            assertEquals(DecodeStatus.PASS, decodedF68.crcStatus)
            assertEquals(DecodeStatus.PASS, decodedF68.runnerStatus)
            assertEquals(32, decodedF68.hardBitCount)

            // C52 keeps the first 52 modules and has no Runner.
            val c52 = DoubleArray(52) { if (codes[it] == 32) 0.0 else 1.0 }
            val decodedC52 = PayloadDecoder.decode(c52, LayoutProfile.C52)
            assertEquals(listOf(payloadN), decodedC52.payloadCandidates)
            assertEquals(DecodeStatus.PASS, decodedC52.crcStatus)
            assertEquals(DecodeStatus.NOT_APPLICABLE, decodedC52.runnerStatus)
            assertEquals(24, decodedC52.hardBitCount)
        }
    }

    @Test
    fun everyPayloadDecodesHardForBothProfiles() {
        for (payloadN in 0..0xFFFF) {
            val f68 = PayloadDecoder.decode(clean(payloadN, LayoutProfile.F68), LayoutProfile.F68)
            assertEquals(listOf(payloadN), f68.payloadCandidates)
            assertEquals(DecodeStatus.PASS, f68.crcStatus)
            assertEquals(DecodeStatus.PASS, f68.runnerStatus)
            assertEquals(32, f68.hardBitCount)
            assertEquals(payloadN % 8, f68.runnerIndex)

            val c52 = PayloadDecoder.decode(clean(payloadN, LayoutProfile.C52), LayoutProfile.C52)
            assertEquals(listOf(payloadN), c52.payloadCandidates)
            assertEquals(DecodeStatus.PASS, c52.crcStatus)
            assertEquals(DecodeStatus.NOT_APPLICABLE, c52.runnerStatus)
            assertEquals(24, c52.hardBitCount)
        }
    }

    @Test
    fun aFlippedCrcPairKeepsTheGrayCandidateButNoHardState() {
        val levels = clean(1234, LayoutProfile.F68)
        swap(levels, 36)
        swap(levels, 37)
        val decoded = PayloadDecoder.decode(levels, LayoutProfile.F68)
        assertEquals(emptyList<Int>(), decoded.payloadCandidates)
        assertEquals(listOf(1234), decoded.grayCandidates)
        assertEquals(DecodeStatus.FAIL, decoded.crcStatus)
        assertEquals(DecodeStatus.FAIL, decoded.runnerStatus)
        assertNull(decoded.grayValue)
        assertEquals(32, decoded.hardBitCount)
        assertTrue(!decoded.truncated)
    }

    @Test
    fun aFlippedRunnerPairRejectsTheState() {
        val levels = clean(1234, LayoutProfile.F68)
        swap(levels, 52)
        swap(levels, 53)
        val decoded = PayloadDecoder.decode(levels, LayoutProfile.F68)
        assertEquals(emptyList<Int>(), decoded.payloadCandidates)
        assertEquals(listOf(1234), decoded.grayCandidates)
        assertEquals(DecodeStatus.FAIL, decoded.crcStatus)
        assertEquals(DecodeStatus.FAIL, decoded.runnerStatus)
    }

    @Test
    fun oneSoftGrayPairKeepsTheStateButStaysSoft() {
        val levels = clean(1234, LayoutProfile.F68)
        levels[8] = 0.5
        levels[9] = 0.5
        val decoded = PayloadDecoder.decode(levels, LayoutProfile.F68)
        assertEquals(listOf(1234), decoded.payloadCandidates)
        assertEquals(listOf(1234, 15149), decoded.grayCandidates)
        assertEquals(DecodeStatus.SOFT, decoded.crcStatus)
        assertEquals(DecodeStatus.SOFT, decoded.runnerStatus)
        assertEquals(31, decoded.hardBitCount)
    }

    @Test
    fun anExactThresholdScoreIsSoft() {
        val positive = clean(0, LayoutProfile.F68)
        positive[4] = 0.35
        positive[5] = 0.0
        val decodedPositive = PayloadDecoder.decode(positive, LayoutProfile.F68)
        assertNull(decodedPositive.bitDecisions[0].value)
        assertEquals(0.35, decodedPositive.bitDecisions[0].score, 0.0)
        assertEquals(listOf(0), decodedPositive.payloadCandidates)
        assertEquals(listOf(0, 65535), decodedPositive.grayCandidates)
        assertEquals(DecodeStatus.SOFT, decodedPositive.crcStatus)
        assertEquals(DecodeStatus.SOFT, decodedPositive.runnerStatus)
        assertEquals(31, decodedPositive.hardBitCount)

        val negative = clean(0, LayoutProfile.F68)
        negative[4] = 0.0
        negative[5] = 0.35
        val decodedNegative = PayloadDecoder.decode(negative, LayoutProfile.F68)
        assertNull(decodedNegative.bitDecisions[0].value)
        assertEquals(-0.35, decodedNegative.bitDecisions[0].score, 0.0)
        assertEquals(listOf(0), decodedNegative.payloadCandidates)
        assertEquals(listOf(0, 65535), decodedNegative.grayCandidates)
        assertEquals(DecodeStatus.SOFT, decodedNegative.crcStatus)
    }

    @Test
    fun tooManyUndecidedGrayBitsTruncate() {
        val levels = clean(0, LayoutProfile.F68)
        for (base in 4 until 4 + 26 step 2) {
            levels[base] = 0.5
            levels[base + 1] = 0.5
        }
        val decoded = PayloadDecoder.decode(levels, LayoutProfile.F68)
        assertTrue(decoded.truncated)
        assertEquals(emptyList<Int>(), decoded.grayCandidates)
        assertEquals(emptyList<Int>(), decoded.payloadCandidates)
        assertEquals(DecodeStatus.SOFT, decoded.crcStatus)
        assertEquals(DecodeStatus.FAIL, decoded.runnerStatus)
        assertEquals(19, decoded.hardBitCount)
    }

    @Test
    fun aWrongModuleCountIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PayloadDecoder.decode(DoubleArray(10), LayoutProfile.F68)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PayloadDecoder.decode(DoubleArray(68), LayoutProfile.C52)
        }
    }

    @Test
    fun aNonFiniteLevelIsRejected() {
        // The core would score a NaN as a soft bit; this slice requires finite input
        // (reported divergence) and fails explicitly instead.
        val levels = clean(0, LayoutProfile.F68)
        levels[10] = Double.NaN
        assertThrows(IllegalArgumentException::class.java) {
            PayloadDecoder.decode(levels, LayoutProfile.F68)
        }
    }

    private fun vectors(): JsonObject {
        val stream = checkNotNull(javaClass.getResourceAsStream("/optc/protocol_test_vectors.json")) {
            "missing /optc/protocol_test_vectors.json"
        }
        return stream.use { JsonParser.parseString(it.readBytes().toString(Charsets.UTF_8)).asJsonObject }
    }
}
