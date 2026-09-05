package com.kadireren.ex30sensorlab.obd

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class ElmProtocolTest {
    @Test fun initializesAndSwitchesContextInOrder() {
        val transport = FakeTransport()
        val protocol = ElmProtocol(transport)
        protocol.connect()
        protocol.query(EcuContexts.BECM, "224801")
        assertEquals(
            ElmProtocol.INIT_COMMANDS + listOf("ATSP7", "ATSHD01635", "ATCP1D", "ATCRA1EC6AE80", "ATFCSH1DD01635", "ATFCSD300000", "ATFCSM1", "224801"),
            transport.commands.drop(1),
        )
        assertEquals(1, transport.commands.count { it == "ATSP7" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun blocksWriteCommand() {
        val protocol = ElmProtocol(FakeTransport())
        protocol.connect()
        protocol.query(EcuContexts.BECM, "2E1234FFFF")
    }

    @Test fun reconnectsOnceAfterTimeoutAndRestoresEcuContext() {
        val transport = FakeTransport(timeoutCommand = "224801")
        val protocol = ElmProtocol(transport)
        protocol.connect()

        assertEquals("624801A73A", protocol.query(EcuContexts.BECM, "224801"))
        assertEquals(2, transport.connectCount)
        assertEquals(2, transport.commands.count { it == "ATSHD01635" })
        assertEquals(2, transport.commands.count { it == "224801" })
    }

    @Test fun reconnectsOnceAfterIoErrorAndRestoresEcuContext() {
        val transport = FakeTransport(ioErrorCommand = "224801")
        val protocol = ElmProtocol(transport)
        protocol.connect()

        assertEquals("624801A73A", protocol.query(EcuContexts.BECM, "224801"))
        assertEquals(2, transport.connectCount)
        assertEquals(2, transport.commands.count { it == "ATSHD01635" })
        assertEquals(2, transport.commands.count { it == "224801" })
    }

    @Test fun tracesCommandsAndRedactsAtLoggerBoundary() {
        val transport = FakeTransport()
        val traces = mutableListOf<Pair<String, String>>()
        val protocol = ElmProtocol(transport) { command, response -> traces += command to response }
        protocol.connect()
        protocol.query(EcuContexts.BECM, "22F190")
        assertEquals("ATI", traces.first().first)
        assertEquals("[REDACTED]", com.kadireren.ex30sensorlab.logging.LogEncoding.redact(traces.last().first, traces.last().second))
    }

    private class FakeTransport(
        private val timeoutCommand: String? = null,
        private val ioErrorCommand: String? = null,
    ) : ElmTransport {
        val commands = mutableListOf<String>()
        var connectCount = 0
        private var timedOut = false
        private var ioFailed = false
        override var isConnected = false
        override fun connect(deviceIdentifier: String) { isConnected = true; connectCount++ }
        override fun send(command: String, timeoutMs: Long): String {
            commands += command
            if (command == timeoutCommand && !timedOut) {
                timedOut = true
                throw SocketTimeoutException("test timeout")
            }
            if (command == ioErrorCommand && !ioFailed) {
                ioFailed = true
                isConnected = false
                throw IOException("test read failed")
            }
            return when (command) {
                "ATI" -> "ELM327 v2.3"
                "224801" -> "624801A73A"
                "22F190" -> "62F190SECRET"
                else -> "OK"
            }
        }
        override fun close() { isConnected = false }
    }
}
