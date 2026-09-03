package com.kadireren.ex30sensorlab.obd

import com.kadireren.ex30sensorlab.model.EcuContext
import java.net.SocketTimeoutException

class ElmProtocol(
    private val transport: ElmTransport,
    private val trace: (command: String, response: String) -> Unit = { _, _ -> },
) {
    private var initialized = false
    private var currentEcu: EcuContext? = null
    private var currentProtocol: Int? = null
    private var connectedDeviceId: String? = null

    fun connect(deviceIdentifier: String = "Android-Vlink"): String {
        transport.connect(deviceIdentifier)
        connectedDeviceId = deviceIdentifier
        return try {
            val adapterId = send("ATI", 2_500L)
            initialize()
            adapterId
        } catch (e: Exception) {
            disconnect()
            throw e
        }
    }

    fun initialize() {
        INIT_COMMANDS.forEachIndexed { index, command ->
            val timeout = if (index == 0) 4_000L else 2_500L
            val response = send(ElmCommandPolicy.requireAllowed(command), timeout)
            check(!isHardError(response)) { "ELM başlatma hatası $command: $response" }
        }
        initialized = true
        currentEcu = null
        currentProtocol = null
    }

    fun query(ecu: EcuContext?, command: String, timeoutMs: Long = 2_500L): String {
        check(initialized && transport.isConnected) { "ELM327 hazır değil" }
        val safe = ElmCommandPolicy.requireAllowed(command)
        return try {
            if (ecu != null) switchEcu(ecu)
            send(safe, timeoutMs)
        } catch (error: SocketTimeoutException) {
            reconnectAfterTimeout(ecu)
            send(safe, timeoutMs)
        }
    }

    fun switchEcu(ecu: EcuContext) {
        check(initialized) { "ELM327 hazır değil" }
        if (currentEcu == ecu) return
        val commands = buildSwitchCommands(currentProtocol, ecu)
        for (command in commands) {
            val response = send(ElmCommandPolicy.requireAllowed(command))
            if (isHardError(response)) {
                currentEcu = null
                currentProtocol = null
                throw IllegalStateException("ECU geçişi başarısız $command: $response")
            }
        }
        currentEcu = ecu
        currentProtocol = ecu.protocol
    }

    fun disconnect() {
        connectedDeviceId = null
        transport.close()
        initialized = false
        currentEcu = null
        currentProtocol = null
    }

    private fun reconnectAfterTimeout(ecu: EcuContext?) {
        val deviceId = connectedDeviceId ?: throw IllegalStateException("ELM bağlantısı kapatıldı")
        transport.close()
        initialized = false
        currentEcu = null
        currentProtocol = null
        transport.connect(deviceId)
        initialize()
        if (ecu != null) switchEcu(ecu)
    }

    private fun send(command: String, timeoutMs: Long = 2_500L): String = try {
        transport.send(command, timeoutMs).also { trace(command, it) }
    } catch (error: Exception) {
        trace(command, "!ERROR ${error.message ?: error.javaClass.simpleName}")
        throw error
    }

    companion object {
        val INIT_COMMANDS = listOf("ATZ", "ATE0", "ATE0", "ATL0", "ATS0", "ATH0", "ATM0", "ATAT1")

        fun buildSwitchCommands(currentProtocol: Int?, ecu: EcuContext): List<String> {
            val commands = mutableListOf<String>()
            if (currentProtocol != ecu.protocol) {
                if (currentProtocol == 7 && ecu.protocol == 6) commands += "ATFCSM0"
                commands += "ATSP${ecu.protocol}"
            }
            if (ecu.protocol == 6) {
                commands += listOf("ATFCSM0", "ATAR", "ATSH7E3")
            } else {
                commands += "ATSH${ecu.header}"
                commands += "ATCP${ecu.priority}"
                commands += "ATCRA${ecu.rxFilter}"
                commands += "ATFCSH${ecu.flowControlHeader}"
                commands += "ATFCSD${ecu.flowControlData}"
                commands += "ATFCSM${ecu.flowControlMode}"
            }
            return commands
        }

        fun isHardError(response: String): Boolean {
            val text = response.uppercase()
            return text.contains("ERROR") || text.contains("UNABLE TO CONNECT") || text.contains("BUS ERROR")
        }
    }
}
