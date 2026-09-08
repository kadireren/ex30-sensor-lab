package com.kadireren.ex30sensorlab.discovery

import com.kadireren.ex30sensorlab.obd.EcuContexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiscoveredSensorStoreTest {
    @Before
    fun resetStore() {
        DiscoveredSensorStore.load("")
    }

    @Test
    fun mergeReplayDiscoveriesKeepsUniquePendingQueries() {
        val record = DiscoveredQueryRecord(
            service = "22",
            did = "2B04",
            ecu = EcuContexts.ECU_E,
            lastRawResponse = "622B040032",
            discoveredAtMs = 1000L,
            sourceSession = "car-scanner",
        )
        val updated = DiscoveredSensorStore.mergeReplayDiscoveries(listOf(record), "car-scanner", 1000L)
        assertEquals(1, updated.pendingQueries.size)
        assertEquals("car-scanner", updated.lastProfileSession)
        assertEquals("2B04", updated.pendingQueries.single().did)
    }

    @Test
    fun confirmSensorRemovesMatchingPendingQuery() {
        val record = DiscoveredQueryRecord("22", "FEE7", EcuContexts.ECU_E, "62FEE7012C", 1L)
        DiscoveredSensorStore.mergeReplayDiscoveries(listOf(record), "s", 1L)
        DiscoveredSensorStore.confirmSensor(
            DiscoveredSensorConfig(
                target = DiscoveryTarget.ACTUAL_TORQUE,
                key = "discovered_actual_torque",
                name = DiscoveryTarget.ACTUAL_TORQUE.labelTr,
                service = "22",
                did = "FEE7",
                ecu = EcuContexts.ECU_E,
                decodeType = DiscoveryDecodeType.S16,
                unit = "Nm",
                targetHz = 2f,
                confirmedAtMs = 2L,
            ),
            2L,
        )
        val state = DiscoveredSensorStore.snapshot()
        assertTrue(state.pendingQueries.isEmpty())
        assertEquals(DiscoveryTarget.ACTUAL_TORQUE, state.confirmedSensors.single().target)
    }

    @Test
    fun reportTextListsConfirmedTargets() {
        DiscoveredSensorStore.confirmSensor(
            DiscoveredSensorConfig(
                target = DiscoveryTarget.MOTOR_RPM,
                key = "discovered_motor_rpm",
                name = DiscoveryTarget.MOTOR_RPM.labelTr,
                service = "22",
                did = "FEE7",
                ecu = EcuContexts.ECU_E,
                decodeType = DiscoveryDecodeType.U16,
                unit = "rpm",
                targetHz = 10f,
                confirmedAtMs = 1L,
            ),
            1L,
        )
        val report = DiscoveredSensorStore.reportText()
        assertTrue(report.contains("Motor devri (RPM)"))
        assertTrue(report.contains("FEE7"))
    }
}
