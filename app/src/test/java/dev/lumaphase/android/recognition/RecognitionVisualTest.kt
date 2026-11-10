package dev.lumaphase.android.recognition

import dev.lumaphase.android.protocol.LayoutProfile
import dev.lumaphase.android.protocol.PatternEncoder
import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionVisualTest {

    @Test
    fun onlyHardUniqueActualPassBecomesSuccess() {
        val f68 = cleanDecode(0x1234, LayoutProfile.F68)
        assertEquals(RecognitionVisual.PASS, visual(f68, LayoutProfile.F68))
        val c52 = cleanDecode(0x1234, LayoutProfile.C52)
        assertEquals(DecodeStatus.NOT_APPLICABLE, c52.runnerStatus)
        assertEquals(RecognitionVisual.PASS, visual(c52, LayoutProfile.C52))

        val failed = crcFailedDecode(0x1234, LayoutProfile.F68)
        assertEquals(RecognitionVisual.FAILED, visual(failed, LayoutProfile.F68))
        val soft = softDecode(0x1234, LayoutProfile.F68)
        assertEquals(RecognitionVisual.LOCATED_WARNING, visual(soft, LayoutProfile.F68))
        assertEquals(RecognitionVisual.LOCATED_WARNING, visual(null, LayoutProfile.F68))
        assertEquals(RecognitionVisual.NOT_LOCATED, visual(null, LayoutProfile.F68, located = false))
    }

    private fun visual(
        decode: PayloadDecode?,
        profile: LayoutProfile,
        located: Boolean = true,
    ): RecognitionVisual = RecognitionVisualState.of(
        RecognitionReading(
            located = located,
            detectedMarkerIds = emptyList(),
            missingMarkerIds = emptyList(),
            geometryErrorPx = null,
            sample = null,
            bands = listOf(
                BandReading(
                    bandIndex = 0,
                    decode = decode,
                    missingModules = emptyList(),
                    referenceMissing = false,
                    reason = null,
                ),
            ),
            status = if (decode != null) RecognitionStatus.DECODED else RecognitionStatus.NO_DECODE,
            frameWidthPx = 1,
            frameHeightPx = 1,
            homography = null,
            markerFrameCorners = emptyList(),
        ),
        profile,
    )

    private fun cleanDecode(payload: Int, profile: LayoutProfile): PayloadDecode =
        PayloadDecoder.decode(cleanLevels(payload, profile), profile)

    private fun crcFailedDecode(payload: Int, profile: LayoutProfile): PayloadDecode {
        val levels = cleanLevels(payload, profile)
        levels[36] = 1.0 - levels[36]
        levels[37] = 1.0 - levels[37]
        return PayloadDecoder.decode(levels, profile)
    }

    private fun softDecode(payload: Int, profile: LayoutProfile): PayloadDecode {
        val levels = cleanLevels(payload, profile)
        levels[8] = 0.5
        levels[9] = 0.5
        return PayloadDecoder.decode(levels, profile)
    }

    private fun cleanLevels(payload: Int, profile: LayoutProfile): DoubleArray =
        PatternEncoder.encode(payload.toULong(), profile, 0, 1).moduleCodes
            .map { it.toDouble() }
            .toDoubleArray()
}
