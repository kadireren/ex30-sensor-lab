package com.kadireren.ex30sensorlab.obd

import android.os.SystemClock
import com.kadireren.ex30sensorlab.discovery.DiscoveredSensorCatalog
import com.kadireren.ex30sensorlab.discovery.DiscoveryDecoders
import com.kadireren.ex30sensorlab.model.ObdPidDefinition
import com.kadireren.ex30sensorlab.model.SampleStatus
import com.kadireren.ex30sensorlab.model.SensorDefinition
import com.kadireren.ex30sensorlab.model.SensorSample
import com.kadireren.ex30sensorlab.model.SensorSource
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ObdPollingController(private val protocol: ElmProtocol) : PollingScheduler {
    private data class TimedRaw(val raw: String, val timestampMs: Long)

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private val focusChanged = AtomicBoolean(false)
    private val nextDue = mutableMapOf<String, Long>()
    private val timestamps = mutableMapOf<String, ArrayDeque<Long>>()
    private val rawByKey = mutableMapOf<String, TimedRaw>()
    @Volatile private var focusKey: String? = null
    private var brakeFailures = 0
    private var brakeFallback = false
    private var odometerFailures = 0
    private var odometerFallback = false

    override fun start(onSample: (SensorSample) -> Unit, onState: (String) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        val runGeneration = generation.incrementAndGet()
        executor.execute {
            resetRunState()
            onState("OBD okuma başladı")
            while (running.get() && generation.get() == runGeneration) {
                if (focusChanged.compareAndSet(true, false)) nextDue.clear()
                val now = SystemClock.elapsedRealtime()
                val definitions = selectedDefinitions()
                val due = definitions
                    .filter { now >= (nextDue[it.key] ?: 0L) }
                    .sortedWith(compareByDescending<ObdPidDefinition> { it.targetHz }.thenBy { it.ecu?.name ?: "" })
                if (due.isEmpty()) {
                    SystemClock.sleep(10L)
                    continue
                }
                val dueByEcu = due.groupBy { it.ecu?.name ?: "ELM327" }
                for ((_, batch) in dueByEcu.entries.sortedBy { it.key }) {
                    if (!running.get() || generation.get() != runGeneration) break
                    for (definition in batch.sortedByDescending { it.targetHz }) {
                        if (!running.get() || generation.get() != runGeneration) break
                        queryOne(definition, onSample, onState)
                    }
                }
            }
            onState("OBD okuma durdu")
        }
    }

    override fun focus(sensorKey: String?) {
        focusKey = sensorKey
        focusChanged.set(true)
    }

    override fun stop() {
        running.set(false)
        generation.incrementAndGet()
        focusKey = null
        focusChanged.set(false)
    }

    fun close() {
        stop()
        executor.shutdownNow()
    }

    private fun resetRunState() {
        nextDue.clear()
        timestamps.clear()
        rawByKey.clear()
        brakeFailures = 0
        brakeFallback = false
        odometerFailures = 0
        odometerFallback = false
    }

    private fun queryOne(definition: ObdPidDefinition, onSample: (SensorSample) -> Unit, onState: (String) -> Unit) {
        val started = SystemClock.elapsedRealtime()
        var querySucceeded = false
        try {
            val command = if (definition.did.startsWith("AT")) definition.did else "22${definition.did}"
            val raw = protocol.query(definition.ecu, command)
            val discoveredConfig = DiscoveredSensorCatalog.findConfig(definition.key)
            val display = discoveredConfig?.let { DiscoveryDecoders.format(it, raw) }
                ?: ObdDecoders.decode(definition.key, definition.did, raw)
            val success = display != null
            querySucceeded = success
            recordBrakeResult(definition.key, raw, success)
            recordOdometerResult(definition.key, raw, success)
            if (success) rawByKey[definition.key] = TimedRaw(raw, SystemClock.elapsedRealtime())
            else rawByKey.remove(definition.key)
            onSample(sample(definition, raw, display ?: "Yanıt çözülemedi", started, success))
            emitPowerIfReady(onSample, started)
            emitBrakeAverageIfReady(onSample, started)
        } catch (e: Exception) {
            onSample(sample(definition, "", e.message ?: "OBD hatası", started, false))
            onState(e.message ?: "OBD okuma hatası")
        }
        val delayMs = if (querySucceeded) intervalMs(definition) else ERROR_RETRY_DELAY_MS
        nextDue[definition.key] = SystemClock.elapsedRealtime() + delayMs
    }

    private fun recordBrakeResult(key: String, raw: String, success: Boolean) {
        if (key != "brake_multi") return
        if (success) {
            brakeFailures = 0
            return
        }
        if (!ObdDecoders.hasEcuResponse(raw)) return
        if (++brakeFailures >= BRAKE_MULTI_MAX_FAILURES) brakeFallback = true
    }

    private fun recordOdometerResult(key: String, raw: String, success: Boolean) {
        if (key != "odometer") return
        if (success) {
            odometerFailures = 0
            return
        }
        if (!ObdDecoders.hasEcuResponse(raw)) return
        if (++odometerFailures >= ODOMETER_MAX_FAILURES) odometerFallback = true
    }

    private fun selectedDefinitions(): List<ObdPidDefinition> {
        val focused = focusKey
        val discovered = DiscoveredSensorCatalog.confirmedDefinitions()
        if (focused != null) {
            return (discovered + ObdCatalog.confirmed).filter { it.key == focused }
        }
        return discovered + ObdCatalog.confirmed.filter {
            when {
                it.key == "brake_multi" -> !brakeFallback
                it.key.startsWith("brake_") -> brakeFallback && it.key != "brake_multi"
                it.key == "odometer" -> !odometerFallback
                it.key == "odometer_11bit" -> odometerFallback
                else -> it.targetHz > 0f
            }
        }
    }

    private fun intervalMs(def: ObdPidDefinition): Long {
        if (focusKey != null) return FOCUS_INTERVAL_MS
        val hz = if (def.key.startsWith("brake_") && brakeFallback) 8f else def.targetHz
        return if (hz <= 0f) 5_000L else (1000f / hz).toLong().coerceAtLeast(25L)
    }

    private fun sample(def: ObdPidDefinition, raw: String, display: String, started: Long, success: Boolean): SensorSample {
        val now = SystemClock.elapsedRealtime()
        val queue = timestamps.getOrPut(def.key) { ArrayDeque() }
        if (success) queue.addLast(now)
        while (queue.isNotEmpty() && now - queue.first() > 5_000L) queue.removeFirst()
        val hz = if (queue.size > 1) (queue.size - 1) * 1000f / (queue.last() - queue.first()).coerceAtLeast(1L) else 0f
        return SensorSample(
            definition = SensorDefinition(def.key, def.name, SensorSource.OBD, def.did, def.unit, def.targetHz),
            rawValue = raw,
            displayValue = display,
            monotonicTimestampMs = now,
            latencyMs = now - started,
            actualHz = hz,
            status = if (success) SampleStatus.LIVE else SampleStatus.ERROR,
            detail = def.ecu?.name ?: "ELM327",
        )
    }

    private fun emitPowerIfReady(onSample: (SensorSample) -> Unit, started: Long) {
        val voltage = rawByKey["hv_voltage"] ?: return
        val current = rawByKey["hv_current"] ?: return
        if (kotlin.math.abs(voltage.timestampMs - current.timestampMs) > MAX_DERIVED_SAMPLE_SKEW_MS) return
        val power = ObdDecoders.derivedPowerKw(voltage.raw, current.raw) ?: return
        val def = ObdPidDefinition("hv_power", "HV güç (türetilmiş)", "4801×4802", "kW", EcuContexts.BECM, 2f, com.kadireren.ex30sensorlab.model.ResearchStatus.CONFIRMED)
        onSample(sample(def, "4801 + 4802", String.format(Locale.US, "%.2f kW", power), started, true))
    }

    private fun emitBrakeAverageIfReady(onSample: (SensorSample) -> Unit, started: Long) {
        if (!brakeFallback) return
        val bars = BRAKE_CHANNEL_KEYS.mapNotNull { key ->
            rawByKey[key]?.takeIf { SystemClock.elapsedRealtime() - it.timestampMs <= MAX_DERIVED_SAMPLE_SKEW_MS }?.let { timed ->
                ObdDecoders.decode(key, ObdCatalog.confirmed.first { it.key == key }.did, timed.raw)
                    ?.removeSuffix(" bar")?.toFloatOrNull()
            }
        }
        if (bars.size < BRAKE_CHANNEL_KEYS.size) return
        val def = ObdCatalog.confirmed.first { it.key == "brake_multi" }
        val average = bars.average()
        onSample(
            sample(
                def,
                bars.joinToString(prefix = "FD00-FD03 ort.: ", separator = "/") { String.format(Locale.US, "%.2f", it) },
                String.format(Locale.US, "%.2f bar", average),
                started,
                true,
            ),
        )
    }

    companion object {
        private const val BRAKE_MULTI_MAX_FAILURES = 5
        private const val ODOMETER_MAX_FAILURES = 3
        private const val MAX_DERIVED_SAMPLE_SKEW_MS = 1_000L
        private const val ERROR_RETRY_DELAY_MS = 1_000L
        private const val FOCUS_INTERVAL_MS = 33L
        private val BRAKE_CHANNEL_KEYS = listOf("brake_fl", "brake_fr", "brake_rl", "brake_rr")
    }
}
