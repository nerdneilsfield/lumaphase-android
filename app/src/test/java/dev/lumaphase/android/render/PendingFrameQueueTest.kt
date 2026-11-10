package dev.lumaphase.android.render

import dev.lumaphase.android.timing.FrameIdentity
import dev.lumaphase.android.timing.VsyncTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Queue behaviour of the bounded pending-frame store: fixed capacity, submission order, and
 * no silent loss when frames are dropped or drained.
 */
class PendingFrameQueueTest {

    @Test
    fun fixedUpperBoundIsOneHundredAndTwentyEight() {
        assertEquals(128, PendingFrameQueue.DEFAULT_CAPACITY)
        assertEquals(128, PendingFrameQueue().capacity)
    }

    @Test
    fun addSucceedsUntilFullAndThenReportsFailure() {
        val queue = PendingFrameQueue(capacity = 3)
        assertTrue(queue.add(frame(1)))
        assertTrue(queue.add(frame(2)))
        assertTrue(queue.add(frame(3)))
        assertEquals(3, queue.size)

        assertFalse("a submission that cannot be tracked must be reported", queue.add(frame(4)))
        assertEquals(3, queue.size)
    }

    @Test
    fun framesKeepSubmissionOrderOldestFirst() {
        val queue = PendingFrameQueue(capacity = 4)
        listOf(10, 11, 12).forEach { queue.add(frame(it)) }
        assertEquals(listOf("10", "11", "12"), queue.frames().map { it.identity.nativeFrameId })
    }

    @Test
    fun removingASettledFrameLeavesTheOthers() {
        val queue = PendingFrameQueue(capacity = 4)
        val first = frame(1)
        val second = frame(2)
        queue.add(first)
        queue.add(second)

        assertTrue(queue.remove(first))
        assertEquals(listOf(second), queue.frames())
    }

    @Test
    fun clearReturnsEverythingSoDrainedFramesCanBeReported() {
        val queue = PendingFrameQueue(capacity = 4)
        val added = listOf(frame(1), frame(2), frame(3))
        added.forEach { queue.add(it) }

        assertEquals(added, queue.clear())
        assertEquals(0, queue.size)
        assertTrue(queue.frames().isEmpty())
    }

    @Test
    fun aStoredFrameKeepsTheIdentityCapturedAtSubmit() {
        val queue = PendingFrameQueue(capacity = 4)
        val submitted = frame(7)
        queue.add(submitted)

        val tracked = queue.frames().single()
        assertEquals(submitted.identity, tracked.identity)
        assertEquals(submitted.identity.runId, tracked.identity.runId)
        assertEquals(submitted.identity.surfaceInstanceId, tracked.identity.surfaceInstanceId)
        assertEquals(submitted.identity.renderSeq, tracked.identity.renderSeq)
        assertEquals(submitted.identity.layoutId, tracked.identity.layoutId)
        assertEquals(submitted.eglFrameId, tracked.eglFrameId)
        assertEquals(submitted.timeline, tracked.timeline)
        assertEquals(submitted.submittedNanos, tracked.submittedNanos)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveCapacity() {
        PendingFrameQueue(capacity = 0)
    }

    private fun frame(index: Int) = PendingFrame(
        identity = FrameIdentity(
            runId = "run-1",
            surfaceInstanceId = "surface-1",
            nativeFrameId = index.toString(),
            renderSeq = index.toULong(),
            layoutId = "layout-0",
        ),
        eglFrameId = index.toLong(),
        timeline = VsyncTimeline(
            vsyncId = index.toLong(),
            expectedPresentTimeNanos = 1_000_000_000L + index,
            deadlineNanos = 2_000_000_000L + index,
        ),
        submittedNanos = 3_000_000_000L + index,
        lastFields = emptyMap(),
    )
}
