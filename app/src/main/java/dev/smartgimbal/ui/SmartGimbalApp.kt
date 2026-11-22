package dev.smartgimbal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.smartgimbal.GimbalViewModel

private enum class Screen { CAMERA, DEBUG, HELP }

/** Two screens sharing one [dev.smartgimbal.gimbal.GimbalSession]: camera (main) and debug. */
@Composable
fun SmartGimbalApp(vm: GimbalViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.CAMERA) }
    // Kept here, outside the camera screen, so the choice survives a trip to the debug screen.
    var useFrontCamera by rememberSaveable { mutableStateOf(false) }
    when (screen) {
        Screen.CAMERA -> CameraScreen(
            vm = vm,
            useFrontCamera = useFrontCamera,
            onToggleCamera = { useFrontCamera = !useFrontCamera },
            onOpenDebug = { screen = Screen.DEBUG },
            onOpenHelp = { screen = Screen.HELP },
        )
        Screen.HELP -> {
            BackHandler { screen = Screen.CAMERA }
            HelpScreen(onBack = { screen = Screen.CAMERA })
        }
        Screen.DEBUG -> {
            BackHandler { screen = Screen.CAMERA }
            DebugScreen(
                session = vm.session,
                trackingSettings = vm.trackingSettings,
                voice = vm.voice,
                onBack = { screen = Screen.CAMERA },
            )
        }
    }
}
