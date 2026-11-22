package dev.smartgimbal.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import dev.smartgimbal.ble.GimbalBleClient

/**
 * Click action that starts a scan, asking the user to switch Bluetooth on first if it is off.
 * With [autoConnect] the scan connects by itself, see [GimbalBleClient.startScan].
 */
@Composable
fun rememberScanAction(client: GimbalBleClient, autoConnect: Boolean = false): () -> Unit {
    val enableBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { if (client.isBluetoothEnabled) client.startScan(autoConnect) }
    return {
        if (client.isBluetoothEnabled) {
            client.startScan(autoConnect)
        } else {
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }
}
