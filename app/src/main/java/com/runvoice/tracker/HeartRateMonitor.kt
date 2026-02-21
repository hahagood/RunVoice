package com.runvoice.tracker

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class HeartRateMonitor(private val context: Context) {

    companion object {
        val HR_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val PREFS_NAME = "runvoice_prefs"
        private const val KEY_HR_DEVICE_ADDRESS = "hr_device_address"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null

    private val _heartRate = MutableStateFlow(0)
    val heartRate = _heartRate.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()

    // Scanning state
    data class BleDevice(val name: String, val address: String, val rssi: Int)

    private val _discoveredDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val discoveredDevices = _discoveredDevices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    private val deviceSet = mutableMapOf<String, BleDevice>()

    // --- Scanning ---

    @SuppressLint("MissingPermission")
    fun startScan() {
        scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        deviceSet.clear()
        _discoveredDevices.value = emptyList()
        _scanning.value = true

        // No service UUID filter — most HR bands don't advertise it
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(null, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanner?.stopScan(scanCallback)
        _scanning.value = false
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = device.name ?: return  // Skip unnamed devices
            val addr = device.address
            val d = BleDevice(name, addr, result.rssi)
            deviceSet[addr] = d
            _discoveredDevices.value = deviceSet.values.sortedByDescending { it.rssi }
        }
    }

    // --- Connect / Disconnect ---

    fun saveDevice(address: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_HR_DEVICE_ADDRESS, address).apply()
    }

    fun getSavedDeviceAddress(): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HR_DEVICE_ADDRESS, null)
    }

    fun clearSavedDevice() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_HR_DEVICE_ADDRESS).apply()
        disconnect()
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(address: String) {
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        gatt?.close()
        gatt = device.connectGatt(context, true, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun connectSavedDevice() {
        val addr = getSavedDeviceAddress() ?: return
        connectToDevice(addr)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connected.value = false
        _heartRate.value = 0
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connected.value = true
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connected.value = false
                _heartRate.value = 0
                // Auto-reconnect
                g.connect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val hrService = g.getService(HR_SERVICE_UUID) ?: return
            val hrChar = hrService.getCharacteristic(HR_CHARACTERISTIC_UUID) ?: return
            g.setCharacteristicNotification(hrChar, true)
            val descriptor = hrChar.getDescriptor(CCC_DESCRIPTOR_UUID) ?: return
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == HR_CHARACTERISTIC_UUID) {
                val value = characteristic.value ?: return
                val flag = value[0].toInt()
                val hr = if (flag and 0x01 != 0) {
                    // 16-bit heart rate
                    (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
                } else {
                    // 8-bit heart rate
                    value[1].toInt() and 0xFF
                }
                _heartRate.value = hr
            }
        }
    }
}
