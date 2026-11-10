package dev.lumaphase.android.protocol

/**
 * CRC-8 as fixed by OPTC-1: width=8, poly=0x07, init=0, refin=false, refout=false,
 * xorout=0. The check value for the ASCII input "123456789" is 0xF4.
 */
object Crc8 {
    private const val POLYNOMIAL = 0x07

    fun compute(input: ByteArray): Int {
        var crc = 0
        for (byte in input) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) {
                    ((crc shl 1) xor POLYNOMIAL) and 0xFF
                } else {
                    (crc shl 1) and 0xFF
                }
            }
        }
        return crc
    }
}
