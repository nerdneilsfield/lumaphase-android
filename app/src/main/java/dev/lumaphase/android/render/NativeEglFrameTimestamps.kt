package dev.lumaphase.android.render

/**
 * The only JNI boundary in this app.
 *
 * It wraps the three `EGL_ANDROID_get_frame_timestamps` entry points, which have no public
 * Java binding: `android.opengl.EGL14`, `EGL15` and `EGLExt` expose no frame timestamp
 * function, and `EGLExt` only has `eglPresentationTimeANDROID` (which is used directly from
 * Kotlin). The entry points are resolved with `eglGetProcAddress` because extension
 * functions are not exported by the stub `libEGL` the NDK links against.
 *
 * Every function must be called on the thread that currently has the EGL context current.
 * The native side resolves the display and draw surface for that thread with
 * `eglGetCurrentDisplay()` / `eglGetCurrentSurface(EGL_DRAW)`; EGL rendering itself stays in
 * Kotlin and no private Java handle is read.
 *
 * The declarations are public rather than `internal` on purpose: Kotlin mangles the JVM
 * names of internal functions, which would break the JNI lookup.
 *
 * The frame id is an unsigned 64-bit value carried in a `Long` bit pattern. The boolean
 * returned by each function is authoritative; 0 and negative values are not failure
 * sentinels.
 */
object NativeEglFrameTimestamps {
    init {
        System.loadLibrary("optc_egl_timestamps")
    }

    /** True when `eglGetProcAddress` supplied all three entry points. */
    external fun isExtensionAvailable(): Boolean

    /** `eglGetFrameTimestampSupportedANDROID` for one timestamp type. */
    external fun isTimestampTypeSupported(timestampType: Int): Boolean

    /**
     * `eglGetNextFrameIdANDROID` for the current draw surface: the frame id of the next
     * swap. Writes the id (bit pattern preserved) into `outFrameId[0]` and returns whether
     * the call reported success.
     */
    external fun nextFrameId(outFrameId: LongArray): Boolean

    /**
     * `eglGetFrameTimestampsANDROID` for `frameId`. Writes one raw value per requested type
     * into `outValues`, sentinels included, and returns whether the call reported success.
     */
    external fun queryTimestamps(frameId: Long, types: IntArray, outValues: LongArray): Boolean
}
