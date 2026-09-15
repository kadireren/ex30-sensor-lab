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
        assertEquals("100 %", ObdDecoders.decode("soc_display", "D901", "62D90100000064"))
    }

    @Test fun matchesCarScannerDriveMotorSignals() {
        assertEquals("21 % PWM", ObdDecoders.decode("pedal_pwm", "E301", "1EC02E800462E30115"))
        assertEquals("4171 rpm", ObdDecoders.decode("erad_motor_speed", "E303", "1EC6EE800562E303504B"))
        assertEquals("-324 rpm", ObdDecoders.decode("erad_motor_speed", "E303", "62E3033EBC"))
        assertEquals("0 Nm", ObdDecoders.decode("erad_actual_torque", "E304", "1EC6EE800562E3041FFC"))
        assertEquals("181 Nm", ObdDecoders.decode("erad_actual_torque", "E304", "62E30420B1"))
        assertNull(ObdDecoders.decode("pedal_pwm", "E301", "62E301FF"))
        assertNull(ObdDecoders.decode("erad_motor_speed", "E303", "62E30350"))
        assertNull(ObdDecoders.decode("erad_actual_torque", "E304", "62E3041F"))
        assertEquals("D01601", ObdCatalog.motorSignals.first { it.key == "pedal_pwm" }.ecu?.header)
        assertEquals("D01637", ObdCatalog.motorSignals.first { it.key == "erad_motor_speed" }.ecu?.header)
        assertTrue(ObdCatalog.motorSignals.all { it in ObdCatalog.confirmed })
        assertTrue(ObdCatalog.confirmed.none { it.key.startsWith("brake_") || it.did.contains("FD00") })
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
