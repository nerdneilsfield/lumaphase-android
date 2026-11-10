package dev.lumaphase.android.layout

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MacReferenceLayoutsTest {
    private val layouts: List<ImportedLayout> by lazy {
        val file = File("src/main/assets/${MacReferenceLayouts.ASSET_PATH}")
        MacReferenceLayouts.load(file.readText())
    }

    @Test
    fun assetCarriesFourGeometriesAtEachRotationAtTheNamedReferenceSize() {
        assertEquals(16, layouts.size)
        assertEquals(16, layouts.map { it.layoutId }.toSet().size)
        for (geometry in MacReferenceLayouts.GEOMETRIES) {
            val r0Id = checkNotNull(MacReferenceLayouts.select(layouts, geometry.wire, 0)).layout.layoutId
            val r90Id = checkNotNull(MacReferenceLayouts.select(layouts, geometry.wire, 90)).layout.layoutId
            assertNotEquals("${geometry.wire} rotation ids", r0Id, r90Id)
            for (rotation in BuiltInLayouts.ROTATIONS_DEG) {
                val selection = MacReferenceLayouts.select(layouts, geometry.wire, rotation)
                checkNotNull(selection) { "missing ${geometry.wire} at $rotation" }
                val layout = selection.layout
                val label = "${geometry.wire} at $rotation"
                assertEquals(label, geometry, selection.geometry)
                assertEquals(label, rotation, selection.rotationDeg)
                assertEquals(label, rotation, layout.patternRotationDeg)
                assertEquals(label, MacReferenceLayouts.REFERENCE_WIDTH_PX, layout.drawableWidthPx)
                assertEquals(label, MacReferenceLayouts.REFERENCE_HEIGHT_PX, layout.drawableHeightPx)
                assertEquals(label, geometry.layoutProfile, layout.profile)
                assertEquals(label, geometry.moduleCount, layout.modules.size)
                assertEquals(label, geometry.paddingCount, layout.padding.size)
                assertEquals(label, listOf(0, 1, 2, 3), layout.markers.map { it.id })
                assertEquals(label, geometry.isGrid, layout.geometry?.isGrid)
            }
        }
    }

    @Test
    fun selectionReturnsTheParsedAssetGeometryWithoutRebuildingIt() {
        for (geometry in MacReferenceLayouts.GEOMETRIES) {
            val raw = layouts.first {
                it.geometry?.wire == geometry.wire && it.patternRotationDeg == 90
            }
            val selected = MacReferenceLayouts.select(layouts, geometry.wire, 90)
            checkNotNull(selected)
            assertSame(raw, selected.layout)
        }
    }

    @Test
    fun unknownGeometryOrRotationIsRejected() {
        assertNull(MacReferenceLayouts.select(layouts, "F68-G9x8", 0))
        assertNull(MacReferenceLayouts.select(layouts, "F68-G6x12", 45))
    }
}
