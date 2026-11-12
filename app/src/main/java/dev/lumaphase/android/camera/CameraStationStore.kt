package dev.lumaphase.android.camera

import android.content.Context
import com.google.gson.Gson

/**
 * App-private storage for one fixed-station calibration. It is deliberately not named
 * `screen_geometry.json` and is not exported to the KB4/core consumer; the saved transform
 * is an uncalibrated raw-pixel planar homography for the Android camera test only.
 */
object CameraStationStore {
    private const val PREFS = "lumaphase_camera_station"
    private const val KEY_STATION = "station_json"
    private val gson = Gson()

    fun load(context: Context): StationCalibration? {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATION, null) ?: return null
        if (text.isBlank()) return null
        return runCatching {
            gson.fromJson(text, StationCalibration::class.java)
        }.getOrNull()?.takeIf { it.targetCorners.size == 4 }
    }

    fun save(context: Context, station: StationCalibration) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATION, gson.toJson(station))
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
