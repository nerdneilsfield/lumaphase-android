package dev.lumaphase.android.render

import android.opengl.EGL14
import android.util.Log
import dev.lumaphase.android.timing.ProbeCapabilities
import dev.lumaphase.android.timing.TimestampField
import dev.lumaphase.android.timing.TimestampPrefix
import dev.lumaphase.android.timing.TimestampStatus
import dev.lumaphase.android.timing.UnconfirmedReason

/**
 * Capability query and per-frame timestamp query for the EGL-backed fields.
 *
 * All methods must run on the render thread with the EGL context current, because the
 * native entry points resolve the display and draw surface of the calling thread.
 *
 * Only `system_present`, `render_complete` and `latch` are EGL timestamps. `target_present`,
 * `deadline` and `submit` come from `Choreographer.FrameTimeline` and `System.nanoTime()`
 * and are carried as scalars on the submission instead, because T3 has no session time
 * origin from which to compute the contract's relative nanoseconds.
 */
class PresentationProbe(private val egl: EglCore) {

    private var capabilities: ProbeCapabilities? = null

    /**
     * Queries what this device offers: extension presence through `eglGetProcAddress`,
     * `EGL_TIMESTAMPS_ANDROID` on this surface, `EGL_ANDROID_presentation_time`, and each
     * timestamp type's own support. Extension presence is not enough to conclude that a
     * type is supported.
     */
    fun initialize(): ProbeCapabilities {
        val extensionString = egl.extensionString()
        val extensionAvailable = NativeEglFrameTimestamps.isExtensionAvailable()
        val timestampsEnabled = extensionAvailable &&
            egl.setSurfaceAttrib(EglTimestampValues.TIMESTAMPS_ANDROID, EGL14.EGL_TRUE)

        val supported = if (timestampsEnabled) {
            EGL_FIELDS.associateWith { field ->
                NativeEglFrameTimestamps.isTimestampTypeSupported(timestampTypeOf(field))
            }
        } else {
            EGL_FIELDS.associateWith { false }
        }

        val queried = ProbeCapabilities(
            extensionAvailable = extensionAvailable,
            timestampsEnabled = timestampsEnabled,
            presentationTimeSupported = extensionString.contains(PRESENTATION_TIME_EXTENSION),
            supportedTypes = supported,
            extensionString = extensionString,
        )
        capabilities = queried
        Log.i(
            TAG,
            "extension=${queried.extensionAvailable} enabled=${queried.timestampsEnabled} " +
                "presentation_time=${queried.presentationTimeSupported} " +
                "system_present=${queried.supports(TimestampPrefix.SYSTEM_PRESENT)} " +
                "render_complete=${queried.supports(TimestampPrefix.RENDER_COMPLETE)} " +
                "latch=${queried.supports(TimestampPrefix.LATCH)}",
        )
        return queried
    }

    fun capabilities(): ProbeCapabilities? = capabilities

    /**
     * The frame id of the next swap, or null when no id could be obtained.
     *
     * The EGL error is captured here, immediately after the failing call, because a later
     * `eglGetError()` (after the presentation request or the swap) would report a different
     * call's error.
     */
    fun nextFrameId(): FrameIdResult {
        val current = capabilities ?: return FrameIdResult(null, null)
        if (!current.extensionAvailable || !current.timestampsEnabled) {
            return FrameIdResult(null, null)
        }
        val holder = LongArray(1)
        if (!NativeEglFrameTimestamps.nextFrameId(holder)) {
            return FrameIdResult(null, egl.lastError())
        }
        return FrameIdResult(holder[0], null)
    }

    /**
     * Queries all three EGL fields for one frame. Unsupported types are never queried and
     * report `UNSUPPORTED`; a failed call reports `QUERY_FAILED` for every queried type with
     * no native value, and `HISTORY_EXPIRED` when the platform explicitly reports that the
     * history is gone.
     */
    fun query(frameId: Long): FrameProbeResult {
        val current = capabilities ?: return neverQueried()

        val fields = HashMap<TimestampPrefix, TimestampField>(EGL_FIELDS.size)
        val queriedFields = ArrayList<TimestampPrefix>(EGL_FIELDS.size)
        for (field in EGL_FIELDS) {
            if (current.supports(field)) {
                queriedFields += field
            } else {
                fields[field] = TimestampField(TimestampStatus.UNSUPPORTED, null)
            }
        }
        if (queriedFields.isEmpty()) {
            return FrameProbeResult(fields, UnconfirmedReason.TIMESTAMP_UNSUPPORTED, null)
        }

        val types = IntArray(queriedFields.size) { timestampTypeOf(queriedFields[it]) }
        val values = LongArray(queriedFields.size)
        val succeeded = NativeEglFrameTimestamps.queryTimestamps(frameId, types, values)
        val errorCode = if (succeeded) null else egl.lastError()

        queriedFields.forEachIndexed { index, field ->
            val status = EglTimestampValues.statusOf(
                supported = true,
                querySucceeded = succeeded,
                nativeValue = values[index],
            )
            // A failed call produced no trustworthy value, so nothing is retained as one.
            fields[field] = TimestampField(status, if (succeeded) values[index] else null)
        }

        val reason = when {
            !succeeded && errorCode == EglTimestampValues.EGL_BAD_ACCESS -> UnconfirmedReason.HISTORY_EXPIRED
            !succeeded -> UnconfirmedReason.QUERY_FAILED
            else -> EglTimestampValues.reasonOf(
                fields[TimestampPrefix.SYSTEM_PRESENT]?.status ?: TimestampStatus.UNSUPPORTED,
            )
        }
        return FrameProbeResult(fields, reason, errorCode)
    }

    /** Fields for a frame that was never queried, e.g. because no frame id was available. */
    fun neverQueried(): FrameProbeResult {
        val current = capabilities
        val fields = EGL_FIELDS.associateWith { field ->
            val supported = current != null && current.supports(field)
            TimestampField(
                if (supported) TimestampStatus.PENDING else TimestampStatus.UNSUPPORTED,
                null,
            )
        }
        // The caller decides why such a frame is unconfirmed; there is no query outcome here.
        return FrameProbeResult(fields, null, null)
    }

    /**
     * Fields for a frame that can no longer be queried at all, for example because no EGL
     * frame id could be obtained for it. A supported type is reported as `QUERY_FAILED`, not
     * as `PENDING`, because no later callback can ever query it; an unsupported type stays
     * `UNSUPPORTED`.
     */
    fun unqueryable(): FrameProbeResult {
        val current = capabilities
        val fields = EGL_FIELDS.associateWith { field ->
            TimestampField(
                EglTimestampValues.unqueryableStatus(current != null && current.supports(field)),
                null,
            )
        }
        return FrameProbeResult(fields, null, null)
    }

    private fun timestampTypeOf(field: TimestampPrefix): Int = when (field) {
        TimestampPrefix.SYSTEM_PRESENT -> EglTimestampValues.DISPLAY_PRESENT_TIME_ANDROID
        TimestampPrefix.RENDER_COMPLETE -> EglTimestampValues.RENDERING_COMPLETE_TIME_ANDROID
        TimestampPrefix.LATCH -> EglTimestampValues.COMPOSITION_LATCH_TIME_ANDROID
        else -> throw IllegalArgumentException("$field is not an EGL timestamp")
    }

    private companion object {
        const val TAG = "OptcProbe"
        const val PRESENTATION_TIME_EXTENSION = "EGL_ANDROID_presentation_time"
        val EGL_FIELDS = listOf(
            TimestampPrefix.SYSTEM_PRESENT,
            TimestampPrefix.RENDER_COMPLETE,
            TimestampPrefix.LATCH,
        )
    }
}

/** The per-field outcome of one `eglGetFrameTimestampsANDROID` call. */
data class FrameProbeResult(
    val fields: Map<TimestampPrefix, TimestampField>,
    val unconfirmedReason: String?,
    val queryErrorCode: Int?,
) {
    /** True while the field that decides a frame's presentation is still pending. */
    val systemPresentPending: Boolean
        get() = fields[TimestampPrefix.SYSTEM_PRESENT]?.status == TimestampStatus.PENDING
}

/**
 * The outcome of `eglGetNextFrameIdANDROID`. When the call was attempted and failed,
 * [errorCode] is the `eglGetError()` sampled immediately afterwards; when the extension is
 * simply unusable it is null and no call was made.
 */
data class FrameIdResult(
    val frameId: Long?,
    val errorCode: Int?,
)
