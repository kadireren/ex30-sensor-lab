package com.kadireren.ex30sensorlab.vhal

import kotlin.math.abs

object VhalDerivedCalculations {
    fun socPercent(energyWh: Float, capacityWh: Float): Float? {
        if (!energyWh.isFinite() || !capacityWh.isFinite() || capacityWh <= 0f) return null
        return (energyWh / capacityWh * 100f).coerceIn(0f, 100f)
    }

    fun normalizePowerKw(raw: Float): Float = when {
        abs(raw) >= 500_000f -> raw / 1_000_000f
        abs(raw) >= 500f -> raw / 1_000f
        else -> raw
    }

    fun instantConsumptionKwh100(powerKw: Float, speedKmh: Float): Float? {
        if (!powerKw.isFinite() || !speedKmh.isFinite() || speedKmh < 3f) return null
        if (powerKw > 180f || powerKw < -130f) return null
        if (speedKmh < 8f && (powerKw > 70f || powerKw < -70f)) return null
        val value = powerKw / speedKmh * 100f
        return value.takeIf { it.isFinite() }?.coerceIn(-120f, 160f)
    }
}
