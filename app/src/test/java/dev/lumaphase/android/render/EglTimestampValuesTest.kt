package dev.lumaphase.android.render

import dev.lumaphase.android.render.EglTimestampValues.TIMESTAMP_INVALID_ANDROID
import dev.lumaphase.android.render.EglTimestampValues.TIMESTAMP_PENDING_ANDROID
import dev.lumaphase.android.timing.TimestampStatus
import dev.lumaphase.android.timing.UnconfirmedReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The mapping from a native EGL timestamp result to a contract status, exercised with the
 * documented sentinels and with values that must not be mistaken for them.
 */
class EglTimestampValuesTest {

    @Test
    fun anUnsupportedTypeIsNeverQueried() {
        assertEquals(
            TimestampStatus.UNSUPPORTED,
            EglTimestampValues.statusOf(supported = false, querySucceeded = true, nativeValue = 12_345L),
        )
        assertEquals(
            TimestampStatus.UNSUPPORTED,
            EglTimestampValues.statusOf(supported = false, querySucceeded = false, nativeValue = 0L),
        )
    }

    @Test
    fun aFailedQueryNeverProducesAUsableValue() {
        assertEquals(
            TimestampStatus.QUERY_FAILED,
            EglTimestampValues.statusOf(true, querySucceeded = false, nativeValue = 12_345L),
        )
        assertEquals(
            TimestampStatus.QUERY_FAILED,
            EglTimestampValues.statusOf(true, querySucceeded = false, nativeValue = TIMESTAMP_PENDING_ANDROID),
        )
    }

    @Test
    fun documentedSentinelsBecomePendingAndInvalid() {
        assertEquals(
            TimestampStatus.PENDING,
            EglTimestampValues.statusOf(true, true, TIMESTAMP_PENDING_ANDROID),
        )
        assertEquals(
            TimestampStatus.INVALID,
            EglTimestampValues.statusOf(true, true, TIMESTAMP_INVALID_ANDROID),
        )
    }

    @Test
    fun zeroAndOtherValuesAreNotTreatedAsSentinels() {
        assertEquals(TimestampStatus.CLOCK_DOMAIN_UNVERIFIED, EglTimestampValues.statusOf(true, true, 0L))
        assertEquals(
            TimestampStatus.CLOCK_DOMAIN_UNVERIFIED,
            EglTimestampValues.statusOf(true, true, 1_234_567_890L),
        )
        assertEquals(
            TimestampStatus.CLOCK_DOMAIN_UNVERIFIED,
            EglTimestampValues.statusOf(true, true, Long.MIN_VALUE),
        )
    }

    @Test
    fun aRealValueIsNeverClaimedAsValid() {
        // The extension states nanoseconds but never the clock, so T3 cannot issue VALID.
        assertNotEquals(TimestampStatus.VALID, EglTimestampValues.statusOf(true, true, 42L))
    }

    @Test
    fun aFrameThatCanNeverBeQueriedIsNotLeftPending() {
        assertEquals(
            TimestampStatus.QUERY_FAILED,
            EglTimestampValues.unqueryableStatus(supported = true),
        )
        assertNotEquals(
            TimestampStatus.PENDING,
            EglTimestampValues.unqueryableStatus(supported = true),
        )
        assertEquals(
            TimestampStatus.UNSUPPORTED,
            EglTimestampValues.unqueryableStatus(supported = false),
        )
    }

    @Test
    fun reasonForEachSettledStatus() {
        assertEquals(UnconfirmedReason.TIMESTAMP_UNSUPPORTED, EglTimestampValues.reasonOf(TimestampStatus.UNSUPPORTED))
        assertEquals(UnconfirmedReason.TIMESTAMP_INVALID, EglTimestampValues.reasonOf(TimestampStatus.INVALID))
        assertEquals(UnconfirmedReason.QUERY_FAILED, EglTimestampValues.reasonOf(TimestampStatus.QUERY_FAILED))
        assertEquals(
            UnconfirmedReason.CLOCK_DOMAIN_UNVERIFIED,
            EglTimestampValues.reasonOf(TimestampStatus.CLOCK_DOMAIN_UNVERIFIED),
        )
        assertNull(EglTimestampValues.reasonOf(TimestampStatus.PENDING))
        assertNull(EglTimestampValues.reasonOf(TimestampStatus.VALID))
    }
}
