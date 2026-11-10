package dev.lumaphase.android.timing

/** What a frame shows. Only `DYNAMIC` frames carry an encoded dynamic state (contract section 5). */
enum class FrameKind {
    WARMUP,
    SESSION_CARD,
    DYNAMIC,
    END_CARD,
    CALIBRATION,
}

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
 * [renderSeq] is the intra-session state identity and is null for frames that are not a
 * dynamic state (warm-up and cards consume no dynamic sequence). The EGL frame id belongs
 * to the swap and the vsync id to the scheduling timeline; the three are distinct and must
 * not substitute for one another. A result that arrives late still carries the identity
 * captured here, including the segment and layout it was submitted under.
 */
data class FrameIdentity(
    val runId: String,
    val sessionId: String,
    val segmentId: String,
    val surfaceInstanceId: String,
    val nativeFrameId: String,
    val frameKind: FrameKind,
    val phase: String,
    val renderSeq: ULong?,
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
    /** Encoded payload of a dynamic frame; null for warm-up and card frames. */
    val payloadN: Int?,
    /** CRC-8 of the encoded payload; null for warm-up and card frames. */
    val crc8: Int?,
    val swapAccepted: Boolean,
    val failure: String?,
    /** The native error code of a failed swap, kept separately from the message text. */
    val failureErrorCode: String?,
)

/**
 * The timing evidence for one frame. [fields] holds the per-field status and native value;
 * the standard relative times are derived by the session layer from the session origin.
 */
data class FrameResult(
    val identity: FrameIdentity,
    val fields: Map<TimestampPrefix, TimestampField>,
    val unconfirmedReason: String?,
    /** `eglGetError()` from a failed query call, retained for diagnosis. */
    val queryErrorCode: Int?,
)

/**
 * What the session wants stamped on the frames the render thread produces.
 *
 * An immutable snapshot: the host publishes a new instance atomically, and the render thread
 * reads exactly one instance per VSync and uses that same value to choose what to draw and to
 * build the frame identity, so the picture and its identity can never come from two different
 * phases. A null [frameKind] means the render thread must not produce or submit frames at all,
 * which is how a session that was never started stays silent.
 */
data class DisplayStamp(
    val sessionId: String = "",
    val segmentId: String = "",
    val layoutId: String = "",
    val frameKind: FrameKind? = null,
    /** The session phase name the display events carry, e.g. `RUNNING` for dynamic frames. */
    val phase: String = "",
    /** Text the stationary phases show in place of the dynamic area; null for dynamic frames. */
    val cardLabel: String? = null,
    /** True once the session has been started; before that no frames are recorded. */
    val recording: Boolean = false,
)

/** The EGL-level facts of a display target, reported when the render thread starts drawing. */
data class DisplayStart(
    val surfaceInstanceId: String,
    val layoutId: String,
    val drawableWidthPx: Int,
    val drawableHeightPx: Int,
    /** The render surface's colour space as the platform reports it. */
    val colorSpace: String,
    /** The render target's native pixel format name. */
    val pixelFormat: String,
)

/**
 * Receives frame evidence on the render thread plus the display start the session has to
 * record. T3 used the frame callbacks for bounded statistics; T4 implements the session log
 * behind the same seam. The session phases are driven by the host, so there is no phase
 * callback here.
 */
interface FrameTimingSink {
    fun onSubmitted(submission: FrameSubmission)
    fun onResult(result: FrameResult)
    fun onDisplayStarted(displayStart: DisplayStart)
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
     * Null for the first frame of a Surface, so a pause is never counted as one interval.
     */
    val lastVsyncIntervalNanos: Long?,
) {
    companion object {
        val EMPTY = RenderStats("", 0, 0, 0, 0, 0, 0, 0, 0, null)
    }
}
