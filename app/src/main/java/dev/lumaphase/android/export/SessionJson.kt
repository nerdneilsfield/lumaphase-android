package dev.lumaphase.android.export

import android.util.JsonWriter
import dev.lumaphase.android.session.ConfigurationEventRecord
import dev.lumaphase.android.session.DataIntegrity
import dev.lumaphase.android.session.DisplayEventRecord
import dev.lumaphase.android.session.DisplayProfileDocument
import dev.lumaphase.android.session.ManifestDocument
import dev.lumaphase.android.session.SegmentSnapshot
import dev.lumaphase.android.session.SegmentSummary
import dev.lumaphase.android.session.SessionSummary
import dev.lumaphase.android.timing.TimestampPrefix
import java.io.Writer

/**
 * Writes the session documents defined by OPTC-1 with Android's own [JsonWriter].
 *
 * Key names and shapes follow contract sections 2, 6, 7, 9 and 10 exactly; the key lists
 * below are the same constants the writer uses, so a test can compare them with the
 * contract without re-reading the writer.
 */
object SessionJson {
    // manifest.json (section 2.1)
    const val KEY_SCHEMA_VERSION = "schema_version"
    const val KEY_PROTOCOL_VERSION = "protocol_version"
    const val KEY_SESSION_ID = "session_id"
    const val KEY_PLATFORM = "platform"
    const val KEY_SOURCE_DEVICE_ID = "source_device_id"
    const val KEY_APP_ID = "app_id"
    const val KEY_APP_VERSION = "app_version"
    const val KEY_APP_BUILD = "app_build"
    const val KEY_OS_VERSION = "os_version"
    const val KEY_OS_BUILD = "os_build"
    const val KEY_CLOCK_DOMAIN = "clock_domain"
    const val KEY_TIME_ORIGIN = "time_origin"
    const val KEY_TIME_ORIGIN_RAW_NS = "raw_ns"
    const val KEY_TIME_ORIGIN_NATIVE_CLOCK = "native_clock"
    const val KEY_PRESENTATION_DRAIN_TIMEOUT_NS = "presentation_drain_timeout_ns"
    const val KEY_SEGMENTS = "segments"

    // segment snapshot (section 2.2)
    const val KEY_SEGMENT_ID = "segment_id"
    const val KEY_LAYOUT_ID = "layout_id"
    const val KEY_DISPLAY_MODE_ID = "display_mode_id"
    const val KEY_EXPERIMENT_PROFILE = "experiment_profile"
    const val KEY_DRAWABLE_WIDTH_PX = "drawable_width_px"
    const val KEY_DRAWABLE_HEIGHT_PX = "drawable_height_px"
    const val KEY_DISPLAY_ROTATION_DEG = "display_rotation_deg"
    const val KEY_REQUESTED_REFRESH_NUM = "requested_refresh_num"
    const val KEY_REQUESTED_REFRESH_DEN = "requested_refresh_den"
    const val KEY_NOMINAL_REFRESH_NUM = "nominal_refresh_num"
    const val KEY_NOMINAL_REFRESH_DEN = "nominal_refresh_den"
    const val KEY_REPORTED_REFRESH_HZ = "reported_refresh_hz"
    const val KEY_BRIGHTNESS_SETTING = "brightness_setting"
    const val KEY_BRIGHTNESS_SOURCE = "brightness_source"
    const val KEY_AUTO_BRIGHTNESS_ENABLED = "auto_brightness_enabled"
    const val KEY_COLOR_SPACE = "color_space"
    const val KEY_PIXEL_FORMAT = "pixel_format"
    const val KEY_COLOR_MODE = "color_mode"
    const val KEY_DIMMING_MODE = "dimming_mode"
    const val KEY_HDR_ENABLED = "hdr_enabled"
    const val KEY_TRUE_TONE_ENABLED = "true_tone_enabled"
    const val KEY_NIGHT_SHIFT_ENABLED = "night_shift_enabled"
    const val KEY_DISPLAY_PROFILE_ID = "display_profile_id"
    const val KEY_OPERATOR_NOTE = "operator_note"

    // display events (sections 7.1, 6.2)
    const val KEY_EVENT_SEQ = "event_seq"
    const val KEY_EVENT_TYPE = "event_type"
    const val KEY_NATIVE_FRAME_ID = "native_frame_id"
    const val KEY_FRAME_KIND = "frame_kind"
    const val KEY_PHASE = "phase"
    const val KEY_RENDER_SEQ = "render_seq"
    const val KEY_PAYLOAD_N = "payload_n"
    const val KEY_CRC8 = "crc8"
    const val KEY_RECORDED_RAW_NS = "recorded_raw_ns"
    const val KEY_RECORDED_NS = "recorded_ns"
    const val KEY_TIMESTAMP_STATUS = "timestamp_status"
    const val KEY_PRESENT_KIND = "present_kind"
    const val KEY_UNCONFIRMED_REASON = "unconfirmed_reason"
    const val KEY_PLATFORM_IDS = "platform_ids"
    const val KEY_PLATFORM_TIMESTAMPS = "platform_timestamps"
    const val KEY_SURFACE_INSTANCE_ID = "surface_instance_id"
    const val KEY_EGL_FRAME_ID = "egl_frame_id"
    const val KEY_VSYNC_ID = "vsync_id"
    const val KEY_DRAWABLE_ID = "drawable_id"
    const val KEY_API = "api"
    const val KEY_NATIVE_VALUE = "native_value"
    const val KEY_NATIVE_UNIT = "native_unit"
    const val KEY_NATIVE_CLOCK_DOMAIN = "native_clock_domain"
    const val KEY_QUERY_ERROR_CODE = "query_error_code"
    const val EGL_PRESENTATION_REQUEST = "egl_presentation_request"

    // configuration events (sections 2.2, 2.3)
    const val KEY_DETAILS = "details"
    const val KEY_REASON = "reason"
    const val KEY_CONFIGURATION = "configuration"
    const val KEY_PREVIOUS_PHASE = "previous_phase"
    const val KEY_PLANNED_DURATION_NS = "planned_duration_ns"
    const val KEY_OPERATION = "operation"
    const val KEY_ERROR_CODE = "error_code"
    const val KEY_MESSAGE = "message"
    const val KEY_AFFECTED_LOG = "affected_log"
    const val KEY_AFFECTED_SEGMENT_IDS = "affected_segment_ids"
    const val KEY_FIRST_LOST_EVENT_SEQ = "first_lost_event_seq"
    const val KEY_LAST_LOST_EVENT_SEQ = "last_lost_event_seq"
    const val KEY_LOST_EVENT_COUNT = "lost_event_count"
    const val KEY_DATA_INTEGRITY = "data_integrity"
    const val KEY_PENDING_FRAME_COUNT = "pending_frame_count"

    // display_profile.json (section 9)
    const val KEY_PROFILES = "profiles"
    const val KEY_SEGMENT_BINDINGS = "segment_bindings"
    const val KEY_PROFILE_ID = "profile_id"
    const val KEY_STATUS = "status"
    const val KEY_MISMATCH_FIELDS = "mismatch_fields"
    const val KEY_CALIBRATION_KEY = "calibration_key"
    const val KEY_MODEL = "model"

    // session_summary.json (section 10)
    const val KEY_CLOSED_RAW_NS = "closed_raw_ns"
    const val KEY_CLOSED_NS = "closed_ns"
    const val KEY_GENERATED_FRAMES = "generated_frames"
    const val KEY_SUBMITTED_FRAMES = "submitted_frames"
    const val KEY_CONFIRMED_PRESENTED_FRAMES = "confirmed_presented_frames"
    const val KEY_FINAL_UNCONFIRMED_FRAMES = "final_unconfirmed_frames"
    const val KEY_UNCONFIRMED_REASON_COUNTS = "unconfirmed_reason_counts"

    /** The six standard prefixes that carry a status entry. */
    val TIMESTAMP_PREFIX_KEYS: List<String> = TimestampPrefix.entries.map { it.key }

    val MANIFEST_KEYS: List<String> = listOf(
        KEY_SCHEMA_VERSION, KEY_PROTOCOL_VERSION, KEY_SESSION_ID, KEY_PLATFORM,
        KEY_SOURCE_DEVICE_ID, KEY_APP_ID, KEY_APP_VERSION, KEY_APP_BUILD,
        KEY_OS_VERSION, KEY_OS_BUILD, KEY_CLOCK_DOMAIN, KEY_TIME_ORIGIN,
        KEY_PRESENTATION_DRAIN_TIMEOUT_NS, KEY_SEGMENTS,
    )

    val TIME_ORIGIN_KEYS: List<String> = listOf(KEY_TIME_ORIGIN_RAW_NS, KEY_TIME_ORIGIN_NATIVE_CLOCK)

    val SEGMENT_KEYS: List<String> = listOf(
        KEY_SEGMENT_ID, KEY_LAYOUT_ID, KEY_DISPLAY_MODE_ID, KEY_EXPERIMENT_PROFILE,
        KEY_DRAWABLE_WIDTH_PX, KEY_DRAWABLE_HEIGHT_PX, KEY_DISPLAY_ROTATION_DEG,
        KEY_REQUESTED_REFRESH_NUM, KEY_REQUESTED_REFRESH_DEN,
        KEY_NOMINAL_REFRESH_NUM, KEY_NOMINAL_REFRESH_DEN, KEY_REPORTED_REFRESH_HZ,
        KEY_BRIGHTNESS_SETTING, KEY_BRIGHTNESS_SOURCE, KEY_AUTO_BRIGHTNESS_ENABLED,
        KEY_COLOR_SPACE, KEY_PIXEL_FORMAT, KEY_COLOR_MODE, KEY_DIMMING_MODE,
        KEY_HDR_ENABLED, KEY_TRUE_TONE_ENABLED, KEY_NIGHT_SHIFT_ENABLED,
        KEY_DISPLAY_PROFILE_ID, KEY_OPERATOR_NOTE,
    )

    val DISPLAY_EVENT_KEYS: List<String> = buildList {
        add(KEY_EVENT_SEQ)
        add(KEY_EVENT_TYPE)
        add(KEY_SESSION_ID)
        add(KEY_SEGMENT_ID)
        add(KEY_LAYOUT_ID)
        add(KEY_NATIVE_FRAME_ID)
        add(KEY_FRAME_KIND)
        add(KEY_PHASE)
        add(KEY_RENDER_SEQ)
        add(KEY_PAYLOAD_N)
        add(KEY_CRC8)
        for (prefix in TimestampPrefix.entries) {
            add("${prefix.key}_raw_ns")
            add("${prefix.key}_ns")
        }
        add(KEY_TIMESTAMP_STATUS)
        add(KEY_PRESENT_KIND)
        add(KEY_UNCONFIRMED_REASON)
        add(KEY_PLATFORM_IDS)
        add(KEY_PLATFORM_TIMESTAMPS)
        add(KEY_RECORDED_RAW_NS)
        add(KEY_RECORDED_NS)
    }

    val PLATFORM_IDS_KEYS: List<String> = listOf(
        KEY_SURFACE_INSTANCE_ID, KEY_EGL_FRAME_ID, KEY_VSYNC_ID, KEY_DRAWABLE_ID,
    )

    val PLATFORM_TIMESTAMP_KEYS: List<String> = listOf(
        KEY_API, KEY_NATIVE_VALUE, KEY_NATIVE_UNIT, KEY_NATIVE_CLOCK_DOMAIN, KEY_QUERY_ERROR_CODE,
    )

    val CONFIGURATION_EVENT_KEYS: List<String> = listOf(
        KEY_EVENT_SEQ, KEY_EVENT_TYPE, KEY_SESSION_ID, KEY_SEGMENT_ID,
        KEY_RECORDED_RAW_NS, KEY_RECORDED_NS, KEY_DETAILS,
    )

    val DISPLAY_PROFILE_ROOT_KEYS: List<String> = listOf(
        KEY_SCHEMA_VERSION, KEY_PROFILES, KEY_SEGMENT_BINDINGS,
    )

    val SEGMENT_BINDING_KEYS: List<String> = listOf(
        KEY_SEGMENT_ID, KEY_PROFILE_ID, KEY_STATUS, KEY_MISMATCH_FIELDS,
    )

    val SUMMARY_KEYS: List<String> = listOf(
        KEY_SESSION_ID, KEY_CLOSED_RAW_NS, KEY_CLOSED_NS, KEY_DATA_INTEGRITY,
        KEY_LOST_EVENT_COUNT, KEY_SEGMENTS,
    )

    val SEGMENT_SUMMARY_KEYS: List<String> = listOf(
        KEY_SEGMENT_ID, KEY_GENERATED_FRAMES, KEY_SUBMITTED_FRAMES,
        KEY_CONFIRMED_PRESENTED_FRAMES, KEY_FINAL_UNCONFIRMED_FRAMES,
        KEY_DATA_INTEGRITY, KEY_UNCONFIRMED_REASON_COUNTS,
    )

    // ---- writers -------------------------------------------------------------------------

    fun writeManifest(writer: Writer, manifest: ManifestDocument) {
        val json = newWriter(writer)
        json.beginObject()
        json.name(KEY_SCHEMA_VERSION).value(manifest.schemaVersion)
        json.name(KEY_PROTOCOL_VERSION).value(manifest.protocolVersion)
        json.name(KEY_SESSION_ID).value(manifest.sessionId)
        json.name(KEY_PLATFORM).value(manifest.platform)
        json.name(KEY_SOURCE_DEVICE_ID).value(manifest.sourceDeviceId)
        json.name(KEY_APP_ID).value(manifest.appId)
        json.name(KEY_APP_VERSION).value(manifest.appVersion)
        json.name(KEY_APP_BUILD).value(manifest.appBuild)
        json.name(KEY_OS_VERSION).value(manifest.osVersion)
        json.name(KEY_OS_BUILD).value(manifest.osBuild)
        json.name(KEY_CLOCK_DOMAIN).value(manifest.clockDomain)
        json.name(KEY_TIME_ORIGIN).beginObject()
        json.name(KEY_TIME_ORIGIN_RAW_NS).value(manifest.timeOrigin.rawNs)
        json.name(KEY_TIME_ORIGIN_NATIVE_CLOCK).value(manifest.timeOrigin.nativeClock)
        json.endObject()
        json.name(KEY_PRESENTATION_DRAIN_TIMEOUT_NS).value(manifest.presentationDrainTimeoutNs)
        json.name(KEY_SEGMENTS).beginArray()
        for (segment in manifest.segments) writeSegment(json, segment)
        json.endArray()
        json.endObject()
        json.flush()
    }

    fun writeSegment(json: JsonWriter, segment: SegmentSnapshot) {
        json.beginObject()
        json.name(KEY_SEGMENT_ID).value(segment.segmentId)
        json.name(KEY_LAYOUT_ID).value(segment.layoutId)
        json.name(KEY_DISPLAY_MODE_ID).value(segment.displayModeId)
        json.name(KEY_EXPERIMENT_PROFILE).value(segment.experimentProfile)
        json.name(KEY_DRAWABLE_WIDTH_PX).value(segment.drawableWidthPx.toLong())
        json.name(KEY_DRAWABLE_HEIGHT_PX).value(segment.drawableHeightPx.toLong())
        json.name(KEY_DISPLAY_ROTATION_DEG).value(segment.displayRotationDeg?.toLong())
        json.name(KEY_REQUESTED_REFRESH_NUM).value(segment.requestedRefreshNum?.toLong())
        json.name(KEY_REQUESTED_REFRESH_DEN).value(segment.requestedRefreshDen?.toLong())
        json.name(KEY_NOMINAL_REFRESH_NUM).value(segment.nominalRefreshNum?.toLong())
        json.name(KEY_NOMINAL_REFRESH_DEN).value(segment.nominalRefreshDen?.toLong())
        json.name(KEY_REPORTED_REFRESH_HZ).value(segment.reportedRefreshHz)
        json.name(KEY_BRIGHTNESS_SETTING).value(segment.brightnessSetting)
        json.name(KEY_BRIGHTNESS_SOURCE).value(segment.brightnessSource.wire)
        nullableBoolean(json, KEY_AUTO_BRIGHTNESS_ENABLED, segment.autoBrightnessEnabled)
        json.name(KEY_COLOR_SPACE).value(segment.colorSpace)
        json.name(KEY_PIXEL_FORMAT).value(segment.pixelFormat)
        json.name(KEY_COLOR_MODE).value(segment.colorMode)
        json.name(KEY_DIMMING_MODE).value(segment.dimmingMode)
        nullableBoolean(json, KEY_HDR_ENABLED, segment.hdrEnabled)
        nullableBoolean(json, KEY_TRUE_TONE_ENABLED, segment.trueToneEnabled)
        nullableBoolean(json, KEY_NIGHT_SHIFT_ENABLED, segment.nightShiftEnabled)
        json.name(KEY_DISPLAY_PROFILE_ID).value(segment.displayProfileId)
        json.name(KEY_OPERATOR_NOTE).value(segment.operatorNote)
        json.endObject()
    }

    /** One `display_events.jsonl` line, without the trailing newline. */
    fun writeDisplayEventLine(json: JsonWriter, event: DisplayEventRecord) {
        json.beginObject()
        json.name(KEY_EVENT_SEQ).value(event.eventSeq)
        json.name(KEY_EVENT_TYPE).value(event.eventType.wire)
        json.name(KEY_SESSION_ID).value(event.sessionId)
        json.name(KEY_SEGMENT_ID).value(event.segmentId)
        json.name(KEY_LAYOUT_ID).value(event.layoutId)
        json.name(KEY_NATIVE_FRAME_ID).value(event.nativeFrameId)
        json.name(KEY_FRAME_KIND).value(event.frameKind?.name)
        json.name(KEY_PHASE).value(event.phase)
        // render_seq is a decimal string without leading zeroes; the other two are numbers.
        json.name(KEY_RENDER_SEQ).value(event.renderSeq?.toString())
        json.name(KEY_PAYLOAD_N).value(event.payloadN)
        json.name(KEY_CRC8).value(event.crc8)

        for (prefix in TimestampPrefix.entries) {
            val value = event.timestamps[prefix]
            json.name("${prefix.key}_raw_ns").value(value?.rawNs)
            json.name("${prefix.key}_ns").value(value?.ns)
        }

        json.name(KEY_TIMESTAMP_STATUS).beginObject()
        for (prefix in TimestampPrefix.entries) {
            json.name(prefix.key).value(event.statuses[prefix]?.name)
        }
        json.endObject()

        json.name(KEY_PRESENT_KIND).value(event.presentKind?.wire)
        json.name(KEY_UNCONFIRMED_REASON).value(event.unconfirmedReason)

        json.name(KEY_PLATFORM_IDS).beginObject()
        json.name(KEY_SURFACE_INSTANCE_ID).value(event.platformIds.surfaceInstanceId)
        json.name(KEY_EGL_FRAME_ID).value(event.platformIds.eglFrameId)
        json.name(KEY_VSYNC_ID).value(event.platformIds.vsyncId)
        json.name(KEY_DRAWABLE_ID).value(event.platformIds.drawableId)
        json.endObject()

        json.name(KEY_PLATFORM_TIMESTAMPS).beginObject()
        for ((key, entry) in event.platformTimestamps) {
            json.name(key).beginObject()
            json.name(KEY_API).value(entry.api)
            json.name(KEY_NATIVE_VALUE).value(entry.nativeValue)
            json.name(KEY_NATIVE_UNIT).value(entry.nativeUnit)
            json.name(KEY_NATIVE_CLOCK_DOMAIN).value(entry.nativeClockDomain)
            json.name(KEY_QUERY_ERROR_CODE).value(entry.queryErrorCode)
            json.endObject()
        }
        json.endObject()

        json.name(KEY_RECORDED_RAW_NS).value(event.recordedRawNs)
        json.name(KEY_RECORDED_NS).value(event.recordedNs)
        json.endObject()
    }

    /** One `configuration_events.jsonl` line, without the trailing newline. */
    fun writeConfigurationEventLine(json: JsonWriter, event: ConfigurationEventRecord) {
        json.beginObject()
        json.name(KEY_EVENT_SEQ).value(event.eventSeq)
        json.name(KEY_EVENT_TYPE).value(event.eventType.wire)
        json.name(KEY_SESSION_ID).value(event.sessionId)
        json.name(KEY_SEGMENT_ID).value(event.segmentId)
        json.name(KEY_RECORDED_RAW_NS).value(event.recordedRawNs)
        json.name(KEY_RECORDED_NS).value(event.recordedNs)
        json.name(KEY_DETAILS).beginObject()
        for ((key, value) in event.details) {
            json.name(key)
            writeValue(json, value)
        }
        json.endObject()
        json.endObject()
    }

    fun writeDisplayProfile(writer: Writer, document: DisplayProfileDocument) {
        val json = newWriter(writer)
        json.beginObject()
        json.name(KEY_SCHEMA_VERSION).value(document.schemaVersion)
        json.name(KEY_PROFILES).beginArray()
        for (profile in document.profiles) {
            json.beginObject()
            json.name(KEY_PROFILE_ID).value(profile.profileId)
            json.name(KEY_CALIBRATION_KEY).beginObject()
            for ((key, value) in profile.calibrationKey) {
                json.name(key)
                writeValue(json, value)
            }
            json.endObject()
            json.name(KEY_MODEL)
            writeValue(json, profile.model)
            json.endObject()
        }
        json.endArray()
        json.name(KEY_SEGMENT_BINDINGS).beginArray()
        for (binding in document.segmentBindings) {
            json.beginObject()
            json.name(KEY_SEGMENT_ID).value(binding.segmentId)
            json.name(KEY_PROFILE_ID).value(binding.profileId)
            json.name(KEY_STATUS).value(binding.status.wire)
            json.name(KEY_MISMATCH_FIELDS).beginArray()
            for (field in binding.mismatchFields) json.value(field)
            json.endArray()
            json.endObject()
        }
        json.endArray()
        json.endObject()
        json.flush()
    }

    fun writeSessionSummary(writer: Writer, summary: SessionSummary) {
        val json = newWriter(writer)
        json.beginObject()
        json.name(KEY_SESSION_ID).value(summary.sessionId)
        json.name(KEY_CLOSED_RAW_NS).value(summary.closedRawNs)
        json.name(KEY_CLOSED_NS).value(summary.closedNs)
        json.name(KEY_DATA_INTEGRITY).value(summary.dataIntegrity.wire)
        json.name(KEY_LOST_EVENT_COUNT).value(summary.lostEventCount)
        json.name(KEY_SEGMENTS).beginArray()
        for (segment in summary.segments) writeSegmentSummary(json, segment)
        json.endArray()
        json.endObject()
        json.flush()
    }

    private fun writeSegmentSummary(json: JsonWriter, segment: SegmentSummary) {
        json.beginObject()
        json.name(KEY_SEGMENT_ID).value(segment.segmentId)
        json.name(KEY_GENERATED_FRAMES).value(segment.generatedFrames.toLong())
        json.name(KEY_SUBMITTED_FRAMES).value(segment.submittedFrames.toLong())
        json.name(KEY_CONFIRMED_PRESENTED_FRAMES).value(segment.confirmedPresentedFrames.toLong())
        json.name(KEY_FINAL_UNCONFIRMED_FRAMES).value(segment.finalUnconfirmedFrames.toLong())
        json.name(KEY_DATA_INTEGRITY).value(segment.dataIntegrity.wire)
        json.name(KEY_UNCONFIRMED_REASON_COUNTS).beginObject()
        for ((reason, count) in segment.unconfirmedReasonCounts) {
            json.name(reason).value(count.toLong())
        }
        json.endObject()
        json.endObject()
    }

    /** `JsonWriter` has no nullable Boolean overload, so absence is written explicitly. */
    private fun nullableBoolean(json: JsonWriter, name: String, value: Boolean?) {
        json.name(name)
        if (value == null) json.nullValue() else json.value(value)
    }

    private fun newWriter(writer: Writer): JsonWriter =
        JsonWriter(writer).apply { setIndent("  ") }

    /** Writes the value shapes the `details` objects use. */
    private fun writeValue(json: JsonWriter, value: Any?) {
        when (value) {
            null -> json.nullValue()
            // The full segment object of contract section 2.2, as SEGMENT_STARTED requires;
            // without this branch it would be written as a Kotlin toString().
            is SegmentSnapshot -> writeSegment(json, value)
            is String -> json.value(value)
            is Int -> json.value(value.toLong())
            is Long -> json.value(value)
            is Boolean -> json.value(value)
            is Double -> json.value(value)
            is List<*> -> {
                json.beginArray()
                for (item in value) writeValue(json, item)
                json.endArray()
            }
            is Map<*, *> -> {
                json.beginObject()
                for ((key, item) in value) {
                    json.name(key.toString())
                    writeValue(json, item)
                }
                json.endObject()
            }
            else -> json.value(value.toString())
        }
    }

    /**
     * True when [writeValue] has an explicit branch for this value, so nothing reaches the
     * `toString()` fallback. A `null` entry counts as writable because the details objects may
     * legitimately carry null, for example `error_code`.
     */
    fun isWritableDetailValue(value: Any?): Boolean = when (value) {
        null -> true
        is String, is Int, is Long, is Boolean, is Double -> true
        is SegmentSnapshot -> true
        is List<*> -> value.all { isWritableDetailValue(it) }
        is Map<*, *> -> value.values.all { isWritableDetailValue(it) }
        else -> false
    }

    fun integrityWire(integrity: DataIntegrity): String = integrity.wire
}
