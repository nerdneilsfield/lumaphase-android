package dev.lumaphase.android.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

/**
 * Minimal EGL owner for one window [Surface].
 *
 * The configuration requests an RGB888 window surface with no alpha and asks for zero
 * sample buffers and zero samples. EGL config attributes are "at least" constraints, so
 * the request proves nothing on its own: each candidate returned by `eglChooseConfig` is
 * queried, and the first one that is *verified* to be single-sampled
 * (`EGL_SAMPLE_BUFFERS == 0`, `EGL_SAMPLES == 0`), to be an `EGL_RGB_BUFFER` and to have
 * exactly 8 bits each for red, green and blue is used. If no candidate qualifies, [init]
 * logs an error and returns false rather than dropping to a multisampled or non-8-bit
 * configuration: hard module edges and exact 8-bit SDR code values are requirements of the
 * optical target, not preferences. [describe] reports the values actually obtained,
 * including the sample counts and the surface colour space, so they can be checked in
 * logcat.
 *
 * No sRGB colour space is requested, so a fragment value of `level/255` is written to the
 * buffer as that 8-bit SDR code value. Whether the compositor and `screencap` preserve
 * those values is not verified by this task.
 *
 * All calls must happen on the single thread that created the context.
 */
class EglCore {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var config: EGLConfig? = null
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    val isInitialized: Boolean
        get() = display != EGL14.EGL_NO_DISPLAY && surface != EGL14.EGL_NO_SURFACE

    fun init(windowSurface: Surface): Boolean {
        release()
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay failed, error 0x${Integer.toHexString(EGL14.eglGetError())}")
            return false
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed, error 0x${Integer.toHexString(EGL14.eglGetError())}")
            display = EGL14.EGL_NO_DISPLAY
            return false
        }
        Log.i(TAG, "EGL ${version[0]}.${version[1]}")

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 0,
            EGL14.EGL_SAMPLE_BUFFERS, 0,
            EGL14.EGL_SAMPLES, 0,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(MAX_CONFIGS)
        val configCount = IntArray(1)
        val choseConfig = EGL14.eglChooseConfig(
            display,
            configAttribs,
            0,
            configs,
            0,
            configs.size,
            configCount,
            0,
        )
        if (!choseConfig) {
            Log.e(TAG, "eglChooseConfig failed, error 0x${Integer.toHexString(EGL14.eglGetError())}")
            return false
        }
        val candidates = configs.take(configCount[0]).filterNotNull()
        if (candidates.isEmpty()) {
            Log.e(TAG, "eglChooseConfig matched no config for an RGB888 single-sampled window surface")
            return false
        }
        // EGL config attributes are "at least" constraints, so the request alone proves
        // nothing: every candidate's real attributes are queried. The optical target needs
        // a plain 8-bit RGB buffer drawn without multisampling, so that a shader value of
        // level/255 lands in the buffer as that exact 8-bit SDR code value with hard module
        // edges. If no candidate qualifies there is no supported configuration and this
        // returns false instead of degrading.
        val selected = candidates.firstOrNull { isSingleSampledRgb8(it) }
        if (selected == null) {
            Log.e(
                TAG,
                "no single-sampled RGB888 EGL config among ${configCount[0]} candidates; " +
                    "refusing to render the optical target with multisampling or a non-8-bit buffer",
            )
            return false
        }
        config = selected

        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext failed, error 0x${Integer.toHexString(EGL14.eglGetError())}")
            return false
        }
        surface = EGL14.eglCreateWindowSurface(
            display,
            config,
            windowSurface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        if (surface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreateWindowSurface failed, error 0x${Integer.toHexString(EGL14.eglGetError())}")
            return false
        }
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            Log.e(TAG, "eglMakeCurrent failed, error 0x${Integer.toHexString(EGL14.eglGetError())}")
            return false
        }
        Log.i(TAG, describe())
        return true
    }

    fun swapBuffers(): Boolean {
        if (!isInitialized) return false
        return EGL14.eglSwapBuffers(display, surface)
    }

    fun lastError(): Int = EGL14.eglGetError()

    /** Sets a surface attribute, for example enabling `EGL_TIMESTAMPS_ANDROID`. */
    fun setSurfaceAttrib(attribute: Int, value: Int): Boolean {
        if (!isInitialized) return false
        return EGL14.eglSurfaceAttrib(display, surface, attribute, value)
    }

    /** The display's raw `EGL_EXTENSIONS` string, or empty when unavailable. */
    fun extensionString(): String =
        EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()

    /**
     * Requests a presentation time for the next swap through `EGL_ANDROID_presentation_time`
     * (bound as `EGLExt.eglPresentationTimeANDROID`). This is a request, not a result.
     */
    fun requestPresentationTime(nanos: Long): Boolean {
        if (!isInitialized) return false
        return EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
    }

    /** Configuration and surface attributes in force, for logcat evidence. */
    fun describe(): String {
        val chosen = config ?: return "no EGL config"
        val text = StringBuilder("config:")
        fun attrib(name: String, attribute: Int) {
            val value = IntArray(1)
            if (EGL14.eglGetConfigAttrib(display, chosen, attribute, value, 0)) {
                text.append(' ').append(name).append('=').append(value[0])
            }
        }
        attrib("red", EGL14.EGL_RED_SIZE)
        attrib("green", EGL14.EGL_GREEN_SIZE)
        attrib("blue", EGL14.EGL_BLUE_SIZE)
        attrib("alpha", EGL14.EGL_ALPHA_SIZE)
        attrib("sample_buffers", EGL14.EGL_SAMPLE_BUFFERS)
        attrib("samples", EGL14.EGL_SAMPLES)
        attrib("color_buffer_type", EGL14.EGL_COLOR_BUFFER_TYPE)
        attrib("native_visual_id", EGL14.EGL_NATIVE_VISUAL_ID)

        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()
        text.append(" has_EGL_KHR_gl_colorspace=").append(extensions.contains(EXT_KHR_GL_COLORSPACE))
        if (extensions.contains(EXT_KHR_GL_COLORSPACE) && surface != EGL14.EGL_NO_SURFACE) {
            val value = IntArray(1)
            if (EGL14.eglQuerySurface(display, surface, EGL_GL_COLORSPACE, value, 0)) {
                text.append(" surface_EGL_GL_COLORSPACE=0x")
                    .append(Integer.toHexString(value[0]))
                    .append(if (value[0] == EGL_GL_COLORSPACE_SRGB) "(srgb)" else "(not srgb)")
            }
        }
        return text.toString()
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, surface)
                surface = EGL14.EGL_NO_SURFACE
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context)
                context = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
            display = EGL14.EGL_NO_DISPLAY
        }
        config = null
    }

    private fun configInt(candidate: EGLConfig, attribute: Int): Int? {
        val value = IntArray(1)
        return if (EGL14.eglGetConfigAttrib(display, candidate, attribute, value, 0)) value[0] else null
    }

    /** True only for a config that is single-sampled and has an exact 8-bit per channel RGB buffer. */
    private fun isSingleSampledRgb8(candidate: EGLConfig): Boolean {
        val sampleBuffers = configInt(candidate, EGL14.EGL_SAMPLE_BUFFERS) ?: return false
        val samples = configInt(candidate, EGL14.EGL_SAMPLES) ?: return false
        val colorBufferType = configInt(candidate, EGL14.EGL_COLOR_BUFFER_TYPE) ?: return false
        val red = configInt(candidate, EGL14.EGL_RED_SIZE) ?: return false
        val green = configInt(candidate, EGL14.EGL_GREEN_SIZE) ?: return false
        val blue = configInt(candidate, EGL14.EGL_BLUE_SIZE) ?: return false
        return sampleBuffers == 0 &&
            samples == 0 &&
            colorBufferType == EGL14.EGL_RGB_BUFFER &&
            red == REQUIRED_CHANNEL_BITS &&
            green == REQUIRED_CHANNEL_BITS &&
            blue == REQUIRED_CHANNEL_BITS
    }

    private companion object {
        const val TAG = "OptcEgl"
        const val MAX_CONFIGS = 32
        const val REQUIRED_CHANNEL_BITS = 8
        const val EXT_KHR_GL_COLORSPACE = "EGL_KHR_gl_colorspace"
        /** `EGL_GL_COLORSPACE_KHR` / `EGL_GL_COLORSPACE_SRGB_KHR`, not exposed by android.opengl.EGL14. */
        const val EGL_GL_COLORSPACE = 0x308D
        const val EGL_GL_COLORSPACE_SRGB = 0x3089
    }
}
