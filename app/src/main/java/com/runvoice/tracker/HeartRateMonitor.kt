package com.runvoice.tracker

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface HeartRateState {
    data object Idle : HeartRateState
    data object Unavailable : HeartRateState
    data object PermissionDenied : HeartRateState
    data object Scanning : HeartRateState
    data class Connecting(val address: String) : HeartRateState
    data class Connected(val address: String) : HeartRateState
    data class Error(val message: String) : HeartRateState
}

class HeartRateMonitor(context: Context) {
    companion object {
        val HR_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val PREFS_NAME = "runvoice_prefs"
        private const val KEY_HR_DEVICE_ADDRESS = "hr_device_address"
        private const val TAG = "HeartRateMonitor"
        private const val SCAN_TIMEOUT_MILLIS = 15_000L

        fun parseHeartRate(value: ByteArray): Int? {
            if (value.size < 2) return null
            val usesSixteenBits = value[0].toInt() and 0x01 != 0
            return if (usesSixteenBits) {
                if (value.size < 3) null
                else (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
            } else {
                value[1].toInt() and 0xFF
            }
        }
    }

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var reconnectAllowed = false

    private val _state = MutableStateFlow<HeartRateState>(
        if (bluetoothAdapter == null) HeartRateState.Unavailable else HeartRateState.Idle
    )
    val state = _state.asStateFlow()

    private val _heartRate = MutableStateFlow(0)
    val heartRate = _heartRate.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()

    data class BleDevice(val name: String, val address: String, val rssi: Int)

    private val _discoveredDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val discoveredDevices = _discoveredDevices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()
    private val deviceSet = mutableMapOf<String, BleDevice>()

    private val stopScanRunnable = Runnable { stopScan() }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasScanPermission()) {
            updateDisconnectedState(HeartRateState.PermissionDenied)
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            updateDisconnectedState(HeartRateState.Unavailable)
            return
        }
        if (_scanning.value) return

        runCatching {
            scanner = adapter.bluetoothLeScanner ?: error("BLE scanner unavailable")
            deviceSet.clear()
            _discoveredDevices.value = emptyList()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner?.startScan(null, settings, scanCallback)
            _scanning.value = true
            _state.value = HeartRateState.Scanning
            mainHandler.removeCallbacks(stopScanRunnable)
            mainHandler.postDelayed(stopScanRunnable, SCAN_TIMEOUT_MILLIS)
        }.onFailure { failure ->
            Log.w(TAG, "Unable to start BLE scan", failure)
            _scanning.value = false
            _state.value = if (failure is SecurityException) {
                HeartRateState.PermissionDenied
            } else {
                HeartRateState.Error("无法启动蓝牙扫描")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        mainHandler.removeCallbacks(stopScanRunnable)
        runCatching {
            if (hasScanPermission()) scanner?.stopScan(scanCallback)
        }.onFailure { Log.w(TAG, "Unable to stop BLE scan", it) }
        _scanning.value = false
        if (_state.value is HeartRateState.Scanning) _state.value = HeartRateState.Idle
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!hasConnectPermission()) {
                stopScan()
                _state.value = HeartRateState.PermissionDenied
                return
            }
            runCatching {
                val device = result.device
                val item = BleDevice(device.name ?: "未知设备", device.address, result.rssi)
                deviceSet[item.address] = item
                _discoveredDevices.value = deviceSet.values.sortedByDescending { it.rssi }
            }.onFailure { Log.w(TAG, "Unable to read scanned BLE device", it) }
        }

        override fun onScanFailed(errorCode: Int) {
            mainHandler.removeCallbacks(stopScanRunnable)
            _scanning.value = false
            _state.value = HeartRateState.Error("蓝牙扫描失败：$errorCode")
            Log.w(TAG, "BLE scan failed: $errorCode")
        }
    }

    fun saveDevice(address: String) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_HR_DEVICE_ADDRESS, address).apply()
    }

    fun getSavedDeviceAddress(): String? = appContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_HR_DEVICE_ADDRESS, null)

    fun clearSavedDevice() {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_HR_DEVICE_ADDRESS).apply()
        disconnect()
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(address: String) {
        stopScan()
        if (!hasConnectPermission()) {
            updateDisconnectedState(HeartRateState.PermissionDenied)
            return
        }
        runCatching {
            reconnectAllowed = false
            closeCurrentGatt()
            val device = bluetoothAdapter?.getRemoteDevice(address) ?: error("Bluetooth unavailable")
            reconnectAllowed = true
            _state.value = HeartRateState.Connecting(address)
            gatt = device.connectGatt(appContext, false, gattCallback)
        }.onFailure { failure ->
            reconnectAllowed = false
            updateDisconnectedState(
                if (failure is SecurityException) HeartRateState.PermissionDenied
                else HeartRateState.Error("无法连接心率设备")
            )
            Log.w(TAG, "Unable to connect BLE device", failure)
        }
    }

    fun connectSavedDevice() {
        val address = getSavedDeviceAddress() ?: return
        connectToDevice(address)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        reconnectAllowed = false
        closeCurrentGatt()
        updateDisconnectedState(HeartRateState.Idle)
    }

    @SuppressLint("MissingPermission")
    private fun closeCurrentGatt() {
        val current = gatt
        gatt = null
        if (current != null) {
            runCatching { current.disconnect() }
            runCatching { current.close() }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
            if (callbackGatt !== gatt) {
                runCatching { callbackGatt.close() }
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connected.value = true
                    _state.value = HeartRateState.Connected(callbackGatt.device.address)
                    runCatching { callbackGatt.discoverServices() }
                        .onFailure { _state.value = HeartRateState.Error("无法发现心率服务") }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    updateDisconnectedState(HeartRateState.Idle)
                    if (reconnectAllowed && hasConnectPermission()) {
                        runCatching {
                            _state.value = HeartRateState.Connecting(callbackGatt.device.address)
                            callbackGatt.connect()
                        }.onFailure {
                            reconnectAllowed = false
                            _state.value = HeartRateState.Error("心率设备重连失败")
                        }
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            if (callbackGatt !== gatt || status != BluetoothGatt.GATT_SUCCESS) return
            val characteristic = callbackGatt.getService(HR_SERVICE_UUID)
                ?.getCharacteristic(HR_CHARACTERISTIC_UUID) ?: run {
                _state.value = HeartRateState.Error("设备不支持标准心率服务")
                return
            }
            val descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID) ?: return
            runCatching {
                callbackGatt.setCharacteristicNotification(characteristic, true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    callbackGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        callbackGatt.writeDescriptor(descriptor)
                    }
                }
            }.onFailure { _state.value = HeartRateState.Error("无法订阅心率数据") }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (callbackGatt === gatt && characteristic.uuid == HR_CHARACTERISTIC_UUID) {
                @Suppress("DEPRECATION")
                parseHeartRate(characteristic.value ?: return)?.let { _heartRate.value = it }
            }
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (callbackGatt === gatt && characteristic.uuid == HR_CHARACTERISTIC_UUID) {
                parseHeartRate(value)?.let { _heartRate.value = it }
            }
        }
    }

    private fun updateDisconnectedState(state: HeartRateState) {
        _connected.value = false
        _heartRate.value = 0
        _state.value = state
    }

    private fun hasScanPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}
