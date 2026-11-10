package dev.lumaphase.android.recognition

import dev.lumaphase.android.protocol.LayoutProfile

enum class RecognitionVisual { NOT_LOCATED, LOCATED_WARNING, FAILED, PASS }

/**
 * Pure mapping from a [RecognitionReading] to overlay/status severity. A green "success" is
 * allowed only for a hard, unique decoder candidate whose CRC and Runner (where applicable)
 * actually passed. A positioned but unverified/soft result stays a neutral warning.
 */
object RecognitionVisualState {
    fun of(reading: RecognitionReading, profile: LayoutProfile): RecognitionVisual {
        if (!reading.located) return RecognitionVisual.NOT_LOCATED
        val decodes = reading.bands.mapNotNull { it.decode }
        if (decodes.any { isHardPass(it, profile) }) return RecognitionVisual.PASS
        if (decodes.any {
                it.crcStatus == DecodeStatus.FAIL || it.runnerStatus == DecodeStatus.FAIL
            }
        ) {
            return RecognitionVisual.FAILED
        }
        return RecognitionVisual.LOCATED_WARNING
    }

    fun isHardPass(decode: PayloadDecode, profile: LayoutProfile): Boolean {
        val requiredHardBits = profile.moduleCount / 2 - 2
        val runnerOk = decode.runnerStatus == DecodeStatus.PASS ||
            decode.runnerStatus == DecodeStatus.NOT_APPLICABLE
        return decode.payloadCandidates.size == 1 &&
            !decode.truncated &&
            decode.crcStatus == DecodeStatus.PASS &&
            runnerOk &&
            decode.hardBitCount == requiredHardBits
    }
}
