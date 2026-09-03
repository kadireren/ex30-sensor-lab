package com.kadireren.ex30sensorlab.vhal

import android.car.VehiclePropertyIds
import java.util.Locale

object VhalFormatter {
    fun raw(value: Any?): String = when (value) {
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is LongArray -> value.joinToString(prefix = "[", postfix = "]")
        is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]")
        null -> "—"
        else -> value.toString()
    }

    fun display(propertyId: Int, value: Any?, capacityWh: Float?, powerMultiplier: Int = 0): String {
        val number = (value as? Number)?.toFloat()
        return when (propertyId) {
            VehiclePropertyIds.PERF_VEHICLE_SPEED,
            VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY -> number?.let { f("%.1f km/h", it * 3.6f) } ?: raw(value)
            VehiclePropertyIds.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE -> number?.let {
                val rawKw = it / 1_000_000f
                if (powerMultiplier == 0) f("%.3f kW (yön doğrulanmadı)", rawKw)
                else f("%.3f kW (kalibre)", rawKw * powerMultiplier)
            } ?: raw(value)
            VehiclePropertyIds.RANGE_REMAINING -> number?.let { f("%.1f km", it / 1000f) } ?: raw(value)
            VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY -> number?.let { f("%.2f kWh", it / 1000f) } ?: raw(value)
            VehiclePropertyIds.EV_BATTERY_LEVEL -> number?.let {
                val kwh = it / 1000f
                if (capacityWh != null && capacityWh > 0f) f("%.2f kWh · türetilmiş %.1f%%", kwh, it / capacityWh * 100f)
                else f("%.2f kWh", kwh)
            } ?: raw(value)
            VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> number?.let { f("%.1f °C", it) } ?: raw(value)
            else -> raw(value)
        }
    }

    private fun f(format: String, vararg values: Any): String = String.format(Locale.US, format, *values)
}
