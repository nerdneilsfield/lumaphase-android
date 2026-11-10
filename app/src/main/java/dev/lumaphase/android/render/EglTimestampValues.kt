package dev.lumaphase.android.render

import dev.lumaphase.android.timing.TimestampStatus
import dev.lumaphase.android.timing.UnconfirmedReason

/**
 * Tokens, sentinels and status mapping for `EGL_ANDROID_get_frame_timestamps`.
 *
 * Everything here is taken from the extension text rather than from memory:
 * <https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_get_frame_timestamps.txt>
 *
 * - tokens: `EGL_TIMESTAMPS_ANDROID 0x3430`, `EGL_RENDERING_COMPLETE_TIME_ANDROID 0x3435`,
 *   `EGL_COMPOSITION_LATCH_TIME_ANDROID 0x3436`, `EGL_DISPLAY_PRESENT_TIME_ANDROID 0x343A`
 * - sentinels: `EGL_TIMESTAMP_PENDING_ANDROID -2`, `EGL_TIMESTAMP_INVALID_ANDROID -1`, with
 *   `EGLnsecsANDROID` a 64-bit signed nanosecond count
 * - `EGL_BAD_ACCESS` is reported when the queried timestamp history no longer exists
 *
 * The extension says only that the values are nanoseconds; it never identifies their clock.
 * A real value is therefore [TimestampStatus.CLOCK_DOMAIN_UNVERIFIED] and no standard or
 * relative time is derived from it. Zero is deliberately not treated as a sentinel.
 */
object EglTimestampValues {
    const val TIMESTAMPS_ANDROID = 0x3430
    const val RENDERING_COMPLETE_TIME_ANDROID = 0x3435
    const val COMPOSITION_LATCH_TIME_ANDROID = 0x3436
    const val DISPLAY_PRESENT_TIME_ANDROID = 0x343A

    const val TIMESTAMP_PENDING_ANDROID = -2L
    const val TIMESTAMP_INVALID_ANDROID = -1L

    /** `EGL_BAD_ACCESS`, the extension's "timestamp history no longer exists" error. */
    const val EGL_BAD_ACCESS = 0x3002

    fun statusOf(supported: Boolean, querySucceeded: Boolean, nativeValue: Long): TimestampStatus = when {
        !supported -> TimestampStatus.UNSUPPORTED
        !querySucceeded -> TimestampStatus.QUERY_FAILED
        nativeValue == TIMESTAMP_PENDING_ANDROID -> TimestampStatus.PENDING
        nativeValue == TIMESTAMP_INVALID_ANDROID -> TimestampStatus.INVALID
        else -> TimestampStatus.CLOCK_DOMAIN_UNVERIFIED
    }

    /**
     * Status for a field that can never be queried for a frame, for example because no EGL
     * frame id was obtained. A supported type is `QUERY_FAILED`, not `PENDING`: no later
     * callback can ever query it, so leaving it pending would be untrue. An unsupported type
     * stays `UNSUPPORTED`.
     */
    fun unqueryableStatus(supported: Boolean): TimestampStatus =
        if (supported) TimestampStatus.QUERY_FAILED else TimestampStatus.UNSUPPORTED

    /** The `unconfirmed_reason` implied by a settled field status, or null when there is none. */
    fun reasonOf(status: TimestampStatus): String? = when (status) {
        TimestampStatus.UNSUPPORTED -> UnconfirmedReason.TIMESTAMP_UNSUPPORTED
        TimestampStatus.INVALID -> UnconfirmedReason.TIMESTAMP_INVALID
        TimestampStatus.QUERY_FAILED -> UnconfirmedReason.QUERY_FAILED
        TimestampStatus.CLOCK_DOMAIN_UNVERIFIED -> UnconfirmedReason.CLOCK_DOMAIN_UNVERIFIED
        else -> null
    }
}
