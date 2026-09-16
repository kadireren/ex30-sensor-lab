package com.kadireren.ex30sensorlab.vhal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VhalDerivedCalculationsTest {
    @Test fun calculatesFractionalSocFromEnergyAndCapacity() {
        assertEquals(62.5f, VhalDerivedCalculations.socPercent(41_250f, 66_000f)!!, 0.001f)
    }

    @Test fun calculatesSignedInstantConsumptionLikeDashboard() {
        assertEquals(20f, VhalDerivedCalculations.instantConsumptionKwh100(20f, 100f)!!, 0.001f)
        assertEquals(-25f, VhalDerivedCalculations.instantConsumptionKwh100(-10f, 40f)!!, 0.001f)
        assertNull(VhalDerivedCalculations.instantConsumptionKwh100(2f, 2.9f))
    }

    @Test fun normalizesPowerUnitsLikeDashboard() {
        assertEquals(25f, VhalDerivedCalculations.normalizePowerKw(25_000_000f), 0.001f)
        assertEquals(25f, VhalDerivedCalculations.normalizePowerKw(25_000f), 0.001f)
        assertEquals(25f, VhalDerivedCalculations.normalizePowerKw(25f), 0.001f)
    }
}
