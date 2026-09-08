package com.kadireren.ex30sensorlab.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanProfilePolicyTest {
    @Test fun acceptsSupportedReadQueriesOnly() {
        assertTrue(ScanProfilePolicy.isValidQuery("01", "0D"))
        assertTrue(ScanProfilePolicy.isValidQuery("22", "4801"))
        assertTrue(ScanProfilePolicy.isValidQuery("22", "FD00FD01FD02FD03"))
        assertFalse(ScanProfilePolicy.isValidQuery("22", "480"))
        assertFalse(ScanProfilePolicy.isValidQuery("2E", "4801"))
        assertFalse(ScanProfilePolicy.isValidQuery("01", "000D"))
    }

    @Test fun validatesEcuTransportFields() {
        assertTrue(ScanProfilePolicy.isValidProtocol(7))
        assertTrue(ScanProfilePolicy.isValidHeader("D01635"))
        assertTrue(ScanProfilePolicy.isValidHeader("7E3"))
        assertTrue(ScanProfilePolicy.isValidFilter("1EC6AE80"))
        assertTrue(ScanProfilePolicy.isValidFlowData("300000"))
        assertTrue(ScanProfilePolicy.isValidFlowMode(1))
        assertFalse(ScanProfilePolicy.isValidProtocol(99))
        assertFalse(ScanProfilePolicy.isValidHeader("NOTHEX"))
        assertFalse(ScanProfilePolicy.isValidFilter("7E8"))
        assertFalse(ScanProfilePolicy.isValidFlowData("3000"))
        assertFalse(ScanProfilePolicy.isValidFlowMode(3))
    }
}
