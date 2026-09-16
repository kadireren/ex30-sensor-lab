package com.kadireren.ex30sensorlab.obd

import java.util.Locale

object ObdDecoders {
    fun cleanHex(raw: String): String = raw
        .replace(Regex("[0-9A-Fa-f]:"), "")
        .replace(Regex("[^0-9A-Fa-f]"), "")
        .uppercase(Locale.US)

    fun extractData(raw: String, did: String): String? {
        val clean = cleanHex(raw)
        val marker = "62${did.take(4).uppercase(Locale.US)}"
        val index = clean.indexOf(marker)
        return if (index < 0) null else clean.substring(index + marker.length)
    }

    fun decode(definitionKey: String, did: String, raw: String): String? {
        if (definitionKey == "voltage_12v") {
            val value = Regex("(\\d+(?:\\.\\d+)?)").find(raw)?.groupValues?.get(1)?.toFloatOrNull()
            return value?.takeIf { it in 6f..16.5f }?.let { f("%.2f V", it) }
        }
        val data = extractData(raw, did) ?: return null
        return try {
            when (definitionKey) {
                "hv_voltage" -> f("%.2f V", data.take(4).toInt(16) / 100f)
                "hv_current" -> f("%.1f A", (data.take(4).toInt(16) - 16384) * 0.1f)
                "obd_soc" -> f("%.3f %%", data.take(4).toInt(16) / 500f)
                "hv_temp_avg" -> f("%.2f °C", data.take(4).toInt(16) / 100f - 50f)
                "hv_temp_max" -> f("%.2f °C · sensör %d", data.substring(2, 6).toInt(16) / 100f - 50f, data.take(2).toInt(16))
                "hv_soh" -> f("%.2f %%", data.take(8).toLong(16) * 0.01f)
                "odometer", "odometer_11bit" -> f("%d km", data.take(6).toLong(16))
                "cell_min_soc" -> f("%.6f %%", data.take(8).toLong(16) / 1_000_000.0)
                "hv_inlet_coolant_temp" -> f("%.1f °C", data.take(2).toInt(16) - 50f)
                "battery_12v" -> f("%.2f V", data.take(2).toInt(16) / 4f)
                "charge_power_limit", "discharge_power_limit" -> f("%.3f W", data.take(8).toLong(16) / 1_000.0)
                "ecu_supply_voltage" -> f("%.3f V", data.take(4).toInt(16) / 1_000f)
                "cell_voltage_sum", "igm_fuse_voltage" -> f("%.2f V", data.take(4).toInt(16) / 100f)
                "cell_max_voltage", "cell_min_voltage" -> f("%.3f V · hücre %d", data.substring(2, 6).toInt(16) / 1_000f, data.take(2).toInt(16))
                "hv_soh_secondary" -> f("%.2f %%", data.take(4).toInt(16) / 100f)
                "dc_connector_temp" -> f("%.2f °C", data.take(4).toInt(16) / 100f - 50f)
                "ac_temp", "ac_temp_chiller", "ac_temp_inner_condenser", "ac_temp_evaporator", "ac_temp_compressor", "powertrain_coolant_temp_vcu" ->
                    f("%.1f °C", data.take(8).toLong(16) / 10f - 40f)
                "ac_pressure" -> f("%d", data.take(8).toLong(16))
                "cooling_request", "coolant_pump_chamber_request" -> f("%.1f %%", data.take(8).toLong(16) / 10f)
                "cooling_valve_actual", "cooling_valve_requested" -> f("%d %%", data.take(2).toInt(16))
                "erad_wheel_speed" -> f("%.1f rpm", (data.take(4).toInt(16) - 16384) / 10f)
                "iem_hv_current" -> f("%.1f A", (data.take(4).toInt(16) - 8188) / 10f)
                "erad_motor_temp" -> f("%.1f °C", data.take(2).toInt(16) - 50f)
                "powertrain_coolant_temp_iem" -> f("%.1f °C", data.take(2).toInt(16) - 40f)
                "pedal_pwm" -> data.takeIf { it.length >= 2 }?.take(2)?.toInt(16)?.takeIf { it in 0..100 }?.let { f("%d %% PWM", it) }
                "erad_motor_speed" -> data.takeIf { it.length >= 4 }?.take(4)?.toInt(16)?.let { f("%d rpm", it - 16384) }
                "erad_actual_torque" -> data.takeIf { it.length >= 4 }?.take(4)?.toInt(16)?.let { f("%d Nm", it - 8188) }
                "vehicle_speed", "wheel_fl", "wheel_fr", "wheel_rl", "wheel_rr" -> f("%d km/h", data.take(2).toInt(16))
                else -> data
            }
        } catch (_: Exception) {
            null
        }
    }

    fun derivedPowerKw(voltageRaw: String, currentRaw: String): Float? {
        val voltageData = extractData(voltageRaw, "4803") ?: return null
        val currentData = extractData(currentRaw, "4802") ?: return null
        return try {
            val voltage = voltageData.take(4).toInt(16) / 100f
            val current = (currentData.take(4).toInt(16) - 16384) * 0.1f
            voltage * current / 1000f
        } catch (_: Exception) {
            null
        }
    }

    fun derivedDisplaySoc(raw: String): Float? {
        val data = extractData(raw, "4801") ?: return null
        return try {
            val batterySoc = data.take(4).toInt(16) / 500f
            (batterySoc * 1.0625f - 3.125f).coerceIn(0f, 100f)
        } catch (_: Exception) {
            null
        }
    }

    fun hasEcuResponse(raw: String): Boolean {
        val upper = raw.uppercase(Locale.US)
        if (upper.contains("NO DATA") || upper.contains("UNABLE TO CONNECT") || upper.contains("BUS ERROR")) return false
        val clean = cleanHex(raw)
        return clean.contains("62") || clean.contains("7F")
    }

    fun isPositiveResponse(raw: String, did: String): Boolean =
        cleanHex(raw).contains("62${did.take(4).uppercase(Locale.US)}")

    fun isNegativeResponse(raw: String): Boolean =
        Regex("7F(22|01)").containsMatchIn(cleanHex(raw))

    fun negativeResponseCode(raw: String): String? {
        val clean = cleanHex(raw)
        return Regex("7F(?:22|01)([0-9A-F]{2})").find(clean)?.groupValues?.get(1)
    }

    private fun f(format: String, vararg value: Any): String = String.format(Locale.US, format, *value)
}
