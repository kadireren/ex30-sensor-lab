package com.kadireren.ex30sensorlab.obd

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ElmProtocolTest {
    @Test fun initializesAndSwitchesContextInOrder() {
        val transport = FakeTransport()
        val protocol = ElmProtocol(transport)
        protocol.connect()
        assertEquals(
            ElmProtocol.INIT_COMMANDS + listOf(
                "ATSP7", "ATSHD01635", "ATCP1D", "ATCRA1EC6AE80", "ATFCSH1DD01635", "ATFCSD300000", "ATFCSM1",
                "224801", "22491B",
            ),
            transport.commands.drop(1),
        )
        protocol.query(EcuContexts.BECM, "224801")
        assertEquals(1, transport.commands.count { it == "ATSP7" })
        assertEquals(2, transport.commands.count { it == "224801" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun blocksWriteCommand() {
        val protocol = ElmProtocol(FakeTransport())
        protocol.connect()
        protocol.query(EcuContexts.BECM, "2E1234FFFF")
    }

    @Test fun reconnectsOnceAfterTimeoutAndRestoresEcuContext() {
        val transport = FakeTransport(timeoutCommand = "224802")
        val protocol = ElmProtocol(transport)
        protocol.connect()

        assertEquals("6248024852", protocol.query(EcuContexts.BECM, "224802"))
        assertEquals(2, transport.connectCount)
        assertEquals(3, transport.commands.count { it == "ATSHD01635" })
        assertEquals(2, transport.commands.count { it == "224802" })
    }

    @Test fun reconnectsOnceAfterIoErrorAndRestoresEcuContext() {
        val transport = FakeTransport(ioErrorCommand = "224801")
        val protocol = ElmProtocol(transport)
        protocol.connect(verifyLink = false)

        assertEquals("624801A73A", protocol.query(EcuContexts.BECM, "224801"))
        assertEquals(2, transport.connectCount)
        assertEquals(2, transport.commands.count { it == "ATSHD01635" })
        assertEquals(2, transport.commands.count { it == "224801" })
    }

    @Test fun keepsRecoveringAfterOneWholeQueryExhaustsItsRetries() {
        val events = mutableListOf<ElmConnectionEvent>()
        val transport = FakeTransport(ioErrorCommand = "224801", ioFailuresBeforeSuccess = 3)
        val protocol = ElmProtocol(transport, connectionEvent = { events += it })
        protocol.connect(verifyLink = false)

        try {
            protocol.query(EcuContexts.BECM, "224801")
            org.junit.Assert.fail("İlk sorgunun yeniden bağlanma bütçesini tüketmesi bekleniyordu")
        } catch (_: IOException) {
        }

        assertEquals("624801A73A", protocol.query(EcuContexts.BECM, "224801"))
        org.junit.Assert.assertTrue(events.contains(ElmConnectionEvent.RECONNECTING))
        org.junit.Assert.assertTrue(events.contains(ElmConnectionEvent.RECONNECTED))
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

    @Test fun serializesConcurrentQueriesAcrossEcuContexts() {
        val firstQueryEntered = CountDownLatch(1)
        val releaseFirstQuery = CountDownLatch(1)
        val transport = BlockingTransport(firstQueryEntered, releaseFirstQuery)
        val protocol = ElmProtocol(transport)
        protocol.connect(verifyLink = false)
        val executor = Executors.newFixedThreadPool(2)

        val first = executor.submit<String> { protocol.query(EcuContexts.BECM, "224801") }
        org.junit.Assert.assertTrue(firstQueryEntered.await(1, TimeUnit.SECONDS))
        val second = executor.submit<String> { protocol.query(EcuContexts.ECU_E, "22F40D") }
        Thread.sleep(50L)
        org.junit.Assert.assertFalse(transport.commands.contains("ATSHD01701"))

        releaseFirstQuery.countDown()
        assertEquals("624801A73A", first.get(1, TimeUnit.SECONDS))
        assertEquals("62F40D00", second.get(1, TimeUnit.SECONDS))
        org.junit.Assert.assertTrue(transport.commands.indexOf("22F40D") > transport.commands.indexOf("224801"))
        executor.shutdownNow()
    }

    private class BlockingTransport(
        private val firstQueryEntered: CountDownLatch,
        private val releaseFirstQuery: CountDownLatch,
    ) : ElmTransport {
        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override var isConnected = false
        override fun connect(deviceIdentifier: String) { isConnected = true }
        override fun send(command: String, timeoutMs: Long): String {
            commands += command
            if (command == "224801") {
                firstQueryEntered.countDown()
                releaseFirstQuery.await(1, TimeUnit.SECONDS)
                return "624801A73A"
            }
            return when (command) {
                "ATI" -> "ELM327 v2.3"
                "22F40D" -> "62F40D00"
                else -> "OK"
            }
        }
        override fun close() { isConnected = false }
    }

    private class FakeTransport(
        private val timeoutCommand: String? = null,
        private val ioErrorCommand: String? = null,
        private val ioFailuresBeforeSuccess: Int = if (ioErrorCommand == null) 0 else 1,
    ) : ElmTransport {
        val commands = mutableListOf<String>()
        var connectCount = 0
        private var timedOut = false
        private var ioFailureCount = 0
        override var isConnected = false
        override fun connect(deviceIdentifier: String) { isConnected = true; connectCount++ }
        override fun send(command: String, timeoutMs: Long): String {
            commands += command
            if (command == timeoutCommand && !timedOut) {
                timedOut = true
                throw SocketTimeoutException("test timeout")
            }
            if (command == ioErrorCommand && ioFailureCount < ioFailuresBeforeSuccess) {
                ioFailureCount++
                isConnected = false
                throw IOException("test read failed")
            }
            return when (command) {
                "ATI" -> "ELM327 v2.3"
                "224801" -> "624801A73A"
                "224802" -> "6248024852"
                "22491B" -> "62491B1FD1"
                "22F190" -> "62F190SECRET"
                else -> "OK"
            }
        }
        override fun close() { isConnected = false }
    }
}
