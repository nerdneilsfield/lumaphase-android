package dev.lumaphase.android.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key names written by [LayoutJson] must cover exactly the fields OPTC-1 requires for
 * the layout collection, a layout object, a module and a marker (sections 3.1 and 4).
 * The lists below are transcribed from the contract, not from the writer.
 */
class LayoutJsonTest {

    @Test
    fun rootKeysAndSchemaVersion() {
        assertEquals(listOf("schema_version", "layouts"), LayoutJson.ROOT_KEYS)
        assertEquals("1.0", LayoutJson.SCHEMA_VERSION)
    }

    @Test
    fun layoutKeysMatchTheContract() {
        assertEquals(
            listOf(
                "layout_id",
                "layout_profile",
                "drawable_width_px",
                "drawable_height_px",
                "coordinate_system",
                "pattern_rotation_deg",
                "pattern_width_px",
                "pattern_height_px",
                "pattern_to_drawable",
                "dynamic_rect_px",
                "modules",
                "markers",
                "level_low",
                "level_high",
            ),
            LayoutJson.LAYOUT_KEYS,
        )
    }

    @Test
    fun moduleAndMarkerKeysMatchTheContract() {
        assertEquals(listOf("index", "rect_px"), LayoutJson.MODULE_KEYS)
        assertEquals(
            listOf(
                "id",
                "dictionary",
                "marker_size_bits",
                "border_bits",
                "cell_size_px",
                "quiet_zone_px",
                "corners_px",
            ),
            LayoutJson.MARKER_KEYS,
        )
    }

    @Test
    fun keyConstantsAreDistinct() {
        val all = LayoutJson.ROOT_KEYS + LayoutJson.LAYOUT_KEYS +
            LayoutJson.MODULE_KEYS + LayoutJson.MARKER_KEYS
        assertTrue("duplicate key names", all.size == all.toSet().size)
    }
}
