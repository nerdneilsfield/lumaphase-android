package dev.lumaphase.android.timing

/** One standard timestamp field: its status and the raw native value when one was obtained. */
data class TimestampField(
    val status: TimestampStatus,
    val nativeValue: Long?,
)

/**
 * The scalar values copied out of one `Choreographer.FrameTimeline` inside the VSync
 * callback. FrameData and its timeline objects are only valid inside the callback, so the
 * numbers are copied immediately and never the objects.
 */
data class VsyncTimeline(
    val vsyncId: Long,
    val expectedPresentTimeNanos: Long,
    val deadlineNanos: Long,
)

/**
 * Identity of one submitted frame, captured when the frame is submitted and never
 * re-derived from the "current" segment afterwards.
 *
 * [renderSeq] is the intra-session state identity, [eglFrameId] belongs to the EGL swap and
 * [vsyncId] to the scheduling timeline; the three are distinct and must not substitute for
 * one another. A result that arrives late still carries the identity captured here.
 */
data class FrameIdentity(
    val runId: String,
    val surfaceInstanceId: String,
    val nativeFrameId: String,
    val renderSeq: ULong,
    val layoutId: String,
)

/** Evidence that one frame was submitted to the compositor, or that the swap failed. */
data class FrameSubmission(
    val identity: FrameIdentity,
    val timeline: VsyncTimeline,
    /** Monotonic nanoseconds sampled immediately before `eglSwapBuffers`. */
    val submitNanos: Long,
    /** The EGL frame id as unsigned decimal, or null when the extension could not supply one. */
    val eglFrameId: String?,
    /** Argument passed to `eglPresentationTimeANDROID`, or null when it was not called. */
    val presentationRequestNanos: Long?,
    val swapAccepted: Boolean,
    val failure: String?,
)

/**
 * The timing evidence for one frame. [fields] holds the per-field status and native value;
 * standard relative times are not produced in T3 because the EGL clock domain is not
 * verified and no session origin exists yet.
 */
data class FrameResult(
    val identity: FrameIdentity,
    val fields: Map<TimestampPrefix, TimestampField>,
    val unconfirmedReason: String?,
    /** `eglGetError()` from a failed query call, retained for diagnosis. */
    val queryErrorCode: Int?,
)

/**
 * Receives frame evidence on the render thread. T4 will implement this to build the
 * session log; T3 uses it for bounded statistics and logcat evidence.
 */
interface FrameTimingSink {
    fun onSubmitted(submission: FrameSubmission)
    fun onResult(result: FrameResult)
}

/** What the EGL timestamp extension offers on this device, queried once after EGL init. */
data class ProbeCapabilities(
    /** `eglGetProcAddress` supplied all three `EGL_ANDROID_get_frame_timestamps` entry points. */
    val extensionAvailable: Boolean,
    /** `EGL_TIMESTAMPS_ANDROID` was accepted for this surface. */
    val timestampsEnabled: Boolean,
    /** `EGL_ANDROID_presentation_time` is present, so the expected time can be requested. */
    val presentationTimeSupported: Boolean,
    /** Per EGL-backed field: whether that timestamp type reports as supported. */
    val supportedTypes: Map<TimestampPrefix, Boolean>,
    /** The EGL extension string, kept as evidence of what the driver advertised. */
    val extensionString: String,
) {
    fun supports(field: TimestampPrefix): Boolean = supportedTypes[field] == true
}

/** Bounded counters for one dynamic run. Read from any thread; never a full log schema. */
data class RenderStats(
    val runId: String,
    val framesSubmitted: Int,
    val framesFailed: Int,
    val resultsSettled: Int,
    val resultsUnconfirmed: Int,
    val pendingNow: Int,
    val maxPendingSeen: Int,
    val droppedQueueFull: Int,
    val droppedAtStop: Int,
    /**
     * Difference between the two most recent `FrameTimeline` expected presentation times,
     * i.e. the cadence the platform expected. It is evidence for a device run, not a
     * measurement of the panel and not proof that the requested refresh rate was granted.
     */
    val lastVsyncIntervalNanos: Long?,
) {
    companion object {
        val EMPTY = RenderStats("", 0, 0, 0, 0, 0, 0, 0, 0, null)
    }
}
