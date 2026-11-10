package dev.lumaphase.android.render

import dev.lumaphase.android.timing.FrameIdentity
import dev.lumaphase.android.timing.TimestampField
import dev.lumaphase.android.timing.TimestampPrefix
import dev.lumaphase.android.timing.VsyncTimeline

/**
 * One submitted frame that is still waiting for a presentation result.
 *
 * The identity is captured at submit time and never re-derived, so a result that arrives
 * after the surface was rebuilt still belongs to the frame that was actually submitted.
 * [lastFields] is the most recent per-field observation and is written only on the render
 * thread.
 */
data class PendingFrame(
    val identity: FrameIdentity,
    val eglFrameId: Long,
    val timeline: VsyncTimeline,
    val submittedNanos: Long,
    var lastFields: Map<TimestampPrefix, TimestampField>,
)

/**
 * Fixed-capacity queue of frames still waiting for a presentation result.
 *
 * The render thread never waits for a single frame to settle and never grows the queue: it
 * queries whatever is queued on each callback and drops nothing silently. [add] returns
 * false when the queue is full, which the caller reports as `PENDING_QUEUE_FULL`; [clear]
 * returns everything still queued so the caller can report `SURFACE_DESTROYED` per frame.
 *
 * Only the render thread touches this queue, so no locking is used.
 */
class PendingFrameQueue(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "pending queue capacity must be positive, got $capacity" }
    }

    private val frames = ArrayDeque<PendingFrame>(capacity)

    val size: Int
        get() = frames.size

    fun add(frame: PendingFrame): Boolean {
        if (frames.size >= capacity) return false
        frames.addLast(frame)
        return true
    }

    /** Frames in submission order, oldest first. */
    fun frames(): List<PendingFrame> = frames.toList()

    fun remove(frame: PendingFrame): Boolean = frames.remove(frame)

    /** Empties the queue and returns everything that was in it, oldest first. */
    fun clear(): List<PendingFrame> {
        val drained = frames.toList()
        frames.clear()
        return drained
    }

    companion object {
        /** Fixed upper bound: at 120 Hz this covers about one second of submissions. */
        const val DEFAULT_CAPACITY = 128
    }
}
