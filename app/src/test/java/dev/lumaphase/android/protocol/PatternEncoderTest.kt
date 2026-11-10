package dev.lumaphase.android.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Properties that the 14 shared vectors do not cover: the full 16-bit payload range,
 * the C52 truncation, the reference/complement module structure, and the unsigned
 * 64-bit input range.
 */
class PatternEncoderTest {

    @Test
    fun everyPayloadKeepsTheContractStructure() {
        for (payload in 0..0xFFFF) {
            val renderSeq = payload.toULong()
            val f68 = PatternEncoder.encode(renderSeq, LayoutProfile.F68)
            val c52 = PatternEncoder.encode(renderSeq, LayoutProfile.C52)

            assertEquals(payload, f68.payloadN)
            assertEquals(payload, GrayCode.fromGray(f68.gray))
            assertEquals(68, f68.moduleCodes.size)
            assertEquals(34, f68.moduleCodes.count { it == PatternEncoder.DEFAULT_LEVEL_HIGH })
            assertEquals(52, c52.moduleCodes.size)
            assertEquals(26, c52.moduleCodes.count { it == PatternEncoder.DEFAULT_LEVEL_HIGH })
            assertArrayEquals(f68.moduleCodes.copyOf(52), c52.moduleCodes)

            assertArrayEquals(REFERENCE_MODULES, f68.moduleCodes.copyOf(4))
            for (index in f68.moduleCodes.indices step 2) {
                val first = f68.moduleCodes[index]
                val second = f68.moduleCodes[index + 1]
                assertTrue(
                    "module pair $index is $first/$second",
                    first == PatternEncoder.DEFAULT_LEVEL_LOW && second == PatternEncoder.DEFAULT_LEVEL_HIGH ||
                        first == PatternEncoder.DEFAULT_LEVEL_HIGH && second == PatternEncoder.DEFAULT_LEVEL_LOW,
                )
            }
        }
    }

    @Test
    fun runnerRegionIsOneHotAtPayloadModuloEight() {
        for (payload in 0 until 64) {
            val codes = PatternEncoder.encode(payload.toULong(), LayoutProfile.F68).moduleCodes
            for (index in 0 until 8) {
                val pair = intArrayOf(codes[52 + index * 2], codes[53 + index * 2])
                val lit = pair.contentEquals(intArrayOf(224, 32))
                val unlit = pair.contentEquals(intArrayOf(32, 224))
                assertTrue("runner module $index is not a valid pair", lit || unlit)
                assertEquals("runner index for payload $payload", index == payload % 8, lit)
            }
        }
    }

    @Test
    fun unsignedSixtyFourBitRangeIsHandled() {
        val max = PatternEncoder.encode(ULong.MAX_VALUE, LayoutProfile.C52)
        assertEquals("18446744073709551615", max.renderSeq.toString())
        assertEquals(65535, max.payloadN)
        assertEquals(0x8000, max.gray)
        assertEquals(0x24, max.crc8)

        val wrap = PatternEncoder.encode(65536uL, LayoutProfile.F68)
        assertEquals(0, wrap.payloadN)
        assertEquals(0x0000, wrap.gray)
        assertEquals(0x00, wrap.crc8)

        assertEquals(1, PatternEncoder.encode(65537uL, LayoutProfile.F68).payloadN)
        assertEquals(0, PatternEncoder.encode(4294967296uL, LayoutProfile.F68).payloadN)
        assertEquals(0, PatternEncoder.encode(9223372036854775808uL, LayoutProfile.F68).payloadN)
    }

    private companion object {
        val REFERENCE_MODULES = intArrayOf(32, 224, 224, 32)
    }
}
