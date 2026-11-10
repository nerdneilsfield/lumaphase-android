/*
 * The only native code in this app: a thin bridge to the three
 * EGL_ANDROID_get_frame_timestamps entry points.
 *
 * They have no public Java binding (android.opengl.EGL14, EGL15 and EGLExt expose no frame
 * timestamp function), so they are resolved with eglGetProcAddress: extension functions are
 * not exported by the stub libEGL that the NDK links against.
 *
 * The display and draw surface are taken from the calling thread with
 * eglGetCurrentDisplay() / eglGetCurrentSurface(EGL_DRAW). The caller is the app's render
 * thread, which has the EGL context current; EGL rendering stays in Kotlin and no private
 * Java handle is read.
 *
 * Token values and sentinels are from the Khronos extension text:
 * https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_get_frame_timestamps.txt
 *   EGL_TIMESTAMPS_ANDROID                0x3430
 *   EGL_RENDERING_COMPLETE_TIME_ANDROID   0x3435
 *   EGL_COMPOSITION_LATCH_TIME_ANDROID    0x3436
 *   EGL_DISPLAY_PRESENT_TIME_ANDROID      0x343A
 *   EGL_TIMESTAMP_PENDING_ANDROID        -2
 *   EGL_TIMESTAMP_INVALID_ANDROID        -1
 *   EGLnsecsANDROID is a 64-bit signed nanosecond count.
 * The extension never names the clock of these values, so the Kotlin side treats a real
 * value as CLOCK_DOMAIN_UNVERIFIED.
 *
 * The frame id is an unsigned 64-bit value. The EGLBoolean returned by each call is the
 * authoritative success flag; 0 and negative ids are not failure sentinels.
 */

#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <stdint.h>
#include <string.h>

/* Kept as an upper bound so no allocation happens inside the bridge. The app queries three. */
#define OPTCEGL_MAX_QUERY 8

/*
 * ABI-exact local aliases: EGLuint64KHR is a 64-bit unsigned integer and EGLnsecsANDROID a
 * 64-bit signed nanosecond count. Using plain fixed-width types keeps this file independent
 * of whether the toolchain's <EGL/eglext.h> declares the extension.
 */
typedef uint64_t optc_egl_frame_id;
typedef int64_t optc_egl_nsecs;

typedef EGLBoolean (*PFN_optc_get_next_frame_id)(EGLDisplay, EGLSurface, optc_egl_frame_id *);
typedef EGLBoolean (*PFN_optc_timestamp_supported)(EGLDisplay, EGLSurface, EGLint);
typedef EGLBoolean (*PFN_optc_get_frame_timestamps)(
        EGLDisplay, EGLSurface, optc_egl_frame_id, EGLint, const EGLint *, optc_egl_nsecs *);

static PFN_optc_get_next_frame_id g_next_frame_id = NULL;
static PFN_optc_timestamp_supported g_timestamp_supported = NULL;
static PFN_optc_get_frame_timestamps g_get_frame_timestamps = NULL;
/* 0 = not tried, 1 = all entry points resolved, -1 = unavailable. */
static int g_load_state = 0;

static void optc_load_entry_points(void) {
    if (g_load_state != 0) {
        return;
    }
    g_next_frame_id = (PFN_optc_get_next_frame_id) eglGetProcAddress("eglGetNextFrameIdANDROID");
    g_timestamp_supported =
            (PFN_optc_timestamp_supported) eglGetProcAddress("eglGetFrameTimestampSupportedANDROID");
    g_get_frame_timestamps =
            (PFN_optc_get_frame_timestamps) eglGetProcAddress("eglGetFrameTimestampsANDROID");
    g_load_state = (g_next_frame_id != NULL && g_timestamp_supported != NULL &&
                    g_get_frame_timestamps != NULL) ? 1 : -1;
}

static int optc_current_target(EGLDisplay *display, EGLSurface *surface) {
    *display = eglGetCurrentDisplay();
    *surface = eglGetCurrentSurface(EGL_DRAW);
    return (*display != EGL_NO_DISPLAY && *surface != EGL_NO_SURFACE) ? 1 : 0;
}

JNIEXPORT jboolean JNICALL
Java_dev_lumaphase_android_render_NativeEglFrameTimestamps_isExtensionAvailable(
        JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    optc_load_entry_points();
    return (g_load_state == 1) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_lumaphase_android_render_NativeEglFrameTimestamps_isTimestampTypeSupported(
        JNIEnv *env, jobject thiz, jint timestamp_type) {
    EGLDisplay display;
    EGLSurface surface;
    (void) env;
    (void) thiz;
    optc_load_entry_points();
    if (g_load_state != 1 || !optc_current_target(&display, &surface)) {
        return JNI_FALSE;
    }
    return (g_timestamp_supported(display, surface, (EGLint) timestamp_type) == EGL_TRUE)
           ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_lumaphase_android_render_NativeEglFrameTimestamps_nextFrameId(
        JNIEnv *env, jobject thiz, jlongArray out_frame_id) {
    EGLDisplay display;
    EGLSurface surface;
    optc_egl_frame_id frame_id = 0;
    jlong bits = 0;
    (void) thiz;
    optc_load_entry_points();
    if (g_load_state != 1 || out_frame_id == NULL ||
        (*env)->GetArrayLength(env, out_frame_id) < 1) {
        return JNI_FALSE;
    }
    if (!optc_current_target(&display, &surface)) {
        return JNI_FALSE;
    }
    if (g_next_frame_id(display, surface, &frame_id) != EGL_TRUE) {
        return JNI_FALSE;
    }
    /* Bit pattern preserved: the id is unsigned, the success flag already decided. */
    memcpy(&bits, &frame_id, sizeof(bits));
    (*env)->SetLongArrayRegion(env, out_frame_id, 0, 1, &bits);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_dev_lumaphase_android_render_NativeEglFrameTimestamps_queryTimestamps(
        JNIEnv *env, jobject thiz, jlong frame_id_bits, jintArray types, jlongArray out_values) {
    EGLDisplay display;
    EGLSurface surface;
    jsize count;
    jint *type_elements;
    EGLint names[OPTCEGL_MAX_QUERY];
    optc_egl_nsecs values[OPTCEGL_MAX_QUERY];
    jlong out[OPTCEGL_MAX_QUERY];
    optc_egl_frame_id frame_id = 0;
    jsize i;
    (void) thiz;

    optc_load_entry_points();
    if (g_load_state != 1 || types == NULL || out_values == NULL) {
        return JNI_FALSE;
    }
    count = (*env)->GetArrayLength(env, types);
    if (count <= 0 || count > OPTCEGL_MAX_QUERY ||
        (*env)->GetArrayLength(env, out_values) < count) {
        return JNI_FALSE;
    }
    if (!optc_current_target(&display, &surface)) {
        return JNI_FALSE;
    }

    type_elements = (*env)->GetIntArrayElements(env, types, NULL);
    if (type_elements == NULL) {
        return JNI_FALSE;
    }
    for (i = 0; i < count; ++i) {
        names[i] = (EGLint) type_elements[i];
    }
    (*env)->ReleaseIntArrayElements(env, types, type_elements, JNI_ABORT);

    memcpy(&frame_id, &frame_id_bits, sizeof(frame_id));
    if (g_get_frame_timestamps(display, surface, frame_id, (EGLint) count, names, values) != EGL_TRUE) {
        return JNI_FALSE;
    }
    for (i = 0; i < count; ++i) {
        memcpy(&out[i], &values[i], sizeof(out[i]));
    }
    (*env)->SetLongArrayRegion(env, out_values, 0, count, out);
    return JNI_TRUE;
}
