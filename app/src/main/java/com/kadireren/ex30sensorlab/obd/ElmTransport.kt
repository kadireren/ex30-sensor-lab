package com.kadireren.ex30sensorlab.obd

interface ElmTransport {
    val isConnected: Boolean
    fun connect(deviceName: String = "Android-Vlink")
    fun send(command: String, timeoutMs: Long = 2_500L): String
    fun close()
}
