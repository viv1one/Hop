package com.hop.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID

/**
 * Real (non-spike) BLE presence layer: ported near-verbatim from
 * `com.hop.spike.BleDiscoverySpike` (untouched, not imported from) since it
 * was already correctly discovery-only -- it advertises/scans for presence
 * so peers can find each other, and never carries a [com.hop.protocol.Frame]
 * or any post payload. Payload transfer happens exclusively over the WiFi
 * Direct socket in [WifiDirectTransport] once discovery hands off a
 * connection.
 *
 * [onLog] is a plain internal debug-logging hook (not user-facing UI text --
 * BLE/WiFi Direct mechanics stay invisible to the user per hop-dev's skill
 * invariant #5); [TransportManager] wires it to `android.util.Log`.
 */
class BleDiscovery(context: Context, private val onLog: (String) -> Unit = {}) {

    companion object {
        // Placeholder spike UUID, carried over unchanged -- a real service UUID
        // scheme can be finalized independent of, and later than, the frame spec
        // (see BleDiscoverySpike's original doc). BLE here is discovery-only.
        val SERVICE_UUID: UUID = UUID.fromString("6f491c00-92f7-4a9a-9c1e-000000000001")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanStartedAtMs = 0L
    private val seenDevices = mutableSetOf<String>()

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: run {
            onLog("BLE advertiser unavailable on this device")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(true)
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                onLog("BLE advertising started")
            }

            override fun onStartFailure(errorCode: Int) {
                onLog("BLE advertising failed: error $errorCode")
            }
        }
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        advertiseCallback?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
        advertiseCallback = null
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        scanner = adapter?.bluetoothLeScanner ?: run {
            onLog("BLE scanner unavailable on this device")
            return
        }
        seenDevices.clear()
        scanStartedAtMs = System.currentTimeMillis()
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        onLog("BLE scan started")
        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val latencyMs = System.currentTimeMillis() - scanStartedAtMs
            if (seenDevices.add(address)) {
                onLog("BLE discovered $address rssi=${result.rssi}dBm latency=${latencyMs}ms")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            onLog("BLE scan failed: error $errorCode")
        }
    }
}
