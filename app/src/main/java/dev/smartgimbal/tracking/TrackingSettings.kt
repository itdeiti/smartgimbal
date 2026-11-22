package dev.smartgimbal.tracking

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.smartgimbal.PersistedParams

/** [TrackingParams] persisted in SharedPreferences, so tuning survives restarts. */
class TrackingSettings(context: Context) : PersistedParams<TrackingParams>(
    prefs = context.getSharedPreferences("tracking", Context.MODE_PRIVATE),
    load = { migrateAndLoad(it) },
    save = { p ->
        putFloat(KP, p.kp)
        putFloat(KD, p.kd)
        putFloat(DEADZONE, p.deadzoneDeg)
        putInt(MAX_UNITS, p.maxUnits)
        putFloat(MAX_ACCEL, p.maxAccelUnitsPerSec)
        putFloat(SMOOTHING, p.smoothing)
        putInt(REAR_PAN_SIGN, p.rearPanSign)
        putInt(REAR_TILT_SIGN, p.rearTiltSign)
        putInt(FRONT_PAN_SIGN, p.frontPanSign)
        putInt(FRONT_TILT_SIGN, p.frontTiltSign)
        putFloat(TILT_UP_LIMIT, p.tiltUpLimitDeg)
        putFloat(TILT_DOWN_LIMIT, p.tiltDownLimitDeg)
        putInt(VERSION_KEY, VERSION)
    },
) {
    fun resetToDefaults() = update { DEFAULTS }

    private companion object {
        const val VERSION = 2
        val DEFAULTS = TrackingParams()

        const val VERSION_KEY = "version"
        const val KP = "kp"
        const val KD = "kd"
        const val DEADZONE = "deadzoneDeg"
        const val MAX_UNITS = "maxUnits"
        const val MAX_ACCEL = "maxAccel"
        const val SMOOTHING = "smoothing"
        const val REAR_PAN_SIGN = "rearPanSign"
        const val REAR_TILT_SIGN = "rearTiltSign"
        const val FRONT_PAN_SIGN = "frontPanSign"
        const val FRONT_TILT_SIGN = "frontTiltSign"
        const val TILT_UP_LIMIT = "tiltUpLimit"
        const val TILT_DOWN_LIMIT = "tiltDownLimit"

        /** Version 1 stored the wrong direction signs as defaults; drop them so the new defaults apply. */
        fun migrateAndLoad(prefs: SharedPreferences): TrackingParams {
            if (prefs.getInt(VERSION_KEY, 1) < VERSION) {
                prefs.edit {
                    remove(REAR_PAN_SIGN)
                    remove(REAR_TILT_SIGN)
                    remove(FRONT_PAN_SIGN)
                    remove(FRONT_TILT_SIGN)
                    putInt(VERSION_KEY, VERSION)
                }
            }
            return TrackingParams(
                kp = prefs.getFloat(KP, DEFAULTS.kp),
                kd = prefs.getFloat(KD, DEFAULTS.kd),
                deadzoneDeg = prefs.getFloat(DEADZONE, DEFAULTS.deadzoneDeg),
                maxUnits = prefs.getInt(MAX_UNITS, DEFAULTS.maxUnits),
                maxAccelUnitsPerSec = prefs.getFloat(MAX_ACCEL, DEFAULTS.maxAccelUnitsPerSec),
                smoothing = prefs.getFloat(SMOOTHING, DEFAULTS.smoothing),
                rearPanSign = prefs.getInt(REAR_PAN_SIGN, DEFAULTS.rearPanSign),
                rearTiltSign = prefs.getInt(REAR_TILT_SIGN, DEFAULTS.rearTiltSign),
                frontPanSign = prefs.getInt(FRONT_PAN_SIGN, DEFAULTS.frontPanSign),
                frontTiltSign = prefs.getInt(FRONT_TILT_SIGN, DEFAULTS.frontTiltSign),
                tiltUpLimitDeg = prefs.getFloat(TILT_UP_LIMIT, DEFAULTS.tiltUpLimitDeg),
                tiltDownLimitDeg = prefs.getFloat(TILT_DOWN_LIMIT, DEFAULTS.tiltDownLimitDeg),
            )
        }
    }
}
