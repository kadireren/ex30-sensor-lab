package com.kadireren.ex30sensorlab.obd

import com.kadireren.ex30sensorlab.model.EcuContext
import java.io.IOException

enum class ElmConnectionEvent { RECONNECTING, RECONNECTED }

class ElmProtocol(
    private val transport: ElmTransport,
    private val connectionEvent: (ElmConnectionEvent) -> Unit = {},
    private val trace: (command: String, response: String) -> Unit = { _, _ -> },
) {
    private var initialized = false
    private var currentEcu: EcuContext? = null
    private var currentProtocol: Int? = null
    private var connectedDeviceId: String? = null

    fun connect(deviceIdentifier: String = "Android-Vlink", verifyLink: Boolean = true): String {
        transport.connect(deviceIdentifier)
        connectedDeviceId = deviceIdentifier
        return try {
            val adapterId = send("ATI", 2_500L)
            initialize()
            if (verifyLink) verifyBecmLink()
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

    @Synchronized
    fun query(ecu: EcuContext?, command: String, timeoutMs: Long = 2_500L): String {
        val safe = ElmCommandPolicy.requireAllowed(command)
        var lastError: IOException? = null
        repeat(QUERY_ATTEMPTS) { attempt ->
            try {
                if (!initialized || !transport.isConnected) {
                    connectionEvent(ElmConnectionEvent.RECONNECTING)
                    reconnect(ecu)
                } else if (ecu != null) {
                    switchEcu(ecu)
                }
                return send(safe, timeoutMs)
            } catch (error: IOException) {
                lastError = error
                transport.close()
                initialized = false
                currentEcu = null
                currentProtocol = null
                if (connectedDeviceId == null || attempt == QUERY_ATTEMPTS - 1) return@repeat
                connectionEvent(ElmConnectionEvent.RECONNECTING)
                Thread.sleep(RETRY_DELAYS_MS[attempt])
            }
        }
        throw IOException("OBD bağlantısı ${QUERY_ATTEMPTS} denemede geri kurulamadı", lastError)
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

    @Synchronized
    fun disconnect() {
        connectedDeviceId = null
        transport.close()
        initialized = false
        currentEcu = null
        currentProtocol = null
    }

    @Synchronized
    fun verifyLink() {
        check(initialized && transport.isConnected) { "ELM327 hazır değil" }
        verifyBecmLink()
    }

    private fun verifyBecmLink() {
        switchEcu(EcuContexts.BECM)
        for (command in BECM_LINK_TESTS) {
            val response = send(command)
            val did = command.removePrefix("22")
            check(!isHardError(response) && ObdDecoders.isPositiveResponse(response, did)) {
                "ECU bağlantı testi başarısız $command: $response"
            }
        }
        currentEcu = null
    }

    private fun reconnect(ecu: EcuContext?) {
        val deviceId = connectedDeviceId ?: throw IllegalStateException("ELM bağlantısı kapatıldı")
        transport.close()
        initialized = false
        currentEcu = null
        currentProtocol = null
        transport.connect(deviceId)
        initialize()
        if (ecu != null) switchEcu(ecu)
        connectionEvent(ElmConnectionEvent.RECONNECTED)
    }

    private fun send(command: String, timeoutMs: Long = 2_500L): String = try {
        transport.send(command, timeoutMs).also { trace(command, it) }
    } catch (error: Exception) {
        trace(command, "!ERROR ${error.message ?: error.javaClass.simpleName}")
        throw error
    }

    companion object {
        val INIT_COMMANDS = listOf("ATZ", "ATE0", "ATE0", "ATL0", "ATS0", "ATH1", "ATM0", "ATAT1")
        val BECM_LINK_TESTS = listOf("224801", "22491B")
        private const val QUERY_ATTEMPTS = 3
        private val RETRY_DELAYS_MS = longArrayOf(500L, 1_500L)

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
