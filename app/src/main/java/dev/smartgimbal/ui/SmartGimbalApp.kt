package dev.smartgimbal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.smartgimbal.GimbalViewModel

private enum class Screen { CAMERA, DEBUG }

/** Two screens sharing one [dev.smartgimbal.gimbal.GimbalSession]: camera (main) and debug. */
@Composable
fun SmartGimbalApp(vm: GimbalViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.CAMERA) }
    when (screen) {
        Screen.CAMERA -> CameraScreen(session = vm.session, onOpenDebug = { screen = Screen.DEBUG })
        Screen.DEBUG -> {
            BackHandler { screen = Screen.CAMERA }
            DebugScreen(session = vm.session, onBack = { screen = Screen.CAMERA })
        }
    }
}
