package dev.lumaphase.android.layout

/**
 * Marker bitmaps for the OpenCV predefined dictionary `DICT_4X4_50`, ids 0-3.
 *
 * Source (pinned): OpenCV 4.10.0,
 * `modules/objdetect/src/aruco/predefined_dictionaries.hpp` and
 * `modules/objdetect/src/aruco/aruco_dictionary.cpp`. In that release `DICT_4X4_50` is
 * built as `Dictionary(Mat(50, 2, CV_8UC4, DICT_4X4_1000_BYTES), 4, 1)`, so it is the
 * first 50 rows of `DICT_4X4_1000_BYTES`; each row holds four rotations of two bytes.
 * The bit order is the one used by `Dictionary::getBitsFromByteList`: row-major, most
 * significant bit first, reading the first `ceil(4*4/8) = 2` bytes (rotation 0).
 * `Dictionary::generateImageMarker` copies those bits into a
 * `(4 + 2*borderBits)^2` image with a black border and nearest-neighbour scaling, so a
 * bit of 1 is white (code 255) and a bit of 0 is black (code 0).
 * License: OpenCV is Apache-2.0; see https://opencv.org/license/
 *
 * Only these four 4x4 bitmaps are embedded. No OpenCV runtime dependency is used.
 *
 * Verified 2026-11-10 against the pinned sources (652 KB header, SHA-256
 * de527367d72aaa2c6fe77a1181cb03593fd0aa6e0c0b5a7a98d2c22ef977f451):
 * for all 50 markers the three stored rotations equal 90-degree anticlockwise
 * rotations of rotation 0; all 200 rotation codes are distinct; the minimum Hamming
 * distance over them is 4, matching the value OpenCV documents for `DICT_4X4_50`.
 * All four entries below also agree byte-for-byte with opencv_contrib 4.5.5 and re-pack
 * to the official rotation-0 bytes in [officialRotationZeroBytes].
 */
object ArUcoDict4x4_50 {
    const val DICTIONARY = "DICT_4X4_50"
    const val MARKER_SIZE_BITS = 4
    const val BORDER_BITS = 1
    const val SOURCE_VERSION = "opencv-4.10.0"
    const val SOURCE_PATH = "modules/objdetect/src/aruco/predefined_dictionaries.hpp"
    const val SOURCE_URL =
        "https://github.com/opencv/opencv/blob/4.10.0/modules/objdetect/src/aruco/predefined_dictionaries.hpp"
    const val LICENSE = "Apache-2.0"

    val IDS: List<Int> = listOf(0, 1, 2, 3)

    /** Official rotation-0 bytes per id, in `DICT_4X4_1000_BYTES` / `getBitsFromByteList` order. */
    val officialRotationZeroBytes: Map<Int, IntArray> = mapOf(
        0 to intArrayOf(181, 50),
        1 to intArrayOf(15, 154),
        2 to intArrayOf(51, 45),
        3 to intArrayOf(153, 70),
    )

    private val bitmaps: Map<Int, Array<IntArray>> = mapOf(
        0 to arrayOf(
            intArrayOf(1, 0, 1, 1),
            intArrayOf(0, 1, 0, 1),
            intArrayOf(0, 0, 1, 1),
            intArrayOf(0, 0, 1, 0),
        ),
        1 to arrayOf(
            intArrayOf(0, 0, 0, 0),
            intArrayOf(1, 1, 1, 1),
            intArrayOf(1, 0, 0, 1),
            intArrayOf(1, 0, 1, 0),
        ),
        2 to arrayOf(
            intArrayOf(0, 0, 1, 1),
            intArrayOf(0, 0, 1, 1),
            intArrayOf(0, 0, 1, 0),
            intArrayOf(1, 1, 0, 1),
        ),
        3 to arrayOf(
            intArrayOf(1, 0, 0, 1),
            intArrayOf(1, 0, 0, 1),
            intArrayOf(0, 1, 0, 0),
            intArrayOf(0, 1, 1, 0),
        ),
    )

    /** Row-major copy of the marker bitmap for [id]; 1 renders white, 0 renders black. */
    fun bitmap(id: Int): Array<IntArray> {
        val rows = bitmaps[id]
            ?: throw IllegalArgumentException(
                "$DICTIONARY id $id is outside the embedded range $IDS",
            )
        return Array(rows.size) { rows[it].copyOf() }
    }
}
