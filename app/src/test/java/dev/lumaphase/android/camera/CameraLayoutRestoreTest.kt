package dev.lumaphase.android.camera

import dev.lumaphase.android.layout.LayoutImportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLayoutRestoreTest {
    @Test
    fun selectedIdChoosesTheExactLayoutAndMultipleWithoutIdRequiresChoice() {
        val restored = CameraLayoutRestore.restore(resource("camera/multi-1.1.json"), "camera-test-c52-grid")
        assertEquals(2, restored.layouts.size)
        assertEquals(1, restored.selectedIndex)
        val unresolved = CameraLayoutRestore.restore(resource("camera/multi-1.1.json"), null)
        assertEquals(2, unresolved.layouts.size)
        assertEquals(-1, unresolved.selectedIndex)
        val single = CameraLayoutRestore.restore(resource("camera/valid-1.1.json"), null)
        assertEquals(0, single.selectedIndex)
    }

    @Test
    fun unknownIdAndMalformedTextAreRejectedNotGuessed() {
        assertThrows(LayoutImportException::class.java) {
            CameraLayoutRestore.restore(resource("camera/multi-1.1.json"), "missing-id")
        }
        assertThrows(LayoutImportException::class.java) {
            CameraLayoutRestore.restore("{not-json", "id")
        }
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
}
