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
        name = def.name,
        source = SensorSource.VHAL,
        identifier = "0x%08X".format(Locale.US, def.id),
        unit = def.unit,
        targetHz = def.requestedHz,
    )

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
