package com.kadireren.ex30sensorlab.obd

import java.util.Locale

object ObdDecoders {
    fun extractData(raw: String, did: String): String? {
        val clean = raw
            .replace(Regex("[0-9A-Fa-f]:"), "")
            .replace(Regex("[^0-9A-Fa-f]"), "")
            .uppercase(Locale.US)
        val marker = "62${did.take(4).uppercase(Locale.US)}"
        val index = clean.indexOf(marker)
        return if (index < 0) null else clean.substring(index + marker.length)
    }

    fun decode(definitionKey: String, did: String, raw: String): String? {
        if (definitionKey == "voltage_12v") {
            val value = Regex("(\\d+(?:\\.\\d+)?)").find(raw)?.groupValues?.get(1)?.toFloatOrNull()
            return value?.takeIf { it in 6f..16.5f }?.let { f("%.2f V", it) }
        }
        if (definitionKey == "brake_multi") return decodeBrakeMulti(raw)
        val data = extractData(raw, did) ?: return null
        return try {
            when (definitionKey) {
                "hv_voltage" -> f("%.2f V", data.take(4).toInt(16) / 100f)
                "hv_current" -> f("%.1f A", (data.take(4).toInt(16) - 16384) * 0.1f)
                "hv_temp_avg" -> f("%.2f °C", data.take(4).toInt(16) / 100f - 50f)
                "hv_temp_max" -> f("%.2f °C · sensör %d", data.substring(2, 6).toInt(16) / 100f - 50f, data.take(2).toInt(16))
                "hv_soh" -> f("%.2f %%", data.take(8).toLong(16) * 0.01f)
                "odometer" -> f("%d km", data.take(6).toLong(16))
                "soc_display" -> f("%d %%", data.take(2).toInt(16))
                "vehicle_speed", "wheel_fl", "wheel_fr", "wheel_rl", "wheel_rr" -> f("%d km/h", data.take(2).toInt(16))
                "brake_fl", "brake_fr", "brake_rl", "brake_rr" -> f("%.2f bar", data.take(4).toInt(16) / 100f)
                else -> data
            }
        } catch (_: Exception) {
            null
        }
    }

    fun decodeBrakeMulti(raw: String): String? {
        val clean = raw.replace(Regex("[^0-9A-Fa-f]"), "").uppercase(Locale.US)
        val match = Regex("62FD00([0-9A-F]{4}).*?FD01([0-9A-F]{4}).*?FD02([0-9A-F]{4}).*?FD03([0-9A-F]{4})").find(clean) ?: return null
        val values = match.groupValues.drop(1).map { it.toInt(16) }
        if (values.any { it > 0x4000 }) return null
        return f("%.2f bar", values.average() / 100.0)
    }

    fun derivedPowerKw(voltageRaw: String, currentRaw: String): Float? {
        val voltageData = extractData(voltageRaw, "4801") ?: return null
        val currentData = extractData(currentRaw, "4802") ?: return null
        return try {
            val voltage = voltageData.take(4).toInt(16) / 100f
            val current = (currentData.take(4).toInt(16) - 16384) * 0.1f
            voltage * current / 1000f
        } catch (_: Exception) {
            null
        }
    }

    fun isPositiveResponse(raw: String, did: String): Boolean =
        raw.replace(Regex("[^0-9A-Fa-f]"), "").uppercase(Locale.US).contains("62${did.take(4).uppercase(Locale.US)}")

    fun isNegativeResponse(raw: String): Boolean =
        Regex("7F(22|01)").containsMatchIn(raw.replace(" ", "").uppercase(Locale.US))

    fun negativeResponseCode(raw: String): String? {
        val clean = raw.replace(Regex("[^0-9A-Fa-f]"), "").uppercase(Locale.US)
        return Regex("7F(?:22|01)([0-9A-F]{2})").find(clean)?.groupValues?.get(1)
    }

    private fun f(format: String, vararg value: Any): String = String.format(Locale.US, format, *value)
}
