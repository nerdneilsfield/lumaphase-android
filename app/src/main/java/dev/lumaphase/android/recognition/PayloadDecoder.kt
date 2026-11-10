package dev.lumaphase.android.recognition

import dev.lumaphase.android.protocol.Crc8
import dev.lumaphase.android.protocol.GrayCode
import dev.lumaphase.android.protocol.LayoutProfile

/**
 * Hard or soft decision of one logical bit. [value] is `0`/`1` when the complementary pair
 * could be decided, `null` when the two modules were too close to call (mixed exposure,
 * noise or occlusion); [score] is `first - second`.
 */
data class BitDecision(val value: Int?, val score: Double)

/** Status of the CRC or Runner region after decoding; the wire strings match the Python core. */
enum class DecodeStatus(val wire: String) {
    PASS("PASS"),
    SOFT("SOFT"),
    FAIL("FAIL"),
    NOT_APPLICABLE("NOT_APPLICABLE"),
}

/**
 * Result of decoding one horizontal cross-section of a layout, mirroring
 * `lumaphase_sync.protocol.decode_payload`.
 *
 * [payloadCandidates] are 16-bit payload numbers (`render_seq mod 65536`); a full 64-bit
 * `render_seq` is never reconstructed from them. [grayCandidates] keeps the numbers obtained
 * from the Gray region alone, so a frame whose CRC failed because of a mixed exposure is not
 * thrown away but also never turned into a hard state.
 */
data class PayloadDecode(
    val layoutProfile: LayoutProfile,
    val payloadCandidates: List<Int>,
    val grayCandidates: List<Int>,
    val crcStatus: DecodeStatus,
    val runnerStatus: DecodeStatus,
    /** Gray code of the first candidate, or null when nothing passed CRC and Runner. */
    val grayValue: Int?,
    val crcExpected: Int?,
    val runnerIndex: Int?,
    val bitDecisions: List<BitDecision>,
    val hardBitCount: Int,
    val truncated: Boolean,
)

/**
 * Pure-Kotlin port of the Python core `protocol.decode_payload`. It reads only the 68 or 52
 * normalised module brightness values of one cross-section and performs no I/O, no Android
 * and no camera access, so it is exercised directly by JVM tests.
 *
 * Geometry (marker detection, homography, sampling) and the display-log based timing fit are
 * deliberately out of this slice; the caller supplies the per-module normalised levels
 * (`0` = low, `1` = high) in original module index order.
 *
 * Decision rules (contract section 1, identical to the core):
 * - complementary pairs start at module 4: `(4,5), (6,7), ...`, `score = first - second`;
 * - `score > threshold` -> 1, `score < -threshold` -> 0, otherwise a soft bit (`null`);
 * - Gray = first 16 pairs, CRC = next 8, Runner = next 8 (F68 only);
 * - at most 12 free Gray bits are enumerated (`2^12 = 4096`); more is `truncated`;
 * - a candidate must satisfy every hard CRC and Runner bit;
 * - `PASS` needs an all-hard decision and exactly one candidate; a single candidate that
 *   still contains a soft bit stays `SOFT`.
 *
 * One deliberate, reported divergence from the core: the core does not validate the input, so
 * a `NaN` level scores as a soft bit. This slice requires every level to be finite and fails
 * explicitly on a non-finite value; the sampler must hand over finite normalised levels.
 */
object PayloadDecoder {
    const val DEFAULT_THRESHOLD = 0.35

    private const val REFERENCE_MODULE_COUNT = 4
    private const val GRAY_BIT_COUNT = 16
    private const val CRC_BIT_COUNT = 8
    private const val RUNNER_BIT_COUNT = 8
    private const val MAX_FREE_GRAY_BITS = 12

    fun decode(
        moduleLevels: DoubleArray,
        profile: LayoutProfile,
        threshold: Double = DEFAULT_THRESHOLD,
    ): PayloadDecode {
        require(moduleLevels.size == profile.moduleCount) {
            "expected ${profile.moduleCount} module levels for ${profile.name}, " +
                "got ${moduleLevels.size}"
        }
        // The core tolerates a non-finite level (``NaN`` scores as a soft bit); the sampled
        // slice feeds only finite normalised values, so a non-finite input is an invalid
        // observation and fails explicitly instead of silently becoming a soft bit.
        require(moduleLevels.all { it.isFinite() }) {
            "module levels must all be finite"
        }

        val decisions = pairDecisions(moduleLevels, profile, threshold)
        val grayDecisions = decisions.subList(0, GRAY_BIT_COUNT)
        val crcDecisions = decisions.subList(GRAY_BIT_COUNT, GRAY_BIT_COUNT + CRC_BIT_COUNT)
        val runnerDecisions = if (profile == LayoutProfile.F68) {
            decisions.subList(GRAY_BIT_COUNT + CRC_BIT_COUNT, decisions.size)
        } else {
            emptyList()
        }

        val hardBitCount = decisions.count { it.value != null }
        val freeGray = grayDecisions.indices.filter { grayDecisions[it].value == null }
        val truncated = freeGray.size > MAX_FREE_GRAY_BITS

        val grayCandidates = ArrayList<Int>()
        if (!truncated) {
            val freePosition = IntArray(GRAY_BIT_COUNT) { -1 }
            freeGray.forEachIndexed { position, index -> freePosition[index] = position }
            for (combination in 0 until (1 shl freeGray.size)) {
                var gray = 0
                for (bitIndex in 0 until GRAY_BIT_COUNT) {
                    val bit = grayDecisions[bitIndex].value
                        ?: ((combination ushr freePosition[bitIndex]) and 1)
                    gray = (gray shl 1) or bit
                }
                grayCandidates += GrayCode.fromGray(gray)
            }
        }

        val payloadCandidates = ArrayList<Int>()
        for (candidate in grayCandidates) {
            if (!matches(crcDecisions, crcBits(candidate))) continue
            if (runnerDecisions.isNotEmpty() && !matches(runnerDecisions, runnerBits(candidate))) {
                continue
            }
            payloadCandidates += candidate
        }

        val allHard = decisions.all { it.value != null }
        val crcStatus = when {
            truncated -> DecodeStatus.SOFT
            payloadCandidates.isEmpty() -> DecodeStatus.FAIL
            payloadCandidates.size > 1 -> DecodeStatus.SOFT
            allHard -> DecodeStatus.PASS
            else -> DecodeStatus.SOFT
        }
        val runnerStatus = when {
            runnerDecisions.isEmpty() -> DecodeStatus.NOT_APPLICABLE
            truncated || payloadCandidates.isEmpty() ->
                if (payloadCandidates.isEmpty()) DecodeStatus.FAIL else DecodeStatus.SOFT
            payloadCandidates.size > 1 -> DecodeStatus.SOFT
            allHard -> DecodeStatus.PASS
            else -> DecodeStatus.SOFT
        }

        val first = payloadCandidates.firstOrNull()
        return PayloadDecode(
            layoutProfile = profile,
            payloadCandidates = payloadCandidates,
            grayCandidates = grayCandidates,
            crcStatus = crcStatus,
            runnerStatus = runnerStatus,
            grayValue = first?.let { GrayCode.toGray(it) },
            crcExpected = first?.let { crc8Payload(it) },
            runnerIndex = first?.let { it and 0x07 },
            bitDecisions = decisions,
            hardBitCount = hardBitCount,
            truncated = truncated,
        )
    }

    private fun pairDecisions(
        moduleLevels: DoubleArray,
        profile: LayoutProfile,
        threshold: Double,
    ): List<BitDecision> {
        val decisions = ArrayList<BitDecision>((profile.moduleCount - REFERENCE_MODULE_COUNT) / 2)
        var index = REFERENCE_MODULE_COUNT
        while (index < profile.moduleCount) {
            val score = moduleLevels[index] - moduleLevels[index + 1]
            val value = when {
                score > threshold -> 1
                score < -threshold -> 0
                else -> null
            }
            decisions += BitDecision(value, score)
            index += 2
        }
        return decisions
    }

    private fun crcBits(payloadN: Int): IntArray {
        val crc = crc8Payload(payloadN)
        return IntArray(CRC_BIT_COUNT) { (crc ushr (CRC_BIT_COUNT - 1 - it)) and 1 }
    }

    private fun runnerBits(payloadN: Int): IntArray =
        IntArray(RUNNER_BIT_COUNT) { if (it == (payloadN and 0x07)) 1 else 0 }

    private fun crc8Payload(payloadN: Int): Int = Crc8.compute(
        byteArrayOf(
            (payloadN and 0xFF).toByte(),
            ((payloadN ushr 8) and 0xFF).toByte(),
        ),
    )

    private fun matches(decisions: List<BitDecision>, expected: IntArray): Boolean {
        for (index in decisions.indices) {
            val value = decisions[index].value ?: continue
            if (value != expected[index]) return false
        }
        return true
    }
}
