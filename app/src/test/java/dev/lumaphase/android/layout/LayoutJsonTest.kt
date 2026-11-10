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
        assertEquals("1.1", LayoutJson.SCHEMA_VERSION)
    }

    @Test
    fun layoutKeysMatchTheContract() {
        assertEquals(
            listOf(
                "layout_id",
                "layout_profile",
                "geometry_profile",
                "grid_rows",
                "grid_cols",
                "module_count",
                "padding_count",
                "module_order",
                "sampling_inner_fraction",
                "drawable_width_px",
                "drawable_height_px",
                "coordinate_system",
                "pattern_rotation_deg",
                "pattern_width_px",
                "pattern_height_px",
                "pattern_to_drawable",
                "dynamic_rect_px",
                "modules",
                "padding",
                "markers",
                "level_low",
                "level_high",
            ),
            LayoutJson.LAYOUT_KEYS,
        )
    }

    @Test
    fun modulePaddingAndMarkerKeysMatchTheContract() {
        assertEquals(listOf("index", "rect_px"), LayoutJson.MODULE_KEYS)
        assertEquals(
            listOf("slot_index", "role", "rect_px", "level"),
            LayoutJson.PADDING_KEYS,
        )
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
    fun keyConstantsAreDistinctWithinEachObject() {
        // rect_px appears in both a module and a padding object, which is intentional; keys
        // must be unique inside one object.
        val rootAndLayout = LayoutJson.ROOT_KEYS + LayoutJson.LAYOUT_KEYS
        assertTrue("duplicate root/layout key names", rootAndLayout.size == rootAndLayout.toSet().size)
        assertTrue(
            "duplicate module key names",
            LayoutJson.MODULE_KEYS.size == LayoutJson.MODULE_KEYS.toSet().size,
        )
        assertTrue(
            "duplicate padding key names",
            LayoutJson.PADDING_KEYS.size == LayoutJson.PADDING_KEYS.toSet().size,
        )
        assertTrue(
            "duplicate marker key names",
            LayoutJson.MARKER_KEYS.size == LayoutJson.MARKER_KEYS.toSet().size,
        )
    }
}
