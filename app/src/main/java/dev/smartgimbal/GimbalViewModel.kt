package dev.smartgimbal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.smartgimbal.gimbal.GimbalSession

/** Activity-scoped owner of the [GimbalSession], so the BLE link survives screen switches. */
class GimbalViewModel(app: Application) : AndroidViewModel(app) {

    val session = GimbalSession(app, viewModelScope)

    override fun onCleared() {
        session.close()
    }
}
