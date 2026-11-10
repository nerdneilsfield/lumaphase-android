package dev.lumaphase.android.session

import dev.lumaphase.android.timing.DisplayStart
import dev.lumaphase.android.timing.FrameIdentity
import dev.lumaphase.android.timing.FrameKind
import dev.lumaphase.android.timing.FrameResult
import dev.lumaphase.android.timing.FrameSubmission
import dev.lumaphase.android.timing.TimestampField
import dev.lumaphase.android.timing.TimestampPrefix
import dev.lumaphase.android.timing.TimestampStatus
import dev.lumaphase.android.timing.UnconfirmedReason
import dev.lumaphase.android.timing.VsyncTimeline
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A representative session driven entirely on the host: the real [SessionController] with an
 * injected clock, real [SessionLog] files in a temporary directory and a test serializer.
 *
 * The four sealed documents are written by Android's `JsonWriter`, which cannot run in a JVM
 * unit test, so this verifies the records, the printed models and every relation between
 * them; the document bytes are only produced on a device.
 */
class SessionControllerTest {

    @Test
    fun aRepresentativeSessionRecordsTheContractRelations() {
        val directory = Files.createTempDirectory("optc-session").toFile()
        val records = ArrayList<SessionRecord>()
        val log = SessionLog(directory, serialize = { record -> records += record; line(record) })
        var now = 1_000_000_000L
        val controller = SessionController(
            sessionId = "6bd9ff04-8b4c-4df4-a5d9-86baa7c86d30",
            environment = environment(),
            eventLog = log,
            clock = { now },
        )
        controller.updateSegmentEnvironment(segmentEnvironment())
        controller.registerLayout(layout("dynamic-layout-0", 1080, 2400))

        controller.startSession()
        controller.advancePhase(SessionPhase.WARMUP)
        controller.onDisplayStarted(displayStart("surface-1", "dynamic-layout-0"))

        // Warm-up: a stationary frame, no dynamic sequence and no payload.
        val warmup = submit(controller, kind = FrameKind.WARMUP, seq = null, frame = "1", now = now)
        now += 1_000_000
        controller.onResult(result(warmup, TimestampStatus.CLOCK_DOMAIN_UNVERIFIED))

        controller.advancePhase(SessionPhase.SESSION_CARD)
        val card = submit(controller, kind = FrameKind.SESSION_CARD, seq = null, frame = "2", now = now)
        now += 1_000_000
        controller.onResult(result(card, TimestampStatus.CLOCK_DOMAIN_UNVERIFIED))

        controller.advancePhase(SessionPhase.RUNNING)
        // Three dynamic states: one presented, one unconfirmed with a specific reason, one
        // that never resolves and must be reported at the seal.
        val presented = submit(controller, FrameKind.DYNAMIC, 0uL, "3", now)
        now += 1_000_000
        controller.onResult(result(presented, TimestampStatus.VALID))
        val unsupported = submit(controller, FrameKind.DYNAMIC, 1uL, "4", now)
        now += 1_000_000
        controller.onResult(result(unsupported, TimestampStatus.UNSUPPORTED))
        val neverResolved = submit(controller, FrameKind.DYNAMIC, 2uL, "5", now)
        now += 1_000_000

        controller.advancePhase(SessionPhase.END_CARD)
        val endCard = submit(controller, FrameKind.END_CARD, null, "6", now)
        now += 1_000_000
        controller.onResult(result(endCard, TimestampStatus.CLOCK_DOMAIN_UNVERIFIED))

        // A different geometry ends the segment and opens a new immutable layout identity.
        controller.endSegment(SegmentEndReason.CONFIGURATION_CHANGED)
        controller.registerLayout(layout("dynamic-layout-1", 1080, 2280))
        controller.advancePhase(SessionPhase.RUNNING)
        controller.onDisplayStarted(displayStart("surface-2", "dynamic-layout-1", drawableHeightPx = 2280))
        val secondSegmentFrame = submit(controller, FrameKind.DYNAMIC, 3uL, "7", now, surfaceId = "surface-2")
        now += 1_000_000
        controller.onResult(result(secondSegmentFrame, TimestampStatus.VALID))

        // A late result for the frame of the first segment: it must keep that segment and layout.
        controller.onResult(result(neverResolved, TimestampStatus.VALID))

        val sealed = controller.stopSession(SessionClosedReason.NORMAL_END)
        assertNotNull(sealed)
        sealed!!

        // -- manifest ---------------------------------------------------------------------
        assertEquals("1.0", sealed.manifest.schemaVersion)
        assertEquals("OPTC-1", sealed.manifest.protocolVersion)
        assertEquals("ANDROID", sealed.manifest.platform)
        assertEquals("ANDROID_MONOTONIC", sealed.manifest.clockDomain)
        assertEquals("System.nanoTime", sealed.manifest.timeOrigin.nativeClock)
        assertEquals(1_000_000_000L, sealed.manifest.timeOrigin.rawNs)
        assertEquals(2_000_000_000L, sealed.manifest.presentationDrainTimeoutNs)
        assertEquals(2, sealed.manifest.segments.size)
        assertEquals("0", sealed.manifest.segments[0].segmentId)
        assertEquals("1", sealed.manifest.segments[1].segmentId)
        assertEquals("dynamic-layout-0", sealed.manifest.segments[0].layoutId)
        assertEquals("dynamic-layout-1", sealed.manifest.segments[1].layoutId)
        assertEquals(1080, sealed.manifest.segments[0].drawableWidthPx)
        assertEquals(2400, sealed.manifest.segments[0].drawableHeightPx)
        assertEquals(2280, sealed.manifest.segments[1].drawableHeightPx)
        assertEquals("A120-S1", sealed.manifest.segments[0].experimentProfile)
        assertEquals("EGL_RGB_BUFFER/R8G8B8A0", sealed.manifest.segments[0].pixelFormat)
        // The nominal rational is not exposed by Android, so it stays null rather than faked.
        assertNull(sealed.manifest.segments[0].nominalRefreshNum)
        assertEquals(120, sealed.manifest.segments[0].requestedRefreshNum)
        assertEquals(1, sealed.manifest.segments[0].requestedRefreshDen)
        assertEquals(120.0, sealed.manifest.segments[0].reportedRefreshHz!!, 0.001)
        assertEquals(BrightnessSource.SYSTEM_REPORTED, sealed.manifest.segments[0].brightnessSource)
        // No calibration is imported: the reference is null, never invented.
        assertNull(sealed.manifest.segments[0].displayProfileId)

        // -- layouts ----------------------------------------------------------------------
        assertEquals(listOf("dynamic-layout-0", "dynamic-layout-1"), sealed.layouts.map { it.layoutId })

        // -- display profile: empty set plus MISSING bindings ------------------------------
        assertTrue(sealed.displayProfile.profiles.isEmpty())
        assertEquals(2, sealed.displayProfile.segmentBindings.size)
        assertTrue(sealed.displayProfile.segmentBindings.all { it.status == DisplayProfileStatus.MISSING })
        assertTrue(sealed.displayProfile.segmentBindings.all { it.profileId == null })

        // -- summary: counts merged by primary key -----------------------------------------
        assertEquals("COMPLETE", sealed.summary.dataIntegrity.wire)
        assertEquals(0L, sealed.summary.lostEventCount)
        assertEquals(2, sealed.summary.segments.size)
        val first = sealed.summary.segments[0]
        // Segment 0: warm-up, card, three dynamic and the end card were submitted.
        assertEquals(6, first.generatedFrames)
        assertEquals(6, first.submittedFrames)
        // Only the two frames whose system_present reached VALID are confirmed presented.
        assertEquals(2, first.confirmedPresentedFrames)
        // The warm-up, the card and the end card had a real EGL value whose clock domain is not
        // verified, so they stay unconfirmed; the unsupported dynamic frame does too.
        assertEquals(4, first.finalUnconfirmedFrames)
        assertEquals(3, first.unconfirmedReasonCounts[UnconfirmedReason.CLOCK_DOMAIN_UNVERIFIED])
        assertEquals(1, first.unconfirmedReasonCounts[UnconfirmedReason.TIMESTAMP_UNSUPPORTED])
        assertEquals("COMPLETE", first.dataIntegrity.wire)

        val second = sealed.summary.segments[1]
        assertEquals(1, second.generatedFrames)
        assertEquals(1, second.submittedFrames)
        assertEquals(1, second.confirmedPresentedFrames)
        assertEquals(0, second.finalUnconfirmedFrames)

        // -- events ------------------------------------------------------------------------
        val display = records.filterIsInstance<DisplayEventRecord>()
        // The SUBMITTED, PRESENTED and UNCONFIRMED rows are all present and ordered by seq.
        assertEquals(
            display.map { it.eventSeq },
            display.map { it.eventSeq }.sorted(),
        )
        val submitted = display.filter { it.eventType == DisplayEventType.SUBMITTED }
        assertEquals(7, submitted.size)
        assertTrue(submitted.all { it.presentKind == null })
        assertTrue(
            "only DYNAMIC frames carry a render_seq",
            submitted.all { it.frameKind == FrameKind.DYNAMIC || it.renderSeq == null },
        )
        assertEquals(
            "the dynamic sequence is printed as a decimal string",
            "0",
            submitted.first { it.frameKind == FrameKind.DYNAMIC }.renderSeq.toString(),
        )
        assertEquals(0, submitted.first { it.frameKind == FrameKind.DYNAMIC }.payloadN)
        // Kinds keep their own phase names.
        assertEquals("WARMUP", submitted.first { it.frameKind == FrameKind.WARMUP }.phase)
        assertEquals("RUNNING", submitted.first { it.frameKind == FrameKind.DYNAMIC }.phase)
        assertEquals("END_CARD", submitted.first { it.frameKind == FrameKind.END_CARD }.phase)
        // The late result for frame 5 stays in segment 0 with layout 0.
        val late = display.last { it.nativeFrameId == "5" && it.eventType == DisplayEventType.PRESENTED }
        assertEquals("0", late.segmentId)
        assertEquals("dynamic-layout-0", late.layoutId)
        assertEquals(PresentKind.SYSTEM_REPORTED, late.presentKind)
        // The unconfirmed row keeps its specific reason instead of a generic pending.
        val unconfirmed = display.single { it.unconfirmedReason == UnconfirmedReason.TIMESTAMP_UNSUPPORTED }
        assertEquals(DisplayEventType.UNCONFIRMED, unconfirmed.eventType)
        assertEquals(PresentKind.UNCONFIRMED, unconfirmed.presentKind)
        // An explicitly unsupported presentation timestamp keeps its own status.
        assertEquals(TimestampStatus.UNSUPPORTED, unconfirmed.statuses[TimestampPrefix.SYSTEM_PRESENT])
        assertNull(unconfirmed.timestamps[TimestampPrefix.SYSTEM_PRESENT]!!.rawNs)

        // A real EGL value whose clock domain is not verified keeps the native value but never
        // becomes a standard time.
        val domainUnverified = display.first {
            it.unconfirmedReason == UnconfirmedReason.CLOCK_DOMAIN_UNVERIFIED
        }
        assertEquals(
            TimestampStatus.CLOCK_DOMAIN_UNVERIFIED,
            domainUnverified.statuses[TimestampPrefix.SYSTEM_PRESENT],
        )
        assertNull(domainUnverified.timestamps[TimestampPrefix.SYSTEM_PRESENT]!!.rawNs)
        assertNull(domainUnverified.timestamps[TimestampPrefix.SYSTEM_PRESENT]!!.ns)
        assertEquals(
            "1500000000",
            domainUnverified.platformTimestamps[TimestampPrefix.SYSTEM_PRESENT.key]!!.nativeValue,
        )
        assertNull(domainUnverified.platformTimestamps[TimestampPrefix.SYSTEM_PRESENT.key]!!.nativeClockDomain)
        // The submission's own fields are in the session clock domain, so they do have both.
        val submitField = domainUnverified.timestamps[TimestampPrefix.SUBMIT]!!
        assertEquals(TimestampStatus.VALID, domainUnverified.statuses[TimestampPrefix.SUBMIT])
        assertNotNull(submitField.rawNs)
        assertEquals(submitField.rawNs!! - 1_000_000_000L, submitField.ns)
        // The platform entry keeps the raw value and its source.
        assertEquals(
            "System.nanoTime sampled before eglSwapBuffers",
            domainUnverified.platformTimestamps[TimestampPrefix.SUBMIT.key]!!.api,
        )
        // The presentation request is recorded separately from target_present.
        assertEquals(
            "eglPresentationTimeANDROID",
            domainUnverified.platformTimestamps["egl_presentation_request"]!!.api,
        )
        // platform_ids carry the surface and the vsync, and drawable_id is never faked.
        assertEquals("surface-1", domainUnverified.platformIds.surfaceInstanceId)
        assertNotNull(domainUnverified.platformIds.vsyncId)
        assertNull(domainUnverified.platformIds.drawableId)

        val configuration = records.filterIsInstance<ConfigurationEventRecord>()
        assertTrue(configuration.any { it.eventType == ConfigurationEventType.SEGMENT_STARTED })
        assertTrue(configuration.any { it.eventType == ConfigurationEventType.SEGMENT_ENDED })
        assertTrue(configuration.any { it.eventType == ConfigurationEventType.PHASE_CHANGED })
        val closed = configuration.last { it.eventType == ConfigurationEventType.SESSION_CLOSED }
        assertEquals("NORMAL_END", closed.details["reason"])
        assertEquals("COMPLETE", closed.details["data_integrity"])
        // Contract section 2.3: every submitted frame without a usable presentation result,
        // which here is the four unconfirmed frames.
        assertEquals(4, closed.details["pending_frame_count"])
        val started = configuration.first { it.eventType == ConfigurationEventType.SEGMENT_STARTED }
        val startedConfiguration = started.details["configuration"] as SegmentSnapshot
        assertEquals(startedConfiguration.segmentId, started.segmentId)
        assertEquals(SegmentStartReason.SESSION_START.wire, started.details["reason"])
        val phaseChanged = configuration.first { it.eventType == ConfigurationEventType.PHASE_CHANGED }
        assertNull(phaseChanged.details["previous_phase"])
        assertEquals("CONFIGURED", phaseChanged.details["phase"])

        // -- every details value must be handled by an explicit branch of the real writer -----
        // This is the host-side check for the writer/contract correspondence: a value without a
        // branch would silently reach the writer's toString() fallback. Android's JsonWriter
        // cannot run here (its methods throw "not mocked"), so the check is on the branch set.
        for (event in records.filterIsInstance<ConfigurationEventRecord>()) {
            for ((key, value) in event.details) {
                assertTrue(
                    "detail '$key' of ${event.eventType.wire} has no writer branch",
                    dev.lumaphase.android.export.SessionJson.isWritableDetailValue(value),
                )
            }
        }
        val snapshot = sealed.manifest.segments.first()
        assertTrue(
            "a segment snapshot must have its own writer branch",
            dev.lumaphase.android.export.SessionJson.isWritableDetailValue(snapshot),
        )
        // A value the writer does not model must be reported, not silently stringified.
        assertFalse(dev.lumaphase.android.export.SessionJson.isWritableDetailValue(Any()))

        // -- the two JSONL files really exist and hold one line per event -------------------
        val displayLines = File(directory, SessionFiles.DISPLAY_EVENTS).readLines().filter { it.isNotEmpty() }
        val configurationLines =
            File(directory, SessionFiles.CONFIGURATION_EVENTS).readLines().filter { it.isNotEmpty() }
        assertEquals(display.size, displayLines.size)
        assertEquals(configuration.size, configurationLines.size)
        assertTrue(displayLines.first().contains("event_seq=0"))
        assertTrue(displayLines.first().contains("event_type=SUBMITTED"))
    }

    @Test
    fun aFullQueueLatchesTheLossIndependentlyOfTheQueue() {
        val directory = Files.createTempDirectory("optc-overflow").toFile()
        val log = SessionLog(directory, serialize = { line(it) }, capacity = 2)
        // The queue is filled before the writer exists, so the third record provably cannot be
        // retained; the latch must survive that on its own (contract section 10).
        assertTrue(log.offer(configurationRecord(0)))
        assertTrue(log.offer(configurationRecord(1)))
        assertFalse("a full queue loses the record", log.offer(configurationRecord(2)))
        assertTrue(log.hasOverflowed)
        assertEquals(1L, log.lostEventCount)
        // The loss is attributed to the log the record belonged to, with its own sequence
        // numbers, because the two logs have independent event sequences.
        val loss = log.losses().single()
        assertEquals(SessionFiles.CONFIGURATION_EVENTS, loss.affectedLog)
        assertEquals(2L, loss.firstLostEventSeq)
        assertEquals(2L, loss.lastLostEventSeq)
        assertEquals(1L, loss.lostEventCount)
        assertEquals(listOf("0"), loss.affectedSegmentIds)
    }

    @Test
    fun anOverflowedSessionIsSealedAsIncomplete() {
        val directory = Files.createTempDirectory("optc-overflow-session").toFile()
        // The writer is parked on the first record, so the bounded queue provably overflows
        // while a segment is open and the loss is attributed to it. Without the park the
        // background writer would drain fast enough to make the overflow a race.
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val parkOnce = AtomicBoolean(true)
        val log = SessionLog(
            directory,
            serialize = { record ->
                if (parkOnce.getAndSet(false)) {
                    parked.countDown()
                    release.await()
                }
                line(record)
            },
            capacity = 1,
        )

        var now = 0L
        val controller = SessionController(
            sessionId = "overflow-session",
            environment = environment(),
            eventLog = log,
            clock = { now },
        )
        controller.registerLayout(layout("dynamic-layout-0", 1080, 2400))
        controller.startSession()
        assertTrue("the writer must pick up the first record", parked.await(2, TimeUnit.SECONDS))
        now += 1
        controller.onDisplayStarted(displayStart("surface-1", "dynamic-layout-0"))
        // The queue holds one record and the writer is parked, so this provably overflows with
        // segment 0 still active.
        controller.advancePhase(SessionPhase.RUNNING)
        assertTrue(controller.isLogFailed())

        // Once the log has failed no new segment may open.
        controller.onDisplayStarted(displayStart("surface-2", "dynamic-layout-0"))

        release.countDown()
        val sealed = controller.stopSession(SessionClosedReason.LOG_OVERFLOW)!!

        // A session touched by log loss is never reported as complete.
        assertEquals("INCOMPLETE", sealed.summary.dataIntegrity.wire)
        assertTrue(sealed.summary.lostEventCount >= 1L)
        // Exactly the affected segment is marked incomplete, and no later segment was opened.
        assertEquals(1, sealed.summary.segments.size)
        assertEquals("0", sealed.summary.segments.single().segmentId)
        assertEquals("INCOMPLETE", sealed.summary.segments.single().dataIntegrity.wire)
    }

    @Test
    fun aFrameThatNeverResolvesIsReportedAtTheSealWithItsReason() {
        val directory = Files.createTempDirectory("optc-seal-pending").toFile()
        val records = ArrayList<SessionRecord>()
        val log = SessionLog(directory, serialize = { record -> records += record; line(record) })
        var now = 0L
        val controller = SessionController(
            sessionId = "pending-session",
            environment = environment(),
            eventLog = log,
            clock = { now },
        )
        controller.updateSegmentEnvironment(segmentEnvironment())
        controller.registerLayout(layout("dynamic-layout-0", 1080, 2400))
        controller.startSession()
        controller.advancePhase(SessionPhase.RUNNING)
        controller.onDisplayStarted(displayStart("surface-1", "dynamic-layout-0"))
        // Submitted, and no result ever arrives for it.
        submit(controller, FrameKind.DYNAMIC, 0uL, "1", now)
        now += 1

        val sealed = controller.stopSession(SessionClosedReason.USER_STOP)!!
        val closing = records.filterIsInstance<DisplayEventRecord>()
            .single { it.eventType == DisplayEventType.UNCONFIRMED }
        // The closing event must name a reason from contract section 7.3, not null.
        assertEquals(UnconfirmedReason.PENDING_AT_SESSION_CLOSE, closing.unconfirmedReason)
        assertEquals(DisplayEventType.UNCONFIRMED, closing.eventType)
        assertEquals(1, sealed.summary.segments.single().finalUnconfirmedFrames)
        assertEquals(1, sealed.summary.segments.single().generatedFrames)
    }

    @Test
    fun aFailedFileWriteIsReportedAndNotSealedAsComplete() {
        val base = Files.createTempDirectory("optc-write-fail").toFile()
        // A regular file where the session directory should be: opening the log files fails.
        val notADirectory = File(base, "session_dir").apply { writeText("not a directory") }
        val log = SessionLog(notADirectory, serialize = { line(it) })
        var now = 0L
        val controller = SessionController(
            sessionId = "write-failure-session",
            environment = environment(),
            eventLog = log,
            clock = { now },
        )
        controller.registerLayout(layout("dynamic-layout-0", 1080, 2400))
        controller.startSession()
        now += 1
        controller.onDisplayStarted(displayStart("surface-1", "dynamic-layout-0"))
        val sealed = controller.stopSession(SessionClosedReason.USER_STOP)!!

        assertNotNull("the write failure must be reported", log.writeFailure)
        assertEquals("INCOMPLETE", sealed.summary.dataIntegrity.wire)
        assertNotNull(sealed.writeFailure)
        // The export path refuses this package by construction: the caller checks
        // `sealed.writeFailure` before handing a directory to the operator, and the four sealed
        // documents cannot be produced without android.util.JsonWriter, which a JVM test cannot
        // run. The refusal is therefore asserted through the value that drives it.
    }

    @Test
    fun aCloseTimeoutIsReportedAndSealedAsIncomplete() {
        val directory = Files.createTempDirectory("optc-close-timeout").toFile()
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val log = SessionLog(
            directory,
            serialize = { record ->
                // Hold the writer inside the first record until the sealing path has given up,
                // which is the real condition a close-timeout reports.
                parked.countDown()
                release.await()
                line(record)
            },
        )
        var now = 0L
        val controller = SessionController(
            sessionId = "close-timeout-session",
            environment = environment(),
            eventLog = log,
            clock = { now },
        )
        controller.registerLayout(layout("dynamic-layout-0", 1080, 2400))
        controller.startSession()
        assertTrue("the writer must be parked", parked.await(2, TimeUnit.SECONDS))
        now += 1
        controller.onDisplayStarted(displayStart("surface-1", "dynamic-layout-0"))

        val sealed = controller.stopSession(SessionClosedReason.USER_STOP)!!

        // The timeout is a real close failure: the sealed session must carry it and must not be
        // COMPLETE, so the caller refuses to hand the directory over as a finished package.
        assertNotNull(sealed.writeFailure)
        assertTrue(
            "a close timeout must name the writer",
            sealed.writeFailure!!.contains("did not finish"),
        )
        assertEquals("INCOMPLETE", sealed.summary.dataIntegrity.wire)

        release.countDown()
    }

    @Test
    fun aLargeUnresolvedSetAtCloseIsBoundedByTheQueue() {
        val directory = Files.createTempDirectory("optc-close-pending").toFile()
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        // The writer is parked on the first record, so the queue is provably full while the
        // close walks a large set of frames that never resolved.
        val log = SessionLog(
            directory,
            serialize = { record ->
                parked.countDown()
                release.await()
                line(record)
            },
            capacity = 4,
        )
        var now = 0L
        val controller = SessionController(
            sessionId = "close-pending-session",
            environment = environment(),
            eventLog = log,
            clock = { now },
        )
        controller.registerLayout(layout("dynamic-layout-0", 1080, 2400))
        controller.startSession()
        assertTrue(parked.await(2, TimeUnit.SECONDS))
        controller.advancePhase(SessionPhase.RUNNING)
        controller.onDisplayStarted(displayStart("surface-1", "dynamic-layout-0"))
        repeat(200) { index ->
            now += 1
            submit(controller, FrameKind.DYNAMIC, index.toULong(), "${index + 10}", now)
        }

        val sealedRef = java.util.concurrent.atomic.AtomicReference<SealedSession?>()
        val closer = Thread { sealedRef.set(controller.stopSession(SessionClosedReason.USER_STOP)) }
        closer.start()
        // The writer stays parked while the close walks the 200 unresolved frames; the queue
        // holds four records, so the rest must be counted as lost, not queued on a side tail.
        Thread.sleep(100)
        release.countDown()
        closer.join(10_000)
        assertFalse("the close must finish without waiting for a drained tail", closer.isAlive)

        val sealed = sealedRef.get()!!
        assertNull("overflow is not a write failure", sealed.writeFailure)
        assertEquals("INCOMPLETE", sealed.summary.dataIntegrity.wire)
        assertTrue(sealed.summary.lostEventCount >= 100L)
        assertEquals(200, sealed.summary.segments.single().finalUnconfirmedFrames)
        // Before the fix every never-resolved frame was appended to the close tail and written;
        // now they take the normal bounded queue and a full queue drops them. A few may pass
        // through while the closer and the released writer race, but never the whole set.
        val unconfirmedLines = File(directory, SessionFiles.DISPLAY_EVENTS)
            .readLines()
            .count { it.contains("UNCONFIRMED") }
        assertTrue(
            "an unresolved set must not all reach the close tail (got $unconfirmedLines)",
            unconfirmedLines < 50,
        )
        assertTrue(
            "the closing diagnostics still reach the file",
            File(directory, SessionFiles.CONFIGURATION_EVENTS).readLines()
                .any { it.contains("SESSION_CLOSED") },
        )
    }

    @Test
    fun aPartiallyOpenedLogClosesTheFirstWriter() {
        val directory = Files.createTempDirectory("optc-partial-open").toFile()
        // The display log opens first; the configuration log cannot be opened because its path
        // is a directory. The already-open first writer must be closed, not leaked.
        File(directory, SessionFiles.CONFIGURATION_EVENTS).mkdirs()
        val log = SessionLog(directory, serialize = { line(it) })

        log.start()

        assertNotNull(log.writeFailure)
        assertEquals(0L, File(directory, SessionFiles.DISPLAY_EVENTS).length())
    }

    @Test
    fun theCloseTailDropsRecordsBeyondItsFixedBound() {
        val directory = Files.createTempDirectory("optc-close-tail").toFile()
        val log = SessionLog(directory, serialize = { line(it) }, capacity = 2)

        repeat(100) { log.offerAtClose(configurationRecord(it.toLong())) }

        // Only the fixed closing set fits; the flood must be latched as loss, not retained.
        assertTrue(log.hasOverflowed)
        assertTrue("a bounded tail must drop the excess", log.lostEventCount >= 90L)
        val loss = log.losses().single()
        assertEquals(SessionFiles.CONFIGURATION_EVENTS, loss.affectedLog)
        assertEquals(log.lostEventCount, loss.lostEventCount)
    }

    @Test
    fun configurationEventDetailsCarryTheContractKeys() {
        val directory = Files.createTempDirectory("optc-details").toFile()
        val records = ArrayList<SessionRecord>()
        val log = SessionLog(directory, serialize = { record -> records += record; line(record) })
        var now = 0L
        val controller = SessionController(
            sessionId = "details-session",
            environment = environment(),
            eventLog = log,
            clock = { now },
        )
        controller.updateSegmentEnvironment(segmentEnvironment())
        controller.registerLayout(layout("dynamic-layout-0", 1080, 2400))
        controller.startSession()
        controller.advancePhase(SessionPhase.RUNNING)
        controller.onDisplayStarted(displayStart("surface-1", "dynamic-layout-0"))

        // A failed swap is a RENDER_FAILURE configuration event, never a SUBMITTED one.
        val failedIdentity = FrameIdentity(
            runId = "run-1",
            sessionId = "details-session",
            segmentId = "0",
            surfaceInstanceId = "surface-1",
            nativeFrameId = "1",
            frameKind = FrameKind.DYNAMIC,
            phase = "RUNNING",
            renderSeq = 0uL,
            layoutId = "dynamic-layout-0",
        )
        controller.onSubmitted(
            FrameSubmission(
                identity = failedIdentity,
                timeline = VsyncTimeline(1L, 100L, 90L),
                submitNanos = 50L,
                eglFrameId = null,
                presentationRequestNanos = null,
                payloadN = 0,
                crc8 = 0,
                swapAccepted = false,
                failure = "eglSwapBuffers failed 0x3001",
                failureErrorCode = "0x3001",
            ),
        )
        now += 1
        controller.stopSession(SessionClosedReason.RENDER_FAILURE)

        val byType = records.filterIsInstance<ConfigurationEventRecord>().groupBy { it.eventType }
        assertTrue(
            byType.getValue(ConfigurationEventType.SEGMENT_STARTED).single()
                .details.keys.containsAll(listOf("configuration", "reason")),
        )
        assertTrue(
            byType.getValue(ConfigurationEventType.SEGMENT_ENDED).single()
                .details.keys.containsAll(listOf("reason")),
        )
        assertTrue(
            byType.getValue(ConfigurationEventType.PHASE_CHANGED).all {
                it.details.keys.containsAll(listOf("previous_phase", "phase", "planned_duration_ns"))
            },
        )
        val failure = byType.getValue(ConfigurationEventType.RENDER_FAILURE).single()
        assertTrue(
            failure.details.keys.containsAll(
                listOf("native_frame_id", "render_seq", "operation", "error_code", "message"),
            ),
        )
        assertEquals("1", failure.details["native_frame_id"])
        assertEquals("0", failure.details["render_seq"])
        assertEquals("eglSwapBuffers", failure.details["operation"])
        assertTrue(
            byType.getValue(ConfigurationEventType.SESSION_CLOSED).single()
                .details.keys.containsAll(listOf("reason", "data_integrity", "pending_frame_count")),
        )
        // The failed swap must not have produced a display event at all.
        assertTrue(records.filterIsInstance<DisplayEventRecord>().none { it.nativeFrameId == "1" })
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun environment() = AppEnvironment(
        sourceDeviceId = "OnePlus-CPH2651",
        appId = "dev.lumaphase.android",
        appVersion = "0.1.0",
        appBuild = "1",
        osVersion = "15",
        osBuild = "fingerprint",
    )

    private fun segmentEnvironment() = SegmentEnvironment(
        experimentProfile = "A120-S1",
        displayModeId = "2",
        displayRotationDeg = 0,
        requestedRefreshNum = 120,
        requestedRefreshDen = 1,
        nominalRefreshNum = null,
        nominalRefreshDen = null,
        reportedRefreshHz = 120.0,
        brightnessSetting = 0.75,
        brightnessSource = BrightnessSource.SYSTEM_REPORTED,
        autoBrightnessEnabled = false,
        colorMode = null,
        dimmingMode = null,
        hdrEnabled = null,
        trueToneEnabled = null,
        nightShiftEnabled = null,
        displayProfileId = null,
        operatorNote = "",
    )

    private fun layout(layoutId: String, widthPx: Int, heightPx: Int) =
        dev.lumaphase.android.layout.PatternLayoutBuilder.build(
            layoutId,
            dev.lumaphase.android.protocol.LayoutProfile.F68,
            widthPx,
            heightPx,
            0,
        )

    private fun displayStart(
        surfaceId: String,
        layoutId: String,
        drawableHeightPx: Int = 2400,
    ) = DisplayStart(
        surfaceInstanceId = surfaceId,
        layoutId = layoutId,
        drawableWidthPx = 1080,
        drawableHeightPx = drawableHeightPx,
        colorSpace = "EGL_GL_COLORSPACE_DEFAULT",
        pixelFormat = "EGL_RGB_BUFFER/R8G8B8A0",
    )

    private fun submit(
        controller: SessionController,
        kind: FrameKind,
        seq: ULong?,
        frame: String,
        now: Long,
        surfaceId: String = "surface-1",
    ): FrameIdentity {
        val identity = FrameIdentity(
            runId = "run-1",
            sessionId = controller.sessionId,
            segmentId = controller.stamp.segmentId,
            surfaceInstanceId = surfaceId,
            nativeFrameId = frame,
            frameKind = kind,
            phase = controller.stamp.phase,
            renderSeq = seq,
            layoutId = controller.stamp.layoutId,
        )
        controller.onSubmitted(
            FrameSubmission(
                identity = identity,
                timeline = VsyncTimeline(
                    vsyncId = frame.toLong(),
                    expectedPresentTimeNanos = now + 8_000_000,
                    deadlineNanos = now + 6_000_000,
                ),
                submitNanos = now,
                eglFrameId = (100 + frame.toLong()).toString(),
                presentationRequestNanos = now + 8_000_000,
                payloadN = seq?.let { (it % 65_536uL).toInt() },
                crc8 = seq?.let { 0x5A },
                swapAccepted = true,
                failure = null,
                failureErrorCode = null,
            ),
        )
        return identity
    }

    private fun result(identity: FrameIdentity, systemPresent: TimestampStatus) = FrameResult(
        identity = identity,
        fields = mapOf(
            TimestampPrefix.SYSTEM_PRESENT to TimestampField(
                systemPresent,
                // A real EGL value exists for both VALID and CLOCK_DOMAIN_UNVERIFIED; the
                // difference is only whether its clock domain is proven.
                if (systemPresent == TimestampStatus.VALID ||
                    systemPresent == TimestampStatus.CLOCK_DOMAIN_UNVERIFIED
                ) {
                    1_500_000_000L
                } else {
                    null
                },
            ),
            TimestampPrefix.RENDER_COMPLETE to TimestampField(TimestampStatus.PENDING, -2L),
            TimestampPrefix.LATCH to TimestampField(TimestampStatus.UNSUPPORTED, null),
        ),
        unconfirmedReason = when (systemPresent) {
            TimestampStatus.VALID -> null
            TimestampStatus.UNSUPPORTED -> UnconfirmedReason.TIMESTAMP_UNSUPPORTED
            TimestampStatus.CLOCK_DOMAIN_UNVERIFIED -> UnconfirmedReason.CLOCK_DOMAIN_UNVERIFIED
            else -> UnconfirmedReason.QUERY_FAILED
        },
        queryErrorCode = null,
    )

    /**
     * The test serializer: a stable single line per record so the test can check that the log
     * really received one line per event. The app's own serializer uses Android's JsonWriter,
     * which cannot run here.
     */
    private fun line(record: SessionRecord): String = when (record) {
        is DisplayEventRecord -> listOf(
            "event_seq=${record.eventSeq}",
            "event_type=${record.eventType.wire}",
            "session_id=${record.sessionId}",
            "segment_id=${record.segmentId}",
            "layout_id=${record.layoutId}",
            "native_frame_id=${record.nativeFrameId}",
            "frame_kind=${record.frameKind?.name}",
            "phase=${record.phase}",
            "render_seq=${record.renderSeq?.toString()}",
            "payload_n=${record.payloadN}",
            "crc8=${record.crc8}",
            "present_kind=${record.presentKind?.wire}",
            "unconfirmed_reason=${record.unconfirmedReason}",
            "system_present_raw_ns=${record.timestamps[TimestampPrefix.SYSTEM_PRESENT]?.rawNs}",
            "recorded_raw_ns=${record.recordedRawNs}",
            "recorded_ns=${record.recordedNs}",
        ).joinToString(" ")

        is ConfigurationEventRecord -> listOf(
            "event_seq=${record.eventSeq}",
            "event_type=${record.eventType.wire}",
            "session_id=${record.sessionId}",
            "segment_id=${record.segmentId}",
            "recorded_raw_ns=${record.recordedRawNs}",
            "recorded_ns=${record.recordedNs}",
        ).joinToString(" ")
    }

    private fun configurationRecord(seq: Long) = ConfigurationEventRecord(
        eventSeq = seq,
        eventType = ConfigurationEventType.PHASE_CHANGED,
        sessionId = "overflow-session",
        segmentId = "0",
        recordedRawNs = 0L,
        recordedNs = 0L,
        details = emptyMap(),
    )
}
