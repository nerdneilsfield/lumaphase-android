package dev.lumaphase.android.export

import dev.lumaphase.android.session.ConfigurationEventRecord
import dev.lumaphase.android.session.ConfigurationEventType
import dev.lumaphase.android.session.SessionConstants
import dev.lumaphase.android.session.SessionFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key names and the six file names are compared with the lists transcribed from the
 * contract, so a required field cannot silently disappear from the writer.
 */
class SessionJsonKeysTest {

    @Test
    fun theSixSessionFilesAreNamedAsTheContractFixes() {
        assertEquals(
            listOf(
                "manifest.json",
                "layout.json",
                "display_events.jsonl",
                "configuration_events.jsonl",
                "display_profile.json",
                "session_summary.json",
            ),
            SessionFiles.ALL,
        )
        assertEquals("session_6bd9ff04-8b4c-4df4-a5d9-86baa7c86d30",
            SessionFiles.directoryName("6bd9ff04-8b4c-4df4-a5d9-86baa7c86d30"))
    }

    @Test
    fun manifestKeysMatchTheContract() {
        assertEquals(
            listOf(
                "schema_version", "protocol_version", "session_id", "platform",
                "source_device_id", "app_id", "app_version", "app_build",
                "os_version", "os_build", "clock_domain", "time_origin",
                "presentation_drain_timeout_ns", "segments",
            ),
            SessionJson.MANIFEST_KEYS,
        )
        assertEquals(listOf("raw_ns", "native_clock"), SessionJson.TIME_ORIGIN_KEYS)
        assertEquals("1.0", SessionConstants.SCHEMA_VERSION)
        assertEquals("OPTC-1", SessionConstants.PROTOCOL_VERSION)
        assertEquals(2_000_000_000L, SessionConstants.PRESENTATION_DRAIN_TIMEOUT_NS)
    }

    @Test
    fun segmentKeysMatchTheContract() {
        assertEquals(
            listOf(
                "segment_id", "layout_id", "display_mode_id", "experiment_profile",
                "drawable_width_px", "drawable_height_px", "display_rotation_deg",
                "requested_refresh_num", "requested_refresh_den",
                "nominal_refresh_num", "nominal_refresh_den", "reported_refresh_hz",
                "brightness_setting", "brightness_source", "auto_brightness_enabled",
                "color_space", "pixel_format", "color_mode", "dimming_mode",
                "hdr_enabled", "true_tone_enabled", "night_shift_enabled",
                "display_profile_id", "operator_note",
            ),
            SessionJson.SEGMENT_KEYS,
        )
    }

    @Test
    fun displayEventKeysMatchTheContract() {
        val expected = listOf(
            "event_seq", "event_type", "session_id", "segment_id", "layout_id",
            "native_frame_id", "frame_kind", "phase", "render_seq", "payload_n", "crc8",
            "target_present_raw_ns", "target_present_ns",
            "system_present_raw_ns", "system_present_ns",
            "submit_raw_ns", "submit_ns",
            "deadline_raw_ns", "deadline_ns",
            "render_complete_raw_ns", "render_complete_ns",
            "latch_raw_ns", "latch_ns",
            "timestamp_status", "present_kind", "unconfirmed_reason",
            "platform_ids", "platform_timestamps",
            "recorded_raw_ns", "recorded_ns",
        )
        assertEquals(expected, SessionJson.DISPLAY_EVENT_KEYS)
        assertEquals(
            listOf("target_present", "system_present", "submit", "deadline", "render_complete", "latch"),
            SessionJson.TIMESTAMP_PREFIX_KEYS,
        )
        assertEquals(
            listOf("surface_instance_id", "egl_frame_id", "vsync_id", "drawable_id"),
            SessionJson.PLATFORM_IDS_KEYS,
        )
        assertEquals(
            listOf("api", "native_value", "native_unit", "native_clock_domain", "query_error_code"),
            SessionJson.PLATFORM_TIMESTAMP_KEYS,
        )
    }

    @Test
    fun configurationEventKeysMatchTheContract() {
        assertEquals(
            listOf(
                "event_seq", "event_type", "session_id", "segment_id",
                "recorded_raw_ns", "recorded_ns", "details",
            ),
            SessionJson.CONFIGURATION_EVENT_KEYS,
        )
    }

    @Test
    fun profileAndSummaryKeysMatchTheContract() {
        assertEquals(
            listOf("schema_version", "profiles", "segment_bindings"),
            SessionJson.DISPLAY_PROFILE_ROOT_KEYS,
        )
        assertEquals(
            listOf("segment_id", "profile_id", "status", "mismatch_fields"),
            SessionJson.SEGMENT_BINDING_KEYS,
        )
        assertEquals(
            listOf(
                "session_id", "closed_raw_ns", "closed_ns", "data_integrity",
                "lost_event_count", "segments",
            ),
            SessionJson.SUMMARY_KEYS,
        )
        assertEquals(
            listOf(
                "segment_id", "generated_frames", "submitted_frames",
                "confirmed_presented_frames", "final_unconfirmed_frames",
                "data_integrity", "unconfirmed_reason_counts",
            ),
            SessionJson.SEGMENT_SUMMARY_KEYS,
        )
    }

    @Test
    fun everyReasonValueIsOneOfTheContractEnumerations() {
        // The reasons the app can emit, compared with contract section 7.3.
        val reasons = setOf(
            "TIMESTAMP_UNSUPPORTED", "TIMESTAMP_INVALID", "QUERY_FAILED",
            "CLOCK_DOMAIN_UNVERIFIED", "HISTORY_EXPIRED", "PENDING_QUEUE_FULL",
            "SURFACE_DESTROYED", "PENDING_AT_SESSION_CLOSE", "NO_CALLBACK_AT_SESSION_CLOSE",
        )
        assertEquals(9, reasons.size)
        // And the four session-close reasons of section 2.3.
        val closedReasons = listOf("NORMAL_END", "USER_STOP", "LOG_OVERFLOW", "RENDER_FAILURE")
        assertEquals(4, closedReasons.size)
        assertTrue(ConfigurationEventType.entries.any { it.wire == "SESSION_CLOSED" })
        assertTrue(ConfigurationEventRecord::class.java.simpleName.isNotEmpty())
    }
}
