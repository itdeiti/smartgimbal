package dev.smartgimbal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

val BLE_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
)

/** Without these the main screen cannot work. */
val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA) + BLE_PERMISSIONS

/** Asked together with the required ones; without it recordings have no sound. */
val OPTIONAL_PERMISSIONS = arrayOf(
    Manifest.permission.RECORD_AUDIO,
)

fun Context.hasAll(permissions: Array<String>) = permissions.all {
    checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
}

val Context.hasMicPermission: Boolean get() = hasAll(OPTIONAL_PERMISSIONS)
