package com.kadireren.ex30sensorlab.vhal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGateTest {
    @Test fun allowsOnlyReadyStationaryParkingBrake() {
        assertTrue(SafetyState(0f, true, 4).scannerAllowed)
        assertTrue(SafetyState(0.49f, true, 5).scannerAllowed)
        assertFalse(SafetyState(0.5f, true, 4).scannerAllowed)
        assertFalse(SafetyState(0f, false, 4).scannerAllowed)
        assertFalse(SafetyState(0f, true, 3).scannerAllowed)
        assertFalse(SafetyState().scannerAllowed)
    }
}
