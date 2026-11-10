package dev.lumaphase.android.protocol

/**
 * One encoded dynamic state. [moduleCodes] holds exactly [LayoutProfile.moduleCount]
 * values; with the default levels each value is either
 * [PatternEncoder.DEFAULT_LEVEL_LOW] or [PatternEncoder.DEFAULT_LEVEL_HIGH].
 */
class EncodedPattern(
    val profile: LayoutProfile,
    val renderSeq: ULong,
    val payloadN: Int,
    val gray: Int,
    val crc8: Int,
    val moduleCodes: IntArray,
)

/**
 * OPTC-1 F68/C52 encoder. Pure computation: it reads no clock and performs no I/O.
 *
 * Module layout (contract section 1): modules 0-3 are the reference `[L,H,H,L]`,
 * 4-35 carry the 16-bit Gray code, 36-51 the CRC-8, 52-67 the 8-index Runner.
 * Every encoded bit occupies two adjacent modules as `bit, complement`:
 * bit 0 -> `[L,H]`, bit 1 -> `[H,L]`. Gray and CRC are emitted most significant bit
 * first; the Runner lights the index equal to `payloadN % 8`. C52 keeps modules 0-51.
 */
object PatternEncoder {
    const val DEFAULT_LEVEL_LOW = 32
    const val DEFAULT_LEVEL_HIGH = 224

    private const val PAYLOAD_MODULUS = 0x10000
    private const val GRAY_BITS = 16
    private const val CRC_BITS = 8
    private const val RUNNER_BITS = 8
    private val REFERENCE_BITS = intArrayOf(0, 1)

    fun encode(
        renderSeq: ULong,
        profile: LayoutProfile,
        levelLow: Int = DEFAULT_LEVEL_LOW,
        levelHigh: Int = DEFAULT_LEVEL_HIGH,
    ): EncodedPattern {
        val payloadN = (renderSeq % PAYLOAD_MODULUS.toULong()).toInt()
        val gray = GrayCode.toGray(payloadN)
        val crc = Crc8.compute(
            byteArrayOf(
                (payloadN and 0xFF).toByte(),
                ((payloadN ushr 8) and 0xFF).toByte(),
            ),
        )

        val bits = IntArray(REFERENCE_BITS.size + GRAY_BITS + CRC_BITS + RUNNER_BITS)
        var cursor = 0
        for (bit in REFERENCE_BITS) bits[cursor++] = bit
        for (shift in GRAY_BITS - 1 downTo 0) bits[cursor++] = (gray ushr shift) and 1
        for (shift in CRC_BITS - 1 downTo 0) bits[cursor++] = (crc ushr shift) and 1
        val runnerIndex = payloadN % RUNNER_BITS
        for (index in 0 until RUNNER_BITS) bits[cursor++] = if (index == runnerIndex) 1 else 0

        val moduleCodes = IntArray(profile.moduleCount)
        var module = 0
        for (bit in bits) {
            if (module >= moduleCodes.size) break
            moduleCodes[module++] = if (bit == 0) levelLow else levelHigh
            if (module >= moduleCodes.size) break
            moduleCodes[module++] = if (bit == 0) levelHigh else levelLow
        }
        return EncodedPattern(profile, renderSeq, payloadN, gray, crc, moduleCodes)
    }
}
