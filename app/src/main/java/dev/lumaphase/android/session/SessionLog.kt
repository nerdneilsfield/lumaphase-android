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
 * The two events that close a session are queued on an unbounded tail so they are written even
 * if the bounded queue is still full.
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

    /** Non-null when a file write failed; the session can no longer be exported as complete. */
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
            // to write them: the session cannot be exported as complete.
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
     * Queues a record that must be written even when the bounded queue is full, used for the
     * events that close the session. The tail is unbounded on purpose: it holds at most a
     * couple of records per session and is drained before the files are closed.
     */
    fun offerAtClose(record: SessionRecord) {
        synchronized(tail) { tail.addLast(record) }
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
     */
    fun closeAndDrain(): String? {
        running = false
        val thread = writerThread
        writerThread = null
        thread?.join(WRITER_JOIN_TIMEOUT_MS)
        if (thread != null && thread.isAlive) {
            thread.interrupt()
            closeQuietly()
            return "the session writer thread did not finish within ${WRITER_JOIN_TIMEOUT_MS}ms"
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
            val record = queue.poll(POLL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
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

        private const val POLL_TIMEOUT_MS = 20L
        private const val WRITER_JOIN_TIMEOUT_MS = 5_000L
    }
}
