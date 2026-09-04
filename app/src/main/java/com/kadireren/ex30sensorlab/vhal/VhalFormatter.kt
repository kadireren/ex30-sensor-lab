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
            Ex30VhalIds.ABS_VEHICLE_SPEED_KMH -> number?.let { f("%.1f km/h", it) } ?: raw(value)
            VehiclePropertyIds.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE -> number?.let {
                val kw = if (powerMultiplier == 0) it / 1_000_000f else it / 1_000_000f * powerMultiplier
                if (powerMultiplier == 0) f("%.1f kW", kw) else f("%.1f kW (kalibre)", kw)
            } ?: raw(value)
            Ex30VhalIds.HV_BATTERY_VOLTAGE -> number?.let { f("%.1f V", it) } ?: raw(value)
            Ex30VhalIds.HV_BATTERY_CURRENT -> number?.let { f("%.1f A", it) } ?: raw(value)
            VehiclePropertyIds.RANGE_REMAINING -> number?.let {
                val km = if (it > 5_000f) it / 1000f else it
                f("%.0f km", km)
            } ?: raw(value)
            VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY -> number?.let { f("%.1f kWh", it / 1000f) } ?: raw(value)
            VehiclePropertyIds.EV_BATTERY_LEVEL -> number?.let {
                val kwh = it / 1000f
                if (capacityWh != null && capacityWh > 0f) f("%.1f kWh · %.0f%%", kwh, it / capacityWh * 100f)
                else f("%.1f kWh", kwh)
            } ?: raw(value)
            VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> number?.let { f("%.0f °C", it) } ?: raw(value)
            VehiclePropertyIds.PARKING_BRAKE_ON -> when (value) {
                true -> "Aktif"
                false -> "Kapalı"
                else -> raw(value)
            }
            VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED -> when (value) {
                true -> "Takılı"
                false -> "Çıkarılmış"
                else -> raw(value)
            }
            VehiclePropertyIds.NIGHT_MODE -> when (value) {
                true -> "Gece"
                false -> "Gündüz"
                else -> raw(value)
            }
            else -> raw(value)
        }
    }

    private fun f(format: String, vararg values: Any): String = String.format(Locale.US, format, *values)
}
