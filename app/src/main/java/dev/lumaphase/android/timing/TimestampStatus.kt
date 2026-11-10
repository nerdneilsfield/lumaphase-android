package dev.lumaphase.android.timing

/**
 * Per-field validity of one standard timestamp, exactly as fixed by OPTC-1 section 6.2.
 *
 * `VALID` means the field's raw and relative nanosecond values are both present. Every
 * other status means no standard time is claimed for that field.
 */
enum class TimestampStatus {
    VALID,
    PENDING,
    INVALID,
    UNSUPPORTED,
    NOT_APPLICABLE,
    QUERY_FAILED,
    CLOCK_DOMAIN_UNVERIFIED,
}

/**
 * The standard timestamp field prefixes of OPTC-1 section 6.2.
 *
 * In T3 only `target_present`, `deadline` and `submit` come from Android APIs that are
 * already in the session's clock domain; the three EGL-backed fields stay at
 * [TimestampStatus.CLOCK_DOMAIN_UNVERIFIED] because the EGL specification states only that
 * the values are nanoseconds and never identifies their clock.
 */
enum class TimestampPrefix(val key: String) {
    TARGET_PRESENT("target_present"),
    SYSTEM_PRESENT("system_present"),
    SUBMIT("submit"),
    DEADLINE("deadline"),
    RENDER_COMPLETE("render_complete"),
    LATCH("latch"),
}

/**
 * `unconfirmed_reason` values of OPTC-1 section 7.3. T3 uses the first six plus
 * [SURFACE_DESTROYED]; the session-close reasons belong to T4.
 */
object UnconfirmedReason {
    const val TIMESTAMP_UNSUPPORTED = "TIMESTAMP_UNSUPPORTED"
    const val TIMESTAMP_INVALID = "TIMESTAMP_INVALID"
    const val QUERY_FAILED = "QUERY_FAILED"
    const val CLOCK_DOMAIN_UNVERIFIED = "CLOCK_DOMAIN_UNVERIFIED"
    const val HISTORY_EXPIRED = "HISTORY_EXPIRED"
    const val PENDING_QUEUE_FULL = "PENDING_QUEUE_FULL"
    const val SURFACE_DESTROYED = "SURFACE_DESTROYED"
    const val PENDING_AT_SESSION_CLOSE = "PENDING_AT_SESSION_CLOSE"
    const val NO_CALLBACK_AT_SESSION_CLOSE = "NO_CALLBACK_AT_SESSION_CLOSE"
}
