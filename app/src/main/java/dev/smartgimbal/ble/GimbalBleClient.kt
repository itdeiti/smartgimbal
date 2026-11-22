package dev.smartgimbal.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.smartgimbal.protocol.FeiyuProtocol
import dev.smartgimbal.protocol.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class FoundDevice(val name: String, val address: String, val rssi: Int)

enum class LinkState { IDLE, SCANNING, CONNECTING, DISCOVERING, READY }

/**
 * BLE link to the VLOG pocket: scan, connect, enable RX notifications, write TX frames.
 *
 * Callers must hold BLUETOOTH_SCAN and BLUETOOTH_CONNECT; the UI requests them before use.
 */
@SuppressLint("MissingPermission")
class GimbalBleClient(
    private val context: Context,
    private val log: (LogEntry) -> Unit,
    /** Every notification from RX, called on a binder thread. */
    private val onRx: (ByteArray) -> Unit,
) {
    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(LinkState.IDLE)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val devices: StateFlow<List<FoundDevice>> = _devices.asStateFlow()

    private val _connectedName = MutableStateFlow<String?>(null)
    val connectedName: StateFlow<String?> = _connectedName.asStateFlow()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var txChar: BluetoothGattCharacteristic? = null
    @Volatile private var pendingWrite: CompletableDeferred<Int>? = null
    private val writeMutex = Mutex()
    private val stopScanRunnable = Runnable { stopScan() }

    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    // region Scan

    fun startScan() {
        if (_state.value != LinkState.IDLE) return
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            log(LogEntry.error("Bluetooth ist aus oder nicht verfügbar"))
            return
        }
        _devices.value = emptyList()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        _state.value = LinkState.SCANNING
        log(LogEntry.info("Scan gestartet (${SCAN_TIMEOUT_MS / 1000} s)"))
        mainHandler.postDelayed(stopScanRunnable, SCAN_TIMEOUT_MS)
    }

    fun stopScan() {
        mainHandler.removeCallbacks(stopScanRunnable)
        if (_state.value != LinkState.SCANNING) return
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        _state.value = LinkState.IDLE
        log(LogEntry.info("Scan beendet, ${_devices.value.size} Gerät(e) gefunden"))
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!name.startsWith(FeiyuProtocol.DEVICE_NAME_PREFIX)) return
            val found = FoundDevice(name, result.device.address, result.rssi)
            _devices.update { list ->
                (list.filterNot { it.address == found.address } + found).sortedBy { it.name }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _state.value = LinkState.IDLE
            log(LogEntry.error("Scan fehlgeschlagen: errorCode=$errorCode"))
        }
    }

    // endregion

    // region Connection

    fun connect(device: FoundDevice) {
        stopScan()
        if (_state.value != LinkState.IDLE) return
        val remote = adapter?.getRemoteDevice(device.address) ?: return
        _state.value = LinkState.CONNECTING
        _connectedName.value = device.name
        log(LogEntry.info("Verbinde mit ${device.name} (${device.address})"))
        gatt = remote.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        val g = gatt ?: return
        log(LogEntry.info("Trenne Verbindung"))
        g.disconnect()
        // Fallback in case the stack never reports the disconnect.
        mainHandler.postDelayed({ if (gatt === g) cleanup(g, "Timeout beim Trennen") }, DISCONNECT_TIMEOUT_MS)
    }

    /** Immediate teardown, e.g. when the ViewModel is destroyed. */
    fun close() {
        stopScan()
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        txChar = null
        pendingWrite?.complete(WRITE_ABORTED)
        _state.value = LinkState.IDLE
    }

    private fun cleanup(g: BluetoothGatt, reason: String) {
        g.close()
        if (gatt === g) {
            gatt = null
            txChar = null
            _connectedName.value = null
            _state.value = LinkState.IDLE
        }
        pendingWrite?.complete(WRITE_ABORTED)
        log(LogEntry.info("Getrennt: $reason"))
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                _state.value = LinkState.DISCOVERING
                log(LogEntry.info("Verbunden, suche Services"))
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                mainHandler.post { g.discoverServices() }
            } else {
                mainHandler.post { cleanup(g, "status=$status newState=$newState") }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log(LogEntry.error("Service-Suche fehlgeschlagen: status=$status"))
                g.disconnect()
                return
            }
            // Dump the whole GATT table: useful while the protocol is still being explored.
            g.services.forEach { service ->
                log(LogEntry.info("Service ${service.uuid}"))
                service.characteristics.forEach { c ->
                    log(LogEntry.info("  Char ${c.uuid} [${describeProperties(c.properties)}]"))
                }
            }
            val all = g.services.flatMap { it.characteristics }
            val tx = all.firstOrNull { it.uuid == FeiyuProtocol.TX_UUID }
            val rx = all.firstOrNull { it.uuid == FeiyuProtocol.RX_UUID }
            if (tx == null) {
                log(LogEntry.error("TX-Characteristic ${FeiyuProtocol.TX_UUID} nicht gefunden"))
                g.disconnect()
                return
            }
            txChar = tx
            if (rx == null) {
                log(LogEntry.error("RX-Characteristic nicht gefunden, keine Notifications"))
                markReady()
                return
            }
            enableNotifications(g, rx)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != FeiyuProtocol.CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log(LogEntry.info("Notifications aktiv"))
            } else {
                log(LogEntry.error("CCCD-Write fehlgeschlagen: status=$status"))
            }
            markReady()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onRx(value)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            pendingWrite?.complete(status)
        }
    }

    private fun enableNotifications(g: BluetoothGatt, rx: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(rx, true)
        val cccd = rx.getDescriptor(FeiyuProtocol.CCCD_UUID)
        if (cccd == null) {
            log(LogEntry.error("RX hat keinen CCCD"))
            markReady()
            return
        }
        val value = if (rx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val rc = g.writeDescriptor(cccd, value)
        if (rc != BluetoothStatusCodes.SUCCESS) {
            log(LogEntry.error("CCCD-Write nicht gestartet: rc=$rc"))
            markReady()
        }
    }

    private fun markReady() {
        _state.value = LinkState.READY
        log(LogEntry.info("Bereit"))
    }

    // endregion

    // region Write

    /**
     * Writes one frame to TX and waits for the stack to accept it, so writes never overlap.
     * Returns false when not connected or the write failed.
     */
    suspend fun send(frame: ByteArray, logIt: Boolean = true): Boolean = writeMutex.withLock {
        val g = gatt
        val tx = txChar
        if (g == null || tx == null || _state.value != LinkState.READY) return@withLock false

        val writeType = if (tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val deferred = CompletableDeferred<Int>()
        pendingWrite = deferred
        val rc = g.writeCharacteristic(tx, frame, writeType)
        if (rc != BluetoothStatusCodes.SUCCESS) {
            pendingWrite = null
            log(LogEntry.error("Write nicht gestartet: rc=$rc  ${frame.toHex()}"))
            return@withLock false
        }
        val status = withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() }
        pendingWrite = null
        if (logIt) log(LogEntry.of(LogEntry.Kind.TX, frame.toHex()))
        when (status) {
            BluetoothGatt.GATT_SUCCESS -> true
            null -> true // no callback in time; the frame was handed to the stack anyway
            else -> {
                log(LogEntry.error("Write fehlgeschlagen: status=$status"))
                false
            }
        }
    }

    // endregion

    private fun describeProperties(p: Int): String = buildList {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
    }.joinToString(",")

    private companion object {
        const val SCAN_TIMEOUT_MS = 10_000L
        const val DISCONNECT_TIMEOUT_MS = 2_000L
        const val WRITE_TIMEOUT_MS = 300L
        const val WRITE_ABORTED = -1
    }
}
