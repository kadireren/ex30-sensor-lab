package com.kadireren.ex30sensorlab.obd

import org.junit.Assert.assertEquals
import org.junit.Test

class EcuContextsTest {
    @Test fun derivesKnownResponseFilters() {
        assertEquals("1EC02E80", EcuContexts.deriveProbe(0x1601).rxFilter)
        assertEquals("1EC6AE80", EcuContexts.deriveProbe(0x1635).rxFilter)
        assertEquals("1EE02E80", EcuContexts.deriveProbe(0x1701).rxFilter)
    }
}
