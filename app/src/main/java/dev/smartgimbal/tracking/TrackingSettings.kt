package dev.smartgimbal.tracking

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [TrackingParams] persisted in SharedPreferences, so tuning survives restarts and reinstalls. */
class TrackingSettings(context: Context) {

    private val prefs = context.getSharedPreferences("tracking", Context.MODE_PRIVATE)
    private val defaults = TrackingParams()

    private val _params = MutableStateFlow(migrateAndLoad())
    val params: StateFlow<TrackingParams> = _params.asStateFlow()

    fun update(transform: (TrackingParams) -> TrackingParams) {
        val next = transform(_params.value)
        _params.value = next
        save(next)
    }

    fun resetToDefaults() = update { defaults }

    /** Version 1 stored the wrong direction signs as defaults; drop them so the new defaults apply. */
    private fun migrateAndLoad(): TrackingParams {
        if (prefs.getInt("version", 1) < VERSION) {
            prefs.edit {
                remove("rearPanSign")
                remove("rearTiltSign")
                remove("frontPanSign")
                remove("frontTiltSign")
                putInt("version", VERSION)
            }
        }
        return load()
    }

    private fun load() = TrackingParams(
        kp = prefs.getFloat("kp", defaults.kp),
        kd = prefs.getFloat("kd", defaults.kd),
        deadzoneDeg = prefs.getFloat("deadzoneDeg", defaults.deadzoneDeg),
        maxUnits = prefs.getInt("maxUnits", defaults.maxUnits),
        maxAccelUnitsPerSec = prefs.getFloat("maxAccel", defaults.maxAccelUnitsPerSec),
        smoothing = prefs.getFloat("smoothing", defaults.smoothing),
        rearPanSign = prefs.getInt("rearPanSign", defaults.rearPanSign),
        rearTiltSign = prefs.getInt("rearTiltSign", defaults.rearTiltSign),
        frontPanSign = prefs.getInt("frontPanSign", defaults.frontPanSign),
        frontTiltSign = prefs.getInt("frontTiltSign", defaults.frontTiltSign),
        tiltUpLimitDeg = prefs.getFloat("tiltUpLimit", defaults.tiltUpLimitDeg),
        tiltDownLimitDeg = prefs.getFloat("tiltDownLimit", defaults.tiltDownLimitDeg),
    )

    private fun save(p: TrackingParams) = prefs.edit {
        putFloat("kp", p.kp)
        putFloat("kd", p.kd)
        putFloat("deadzoneDeg", p.deadzoneDeg)
        putInt("maxUnits", p.maxUnits)
        putFloat("maxAccel", p.maxAccelUnitsPerSec)
        putFloat("smoothing", p.smoothing)
        putInt("rearPanSign", p.rearPanSign)
        putInt("rearTiltSign", p.rearTiltSign)
        putInt("frontPanSign", p.frontPanSign)
        putInt("frontTiltSign", p.frontTiltSign)
        putFloat("tiltUpLimit", p.tiltUpLimitDeg)
        putFloat("tiltDownLimit", p.tiltDownLimitDeg)
        putInt("version", VERSION)
    }

    private companion object {
        const val VERSION = 2
    }
}
