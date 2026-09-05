package com.kadireren.ex30sensorlab.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID

class BluetoothElmTransport(context: Context) : ElmTransport {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var socket: BluetoothSocket? = null

    override val isConnected: Boolean get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun connect(deviceIdentifier: String) {
        close()
        val bluetoothAdapter = adapter ?: error("Bluetooth adaptörü bulunamadı")
        check(bluetoothAdapter.isEnabled) { "Bluetooth kapalı" }
        val device = resolveDevice(bluetoothAdapter, deviceIdentifier)
            ?: error("OBD cihazı bulunamadı: $deviceIdentifier")

        // Aktif discovery RFCOMM bağlantısını yavaşlatıp başarısız kılar; bağlanmadan önce durdur.
        if (bluetoothAdapter.isDiscovering) bluetoothAdapter.cancelDiscovery()

        repeat(CONNECT_ATTEMPTS) { attempt ->
            val candidate = connectSocket(device, secure = true) ?: connectSocket(device, secure = false)
            if (candidate?.isConnected == true) {
                socket = candidate
                return
            }
            if (attempt < CONNECT_ATTEMPTS - 1) SystemClock.sleep(CONNECT_RETRY_DELAY_MS)
        }
        close()
        throw IOException("OBD bağlantısı kurulamadı; başka uygulama adaptörü kullanıyor olabilir")
    }

    @SuppressLint("MissingPermission")
    internal fun resolveDevice(bluetoothAdapter: android.bluetooth.BluetoothAdapter, identifier: String): BluetoothDevice? {
        if (MAC_ADDRESS.matches(identifier)) {
            return try {
                bluetoothAdapter.getRemoteDevice(identifier)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return bluetoothAdapter.bondedDevices.firstOrNull {
            it.name.equals(identifier, ignoreCase = true)
        } ?: bluetoothAdapter.bondedDevices.firstOrNull {
            it.name?.contains(identifier, ignoreCase = true) == true
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectSocket(device: BluetoothDevice, secure: Boolean): BluetoothSocket? {
        val candidate = try {
            if (secure) device.createRfcommSocketToServiceRecord(SPP_UUID)
            else device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        } catch (_: IOException) {
            return null
        }
        return try {
            candidate.connect()
            candidate
        } catch (_: IOException) {
            try { candidate.close() } catch (_: IOException) {}
            null
        }
    }

    @Synchronized
    override fun send(command: String, timeoutMs: Long): String {
        val active = socket?.takeIf { it.isConnected } ?: throw IOException("OBD bağlantısı açık değil")
        val input = active.inputStream
        while (input.available() > 0) input.read()
        active.outputStream.write((command.trim() + "\r").toByteArray(Charsets.US_ASCII))
        active.outputStream.flush()

        val response = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (input.available() > 0) {
                val byte = input.read()
                if (byte < 0) break
                val char = byte.toChar()
                if (char == '>') return response.toString().trim()
                response.append(char)
            } else {
                SystemClock.sleep(5L)
            }
        }
        throw SocketTimeoutException("ELM yanıt zaman aşımı: $command")
    }

    @Synchronized
    override fun close() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val MAC_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
        private const val CONNECT_ATTEMPTS = 3
        private const val CONNECT_RETRY_DELAY_MS = 400L
    }
}
