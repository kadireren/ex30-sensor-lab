package com.kadireren.ex30sensorlab.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdDecodersTest {
    @Test fun decodesConfirmedSignals() {
        assertEquals("428.10 V", ObdDecoders.decode("hv_voltage", "4801", "62 48 01 A7 3A"))
        assertEquals("213.0 A", ObdDecoders.decode("hv_current", "4802", "6248024852"))
        assertEquals("-61.0 A", ObdDecoders.decode("hv_current", "4802", "6248023D9E"))
        assertEquals("31.45 °C", ObdDecoders.decode("hv_temp_avg", "491B", "62491B1FD1"))
        assertEquals("32.30 °C · sensör 17", ObdDecoders.decode("hv_temp_max", "4945", "624945112026"))
        assertEquals("100.00 %", ObdDecoders.decode("hv_soh", "496D", "62496D00002710"))
        assertEquals("2368 km", ObdDecoders.decode("odometer", "DD01", "62DD01000940"))
        assertEquals("100 %", ObdDecoders.decode("soc_display", "D901", "62D90164"))
    }

    @Test fun decodesMultiframeBrakeAndRejectsGarbage() {
        assertEquals("10.00 bar", ObdDecoders.decodeBrakeMulti("0: 62 FD00 03E8 FD01 03E8 1: FD02 03E8 FD03 03E8"))
        assertNull(ObdDecoders.decodeBrakeMulti("62FD00FFFFFD0103E8FD0203E8FD0303E8"))
    }

    @Test fun ignoresNoDataForEcuResponseDetection() {
        assertFalse(ObdDecoders.hasEcuResponse("NO DATA"))
        assertTrue(ObdDecoders.hasEcuResponse("7F2231"))
        assertTrue(ObdDecoders.hasEcuResponse("62F40D00"))
    }

    @Test fun decodesOdometerOnGatewayLane() {
        assertEquals("2368 km", ObdDecoders.decode("odometer_11bit", "DD01", "62DD01000940"))
    }

    @Test fun derivesSignedPower() {
        val power = ObdDecoders.derivedPowerKw("624801A028", "6248023D9E")
        assertTrue(requireNotNull(power) < 0f)
    }

    @Test fun extractsNegativeResponseCode() {
        assertEquals("31", ObdDecoders.negativeResponseCode("7F 22 31"))
        assertEquals("12", ObdDecoders.negativeResponseCode("7F0112"))
        assertNull(ObdDecoders.negativeResponseCode("NO DATA"))
    }
}
