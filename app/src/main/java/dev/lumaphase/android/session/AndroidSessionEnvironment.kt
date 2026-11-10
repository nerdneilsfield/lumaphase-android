package dev.lumaphase.android.session

import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.Surface

/**
 * Reads the platform values the manifest and the segment snapshots record.
 *
 * Only what Android actually exposes is read. Values Android does not expose to an app
 * without vendor-specific settings (the colour mode name, the dimming mode, whether HDR,
 * true tone or night shift are on) are left null rather than guessed, which the contract
 * allows for fields typed "or null".
 */
object AndroidSessionEnvironment {

    fun appEnvironment(context: Context): AppEnvironment {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val build = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toString()
        }
        return AppEnvironment(
            sourceDeviceId = "${Build.MANUFACTURER}-${Build.MODEL}",
            appId = context.packageName,
            appVersion = packageInfo.versionName ?: "",
            appBuild = build,
            osVersion = Build.VERSION.RELEASE ?: "",
            osBuild = Build.FINGERPRINT ?: "",
        )
    }

    fun segmentEnvironment(
        activity: Activity,
        experimentProfile: String,
        operatorNote: String = "",
        requestedRefreshNum: Int = REQUESTED_REFRESH_NUM,
        requestedRefreshDen: Int = REQUESTED_REFRESH_DEN,
    ): SegmentEnvironment {
        val display = activity.display
        val mode = display?.mode
        val brightnessRead = readBrightness(activity)
        val autoBrightness = readAutoBrightness(activity)
        return SegmentEnvironment(
            experimentProfile = experimentProfile,
            displayModeId = mode?.modeId?.toString(),
            displayRotationDeg = display?.rotation?.let { rotationDegrees(it) },
            requestedRefreshNum = requestedRefreshNum,
            requestedRefreshDen = requestedRefreshDen,
            // Android reports the mode's refresh rate as a float and does not expose the exact
            // rational, so the nominal pair stays null instead of being faked.
            nominalRefreshNum = null,
            nominalRefreshDen = null,
            reportedRefreshHz = mode?.refreshRate?.toDouble(),
            brightnessSetting = brightnessRead,
            brightnessSource = if (brightnessRead == null) {
                BrightnessSource.UNKNOWN
            } else {
                BrightnessSource.SYSTEM_REPORTED
            },
            autoBrightnessEnabled = autoBrightness,
            colorMode = null,
            dimmingMode = null,
            hdrEnabled = null,
            trueToneEnabled = null,
            nightShiftEnabled = null,
            // No calibration is imported in this version, so the reference stays null and the
            // export binds the segment as MISSING.
            displayProfileId = null,
            operatorNote = operatorNote,
        )
    }

    /** The system brightness setting normalised to 0..1, or null when unreadable. */
    private fun readBrightness(context: Context): Double? {
        val raw = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            UNSET,
        )
        if (raw == UNSET || raw < 0) return null
        return (raw.toDouble() / BRIGHTNESS_MAX).coerceIn(0.0, 1.0)
    }

    private fun readAutoBrightness(context: Context): Boolean? {
        val raw = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            UNSET,
        )
        if (raw == UNSET) return null
        return raw == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }

    /** `Display.getRotation()` maps straight onto the contract's 0/90/180/270 labels. */
    fun rotationDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private const val UNSET = -1
    private const val BRIGHTNESS_MAX = 255.0

    /** The refresh rate this app requests (design section 6). */
    const val REQUESTED_REFRESH_NUM = 120
    const val REQUESTED_REFRESH_DEN = 1
}
