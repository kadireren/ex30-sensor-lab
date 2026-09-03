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
    override fun connect(deviceName: String) {
        close()
        val bluetoothAdapter = adapter ?: error("Bluetooth adaptörü bulunamadı")
        check(bluetoothAdapter.isEnabled) { "Bluetooth kapalı" }
        val device = bluetoothAdapter.bondedDevices.firstOrNull {
            it.name.equals(deviceName, ignoreCase = true) || it.name?.contains("Vlink", ignoreCase = true) == true
        } ?: error("Eşleştirilmiş Android-Vlink bulunamadı")

        socket = connectSocket(device, secure = true) ?: connectSocket(device, secure = false)
        if (socket?.isConnected != true) {
            close()
            throw IOException("Android-Vlink bağlantısı kurulamadı; başka uygulama adaptörü kullanıyor olabilir")
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
    }
}
