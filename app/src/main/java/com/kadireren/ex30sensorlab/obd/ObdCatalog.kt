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

    val known = listOf(BECM, VCFRONT, ECU_D, ECU_E, ECU_F)

    fun deriveProbe(headerLow16: Int): EcuContext {
        require(headerLow16 in 0x1601..0x17FF)
        val header = "D0%04X".format(headerLow16)
        val rx = 0x1EC02E80L + ((headerLow16 - 0x1601L) shl 13)
        return EcuContext("Probe-$header", 7, header, "1D", "%08X".format(rx), "1D$header")
    }
}

object ObdCatalog {
    val confirmed = listOf(
        p("hv_voltage", "HV paket voltajı", "4801", "V", EcuContexts.BECM, 2f),
        p("hv_current", "HV paket akımı", "4802", "A", EcuContexts.BECM, 2f),
        p("hv_temp_avg", "HV batarya ortalama sıcaklığı", "491B", "°C", EcuContexts.BECM, 0.2f),
        p("hv_temp_max", "HV batarya maksimum sıcaklığı", "4945", "°C", EcuContexts.BECM, 0.2f),
        p("hv_soh", "HV batarya SOH", "496D", "%", EcuContexts.BECM, 0.2f),
        p("odometer", "Toplam kilometre", "DD01", "km", EcuContexts.BECM, 0.2f),
        p("soc_display", "Gösterge SOC", "D901", "%", EcuContexts.VCFRONT, 0.2f),
        p("vehicle_speed", "Araç hızı", "F40D", "km/h", EcuContexts.ECU_E, 5f),
        p("wheel_fl", "Teker hızı ön sol", "2B06", "km/h", EcuContexts.ECU_E, 2f),
        p("wheel_fr", "Teker hızı ön sağ", "2B07", "km/h", EcuContexts.ECU_E, 2f),
        p("wheel_rl", "Teker hızı arka sol", "2B08", "km/h", EcuContexts.ECU_E, 2f),
        p("wheel_rr", "Teker hızı arka sağ", "2B09", "km/h", EcuContexts.ECU_E, 2f),
        p("brake_multi", "Fren basıncı ortalaması", "FD00FD01FD02FD03", "bar", EcuContexts.ECU_E, 8f),
        p("brake_fl", "Fren basıncı FD00", "FD00", "bar", EcuContexts.ECU_E, 0f),
        p("brake_fr", "Fren basıncı FD01", "FD01", "bar", EcuContexts.ECU_E, 0f),
        p("brake_rl", "Fren basıncı FD02", "FD02", "bar", EcuContexts.ECU_E, 0f),
        p("brake_rr", "Fren basıncı FD03", "FD03", "bar", EcuContexts.ECU_E, 0f),
        ObdPidDefinition("voltage_12v", "12 V besleme", "ATRV", "V", null, 0.1f, ResearchStatus.CONFIRMED),
    )

    val candidates = listOf(
        candidate("DD02", EcuContexts.BECM), candidate("4907", EcuContexts.BECM),
        candidate("4908", EcuContexts.BECM), candidate("DA17", EcuContexts.BECM),
        candidate("413A", EcuContexts.VCFRONT),
        *listOf("4A28", "4A29", "4A30", "4A31", "4A32", "4A33", "4A34").map { candidate(it, EcuContexts.VCFRONT) }.toTypedArray(),
        *listOf("E300", "E301", "E303", "E304", "E306", "E312", "EE9A").map { candidate(it, EcuContexts.ECU_F) }.toTypedArray(),
        candidate("2B11", EcuContexts.ECU_E), candidate("FEE7", EcuContexts.ECU_E),
        *listOf("EE19", "EE1A", "EE1B", "EE06").map { candidate(it, EcuContexts.ECU_D) }.toTypedArray(),
    )

    private fun p(key: String, name: String, did: String, unit: String, ecu: EcuContext, hz: Float) =
        ObdPidDefinition(key, name, did, unit, ecu, hz, ResearchStatus.CONFIRMED)

    private fun candidate(did: String, ecu: EcuContext) =
        ObdPidDefinition("candidate_${ecu.name}_$did", "$did adayı", did, "ham", ecu, 1f, ResearchStatus.CANDIDATE)
}
