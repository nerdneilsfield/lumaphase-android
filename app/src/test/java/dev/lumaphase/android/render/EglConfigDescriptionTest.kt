package dev.lumaphase.android.render

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pixel-format string is a fixed description of the values actually queried from the
 * chosen EGLConfig. A failed query must be `UNKNOWN`; it must never look like a successful
 * format.
 */
class EglConfigDescriptionTest {

    @Test
    fun successfulQueriesUseTheContractOrderAndNames() {
        assertEquals(
            "EGL_CONFIG(R=8,G=8,B=8,A=8,BUFFER_SIZE=32)",
            eglConfigDescriptionOrUnknown(8, 8, 8, 8, 32),
        )
        assertEquals(
            "EGL_CONFIG(R=8,G=8,B=8,A=0,BUFFER_SIZE=24)",
            eglConfigDescriptionOrUnknown(8, 8, 8, 0, 24),
        )
    }

    @Test
    fun anyMissingAttributeIsUnknown() {
        assertEquals("UNKNOWN", eglConfigDescriptionOrUnknown(null, 8, 8, 8, 32))
        assertEquals("UNKNOWN", eglConfigDescriptionOrUnknown(8, null, 8, 8, 32))
        assertEquals("UNKNOWN", eglConfigDescriptionOrUnknown(8, 8, null, 8, 32))
        assertEquals("UNKNOWN", eglConfigDescriptionOrUnknown(8, 8, 8, null, 32))
        assertEquals("UNKNOWN", eglConfigDescriptionOrUnknown(8, 8, 8, 8, null))
    }
}
