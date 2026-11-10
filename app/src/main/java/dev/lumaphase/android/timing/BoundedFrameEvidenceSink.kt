package dev.lumaphase.android.timing

/**
 * Bounded in-memory evidence sink for the T3 dynamic preview.
 *
 * It keeps counters and the most recent submissions and results so the preview can report
 * what actually happened when it stops. It is written from the render thread and read from
 * the UI thread, and it writes nothing to disk; T4 replaces it with the session log.
 */
class BoundedFrameEvidenceSink(private val retained: Int = DEFAULT_RETAINED) : FrameTimingSink {

    init {
        require(retained > 0) { "retained must be positive, got $retained" }
    }

    private val lock = Any()
    private val submissions = ArrayDeque<String>(retained)
    private val results = ArrayDeque<String>(retained)
    private val displays = ArrayDeque<String>(retained)
    private var submissionCount = 0
    private var resultCount = 0

    override fun onSubmitted(submission: FrameSubmission) {
        synchronized(lock) {
            submissionCount++
            offer(submissions, summary(submission))
        }
    }

    override fun onDisplayStarted(displayStart: DisplayStart) {
        synchronized(lock) {
            offer(displays, "surface=${displayStart.surfaceInstanceId} layout=${displayStart.layoutId} " +
                "drawable=${displayStart.drawableWidthPx}x${displayStart.drawableHeightPx}")
        }
    }

    override fun onResult(result: FrameResult) {
        synchronized(lock) {
            resultCount++
            offer(results, summary(result))
        }
    }

    fun submissionCount(): Int = synchronized(lock) { submissionCount }

    fun resultCount(): Int = synchronized(lock) { resultCount }

    fun recentSubmissions(): List<String> = synchronized(lock) { submissions.toList() }

    fun recentResults(): List<String> = synchronized(lock) { results.toList() }

    fun recentDisplays(): List<String> = synchronized(lock) { displays.toList() }

    private fun offer(bucket: ArrayDeque<String>, line: String) {
        bucket.addLast(line)
        while (bucket.size > retained) bucket.removeFirst()
    }

    companion object {
        const val DEFAULT_RETAINED = 6

        fun summary(submission: FrameSubmission): String = buildString {
            append("native_frame_id=").append(submission.identity.nativeFrameId)
            append(" render_seq=").append(submission.identity.renderSeq)
            append(" vsync_id=").append(submission.timeline.vsyncId)
            append(" egl_frame_id=").append(submission.eglFrameId ?: "null")
            append(" target_present=").append(submission.timeline.expectedPresentTimeNanos)
            append(" deadline=").append(submission.timeline.deadlineNanos)
            append(" submit=").append(submission.submitNanos)
            append(" presentation_request=").append(submission.presentationRequestNanos ?: "null")
            append(" swap_accepted=").append(submission.swapAccepted)
            if (submission.failure != null) append(" failure=").append(submission.failure)
        }

        fun summary(result: FrameResult): String = buildString {
            append("native_frame_id=").append(result.identity.nativeFrameId)
            append(" render_seq=").append(result.identity.renderSeq)
            for (prefix in EGL_FIELDS) {
                val field = result.fields[prefix]
                append(' ').append(prefix.key).append('=')
                append(field?.status?.name ?: "MISSING")
                if (field?.nativeValue != null) append('(').append(field.nativeValue).append(')')
            }
            append(" reason=").append(result.unconfirmedReason ?: "null")
            if (result.queryErrorCode != null) {
                append(" egl_error=0x").append(Integer.toHexString(result.queryErrorCode))
            }
        }

        private val EGL_FIELDS = listOf(
            TimestampPrefix.SYSTEM_PRESENT,
            TimestampPrefix.RENDER_COMPLETE,
            TimestampPrefix.LATCH,
        )
    }
}
