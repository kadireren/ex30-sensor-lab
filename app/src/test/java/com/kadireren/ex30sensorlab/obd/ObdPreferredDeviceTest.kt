package com.kadireren.ex30sensorlab.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdPreferredDeviceTest {
    @Test
    fun matchesAndroidVlinkOnly() {
        assertTrue(ObdPreferredDevice.matchesName("Android-Vlink"))
        assertTrue(ObdPreferredDevice.matchesName("android-vlink"))
        assertFalse(ObdPreferredDevice.matchesName("IOS-Vlink"))
        assertFalse(ObdPreferredDevice.matchesName("Adsız cihaz"))
    }

    @Test
    fun resolveBondedPrefersExactName() {
        val bonded = listOf(
            ObdDeviceEntry("IOS-Vlink", "41:42:86:9A:59:CD", bonded = true),
            ObdDeviceEntry("Android-Vlink", "41:42:86:9A:59:98", bonded = true),
        )
        assertEquals("41:42:86:9A:59:98", ObdPreferredDevice.resolveBonded(bonded)?.address)
    }

    @Test
    fun ignoresNonPreferredBondedDevices() {
        val bonded = listOf(
            ObdDeviceEntry("IOS-Vlink", "41:42:86:9A:59:CD", bonded = true),
            ObdDeviceEntry("CMF by Nothing Phone 1", "AA:BB:CC:DD:EE:FF", bonded = true),
        )
        assertNull(ObdPreferredDevice.resolveBonded(bonded))
    }
}
