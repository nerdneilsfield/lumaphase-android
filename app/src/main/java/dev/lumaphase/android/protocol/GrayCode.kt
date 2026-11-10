package dev.lumaphase.android.protocol

/** Binary-reflected-free Gray code used by OPTC-1: `g = n xor (n ushr 1)`. */
object GrayCode {
    fun toGray(value: Int): Int = value xor (value ushr 1)

    /** Inverse of [toGray]; used by tests to check the round trip. */
    fun fromGray(gray: Int): Int {
        var remaining = gray
        var binary = 0
        while (remaining != 0) {
            binary = binary xor remaining
            remaining = remaining ushr 1
        }
        return binary
    }
}
