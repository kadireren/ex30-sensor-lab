package com.kadireren.ex30sensorlab.obd

import com.kadireren.ex30sensorlab.model.EcuContext
import com.kadireren.ex30sensorlab.model.ObdPidDefinition
import com.kadireren.ex30sensorlab.model.ResearchStatus

object EcuContexts {
    val BECM = EcuContext("BECM", 7, "D01635", "1D", "1EC6AE80", "1DD01635")
    val VCFRONT = EcuContext("VCFRONT", 7, "D01601", "1D", "1EC02E80", "1DD01601")
    val ECU_D = EcuContext("ECU-D", 7, "D01650", "1D", "1ECA0E80", "1DD01650")
    val ECU_E = EcuContext("ECU-E", 7, "D01701", "1D", "1EE02E80", "1DD01701")
    val ECU_F = EcuContext("ECU-F", 7, "D01637", "1D", "1EC6EE80", "1DD01637")
    val GATEWAY_11BIT = EcuContext("11-bit Gateway", 6, "7E3", "", "", "")

    val known = listOf(BECM, VCFRONT, ECU_D, ECU_E, ECU_F)

    fun deriveProbe(headerLow16: Int): EcuContext {
        require(headerLow16 in 0x1601..0x17FF)
        val header = "D0%04X".format(headerLow16)
        val rx = 0x1EC02E80L + ((headerLow16 - 0x1601L) shl 13)
        return EcuContext("Probe-$header", 7, header, "1D", "%08X".format(rx), "1D$header")
    }
}

object ObdCatalog {
    val motorSignals = listOf(
        p("pedal_pwm", "Gaz pedalı PWM sinyali", "E301", "% PWM", EcuContexts.VCFRONT, 10f),
        p("erad_motor_speed", "ERAD motor devri", "E303", "rpm", EcuContexts.ECU_F, 10f),
        p("erad_actual_torque", "ERAD gerçek tork", "E304", "Nm", EcuContexts.ECU_F, 10f),
    )

    val carScannerSignals = listOf(
        p("cell_min_soc", "Minimum hücre SOC", "487A", "%", EcuContexts.BECM, 0.2f),
        p("hv_inlet_coolant_temp", "Batarya giriş soğutma sıcaklığı", "4804", "°C", EcuContexts.BECM, 0.2f),
        p("battery_12v", "12 V batarya voltajı", "DD02", "V", EcuContexts.BECM, 0.1f),
        p("charge_power_limit", "Şarj güç limiti", "489C", "W", EcuContexts.BECM, 0.2f),
        p("discharge_power_limit", "Deşarj güç limiti", "489E", "W", EcuContexts.BECM, 0.2f),
        p("ecu_supply_voltage", "BECM ECU besleme voltajı", "EE02", "V", EcuContexts.BECM, 0.1f),
        p("cell_voltage_sum", "Hücre voltajları toplamı", "497C", "V", EcuContexts.BECM, 0.2f),
        p("cell_max_voltage", "Maksimum hücre voltajı", "4907", "V", EcuContexts.BECM, 0.2f),
        p("cell_min_voltage", "Minimum hücre voltajı", "4908", "V", EcuContexts.BECM, 0.2f),
        p("hv_soh_secondary", "HV batarya SOH (ikinci kanal)", "489A", "%", EcuContexts.BECM, 0.1f),
        p("igm_fuse_voltage", "IGM sigorta voltajı", "4809", "V", EcuContexts.BECM, 0.1f),
        p("dc_connector_temp", "DC şarj soketi sıcaklığı", "EE06", "°C", EcuContexts.BECM, 0.1f),
        p("ac_temp", "Klima sıcaklığı", "4A28", "°C", EcuContexts.VCFRONT, 0.2f),
        p("ac_pressure", "Klima basıncı", "4A29", "ham", EcuContexts.VCFRONT, 0.2f),
        p("ac_temp_chiller", "Chiller sonrası sıcaklık", "4A30", "°C", EcuContexts.VCFRONT, 0.2f),
        p("ac_temp_inner_condenser", "İç kondenser sonrası sıcaklık", "4A31", "°C", EcuContexts.VCFRONT, 0.2f),
        p("ac_temp_evaporator", "Evaporatör sonrası sıcaklık", "4A32", "°C", EcuContexts.VCFRONT, 0.2f),
        p("ac_temp_compressor", "Kompresör sonrası sıcaklık", "4A33", "°C", EcuContexts.VCFRONT, 0.2f),
        p("powertrain_coolant_temp_vcu", "Güç aktarma soğutma sıcaklığı (VCU)", "4A34", "°C", EcuContexts.VCFRONT, 0.2f),
        p("cooling_request", "Soğutma fanı/pompa isteği", "413A", "%", EcuContexts.VCFRONT, 0.2f),
        p("coolant_pump_chamber_request", "Pompa haznesi istenen hızı", "D901", "%", EcuContexts.VCFRONT, 0.2f),
        p("cooling_valve_actual", "HV soğutma valfi gerçek konumu", "E34A", "%", EcuContexts.VCFRONT, 0.2f),
        p("cooling_valve_requested", "HV soğutma valfi istenen konumu", "E349", "%", EcuContexts.VCFRONT, 0.2f),
        p("erad_wheel_speed", "ERAD teker/motor çıkış hızı", "E312", "rpm", EcuContexts.ECU_F, 1f),
        p("iem_hv_current", "IEM HV sistem akımı", "E301", "A", EcuContexts.ECU_F, 1f),
        p("erad_motor_temp", "ERAD motor sıcaklığı", "E306", "°C", EcuContexts.ECU_F, 0.5f),
        p("powertrain_coolant_temp_iem", "Elektrikli aktarma soğutma sıcaklığı", "EE9A", "°C", EcuContexts.ECU_F, 0.2f),
    )

    val confirmed = listOf(
        p("obd_soc", "BECM batarya SOC", "4801", "%", EcuContexts.BECM, 1f),
        p("hv_voltage", "HV paket voltajı", "4803", "V", EcuContexts.BECM, 2f),
        p("hv_current", "HV paket akımı", "4802", "A", EcuContexts.BECM, 2f),
        p("hv_temp_avg", "HV batarya ortalama sıcaklığı", "491B", "°C", EcuContexts.BECM, 0.2f),
        p("hv_temp_max", "HV batarya maksimum sıcaklığı", "4945", "°C", EcuContexts.BECM, 0.2f),
        p("hv_soh", "HV batarya SOH", "496D", "%", EcuContexts.BECM, 0.2f),
        p("odometer", "Toplam kilometre", "DD01", "km", EcuContexts.BECM, 0.2f),
        p("odometer_11bit", "Toplam kilometre (11-bit)", "DD01", "km", EcuContexts.GATEWAY_11BIT, 0.2f),
        p("vehicle_speed", "Araç hızı", "F40D", "km/h", EcuContexts.ECU_E, 5f),
        p("wheel_fl", "Teker hızı ön sol", "2B06", "km/h", EcuContexts.ECU_E, 2f),
        p("wheel_fr", "Teker hızı ön sağ", "2B07", "km/h", EcuContexts.ECU_E, 2f),
        p("wheel_rl", "Teker hızı arka sol", "2B08", "km/h", EcuContexts.ECU_E, 2f),
        p("wheel_rr", "Teker hızı arka sağ", "2B09", "km/h", EcuContexts.ECU_E, 2f),
        ObdPidDefinition("voltage_12v", "12 V besleme", "ATRV", "V", null, 0.1f, ResearchStatus.CONFIRMED),
    ) + carScannerSignals + motorSignals

    val displayChoices: List<Pair<String, String>> = confirmed
        .filterNot { it.key == "odometer_11bit" }
        .map { it.key to it.name } + listOf(
        "hv_power" to "HV güç (türetilmiş)",
        "soc_display" to "Gösterge batarya yüzdesi (OBD)",
    )

    val defaultDisplayKeys: Set<String> = displayChoices.map { it.first }.toSet() -
        carScannerSignals.map { it.key }.toSet() - "obd_soc"

    val candidates = listOf(
        candidate("DA17", EcuContexts.BECM),
    )

    private fun p(key: String, name: String, did: String, unit: String, ecu: EcuContext, hz: Float) =
        ObdPidDefinition(key, name, did, unit, ecu, hz, ResearchStatus.CONFIRMED)

    private fun candidate(did: String, ecu: EcuContext) =
        ObdPidDefinition("candidate_${ecu.name}_$did", "$did adayı", did, "ham", ecu, 1f, ResearchStatus.CANDIDATE)
}
