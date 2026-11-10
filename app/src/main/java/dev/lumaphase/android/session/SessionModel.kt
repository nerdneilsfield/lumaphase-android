package dev.lumaphase.android.session

import dev.lumaphase.android.timing.FrameKind
import dev.lumaphase.android.timing.TimestampPrefix
import dev.lumaphase.android.timing.TimestampStatus

/** Session phases. Only the first four produce display frames (contract section 5). */
enum class SessionPhase(
    val frameKind: FrameKind?,
    val plannedDurationNs: Long?,
    /** Text the stationary phases show in place of the dynamic area. */
    val cardLabel: String?,
) {
    IDLE(null, null, null),
    CONFIGURED(null, null, null),
    WARMUP(FrameKind.WARMUP, 10_000_000_000, "WARMUP"),
    SESSION_CARD(FrameKind.SESSION_CARD, 2_000_000_000, "START"),
    RUNNING(FrameKind.DYNAMIC, 90_000_000_000, null),
    END_CARD(FrameKind.END_CARD, 2_000_000_000, "END"),
    CALIBRATION(FrameKind.CALIBRATION, null, null),
    PAUSED(null, null, null),
    EXPORT(null, null, null),
}

/** The order the default session runs in. */
val DEFAULT_SESSION_PLAN = listOf(
    SessionPhase.WARMUP,
    SessionPhase.SESSION_CARD,
    SessionPhase.RUNNING,
    SessionPhase.END_CARD,
)

enum class DisplayEventType(val wire: String) {
    SUBMITTED("SUBMITTED"),
    PRESENTED("PRESENTED"),
    UNCONFIRMED("UNCONFIRMED"),
}

enum class ConfigurationEventType(val wire: String) {
    SEGMENT_STARTED("SEGMENT_STARTED"),
    SEGMENT_ENDED("SEGMENT_ENDED"),
    PHASE_CHANGED("PHASE_CHANGED"),
    RENDER_FAILURE("RENDER_FAILURE"),
    LOG_OVERFLOW("LOG_OVERFLOW"),
    SESSION_CLOSED("SESSION_CLOSED"),
}

enum class SegmentStartReason(val wire: String) {
    SESSION_START("SESSION_START"),
    RESUME("RESUME"),
    CONFIGURATION_CHANGED("CONFIGURATION_CHANGED"),
}

enum class SegmentEndReason(val wire: String) {
    NORMAL_END("NORMAL_END"),
    PAUSED("PAUSED"),
    BACKGROUND("BACKGROUND"),
    FULLSCREEN_EXIT("FULLSCREEN_EXIT"),
    SYSTEM_SLEEP("SYSTEM_SLEEP"),
    DISPLAY_CHANGED("DISPLAY_CHANGED"),
    SURFACE_CHANGED("SURFACE_CHANGED"),
    CONFIGURATION_CHANGED("CONFIGURATION_CHANGED"),
    LOG_OVERFLOW("LOG_OVERFLOW"),
    RENDER_FAILURE("RENDER_FAILURE"),
    USER_STOP("USER_STOP"),
}

enum class SessionClosedReason(val wire: String) {
    NORMAL_END("NORMAL_END"),
    USER_STOP("USER_STOP"),
    LOG_OVERFLOW("LOG_OVERFLOW"),
    RENDER_FAILURE("RENDER_FAILURE"),
}

enum class DataIntegrity(val wire: String) {
    COMPLETE("COMPLETE"),
    INCOMPLETE("INCOMPLETE"),
}

enum class BrightnessSource(val wire: String) {
    SYSTEM_REPORTED("SYSTEM_REPORTED"),
    OPERATOR_REPORTED("OPERATOR_REPORTED"),
    UNKNOWN("UNKNOWN"),
}

enum class DisplayProfileStatus(val wire: String) {
    MISSING("MISSING"),
    IMPORTED_UNVERIFIED("IMPORTED_UNVERIFIED"),
    COMPATIBLE("COMPATIBLE"),
    INCOMPATIBLE("INCOMPATIBLE"),
}

enum class PresentKind(val wire: String) {
    SYSTEM_REPORTED("SYSTEM_REPORTED"),
    UNCONFIRMED("UNCONFIRMED"),
}

/** `time_origin` of the manifest: the monotonic reading the session's relative times use. */
data class TimeOrigin(val rawNs: Long, val nativeClock: String)

/**
 * One segment snapshot (contract section 2.2). Every key is written; nullable fields are
 * written as JSON `null` when the value could not be obtained, never guessed.
 */
data class SegmentSnapshot(
    val segmentId: String,
    val layoutId: String,
    val displayModeId: String?,
    val experimentProfile: String,
    val drawableWidthPx: Int,
    val drawableHeightPx: Int,
    val displayRotationDeg: Int?,
    val requestedRefreshNum: Int?,
    val requestedRefreshDen: Int?,
    val nominalRefreshNum: Int?,
    val nominalRefreshDen: Int?,
    val reportedRefreshHz: Double?,
    val brightnessSetting: Double?,
    val brightnessSource: BrightnessSource,
    val autoBrightnessEnabled: Boolean?,
    val colorSpace: String,
    val pixelFormat: String,
    val colorMode: String?,
    val dimmingMode: String?,
    val hdrEnabled: Boolean?,
    val trueToneEnabled: Boolean?,
    val nightShiftEnabled: Boolean?,
    val displayProfileId: String?,
    val operatorNote: String,
)

/** `platform_ids` of a display event. */
data class PlatformIds(
    val surfaceInstanceId: String?,
    val eglFrameId: String?,
    val vsyncId: String?,
    val drawableId: String?,
)

/**
 * One `platform_timestamps` entry (contract section 6.1). Native integers are kept as
 * decimal strings so no 64-bit value is lost, sentinels included.
 */
data class PlatformTimestampEntry(
    val api: String,
    val nativeValue: String?,
    val nativeUnit: String?,
    val nativeClockDomain: String?,
    val queryErrorCode: String?,
)

/** A standard timestamp as written: raw and relative nanoseconds, both null unless VALID. */
data class StandardTimestampValue(val rawNs: Long?, val ns: Long?)

/** One `display_events.jsonl` line (contract section 7.1). */
data class DisplayEventRecord(
    override val eventSeq: Long,
    val eventType: DisplayEventType,
    override val sessionId: String,
    override val segmentId: String?,
    val layoutId: String?,
    val nativeFrameId: String?,
    val frameKind: FrameKind?,
    val phase: String?,
    val renderSeq: ULong?,
    val payloadN: Int?,
    val crc8: Int?,
    val timestamps: Map<TimestampPrefix, StandardTimestampValue>,
    val statuses: Map<TimestampPrefix, TimestampStatus>,
    val presentKind: PresentKind?,
    val unconfirmedReason: String?,
    val platformIds: PlatformIds,
    val platformTimestamps: Map<String, PlatformTimestampEntry>,
    override val recordedRawNs: Long,
    override val recordedNs: Long,
) : SessionRecord {
    override val targetFile: String get() = SessionFiles.DISPLAY_EVENTS
}

/** One `configuration_events.jsonl` line (contract section 2.2). */
data class ConfigurationEventRecord(
    override val eventSeq: Long,
    val eventType: ConfigurationEventType,
    override val sessionId: String,
    override val segmentId: String?,
    override val recordedRawNs: Long,
    override val recordedNs: Long,
    val details: Map<String, Any?>,
) : SessionRecord {
    override val targetFile: String get() = SessionFiles.CONFIGURATION_EVENTS
}

/** Final per-segment counts of `session_summary.json` (contract section 10). */
data class SegmentSummary(
    val segmentId: String,
    val generatedFrames: Int,
    val submittedFrames: Int,
    val confirmedPresentedFrames: Int,
    val finalUnconfirmedFrames: Int,
    val dataIntegrity: DataIntegrity,
    val unconfirmedReasonCounts: Map<String, Int>,
)

/** `session_summary.json` (contract section 10). */
data class SessionSummary(
    val sessionId: String,
    val closedRawNs: Long,
    val closedNs: Long,
    val dataIntegrity: DataIntegrity,
    val lostEventCount: Long,
    val segments: List<SegmentSummary>,
)

/** One segment binding of `display_profile.json` (contract section 9). */
data class SegmentProfileBinding(
    val segmentId: String,
    val profileId: String?,
    val status: DisplayProfileStatus,
    val mismatchFields: List<String>,
)

/** `display_profile.json` (contract section 9). With no imported calibration, `profiles` is empty. */
data class DisplayProfileDocument(
    val schemaVersion: String,
    val profiles: List<ImportedProfile>,
    val segmentBindings: List<SegmentProfileBinding>,
)

/** An imported calibration profile; `model` stays opaque for v1.0. */
data class ImportedProfile(
    val profileId: String,
    val calibrationKey: Map<String, Any?>,
    val model: Any?,
)

/** `manifest.json` (contract section 2.1). */
data class ManifestDocument(
    val schemaVersion: String,
    val protocolVersion: String,
    val sessionId: String,
    val platform: String,
    val sourceDeviceId: String,
    val appId: String,
    val appVersion: String,
    val appBuild: String,
    val osVersion: String,
    val osBuild: String,
    val clockDomain: String,
    val timeOrigin: TimeOrigin,
    val presentationDrainTimeoutNs: Long,
    val segments: List<SegmentSnapshot>,
)

/** The independent overflow latch of contract section 10. */
data class OverflowLatch(
    val affectedLog: String,
    val affectedSegmentIds: List<String>,
    val firstLostEventSeq: Long,
    val lastLostEventSeq: Long,
    val lostEventCount: Long,
)

/** The six files of a session directory, in contract order. */
object SessionFiles {
    const val MANIFEST = "manifest.json"
    const val LAYOUT = "layout.json"
    const val DISPLAY_EVENTS = "display_events.jsonl"
    const val CONFIGURATION_EVENTS = "configuration_events.jsonl"
    const val DISPLAY_PROFILE = "display_profile.json"
    const val SESSION_SUMMARY = "session_summary.json"

    val ALL = listOf(
        MANIFEST,
        LAYOUT,
        DISPLAY_EVENTS,
        CONFIGURATION_EVENTS,
        DISPLAY_PROFILE,
        SESSION_SUMMARY,
    )

    fun directoryName(sessionId: String): String = "session_$sessionId"
}

/** Constant values the contract fixes. */
object SessionConstants {
    const val SCHEMA_VERSION = "1.0"
    const val PROTOCOL_VERSION = "OPTC-1"
    const val PLATFORM = "ANDROID"
    const val CLOCK_DOMAIN = "ANDROID_MONOTONIC"
    const val PRESENTATION_DRAIN_TIMEOUT_NS = 2_000_000_000L

    /** The default main experiment name (contract section 2.2 example, design section 7). */
    const val EXPERIMENT_PROFILE = "A120-S1"
}
