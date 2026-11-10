package dev.lumaphase.android.session

import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A record that can be written to one of the two JSONL logs. Which log is decided by the
 * concrete type, so the writer needs no registry or extra bookkeeping.
 */
sealed interface SessionRecord {
    val eventSeq: Long
    val sessionId: String
    val segmentId: String?
    val recordedRawNs: Long
    val recordedNs: Long

    /** The file this record belongs to, one of [SessionFiles]. */
    val targetFile: String
}

/** How much was lost from one log, and from which sequence range. */
data class LogLoss(
    val affectedLog: String,
    val firstLostEventSeq: Long,
    val lastLostEventSeq: Long,
    val lostEventCount: Long,
    val affectedSegmentIds: List<String>,
)

/**
 * Bounded event queue with background file writing, as contract section 10 requires.
 *
 * The render thread only offers records to a fixed-capacity queue; it never waits for disk and
 * never writes a file. A record that cannot be queued is counted and latched per log, in state
 * that lives outside the queue, so the latch survives a full queue. A failed file write is a
 * different failure: it is latched separately, counted, and reported through [writeFailure],
 * and it stops the writer instead of silently dropping everything that follows.
 *
 * The two events that close a session are queued on a small fixed tail so they are written
 * even if the bounded queue is still full; the tail itself never grows past that fixed set.
 */
class SessionLog(
    private val directory: File,
    private val serialize: (SessionRecord) -> String,
    private val capacity: Int = DEFAULT_QUEUE_CAPACITY,
) {
    private val queue = ArrayBlockingQueue<SessionRecord>(capacity)
    private val tail = ArrayDeque<SessionRecord>()
    private val displayEventSeq = AtomicLong(0)
    private val configurationEventSeq = AtomicLong(0)
    private val overflowed = AtomicBoolean(false)
    private val lostCount = AtomicLong(0)
    private val displayLost = AtomicLong(0)
    private val configurationLost = AtomicLong(0)
    private val displayLostFirst = AtomicLong(Long.MAX_VALUE)
    private val displayLostLast = AtomicLong(-1)
    private val configurationLostFirst = AtomicLong(Long.MAX_VALUE)
    private val configurationLostLast = AtomicLong(-1)
    private val lostSegments = LinkedHashSet<String>()
    private val lostSegmentsPerLog = LinkedHashMap<String, LinkedHashSet<String>>()

    @Volatile
    private var writeFailureMessage: String? = null

    @Volatile
    private var writeFailureCount: Long = 0

    @Volatile
    private var running = false

    private var writerThread: Thread? = null
    private var displayWriter: BufferedWriter? = null
    private var configurationWriter: BufferedWriter? = null

    val hasOverflowed: Boolean get() = overflowed.get()

    val lostEventCount: Long get() = lostCount.get()

    /** Non-null when a file write or the writer's close failed; the package is not complete. */
    val writeFailure: String? get() = writeFailureMessage

    /** Records that could not even be handed to the writer because it had already failed. */
    val lostAfterWriteFailure: Long get() = writeFailureCount

    fun nextDisplayEventSeq(): Long = displayEventSeq.getAndIncrement()

    fun nextConfigurationEventSeq(): Long = configurationEventSeq.getAndIncrement()

    fun start() {
        if (running) return
        running = true
        directory.mkdirs()
        try {
            displayWriter = openWriter(SessionFiles.DISPLAY_EVENTS)
            configurationWriter = openWriter(SessionFiles.CONFIGURATION_EVENTS)
        } catch (error: IOException) {
            // Not being able to open the files is the same class of failure as not being able
            // to write them: the session cannot be exported as complete. If the second file
            // failed to open, the first is already open and must be closed here.
            closeQuietly()
            writeFailureMessage = "opening the session files failed: ${error.message}"
            running = false
            return
        }
        writerThread = Thread({ writeLoop() }, "optc-session-writer").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Non-blocking. Returns false when the record could not be retained: either the queue was
     * full or the writer had already failed. Both cases are counted and latched.
     */
    fun offer(record: SessionRecord): Boolean {
        if (writeFailureMessage != null) {
            // The writer is gone, so accepting this record would lose it silently.
            writeFailureCount++
            lostCount.incrementAndGet()
            return false
        }
        if (queue.offer(record)) return true
        latchLoss(record)
        return false
    }

    /**
     * Queues a record that must be written even when the bounded queue is full, used only for
     * the fixed set of closing diagnostics (at most two LOG_OVERFLOW events and one
     * SESSION_CLOSED). A full tail latches the loss like any other full queue, so this path
     * cannot grow without bound.
     */
    fun offerAtClose(record: SessionRecord) {
        val accepted = synchronized(tail) {
            if (tail.size < MAX_CLOSE_RECORDS) {
                tail.addLast(record)
                true
            } else {
                false
            }
        }
        if (!accepted) latchLoss(record)
    }

    /** The loss, per affected log, in the shape `LOG_OVERFLOW` needs. */
    fun losses(): List<LogLoss> {
        val losses = ArrayList<LogLoss>(2)
        if (displayLost.get() > 0) {
            losses += LogLoss(
                affectedLog = SessionFiles.DISPLAY_EVENTS,
                firstLostEventSeq = displayLostFirst.get(),
                lastLostEventSeq = displayLostLast.get(),
                lostEventCount = displayLost.get(),
                affectedSegmentIds = segmentsFor(SessionFiles.DISPLAY_EVENTS),
            )
        }
        if (configurationLost.get() > 0) {
            losses += LogLoss(
                affectedLog = SessionFiles.CONFIGURATION_EVENTS,
                firstLostEventSeq = configurationLostFirst.get(),
                lastLostEventSeq = configurationLostLast.get(),
                lostEventCount = configurationLost.get(),
                affectedSegmentIds = segmentsFor(SessionFiles.CONFIGURATION_EVENTS),
            )
        }
        return losses
    }

    /** Every segment that lost records, across both logs. */
    val firstOverflowSegmentIds: List<String>
        get() = synchronized(lostSegments) { lostSegments.toList() }

    /**
     * Stops accepting records, drains the queue and the closing tail, and closes both files.
     * Returns null on success, or the failure to report.
     *
     * A writer that does not finish within the join timeout is a failure like any other: the
     * writers stay owned by the writer thread, which is interrupted rather than closed from
     * this thread. Closing them concurrently with a write in progress would corrupt the very
     * files the package is built from; the failure is latched instead, so the sealed session is
     * INCOMPLETE and the export is refused. A thread that never returns leaves its descriptors
     * open until the process ends, which is why it is a daemon thread.
     */
    fun closeAndDrain(): String? {
        running = false
        val thread = writerThread
        writerThread = null
        if (thread != null) {
            thread.join(WRITER_JOIN_TIMEOUT_MS)
            if (thread.isAlive) {
                writeFailureMessage = writeFailureMessage
                    ?: "the session writer thread did not finish within ${WRITER_JOIN_TIMEOUT_MS}ms"
                thread.interrupt()
            }
        }
        return writeFailureMessage
    }

    private fun segmentsFor(log: String): List<String> =
        synchronized(lostSegmentsPerLog) { lostSegmentsPerLog[log]?.toList().orEmpty() }

    private fun latchLoss(record: SessionRecord) {
        overflowed.set(true)
        lostCount.incrementAndGet()
        when (record) {
            is DisplayEventRecord -> {
                displayLost.incrementAndGet()
                displayLostFirst.updateAndGet { minOf(it, record.eventSeq) }
                displayLostLast.updateAndGet { maxOf(it, record.eventSeq) }
            }

            is ConfigurationEventRecord -> {
                configurationLost.incrementAndGet()
                configurationLostFirst.updateAndGet { minOf(it, record.eventSeq) }
                configurationLostLast.updateAndGet { maxOf(it, record.eventSeq) }
            }
        }
        record.segmentId?.let { segment ->
            synchronized(lostSegments) { lostSegments.add(segment) }
            synchronized(lostSegmentsPerLog) {
                lostSegmentsPerLog.getOrPut(record.targetFile) { LinkedHashSet() }.add(segment)
            }
        }
    }

    private fun writeLoop() {
        while (true) {
            val record = try {
                queue.poll(POLL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                // closeAndDrain gave up waiting and asked the writer to stop. Leave the loop so
                // the final drain and close below still run; the interrupt itself must not skip
                // closing the files.
                break
            }
            if (record != null) {
                if (!write(record)) break
                continue
            }
            val tailRecord = synchronized(tail) { tail.removeFirstOrNull() }
            if (tailRecord != null) {
                if (!write(tailRecord)) break
                continue
            }
            if (!running) break
        }
        // Drain whatever is left so nothing already accepted is lost.
        var remaining = queue.poll()
        while (remaining != null) {
            if (!write(remaining)) break
            remaining = queue.poll()
        }
        while (true) {
            val tailRecord = synchronized(tail) { tail.removeFirstOrNull() } ?: break
            if (!write(tailRecord)) break
        }
        closeQuietly()
    }

    private fun write(record: SessionRecord): Boolean {
        val target = when (record) {
            is DisplayEventRecord -> displayWriter
            is ConfigurationEventRecord -> configurationWriter
        } ?: return true
        return try {
            target.write(serialize(record))
            target.write("\n")
            true
        } catch (error: IOException) {
            // A write failure is separate from queue overflow and stops the writer: the log can
            // no longer be trusted, so the session is marked incomplete and cannot be exported
            // as complete. Records already accepted but not yet written are counted in
            // [recordsLostAfterWriteFailure].
            writeFailureMessage = "writing ${record.targetFile} failed: ${error.message}"
            false
        }
    }

    /** Records that were accepted but never written because the file write failed. */
    val recordsLostAfterWriteFailure: Long
        get() = writeFailureCount + queue.size + synchronized(tail) { tail.size }.toLong()

    private fun openWriter(fileName: String): BufferedWriter =
        File(directory, fileName).writer(StandardCharsets.UTF_8).buffered()

    private fun closeQuietly() {
        try {
            displayWriter?.flush()
            displayWriter?.close()
        } catch (error: IOException) {
            writeFailureMessage = writeFailureMessage
                ?: "closing ${SessionFiles.DISPLAY_EVENTS} failed: ${error.message}"
        }
        try {
            configurationWriter?.flush()
            configurationWriter?.close()
        } catch (error: IOException) {
            writeFailureMessage = writeFailureMessage
                ?: "closing ${SessionFiles.CONFIGURATION_EVENTS} failed: ${error.message}"
        }
        displayWriter = null
        configurationWriter = null
    }


    companion object {
        /**
         * Fixed upper bound on queued records. It is not derived from the frame rate: when it
         * is reached the loss is latched and reported, and the session is marked incomplete.
         */
        const val DEFAULT_QUEUE_CAPACITY = 4096

        /**
         * Upper bound on the close-only tail. Two LOG_OVERFLOW events (one per log) plus
         * SESSION_CLOSED are the whole fixed set; a larger set is a bug, so the bound turns it
         * into a counted loss instead of unbounded memory.
         */
        private const val MAX_CLOSE_RECORDS = 4

        private const val POLL_TIMEOUT_MS = 20L
        private const val WRITER_JOIN_TIMEOUT_MS = 5_000L
    }
}
