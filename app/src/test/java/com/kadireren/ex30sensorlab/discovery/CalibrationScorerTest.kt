package com.kadireren.ex30sensorlab.discovery

import com.kadireren.ex30sensorlab.obd.EcuContexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationScorerTest {
    @Test
    fun rankCandidatesPrefersPedalResponsiveDid() {
        val throttle = DiscoveredQueryRecord("22", "2B04", EcuContexts.ECU_E, "622B04000A", 0L)
        val flat = DiscoveredQueryRecord("22", "4801", EcuContexts.ECU_E, "624801A028", 0L)
        val samples = buildList {
            repeat(6) {
                add(CalibrationSample(CalibrationPhase.REST, throttle.stableKey, 5f, "622B040005"))
                add(CalibrationSample(CalibrationPhase.PEDAL, throttle.stableKey, 35f, "622B040023"))
                add(CalibrationSample(CalibrationPhase.REST, flat.stableKey, 160f, "624801A028"))
                add(CalibrationSample(CalibrationPhase.PEDAL, flat.stableKey, 161f, "624801A029"))
            }
        }
        val ranked = CalibrationScorer.rankCandidates(listOf(throttle, flat), samples)
        assertTrue(ranked.isNotEmpty())
        assertEquals("2B04", ranked.first().record.did)
    }

    @Test
    fun filtersLikelyBrakeDids() {
        val brake = DiscoveredQueryRecord("22", "FD00", EcuContexts.ECU_E, "62FD0003E8", 0L)
        val samples = listOf(
            CalibrationSample(CalibrationPhase.REST, brake.stableKey, 1f, "x"),
            CalibrationSample(CalibrationPhase.REST, brake.stableKey, 2f, "x"),
            CalibrationSample(CalibrationPhase.REST, brake.stableKey, 3f, "x"),
            CalibrationSample(CalibrationPhase.REST, brake.stableKey, 4f, "x"),
            CalibrationSample(CalibrationPhase.PEDAL, brake.stableKey, 50f, "x"),
            CalibrationSample(CalibrationPhase.PEDAL, brake.stableKey, 55f, "x"),
        )
        assertTrue(CalibrationScorer.rankCandidates(listOf(brake), samples).isEmpty())
    }

    @Test
    fun decodesSignedTorqueRaw() {
        val value = CalibrationScorer.numericFromRaw("2B11", "622B11FF9C", DiscoveryDecodeType.S16)
        assertEquals(-100f, value)
    }
}
