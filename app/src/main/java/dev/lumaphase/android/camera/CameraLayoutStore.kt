package dev.lumaphase.android.camera

import android.content.Context
import dev.lumaphase.android.layout.ImportedLayout
import dev.lumaphase.android.layout.LayoutImportException
import dev.lumaphase.android.layout.LayoutJsonImport

/**
 * Private, app-local memory of the last layout source text and explicitly selected layout_id.
 * Restoration always goes through LayoutJsonImport; a stale/corrupt preference is discarded and
 * never interpreted as a guessed layout.
 */
object CameraLayoutStore {
    private const val PREFS = "lumaphase_camera_layout"
    private const val KEY_TEXT = "layout_json"
    private const val KEY_ID = "layout_id"

    data class Saved(val text: String, val layoutId: String?)

    fun load(context: Context): Saved? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY_TEXT, null) ?: return null
        if (text.isBlank()) return null
        return Saved(text, prefs.getString(KEY_ID, null))
    }

    fun save(context: Context, text: String, layoutId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TEXT, text)
            .putString(KEY_ID, layoutId)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

/** Pure restore choice; malformed text or unknown preferred id is surfaced, never guessed. */
object CameraLayoutRestore {
    data class Restored(val layouts: List<ImportedLayout>, val selectedIndex: Int)

    fun restore(text: String, preferredLayoutId: String?): Restored {
        val layouts = LayoutJsonImport.parse(text)
        val selectedIndex = when {
            preferredLayoutId == null -> if (layouts.size == 1) 0 else -1
            else -> layouts.indexOfFirst { it.layoutId == preferredLayoutId }
        }
        if (preferredLayoutId != null && selectedIndex < 0) {
            throw LayoutImportException("saved layout '$preferredLayoutId' is not in the file")
        }
        return Restored(layouts, selectedIndex)
    }
}
