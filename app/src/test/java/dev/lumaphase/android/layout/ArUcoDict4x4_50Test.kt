package dev.lumaphase.android.layout

import dev.lumaphase.android.protocol.LayoutProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the embedded bitmap data against the source of truth rather than against itself:
 * every bitmap is re-packed the way OpenCV's `getByteListFromBits` packs bytes and compared
 * with the official rotation-0 bytes of `DICT_4X4_50`, and the four markers are checked to
 * be rotationally distinct.
 */
class ArUcoDict4x4_50Test {

    @Test
    fun embeddedBitmapsMatchTheOfficialOpenCvBytes() {
        for (id in ArUcoDict4x4_50.IDS) {
            val bitmap = ArUcoDict4x4_50.bitmap(id)
            assertEquals("row count for id $id", 4, bitmap.size)
            for (row in bitmap) {
                assertEquals("column count for id $id", 4, row.size)
                for (value in row) assertTrue("bit value for id $id", value == 0 || value == 1)
            }
            assertEquals("bitmap for id $id", expected(id), bitmap.map { row -> row.joinToString("") })
            assertArrayEquals(officialBytes.getValue(id), packRowMajorMsbFirst(bitmap))
        }
    }

    @Test
    fun markersAreRotationallyDistinctAndWellSeparated() {
        val codes = ArrayList<List<Int>>()
        for (id in ArUcoDict4x4_50.IDS) {
            var bits = ArUcoDict4x4_50.bitmap(id).flatMap { it.toList() }
            repeat(4) {
                codes += bits
                bits = rotateAnticlockwise(bits)
            }
        }
        assertEquals("rotation codes must all differ", 16, codes.toSet().size)

        var minimumDistance = Int.MAX_VALUE
        for (first in codes.indices) {
            for (second in first + 1 until codes.size) {
                val distance = codes[first].zip(codes[second]).count { (a, b) -> a != b }
                minimumDistance = minOf(minimumDistance, distance)
            }
        }
        assertEquals("minimum Hamming distance over ids 0-3", 7, minimumDistance)
    }

    @Test
    fun provenanceIsRecorded() {
        assertEquals("DICT_4X4_50", ArUcoDict4x4_50.DICTIONARY)
        assertEquals(4, ArUcoDict4x4_50.MARKER_SIZE_BITS)
        assertEquals(1, ArUcoDict4x4_50.BORDER_BITS)
        assertEquals("opencv-4.10.0", ArUcoDict4x4_50.SOURCE_VERSION)
        assertEquals(
            "modules/objdetect/src/aruco/predefined_dictionaries.hpp",
            ArUcoDict4x4_50.SOURCE_PATH,
        )
        assertTrue(
            "source URL must pin the verified version",
            ArUcoDict4x4_50.SOURCE_URL.contains("4.10.0"),
        )
        assertEquals("Apache-2.0", ArUcoDict4x4_50.LICENSE)
        assertEquals(listOf(0, 1, 2, 3), ArUcoDict4x4_50.IDS)
        assertArrayEquals(intArrayOf(181, 50), ArUcoDict4x4_50.officialRotationZeroBytes.getValue(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIdsOutsideTheEmbeddedRange() {
        ArUcoDict4x4_50.bitmap(4)
    }

    /** Row-major, most significant bit first, two bytes: OpenCV's `getByteListFromBits`. */
    private fun packRowMajorMsbFirst(bitmap: Array<IntArray>): IntArray {
        val packed = IntArray(2)
        var accumulator = 0
        var bitCount = 0
        var byteIndex = 0
        for (row in bitmap) {
            for (value in row) {
                accumulator = (accumulator shl 1) or value
                bitCount++
                if (bitCount == 8) {
                    packed[byteIndex++] = accumulator
                    accumulator = 0
                    bitCount = 0
                }
            }
        }
        return packed
    }

    private fun rotateAnticlockwise(flat: List<Int>): List<Int> = List(SIZE * SIZE) { index ->
        val row = index / SIZE
        val column = index % SIZE
        flat[column * SIZE + (SIZE - 1 - row)]
    }

    private fun expected(id: Int): List<String> = expectedBitmaps.getValue(id)

    private companion object {
        const val SIZE = 4

        val officialBytes: Map<Int, IntArray> = mapOf(
            0 to intArrayOf(181, 50),
            1 to intArrayOf(15, 154),
            2 to intArrayOf(51, 45),
            3 to intArrayOf(153, 70),
        )

        val expectedBitmaps: Map<Int, List<String>> = mapOf(
            0 to listOf("1011", "0101", "0011", "0010"),
            1 to listOf("0000", "1111", "1001", "1010"),
            2 to listOf("0011", "0011", "0010", "1101"),
            3 to listOf("1001", "1001", "0100", "0110"),
        )
    }
}
