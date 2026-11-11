package dev.lumaphase.android.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Request lifecycle oracle for the same production state used by CameraController. */
class FreezeCaptureStateTest {
    @Test
    fun requestThenDuplicateKeepsTheFirstReservedFrameAlive() {
        val state = FreezeCaptureState()
        val first = state.reserve(null)
        checkNotNull(first)
        assertNull("duplicate must not replace or cancel the first request", state.reserve(null))
        assertTrue(state.markReady(first))
        assertSame(first, state.take())
        assertTrue("after take the next request slot is free", state.reserve(null) != null)
    }

    @Test
    fun switchCancelsAnUnconsumedRequestSoALateImageDoesNotFreeze() {
        val state = FreezeCaptureState()
        val first = state.reserve(null)
        checkNotNull(first)
        state.cancel()
        assertNull("cancelled request must not consume a late image", state.take())
        assertFalse(state.isActive(first))
    }

    @Test
    fun aNewRequestAfterSwitchSucceedsAndRejectsTheOldRequest() {
        val state = FreezeCaptureState()
        val first = state.reserve(null)
        checkNotNull(first)
        state.cancel()
        val second = state.reserve(null)
        checkNotNull(second)
        assertTrue(state.markReady(second))
        assertFalse(state.isActive(first))
        assertSame(second, state.take())
    }

    @Test
    fun aReservedButNotReadyRequestDoesNotConsumeTheNextImage() {
        val state = FreezeCaptureState()
        val request = state.reserve(null)
        checkNotNull(request)
        assertNull("the request is not ready until stopLiveForFreeze completes", state.take())
        assertTrue(state.markReady(request))
        assertSame(request, state.take())
    }
}
