package dev.lumaphase.android.session

import dev.lumaphase.android.layout.DisplayLayout
import dev.lumaphase.android.layout.PatternLayout
import dev.lumaphase.android.timing.DisplayStamp
import dev.lumaphase.android.timing.DisplayStart
import dev.lumaphase.android.timing.FrameIdentity
import dev.lumaphase.android.timing.FrameResult
import dev.lumaphase.android.timing.FrameSubmission
import dev.lumaphase.android.timing.FrameTimingSink
import dev.lumaphase.android.timing.TimestampPrefix
import dev.lumaphase.android.timing.TimestampStatus
import dev.lumaphase.android.timing.UnconfirmedReason
import dev.lumaphase.android.timing.VsyncTimeline
import java.util.UUID

/** Environment strings the manifest records. */
data class AppEnvironment(
    val sourceDeviceId: String,
    val appId: String,
    val appVersion: String,
    val appBuild: String,
    val osVersion: String,
    val osBuild: String,
)

/**
 * Values the host can read from the platform or ask the operator for, used for the next
 * segment snapshot. Anything the platform cannot report stays null instead of being guessed
 * (contract section 2.2).
 */
data class SegmentEnvironment(
    val experimentProfile: String,
    val displayModeId: String?,
    val displayRotationDeg: Int?,
    val requestedRefreshNum: Int?,
    val requestedRefreshDen: Int?,
    val nominalRefreshNum: Int?,
    val nominalRefreshDen: Int?,
    val reportedRefreshHz: Double?,
    val brightnessSetting: Double?,
    val brightnessSource: BrightnessSource,
    val autoBrightnessEnabled: Boolean?,
    val colorMode: String?,
    val dimmingMode: String?,
    val hdrEnabled: Boolean?,
    val trueToneEnabled: Boolean?,
    val nightShiftEnabled: Boolean?,
    val displayProfileId: String?,
    val operatorNote: String,
) {
    companion object {
        val EMPTY = SegmentEnvironment(
            experimentProfile = "A120-S1",
            displayModeId = null,
            displayRotationDeg = null,
            requestedRefreshNum = null,
            requestedRefreshDen = null,
            nominalRefreshNum = null,
            nominalRefreshDen = null,
            reportedRefreshHz = null,
            brightnessSetting = null,
            brightnessSource = BrightnessSource.UNKNOWN,
            autoBrightnessEnabled = null,
            colorMode = null,
            dimmingMode = null,
            hdrEnabled = null,
            trueToneEnabled = null,
            nightShiftEnabled = null,
            displayProfileId = null,
            operatorNote = "",
        )
    }
}

/**
 * What the exporter needs to write the six files after sealing.
 *
 * [writeFailure] is an in-memory diagnosis only: the contract fixes the summary keys, so a
 * failed file write is reported through the export result and through `data_integrity`
 * instead of a new key.
 */
data class SealedSession(
    val manifest: ManifestDocument,
    val layouts: List<DisplayLayout>,
    val displayProfile: DisplayProfileDocument,
    val summary: SessionSummary,
    val directoryName: String,
    val writeFailure: String?,
)

/**
 * The session state machine: phases, segments, the frozen identity of every submission, the
 * primary-key merge of submissions with their late results, and the seal.
 *
 * The render thread calls the [FrameTimingSink] methods; the host calls [advancePhase],
 * [endSegment] and [stopSession] from the main thread. Mutable state sits behind one short
 * lock that is taken a few times per frame, and everything else is a plain collection:
 * no registry, service or framework.
 */
class SessionController(
    val sessionId: String = UUID.randomUUID().toString(),
    private val environment: AppEnvironment,
    private val eventLog: SessionLog,
    private val clock: () -> Long = { System.nanoTime() },
    private val schemaVersion: String = "1.1",
) : FrameTimingSink {

    private val lock = Any()
    private val layouts = LinkedHashMap<String, DisplayLayout>()
    private val segments = ArrayList<SegmentSnapshot>()
    private val framesInOrder = LinkedHashMap<FrameKey, FrameState>()
    private val generatedPerSegment = LinkedHashMap<String, Int>()

    @Volatile
    private var segmentEnvironment: SegmentEnvironment = SegmentEnvironment.EMPTY

    @Volatile
    private var phase: SessionPhase = SessionPhase.IDLE

    @Volatile
    private var sealed: Boolean = false

    private var activeSegment: SegmentSnapshot? = null
    private data class PendingPhase(
        val previous: SessionPhase?,
        val next: SessionPhase,
        val plannedDurationNs: Long?,
    )
    private var pendingPhase: PendingPhase? = null
    private var nextSegmentIndex = 0
    private var closedRawNs: Long? = null
    private var pendingStartReason: SegmentStartReason? = null

    /** The stamp the render thread reads; replaced as a whole, never mutated in place. */
    val stamp: DisplayStamp get() = stampSnapshot

    @Volatile
    private var stampSnapshot = DisplayStamp()

    @Volatile
    private var logFailureHandled = false

    /**
     * Latched for the rest of the session once the log can no longer be trusted: no new
     * segment may open and no new dynamic state may be recorded (contract section 10).
     */
    @Volatile
    private var logFailed = false

    @Volatile
    private var logFailureListener: ((SessionClosedReason) -> Unit)? = null

    /** Called once, on whichever thread noticed, when the log can no longer be trusted. */
    fun onLogFailure(listener: (SessionClosedReason) -> Unit) {
        logFailureListener = listener
    }

    /** True once the log has failed and the session may only finish. */
    fun isLogFailed(): Boolean = logFailed

    private fun publishStamp(
        sessionId: String = stampSnapshot.sessionId,
        segmentId: String = stampSnapshot.segmentId,
        layoutId: String = stampSnapshot.layoutId,
        frameKind: dev.lumaphase.android.timing.FrameKind? = stampSnapshot.frameKind,
        phase: String = stampSnapshot.phase,
        cardLabel: String? = stampSnapshot.cardLabel,
        recording: Boolean = stampSnapshot.recording,
    ) {
        stampSnapshot = DisplayStamp(sessionId, segmentId, layoutId, frameKind, phase, cardLabel, recording)
    }

    val timeOrigin: TimeOrigin = TimeOrigin(clock(), NATIVE_CLOCK)

    fun relativeNs(rawNs: Long): Long = rawNs - timeOrigin.rawNs

    fun currentPhase(): SessionPhase = phase

    val closed: Boolean get() = sealed

    fun updateSegmentEnvironment(next: SegmentEnvironment) {
        segmentEnvironment = next
    }

    fun registerLayout(layout: PatternLayout) {
        registerLayout(DisplayLayout.Optc(layout))
    }

    fun registerLayout(layout: DisplayLayout) {
        synchronized(lock) { layouts[layout.layoutId] = layout }
    }

    /** The host calls this once, before any display starts. */
    fun startSession() {
        synchronized(lock) {
            if (sealed || phase != SessionPhase.IDLE) return
            eventLog.start()
            emitPhaseChanged(null, SessionPhase.CONFIGURED, SessionPhase.CONFIGURED.plannedDurationNs)
            phase = SessionPhase.CONFIGURED
            publishStamp(recording = true)
        }
    }

    /**
     * Advances the plan and records it. [force] is used by CR-002 repeated CALIBRATION steps,
     * which must emit a new phase event even though the phase enum value is unchanged.
     */
    fun advancePhase(
        next: SessionPhase,
        force: Boolean = false,
        plannedDurationNs: Long? = null,
    ) {
        synchronized(lock) {
            if (sealed) return
            val previous = phase
            if (previous == next && !force) return
            val planned = plannedDurationNs ?: next.plannedDurationNs
            phase = next
            if (activeSegment == null) {
                // The new phase event belongs to the segment opened by the upcoming display
                // start. Defering the event and stamp until onDisplayStarted prevents a
                // PHASE_CHANGED with segment_id=null and prevents an old frame from taking
                // the new phase before the new display target exists.
                pendingPhase = PendingPhase(previous, next, planned)
            } else {
                emitPhaseChanged(previous, next, planned)
                publishStamp(phase = next.name, frameKind = next.frameKind, cardLabel = next.cardLabel)
            }
        }
    }

    fun endSegment(reason: SegmentEndReason) {
        synchronized(lock) {
            if (sealed) return
            endActiveSegmentLocked(reason)
        }
    }

    /**
     * The host announces why the next segment starts, so a resume is recorded as RESUME
     * rather than as a configuration change. Consumed by the next segment start.
     */
    fun expectSegmentReason(reason: SegmentStartReason) {
        pendingStartReason = reason
    }

    /**
     * Stops producing frames, reports the frames that never settled, writes the closing
     * events and seals. Returns null when the session was already sealed.
     */
    fun stopSession(reason: SessionClosedReason): SealedSession? {
        synchronized(lock) {
            if (sealed) return null
            sealed = true
            publishStamp(frameKind = null)
        }
        closeAndSeal(reason)
        return buildSealedSession()
    }

    // ---- FrameTimingSink (render thread) -------------------------------------------------

    override fun onDisplayStarted(displayStart: DisplayStart) {
        synchronized(lock) {
            if (sealed) return
            // Nothing is recorded until the session is explicitly started, so a plain pattern
            // preview can never create a segment or a session directory.
            if (!stampSnapshot.recording) return
            // After a log failure the session may only finish: no new segment opens, so a
            // Surface rebuild cannot restart recording behind the failure.
            if (logFailed) return
            // A new display target always ends the previous segment; the host sets the more
            // specific reason first when it knows one.
            endActiveSegmentLocked(SegmentEndReason.SURFACE_CHANGED)
            startSegmentLocked(displayStart)
            val phasePending = pendingPhase
            pendingPhase = null
            if (phasePending != null) {
                publishStamp(
                    phase = phasePending.next.name,
                    frameKind = phasePending.next.frameKind,
                    cardLabel = phasePending.next.cardLabel,
                )
                emitPhaseChanged(
                    phasePending.previous,
                    phasePending.next,
                    phasePending.plannedDurationNs,
                )
            }
        }
    }

    override fun onSubmitted(submission: FrameSubmission) {
        if (sealed) return
        val identity = submission.identity
        // Every produced frame counts as generated, including one whose swap failed: the
        // dynamic state was generated and its sequence consumed (contract section 1).
        synchronized(lock) {
            generatedPerSegment[identity.segmentId] =
                (generatedPerSegment[identity.segmentId] ?: 0) + 1
        }
        if (!submission.swapAccepted) {
            appendConfiguration(
                configurationEvent(
                    ConfigurationEventType.RENDER_FAILURE,
                    identity.segmentId,
                    mapOf(
                        "native_frame_id" to identity.nativeFrameId,
                        "render_seq" to identity.renderSeq?.toString(),
                        "operation" to "eglSwapBuffers",
                        "error_code" to submission.failureErrorCode,
                        "message" to (submission.failure ?: "swap failed"),
                    ),
                ),
            )
            return
        }
        val state = FrameState(submission)
        val key = FrameKey(identity.segmentId, identity.nativeFrameId)
        synchronized(lock) { framesInOrder.put(key, state) }
        appendDisplay(displayEvent(state, DisplayEventType.SUBMITTED, null, null))
    }

    override fun onResult(result: FrameResult) {
        if (sealed) return
        val identity = result.identity
        val key = FrameKey(identity.segmentId, identity.nativeFrameId)
        val state = synchronized(lock) { framesInOrder[key] }
        val presented = result.fields[TimestampPrefix.SYSTEM_PRESENT]?.status == TimestampStatus.VALID
        if (state != null) {
            synchronized(lock) {
                state.lastResult = result
                if (presented) {
                    state.presented = true
                    state.lastReason = null
                } else if (state.lastReason == null) {
                    state.lastReason = result.unconfirmedReason ?: UnconfirmedReason.TIMESTAMP_INVALID
                }
            }
        }
        val event = displayEvent(
            identity = identity,
            timeline = state?.timeline,
            submitNanos = state?.submitNanos,
            eglFrameId = state?.eglFrameId,
            presentationRequestNanos = state?.presentationRequestNanos,
            payloadN = state?.payloadN,
            crc8 = state?.crc8,
            type = if (presented) DisplayEventType.PRESENTED else DisplayEventType.UNCONFIRMED,
            presentKind = if (presented) PresentKind.SYSTEM_REPORTED else PresentKind.UNCONFIRMED,
            result = result,
        )
        appendDisplay(event)
    }

    // ---- internals -----------------------------------------------------------------------

    private fun startSegmentLocked(displayStart: DisplayStart) {
        val config = segmentEnvironment
        val snapshot = SegmentSnapshot(
            segmentId = nextSegmentIndex.toString(),
            layoutId = displayStart.layoutId,
            displayModeId = config.displayModeId,
            experimentProfile = config.experimentProfile,
            drawableWidthPx = displayStart.drawableWidthPx,
            drawableHeightPx = displayStart.drawableHeightPx,
            displayRotationDeg = config.displayRotationDeg,
            requestedRefreshNum = config.requestedRefreshNum,
            requestedRefreshDen = config.requestedRefreshDen,
            nominalRefreshNum = config.nominalRefreshNum,
            nominalRefreshDen = config.nominalRefreshDen,
            reportedRefreshHz = config.reportedRefreshHz,
            brightnessSetting = config.brightnessSetting,
            brightnessSource = config.brightnessSource,
            autoBrightnessEnabled = config.autoBrightnessEnabled,
            colorSpace = displayStart.colorSpace,
            pixelFormat = displayStart.pixelFormat,
            colorMode = config.colorMode,
            dimmingMode = config.dimmingMode,
            hdrEnabled = config.hdrEnabled,
            trueToneEnabled = config.trueToneEnabled,
            nightShiftEnabled = config.nightShiftEnabled,
            displayProfileId = config.displayProfileId,
            operatorNote = config.operatorNote,
        )
        nextSegmentIndex++
        segments += snapshot
        activeSegment = snapshot
        publishStamp(sessionId = sessionId, segmentId = snapshot.segmentId, layoutId = snapshot.layoutId)
        if (layouts[displayStart.layoutId] == null) {
            // Without the layout the export cannot reference real geometry, so it is reported
            // rather than silently missing from layout.json.
            appendConfiguration(
                configurationEvent(
                    ConfigurationEventType.RENDER_FAILURE,
                    snapshot.segmentId,
                    mapOf(
                        "native_frame_id" to null,
                        "render_seq" to null,
                        "operation" to "registerLayout",
                        "error_code" to null,
                        "message" to "layout ${displayStart.layoutId} was not registered",
                    ),
                ),
            )
        }
        val reason = pendingStartReason
            ?: if (segments.size == 1) SegmentStartReason.SESSION_START else SegmentStartReason.CONFIGURATION_CHANGED
        pendingStartReason = null
        appendConfiguration(
            configurationEvent(
                ConfigurationEventType.SEGMENT_STARTED,
                snapshot.segmentId,
                mapOf("configuration" to snapshot, "reason" to reason.wire),
            ),
        )
    }

    private fun endActiveSegmentLocked(reason: SegmentEndReason) {
        val segment = activeSegment ?: return
        appendConfiguration(
            configurationEvent(
                ConfigurationEventType.SEGMENT_ENDED,
                segment.segmentId,
                mapOf("reason" to reason.wire),
            ),
        )
        activeSegment = null
    }

    private fun emitPhaseChanged(previous: SessionPhase?, next: SessionPhase, planned: Long?) {
        appendConfiguration(
            configurationEvent(
                ConfigurationEventType.PHASE_CHANGED,
                activeSegment?.segmentId,
                mapOf(
                    "previous_phase" to previous?.name,
                    "phase" to next.name,
                    "planned_duration_ns" to planned,
                ),
            ),
        )
    }

    private fun configurationEvent(
        type: ConfigurationEventType,
        segmentId: String?,
        details: Map<String, Any?>,
    ): ConfigurationEventRecord {
        val raw = clock()
        return ConfigurationEventRecord(
            eventSeq = eventLog.nextConfigurationEventSeq(),
            eventType = type,
            sessionId = sessionId,
            segmentId = segmentId,
            recordedRawNs = raw,
            recordedNs = relativeNs(raw),
            details = details,
        )
    }

    private fun closeAndSeal(reason: SessionClosedReason) {
        val closedAt = clock()
        synchronized(lock) {
            closedRawNs = closedAt
            endActiveSegmentLocked(
                when (reason) {
                    SessionClosedReason.NORMAL_END -> SegmentEndReason.NORMAL_END
                    SessionClosedReason.USER_STOP -> SegmentEndReason.USER_STOP
                    SessionClosedReason.LOG_OVERFLOW -> SegmentEndReason.LOG_OVERFLOW
                    SessionClosedReason.RENDER_FAILURE -> SegmentEndReason.RENDER_FAILURE
                },
            )
        }

        // Frames that never produced any result are reported at the seal with the pending
        // reason. Frames that already have an UNCONFIRMED record keep it: their specific
        // obstacle (query failure, unverified clock domain) must not be rewritten.
        val pendingFrames = synchronized(lock) {
            framesInOrder.values.filter { !it.presented }
        }
        val neverResolved = pendingFrames.filter { it.lastResult == null }
        for (state in neverResolved) {
            state.reasonAtClose = UnconfirmedReason.PENDING_AT_SESSION_CLOSE
            // A frame that never resolved is not a closing diagnostic: it goes through the
            // bounded queue like every other record. If the queue is full at this point the
            // record is counted as lost and the session is INCOMPLETE, instead of growing a
            // close-only tail with one record per unresolved frame.
            appendDisplay(
                displayEvent(
                    identity = state.identity,
                    timeline = state.timeline,
                    submitNanos = state.submitNanos,
                    eglFrameId = state.eglFrameId,
                    presentationRequestNanos = state.presentationRequestNanos,
                    payloadN = state.payloadN,
                    crc8 = state.crc8,
                    type = DisplayEventType.UNCONFIRMED,
                    presentKind = PresentKind.UNCONFIRMED,
                    result = null,
                    explicitReason = UnconfirmedReason.PENDING_AT_SESSION_CLOSE,
                ),
            )
        }

        // One event per affected log: the two logs have independent sequence spaces, so a
        // single event could not describe both.
        for (loss in eventLog.losses()) {
            appendConfiguration(
                configurationEvent(
                    ConfigurationEventType.LOG_OVERFLOW,
                    null,
                    mapOf(
                        "affected_log" to loss.affectedLog,
                        "affected_segment_ids" to loss.affectedSegmentIds,
                        "first_lost_event_seq" to loss.firstLostEventSeq,
                        "last_lost_event_seq" to loss.lastLostEventSeq,
                        "lost_event_count" to loss.lostEventCount,
                    ),
                ),
                atClose = true,
            )
        }

        appendConfiguration(
            configurationEvent(
                ConfigurationEventType.SESSION_CLOSED,
                null,
                mapOf(
                    "reason" to reason.wire,
                    "data_integrity" to integrity().wire,
                    // Every submitted frame without a usable presentation result, which is the
                    // same set the summary counts as finally unconfirmed (contract section 2.3).
                    "pending_frame_count" to pendingFrames.size,
                ),
            ),
            atClose = true,
        )

        // A write failure is reported through the log itself; the session is still sealed so
        // the failure surface can tell the operator that the package is incomplete. A writer
        // that cannot finish is latched into the same writeFailure, so the sealed session and
        // its data_integrity see it as well.
        eventLog.closeAndDrain()
    }

    private fun integrity(): DataIntegrity =
        if (eventLog.hasOverflowed || eventLog.writeFailure != null) {
            DataIntegrity.INCOMPLETE
        } else {
            DataIntegrity.COMPLETE
        }

    private fun buildSealedSession(): SealedSession {
        val closedRaw = closedRawNs ?: clock()
        val affected = eventLog.firstOverflowSegmentIds.toSet()
        val summaries = synchronized(lock) {
            segments.map { segment ->
                val states = framesInOrder.filterKeys { it.segmentId == segment.segmentId }.values.toList()
                val reasons = LinkedHashMap<String, Int>()
                states.filter { !it.presented }.forEach { state ->
                    val reason = state.lastReason
                        ?: state.reasonAtClose
                        ?: UnconfirmedReason.PENDING_AT_SESSION_CLOSE
                    reasons[reason] = (reasons[reason] ?: 0) + 1
                }
                SegmentSummary(
                    segmentId = segment.segmentId,
                    generatedFrames = generatedPerSegment[segment.segmentId] ?: 0,
                    submittedFrames = states.size,
                    confirmedPresentedFrames = states.count { it.presented },
                    finalUnconfirmedFrames = states.count { !it.presented },
                    dataIntegrity = if (segment.segmentId in affected) {
                        DataIntegrity.INCOMPLETE
                    } else {
                        DataIntegrity.COMPLETE
                    },
                    unconfirmedReasonCounts = reasons,
                )
            }
        }
        val segmentSnapshots: List<SegmentSnapshot>
        val layoutList: List<DisplayLayout>
        synchronized(lock) {
            segmentSnapshots = segments.toList()
            // The layout collection is a set: the same immutable layout may be referenced by
            // several segments and must appear once.
            layoutList = segments.mapNotNull { layouts[it.layoutId] }.distinctBy { it.layoutId }
        }
        return SealedSession(
            manifest = ManifestDocument(
                schemaVersion = schemaVersion,
                protocolVersion = SessionConstants.PROTOCOL_VERSION,
                sessionId = sessionId,
                platform = SessionConstants.PLATFORM,
                sourceDeviceId = environment.sourceDeviceId,
                appId = environment.appId,
                appVersion = environment.appVersion,
                appBuild = environment.appBuild,
                osVersion = environment.osVersion,
                osBuild = environment.osBuild,
                clockDomain = SessionConstants.CLOCK_DOMAIN,
                timeOrigin = timeOrigin,
                presentationDrainTimeoutNs = SessionConstants.PRESENTATION_DRAIN_TIMEOUT_NS,
                segments = segmentSnapshots,
            ),
            layouts = layoutList,
            displayProfile = DisplayProfileDocument(
                schemaVersion = schemaVersion,
                // No calibration is imported here: an empty set, never a fabricated one.
                profiles = emptyList(),
                segmentBindings = segmentSnapshots.map { segment ->
                    SegmentProfileBinding(
                        segmentId = segment.segmentId,
                        profileId = segment.displayProfileId,
                        status = if (segment.displayProfileId == null) {
                            DisplayProfileStatus.MISSING
                        } else {
                            DisplayProfileStatus.IMPORTED_UNVERIFIED
                        },
                        mismatchFields = emptyList(),
                    )
                },
            ),
            summary = SessionSummary(
                sessionId = sessionId,
                closedRawNs = closedRaw,
                closedNs = relativeNs(closedRaw),
                dataIntegrity = integrity(),
                lostEventCount = eventLog.lostEventCount,
                segments = summaries,
            ),
            directoryName = SessionFiles.directoryName(sessionId),
            writeFailure = eventLog.writeFailure,
        )
    }

    private fun appendDisplay(record: DisplayEventRecord, atClose: Boolean = false) {
        if (atClose) eventLog.offerAtClose(record) else eventLog.offer(record)
        checkLogHealth()
    }

    private fun appendConfiguration(record: ConfigurationEventRecord, atClose: Boolean = false) {
        if (atClose) eventLog.offerAtClose(record) else eventLog.offer(record)
        checkLogHealth()
    }

    /**
     * On the first loss or write failure the session stops producing new dynamic states and
     * ends the affected segment (contract section 10), and the host is told so it can stop
     * scheduling. The flag is set before the segment is ended so the nested append cannot
     * recurse.
     */
    private fun checkLogHealth() {
        val log = eventLog
        if (!log.hasOverflowed && log.writeFailure == null) return
        val failure = log.writeFailure
        val reason = if (failure == null) {
            SessionClosedReason.LOG_OVERFLOW
        } else {
            SessionClosedReason.RENDER_FAILURE
        }
        val firstTime = synchronized(lock) {
            if (logFailureHandled) return
            logFailureHandled = true
            // Latched before the segment ends, so the nested append cannot recurse and any
            // later display start is refused (contract section 10).
            logFailed = true
            if (failure == null) {
                // Queue overflow: the contract names LOG_OVERFLOW for this.
                endActiveSegmentLocked(SegmentEndReason.LOG_OVERFLOW)
            } else {
                // A file that cannot be written is not an overflow, so it must not be reported
                // as one. It is a general failure of the session's own log. It cannot be
                // recorded as a configuration event: the writer is exactly what failed, so any
                // such event would be refused. The real, independent evidence is the log's
                // writeFailure string and the resulting INCOMPLETE integrity.
                endActiveSegmentLocked(SegmentEndReason.RENDER_FAILURE)
            }
            true
        }
        if (firstTime) logFailureListener?.invoke(reason)
    }

    /** Builds one display event from a frozen frame state, or from a late result. */
    private fun displayEvent(
        state: FrameState,
        type: DisplayEventType,
        presentKind: PresentKind?,
        result: FrameResult?,
    ): DisplayEventRecord = displayEvent(
        identity = state.identity,
        timeline = state.timeline,
        submitNanos = state.submitNanos,
        eglFrameId = state.eglFrameId,
        presentationRequestNanos = state.presentationRequestNanos,
        payloadN = state.payloadN,
        crc8 = state.crc8,
        type = type,
        presentKind = presentKind,
        result = result,
    )

    private fun displayEvent(
        identity: FrameIdentity,
        timeline: VsyncTimeline?,
        submitNanos: Long?,
        eglFrameId: String?,
        presentationRequestNanos: Long?,
        payloadN: Int?,
        crc8: Int?,
        type: DisplayEventType,
        presentKind: PresentKind?,
        result: FrameResult?,
        explicitReason: String? = null,
    ): DisplayEventRecord {
        val statuses = LinkedHashMap<TimestampPrefix, TimestampStatus>()
        val timestamps = LinkedHashMap<TimestampPrefix, StandardTimestampValue>()
        val platformTimestamps = LinkedHashMap<String, PlatformTimestampEntry>()

        localField(
            statuses, timestamps, platformTimestamps, TimestampPrefix.TARGET_PRESENT,
            "Choreographer.FrameTimeline.getExpectedPresentationTimeNanos",
            timeline?.expectedPresentTimeNanos,
        )
        localField(
            statuses, timestamps, platformTimestamps, TimestampPrefix.DEADLINE,
            "Choreographer.FrameTimeline.getDeadlineNanos",
            timeline?.deadlineNanos,
        )
        localField(
            statuses, timestamps, platformTimestamps, TimestampPrefix.SUBMIT,
            "System.nanoTime sampled before eglSwapBuffers",
            submitNanos,
        )

        for (prefix in EGL_PREFIXES) {
            val field = result?.fields?.get(prefix)
            // Before any query the field is genuinely pending; a result carries its own status.
            statuses[prefix] = field?.status ?: TimestampStatus.PENDING
            timestamps[prefix] = StandardTimestampValue(null, null)
            platformTimestamps[prefix.key] = PlatformTimestampEntry(
                api = EGL_API.getValue(prefix),
                // EGLnsecsANDROID is signed, so the -1/-2 sentinels are written as they are.
                // Only the unsigned EGL frame id is printed unsigned, in platform_ids.
                nativeValue = field?.nativeValue?.toString(),
                nativeUnit = "NS",
                nativeClockDomain = null,
                queryErrorCode = result?.queryErrorCode?.let { "0x" + Integer.toHexString(it) },
            )
        }

        platformTimestamps[EGL_PRESENTATION_REQUEST] = PlatformTimestampEntry(
            api = "eglPresentationTimeANDROID",
            nativeValue = presentationRequestNanos?.toString(),
            nativeUnit = "NS",
            nativeClockDomain = NATIVE_CLOCK,
            queryErrorCode = null,
        )

        val recordedRaw = clock()
        return DisplayEventRecord(
            eventSeq = eventLog.nextDisplayEventSeq(),
            eventType = type,
            sessionId = sessionId,
            segmentId = identity.segmentId,
            layoutId = identity.layoutId,
            nativeFrameId = identity.nativeFrameId,
            frameKind = identity.frameKind,
            phase = identity.phase,
            renderSeq = identity.renderSeq,
            payloadN = payloadN,
            crc8 = crc8,
            timestamps = timestamps,
            statuses = statuses,
            presentKind = presentKind,
            unconfirmedReason = explicitReason ?: result?.unconfirmedReason,
            platformIds = PlatformIds(
                surfaceInstanceId = identity.surfaceInstanceId,
                eglFrameId = eglFrameId,
                vsyncId = timeline?.vsyncId?.toString(),
                drawableId = null,
            ),
            platformTimestamps = platformTimestamps,
            recordedRawNs = recordedRaw,
            recordedNs = relativeNs(recordedRaw),
        )
    }

    private fun localField(
        statuses: MutableMap<TimestampPrefix, TimestampStatus>,
        timestamps: MutableMap<TimestampPrefix, StandardTimestampValue>,
        platformTimestamps: MutableMap<String, PlatformTimestampEntry>,
        prefix: TimestampPrefix,
        api: String,
        rawNs: Long?,
    ) {
        if (rawNs == null) {
            statuses[prefix] = TimestampStatus.NOT_APPLICABLE
            timestamps[prefix] = StandardTimestampValue(null, null)
            platformTimestamps[prefix.key] = PlatformTimestampEntry(api, null, "NS", NATIVE_CLOCK, null)
            return
        }
        statuses[prefix] = TimestampStatus.VALID
        timestamps[prefix] = StandardTimestampValue(rawNs, relativeNs(rawNs))
        platformTimestamps[prefix.key] =
            PlatformTimestampEntry(api, rawNs.toString(), "NS", NATIVE_CLOCK, null)
    }

    /** Everything frozen when a frame was submitted; late results are merged into it. */
    private class FrameState(submission: FrameSubmission) {
        val identity: FrameIdentity = submission.identity
        val timeline: VsyncTimeline = submission.timeline
        val submitNanos: Long = submission.submitNanos
        val eglFrameId: String? = submission.eglFrameId
        val presentationRequestNanos: Long? = submission.presentationRequestNanos
        val payloadN: Int? = submission.payloadN
        val crc8: Int? = submission.crc8

        var lastResult: FrameResult? = null
        var presented: Boolean = false
        var lastReason: String? = null
        var reasonAtClose: String? = null
    }

    private data class FrameKey(val segmentId: String, val nativeFrameId: String)

    private companion object {
        const val NATIVE_CLOCK = "System.nanoTime"
        const val EGL_PRESENTATION_REQUEST = "egl_presentation_request"

        val EGL_PREFIXES = listOf(
            TimestampPrefix.SYSTEM_PRESENT,
            TimestampPrefix.RENDER_COMPLETE,
            TimestampPrefix.LATCH,
        )

        val EGL_API = mapOf(
            TimestampPrefix.SYSTEM_PRESENT to
                "eglGetFrameTimestampsANDROID:EGL_DISPLAY_PRESENT_TIME_ANDROID",
            TimestampPrefix.RENDER_COMPLETE to
                "eglGetFrameTimestampsANDROID:EGL_RENDERING_COMPLETE_TIME_ANDROID",
            TimestampPrefix.LATCH to
                "eglGetFrameTimestampsANDROID:EGL_COMPOSITION_LATCH_TIME_ANDROID",
        )
    }
}
