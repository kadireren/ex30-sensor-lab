package com.kadireren.ex30sensorlab.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

data class ObdDeviceEntry(
    val name: String,
    val address: String,
    val bonded: Boolean,
)

class BluetoothObdDeviceScanner(context: Context) {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private var discoveryReceiver: BroadcastReceiver? = null
    private var onDeviceFound: ((ObdDeviceEntry) -> Unit)? = null
    private var onDiscoveryFinished: (() -> Unit)? = null
    private var scanning = false

    val isScanning: Boolean get() = scanning

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<ObdDeviceEntry> {
        val bluetoothAdapter = adapter ?: return emptyList()
        if (!bluetoothAdapter.isEnabled) return emptyList()
        return bluetoothAdapter.bondedDevices
            .map(::toEntry)
            .sortedBy { it.name.lowercase() }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery(onFound: (ObdDeviceEntry) -> Unit, onFinished: () -> Unit) {
        val bluetoothAdapter = adapter ?: error("Bluetooth adaptörü bulunamadı")
        check(bluetoothAdapter.isEnabled) { "Bluetooth kapalı" }
        stopDiscovery()
        onDeviceFound = onFound
        onDiscoveryFinished = onFinished
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        } ?: return
                        onDeviceFound?.invoke(toEntry(device))
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> stopDiscovery()
                }
            }
        }
        discoveryReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        if (!bluetoothAdapter.startDiscovery()) {
            stopDiscovery()
            error("Bluetooth taraması başlatılamadı")
        }
        scanning = true
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        adapter?.takeIf { it.isDiscovering }?.cancelDiscovery()
        scanning = false
        discoveryReceiver?.let { receiver ->
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
        }
        discoveryReceiver = null
        onDiscoveryFinished?.invoke()
        onDeviceFound = null
        onDiscoveryFinished = null
    }

    @SuppressLint("MissingPermission")
    private fun toEntry(device: BluetoothDevice): ObdDeviceEntry = ObdDeviceEntry(
        name = device.name?.takeIf { it.isNotBlank() } ?: "Adsız cihaz",
        address = device.address,
        bonded = device.bondState == BluetoothDevice.BOND_BONDED,
    )
}
