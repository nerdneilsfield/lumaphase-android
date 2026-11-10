package dev.lumaphase.android.timing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounded evidence sink keeps counters and the newest lines, and reports an unsigned
 * frame id exactly as the render thread formatted it.
 */
class BoundedFrameEvidenceSinkTest {

    @Test
    fun countsEveryCallbackEvenAfterOlderEvidenceIsDropped() {
        val sink = BoundedFrameEvidenceSink(retained = 2)
        repeat(5) { sink.onSubmitted(submission(it)) }
        repeat(3) { sink.onResult(result(it)) }

        assertEquals(5, sink.submissionCount())
        assertEquals(3, sink.resultCount())
        assertEquals(2, sink.recentSubmissions().size)
        assertEquals(2, sink.recentResults().size)
    }

    @Test
    fun keepsTheMostRecentEvidence() {
        val sink = BoundedFrameEvidenceSink(retained = 2)
        sink.onSubmitted(submission(1))
        sink.onSubmitted(submission(2))
        sink.onSubmitted(submission(3))

        val lines = sink.recentSubmissions()
        assertTrue(lines[0].contains("native_frame_id=2"))
        assertTrue(lines[1].contains("native_frame_id=3"))
    }

    @Test
    fun frameIdIsReportedAsUnsignedDecimal() {
        val sink = BoundedFrameEvidenceSink()
        sink.onSubmitted(submission(1))

        assertTrue(
            "an id with the high bit set must not be printed negative",
            sink.recentSubmissions().single().contains("egl_frame_id=18446744073709551615"),
        )
    }

    @Test
    fun resultLineCarriesEveryFieldStatusAndTheReason() {
        val sink = BoundedFrameEvidenceSink()
        sink.onResult(result(1))

        val line = sink.recentResults().single()
        assertTrue(line.contains("system_present=CLOCK_DOMAIN_UNVERIFIED(-2)"))
        assertTrue(line.contains("render_complete=PENDING(-2)"))
        assertTrue(line.contains("latch=UNSUPPORTED"))
        assertTrue(line.contains("reason=CLOCK_DOMAIN_UNVERIFIED"))
    }

    @Test
    fun failedSubmissionCarriesItsFailureText() {
        val sink = BoundedFrameEvidenceSink()
        sink.onSubmitted(submission(1).copy(swapAccepted = false, failure = "eglSwapBuffers failed 0x3001"))
        assertTrue(sink.recentSubmissions().single().contains("swap_accepted=false"))
        assertTrue(sink.recentSubmissions().single().contains("failure=eglSwapBuffers failed 0x3001"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveRetention() {
        BoundedFrameEvidenceSink(retained = 0)
    }

    private fun submission(index: Int) = FrameSubmission(
        identity = identity(index),
        timeline = VsyncTimeline(index.toLong(), 1_000L + index, 2_000L + index),
        submitNanos = 3_000L + index,
        eglFrameId = java.lang.Long.toUnsignedString(-1L),
        presentationRequestNanos = 1_000L + index,
        swapAccepted = true,
        failure = null,
    )

    private fun result(index: Int) = FrameResult(
        identity = identity(index),
        fields = mapOf(
            TimestampPrefix.SYSTEM_PRESENT to TimestampField(TimestampStatus.CLOCK_DOMAIN_UNVERIFIED, -2L),
            TimestampPrefix.RENDER_COMPLETE to TimestampField(TimestampStatus.PENDING, -2L),
            TimestampPrefix.LATCH to TimestampField(TimestampStatus.UNSUPPORTED, null),
        ),
        unconfirmedReason = UnconfirmedReason.CLOCK_DOMAIN_UNVERIFIED,
        queryErrorCode = null,
    )

    private fun identity(index: Int) = FrameIdentity(
        runId = "run-1",
        surfaceInstanceId = "surface-1",
        nativeFrameId = index.toString(),
        renderSeq = index.toULong(),
        layoutId = "layout-0",
    )
}
