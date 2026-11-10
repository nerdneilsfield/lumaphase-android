package dev.lumaphase.android.layout

import dev.lumaphase.android.protocol.LayoutProfile
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export must write the layout the preview actually rendered. These tests use a
 * full-screen surface size and a different window-metrics size, so any implementation that
 * rebuilt geometry from a window estimate instead of the rendered layout would show up.
 *
 * The serialiser is injected, because Android's `JsonWriter` cannot run in a JVM unit
 * test; that is the only part of the path this file does not exercise.
 */
class PreviewedLayoutTest {

    @Test
    fun snapshotIsCapturedFromTheRenderedLayout() {
        val surfaceLayout = build(heightPx = 2400)
        val windowLayout = build(heightPx = 2280)
        assertNotEquals(
            "the two size sources must be distinguishable",
            surfaceLayout.drawableHeightPx,
            windowLayout.drawableHeightPx,
        )

        var serialisedLayout: PatternLayout? = null
        val snapshot = LayoutExport.capture(surfaceLayout) { layouts ->
            serialisedLayout = layouts.single()
            "serialised-surface-layout"
        }

        assertSame("the snapshot must come from the layout that was drawn", surfaceLayout, serialisedLayout)
        assertEquals(1080, snapshot.drawableWidthPx)
        assertEquals(2400, snapshot.drawableHeightPx)
        assertEquals(LayoutProfile.F68, snapshot.profile)
        assertEquals(0, snapshot.patternRotationDeg)
        assertEquals("serialised-surface-layout", snapshot.json)
        assertNotEquals(windowLayout.drawableHeightPx, snapshot.drawableHeightPx)
    }

    @Test
    fun exportWritesTheSnapshotBytesVerbatim() {
        val snapshot = LayoutExport.capture(build(heightPx = 2400)) { "snapshot-bytes" }
        val writer = StringWriter()

        assertTrue(LayoutExport.write(writer, snapshot))
        assertEquals("snapshot-bytes", writer.toString())
    }

    @Test
    fun exportWritesNothingWithoutARenderedLayout() {
        val writer = StringWriter()

        assertFalse(LayoutExport.write(writer, null))
        assertEquals("", writer.toString())
    }

    @Test
    fun descriptionNamesTheRenderedSizeAndRotation() {
        val snapshot = LayoutExport.capture(build(heightPx = 2400, rotationDeg = 270)) { "" }
        assertTrue(snapshot.description.contains("1080x2400"))
        assertTrue(snapshot.description.contains("270"))
        assertTrue(snapshot.description.contains("F68"))
    }

    private fun build(heightPx: Int, rotationDeg: Int = 0): PatternLayout = PatternLayoutBuilder.build(
        PatternLayoutBuilder.STATIC_LAYOUT_ID,
        LayoutProfile.F68,
        1080,
        heightPx,
        rotationDeg,
    )
}
