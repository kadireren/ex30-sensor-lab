package com.kadireren.ex30sensorlab.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdDecodersTest {
    @Test fun decodesConfirmedSignals() {
        assertEquals("448.35 V", ObdDecoders.decode("hv_voltage", "4803", "62 48 03 AF 23"))
        assertEquals("91.954 %", ObdDecoders.decode("obd_soc", "4801", "62 48 01 B3 99"))
        assertEquals(94.576f, ObdDecoders.derivedDisplaySoc("62 48 01 B3 99")!!, 0.001f)
        assertEquals("213.0 A", ObdDecoders.decode("hv_current", "4802", "6248024852"))
        assertEquals("-61.0 A", ObdDecoders.decode("hv_current", "4802", "6248023D9E"))
        assertEquals("31.45 °C", ObdDecoders.decode("hv_temp_avg", "491B", "62491B1FD1"))
        assertEquals("32.30 °C · sensör 17", ObdDecoders.decode("hv_temp_max", "4945", "624945112026"))
        assertEquals("100.00 %", ObdDecoders.decode("hv_soh", "496D", "62496D00002710"))
        assertEquals("2368 km", ObdDecoders.decode("odometer", "DD01", "62DD01000940"))
    }

    @Test fun decodesNewCarScannerMatchedSignals() {
        assertEquals("91.120025 %", ObdDecoders.decode("cell_min_soc", "487A", "62487A056E6199"))
        assertEquals("26.0 °C", ObdDecoders.decode("hv_inlet_coolant_temp", "4804", "6248044C"))
        assertEquals("14.75 V", ObdDecoders.decode("battery_12v", "DD02", "62DD023B"))
        assertEquals("24629.979 W", ObdDecoders.decode("discharge_power_limit", "489E", "62489E0177D2DB"))
        assertEquals("37.523 V", ObdDecoders.decode("ecu_supply_voltage", "EE02", "62EE029293"))
        assertEquals("446.21 V", ObdDecoders.decode("cell_voltage_sum", "497C", "62497CAE4D"))
        assertEquals("4.184 V · hücre 63", ObdDecoders.decode("cell_max_voltage", "4907", "6249073F1058"))
        assertEquals("4.168 V · hücre 85", ObdDecoders.decode("cell_min_voltage", "4908", "624908551048"))
        assertEquals("100.00 %", ObdDecoders.decode("hv_soh_secondary", "489A", "62489A2710"))
        assertEquals("445.93 V", ObdDecoders.decode("igm_fuse_voltage", "4809", "624809AE31"))
        assertEquals("26.50 °C", ObdDecoders.decode("dc_connector_temp", "EE06", "62EE061DE2"))
        assertEquals("24.7 °C", ObdDecoders.decode("ac_temp", "4A28", "624A2800000287"))
        assertEquals("629", ObdDecoders.decode("ac_pressure", "4A29", "624A2900000275"))
        assertEquals("33.1 °C", ObdDecoders.decode("powertrain_coolant_temp_vcu", "4A34", "624A34000002DB"))
        assertEquals("35.5 %", ObdDecoders.decode("cooling_request", "413A", "62413A00000163"))
        assertEquals("10.0 %", ObdDecoders.decode("coolant_pump_chamber_request", "D901", "62D90100000064"))
        assertEquals("0 %", ObdDecoders.decode("cooling_valve_actual", "E34A", "62E34A00"))
        assertEquals("0.0 rpm", ObdDecoders.decode("erad_wheel_speed", "E312", "62E3124000"))
        assertEquals("0.0 A", ObdDecoders.decode("iem_hv_current", "E301", "62E3011FFC"))
        assertEquals("35.0 °C", ObdDecoders.decode("erad_motor_temp", "E306", "62E30655"))
        assertEquals("31.0 °C", ObdDecoders.decode("powertrain_coolant_temp_iem", "EE9A", "62EE9A47"))
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
        assertTrue(ObdCatalog.motorSignals.all { it.targetHz == 10f })
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
        val power = ObdDecoders.derivedPowerKw("624803A028", "6248023D9E")
        assertTrue(requireNotNull(power) < 0f)
    }

    @Test fun extractsNegativeResponseCode() {
        assertEquals("31", ObdDecoders.negativeResponseCode("7F 22 31"))
        assertEquals("12", ObdDecoders.negativeResponseCode("7F0112"))
        assertNull(ObdDecoders.negativeResponseCode("NO DATA"))
    }
}
