package dev.lumaphase.android.camera

import android.content.Context
import dev.lumaphase.android.layout.ImportedLayout
import dev.lumaphase.android.layout.LayoutImportException
import dev.lumaphase.android.layout.LayoutJsonImport

/**
 * Private, app-local memory of the last layout source and explicitly selected layout_id.
 * Restoration always goes through LayoutJsonImport for imported layouts; built-in presets use
 * their own keys and are never written or restored as imported JSON. A stale/corrupt preference
 * is discarded and never interpreted as a guessed layout.
 */
object CameraLayoutStore {
    private const val PREFS = "lumaphase_camera_layout"
    private const val KEY_TEXT = "layout_json"
    private const val KEY_ID = "layout_id"
    private const val KEY_SOURCE = "layout_source"
    private const val KEY_BUILTIN_ID = "builtin_preset_id"
    private const val KEY_BUILTIN_ROTATION = "builtin_preset_rotation"

    enum class Source { IMPORTED, BUILTIN_ANDROID, BUILTIN_MACOS }

    data class Saved(val text: String, val layoutId: String?)

    data class SavedBuiltIn(val source: Source, val presetId: String, val rotationDeg: Int)

    fun load(context: Context): Saved? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY_TEXT, null) ?: return null
        if (text.isBlank()) return null
        return Saved(text, prefs.getString(KEY_ID, null))
    }

    fun loadSource(context: Context): Source? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SOURCE, null) ?: return null
        return Source.entries.firstOrNull { it.name == value }
    }

    fun loadBuiltIn(context: Context): SavedBuiltIn? {
        val source = loadSource(context) ?: return null
        if (source == Source.IMPORTED) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val presetId = prefs.getString(KEY_BUILTIN_ID, null) ?: return null
        val rotation = prefs.getInt(KEY_BUILTIN_ROTATION, -1)
        if (rotation < 0) return null
        return SavedBuiltIn(source, presetId, rotation)
    }

    fun save(context: Context, text: String, layoutId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TEXT, text)
            .putString(KEY_ID, layoutId)
            .putString(KEY_SOURCE, Source.IMPORTED.name)
            .apply()
    }

    fun saveBuiltIn(
        context: Context,
        source: Source,
        presetId: String,
        rotationDeg: Int,
    ) {
        require(source != Source.IMPORTED) { "imported layouts must use save()" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOURCE, source.name)
            .putString(KEY_BUILTIN_ID, presetId)
            .putInt(KEY_BUILTIN_ROTATION, rotationDeg)
            .apply()
    }

    /** Removes only the imported text/id so a built-in selection stays restorable. */
    fun clearImported(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_TEXT).remove(KEY_ID).apply()
        if (prefs.getString(KEY_SOURCE, null) == Source.IMPORTED.name) {
            prefs.edit().remove(KEY_SOURCE).apply()
        }
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
