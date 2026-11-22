package dev.smartgimbal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.smartgimbal.gimbal.GimbalSession
import dev.smartgimbal.tracking.FacePipeline

/**
 * Activity-scoped owner of the long-lived parts: the [GimbalSession] (BLE link survives screen
 * switches) and the [FacePipeline] (detector is not rebuilt each time the camera screen opens).
 */
class GimbalViewModel(app: Application) : AndroidViewModel(app) {

    val session = GimbalSession(app, viewModelScope)
    val faces = FacePipeline()

    override fun onCleared() {
        session.close()
        faces.close()
    }
}
