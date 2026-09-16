package com.kadireren.ex30sensorlab.vhal

import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.os.SystemClock
import com.kadireren.ex30sensorlab.model.SampleStatus
import com.kadireren.ex30sensorlab.model.SensorDefinition
import com.kadireren.ex30sensorlab.model.SensorSample
import com.kadireren.ex30sensorlab.model.SensorSource
import java.util.ArrayDeque
import java.util.Locale

class AndroidVhalReader(
    private val manager: CarPropertyManager,
    private val powerMultiplier: () -> Int = { 0 },
) : VhalReader {
    private var sampleListener: ((SensorSample) -> Unit)? = null
    private var safetyListener: ((SafetyState) -> Unit)? = null
    private val timestamps = mutableMapOf<Int, ArrayDeque<Long>>()
    private var capacityWh: Float? = null
    private var energyWh: Float? = null
    private var displaySpeedKmh: Float? = null
    private var perfSpeedKmh: Float? = null
    private var powerKw: Float? = null
    private var displaySpeedTimestampMs = 0L
    private var perfSpeedTimestampMs = 0L
    private var powerTimestampMs = 0L
    private var safety = SafetyState()

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val def = VhalCatalog.entries.firstOrNull { it.id == value.propertyId } ?: return
            val now = SystemClock.elapsedRealtime()
            val queue = timestamps.getOrPut(def.id) { ArrayDeque() }
            queue.addLast(now)
            while (queue.isNotEmpty() && now - queue.first() > 5_000L) queue.removeFirst()
            val hz = if (queue.size > 1) (queue.size - 1) * 1000f / (queue.last() - queue.first()).coerceAtLeast(1L) else 0f

            if (def.id == VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY) {
                capacityWh = (value.value as? Number)?.toFloat()
            }
            when (def.id) {
                VehiclePropertyIds.EV_BATTERY_LEVEL -> energyWh = (value.value as? Number)?.toFloat()
                VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY -> {
                    displaySpeedKmh = (value.value as? Number)?.toFloat()?.times(3.6f)
                    displaySpeedTimestampMs = now
                }
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                    perfSpeedKmh = (value.value as? Number)?.toFloat()?.times(3.6f)
                    perfSpeedTimestampMs = now
                }
                VehiclePropertyIds.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE -> {
                    val rawKw = (value.value as? Number)?.toFloat()?.let(VhalDerivedCalculations::normalizePowerKw)
                    val multiplier = powerMultiplier()
                    powerKw = rawKw?.let { if (multiplier == 0) it else it * multiplier }
                    powerTimestampMs = now
                }
            }
            updateSafety(def.id, value.value)

            val latencyMs = ((SystemClock.elapsedRealtimeNanos() - value.timestamp).coerceAtLeast(0L) / 1_000_000L)
            sampleListener?.invoke(
                SensorSample(
                    definition = sensorDefinition(def),
                    rawValue = VhalFormatter.raw(value.value),
                    displayValue = VhalFormatter.display(def.id, value.value, capacityWh, powerMultiplier()),
                    monotonicTimestampMs = now,
                    latencyMs = latencyMs,
                    actualHz = hz,
                    status = SampleStatus.LIVE,
                    detail = "0x%08X".format(Locale.US, def.id),
                )
            )
            emitDerivedSamples(now)
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            val def = VhalCatalog.entries.firstOrNull { it.id == propertyId } ?: return
            sampleListener?.invoke(statusSample(def, SampleStatus.ERROR, "VHAL hata · alan $areaId"))
        }
    }

    @Suppress("DEPRECATION")
    override fun start(onSample: (SensorSample) -> Unit, onSafety: (SafetyState) -> Unit) {
        stop()
        sampleListener = onSample
        safetyListener = onSafety
        val configs = try {
            manager.propertyList.associateBy { it.propertyId }
        } catch (_: Exception) {
            emptyMap()
        }

        VhalCatalog.entries.forEach { def ->
            val config = configs[def.id]
            if (configs.isNotEmpty() && config == null) {
                onSample(statusSample(def, SampleStatus.UNSUPPORTED, "Araç bildirmedi"))
                return@forEach
            }
            val rate = when {
                def.requestedHz <= 0f -> CarPropertyManager.SENSOR_RATE_ONCHANGE
                config == null -> def.requestedHz
                config.maxSampleRate <= 0f -> CarPropertyManager.SENSOR_RATE_ONCHANGE
                else -> def.requestedHz.coerceIn(config.minSampleRate, config.maxSampleRate)
            }
            try {
                manager.registerCallback(callback, def.id, rate)
                onSample(statusSample(def, SampleStatus.WAITING, "Örnek bekleniyor · hedef ${formatHz(rate)}"))
            } catch (_: SecurityException) {
                onSample(statusSample(def, SampleStatus.PERMISSION_DENIED, "İzin verilmedi"))
            } catch (e: Exception) {
                onSample(statusSample(def, SampleStatus.ERROR, e.message ?: "Kayıt hatası"))
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun stop() {
        try {
            manager.unregisterCallback(callback)
        } catch (_: Exception) {
        }
        timestamps.clear()
        capacityWh = null
        energyWh = null
        displaySpeedKmh = null
        perfSpeedKmh = null
        powerKw = null
        displaySpeedTimestampMs = 0L
        perfSpeedTimestampMs = 0L
        powerTimestampMs = 0L
        sampleListener = null
        safetyListener = null
    }

    private fun updateSafety(propertyId: Int, raw: Any?) {
        safety = when (propertyId) {
            VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY -> safety.copy(
                speedKmh = (raw as? Number)?.toFloat()?.times(3.6f)
            )
            VehiclePropertyIds.PARKING_BRAKE_ON -> safety.copy(parkingBrakeOn = raw as? Boolean)
            VehiclePropertyIds.IGNITION_STATE -> safety.copy(ignitionState = (raw as? Number)?.toInt())
            else -> safety
        }
        safetyListener?.invoke(safety)
    }

    private fun sensorDefinition(def: VhalDefinition) = SensorDefinition(
        key = def.name,
        name = def.label,
        source = SensorSource.VHAL,
        identifier = "0x%08X".format(Locale.US, def.id),
        unit = def.unit,
        targetHz = def.requestedHz,
    )

    private fun emitDerivedSamples(now: Long) {
        val capacity = capacityWh
        val energy = energyWh
        if (capacity != null && energy != null) {
            VhalDerivedCalculations.socPercent(energy, capacity)?.let { soc ->
                sampleListener?.invoke(
                    SensorSample(
                        definition = SensorDefinition(
                            VhalCatalog.SOC_PERCENT_KEY,
                            "Gösterge batarya yüzdesi",
                            SensorSource.VHAL,
                            "EV_BATTERY_LEVEL / INFO_EV_BATTERY_CAPACITY",
                            "%",
                            0.5f,
                        ),
                        rawValue = "$energy / $capacity Wh",
                        displayValue = String.format(Locale.US, "%.1f %%", soc),
                        monotonicTimestampMs = now,
                        status = SampleStatus.LIVE,
                        detail = "VHAL enerji / kapasite",
                    ),
                )
            }
        }

        val (speed, speedTimestampMs) = if (displaySpeedKmh != null && now - displaySpeedTimestampMs <= 2_500L) {
            displaySpeedKmh to displaySpeedTimestampMs
        } else {
            perfSpeedKmh to perfSpeedTimestampMs
        }
        val power = powerKw
        if (speed != null && power != null && kotlin.math.abs(speedTimestampMs - powerTimestampMs) <= 2_500L) {
            val consumption = VhalDerivedCalculations.instantConsumptionKwh100(power, speed)
            sampleListener?.invoke(
                SensorSample(
                    definition = SensorDefinition(
                        VhalCatalog.INSTANT_CONSUMPTION_KEY,
                        "Anlık tüketim",
                        SensorSource.VHAL,
                        "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE / hız",
                        "kWh/100 km",
                        10f,
                    ),
                    rawValue = String.format(Locale.US, "%.3f kW / %.2f km/h", power, speed),
                    displayValue = consumption?.let { String.format(Locale.US, "%.1f kWh/100 km", it) } ?: "0.0 kWh/100 km",
                    monotonicTimestampMs = now,
                    status = SampleStatus.LIVE,
                    detail = "Dashboard formülü: güç / hız × 100",
                ),
            )
        }
    }

    private fun statusSample(def: VhalDefinition, status: SampleStatus, detail: String) = SensorSample(
        definition = sensorDefinition(def),
        rawValue = "—",
        displayValue = "—",
        monotonicTimestampMs = SystemClock.elapsedRealtime(),
        status = status,
        detail = detail,
    )

    private fun formatHz(rate: Float): String = if (rate <= 0f) "değişimde" else String.format(Locale.US, "%.1f Hz", rate)
}
