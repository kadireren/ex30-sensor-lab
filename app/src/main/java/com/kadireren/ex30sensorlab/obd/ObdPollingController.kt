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

class ObdPollingController(
    private val protocol: ElmProtocol,
    enabledKeys: Set<String>? = null,
) : PollingScheduler {
    private data class TimedRaw(val raw: String, val timestampMs: Long)

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private val focusChanged = AtomicBoolean(false)
    private val nextDue = mutableMapOf<String, Long>()
    private val timestamps = mutableMapOf<String, ArrayDeque<Long>>()
    private val rawByKey = mutableMapOf<String, TimedRaw>()
    @Volatile private var focusKey: String? = null
    @Volatile private var enabledKeys: Set<String>? = enabledKeys
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
                var connectionHealthy = true
                batchLoop@ for ((_, batch) in dueByEcu.entries.sortedBy { it.key }) {
                    if (!running.get() || generation.get() != runGeneration) break
                    for (definition in batch.sortedByDescending { it.targetHz }) {
                        if (!running.get() || generation.get() != runGeneration) break
                        if (!queryOne(definition, onSample, onState)) {
                            connectionHealthy = false
                            break@batchLoop
                        }
                    }
                }
                if (!connectionHealthy) SystemClock.sleep(ERROR_RETRY_DELAY_MS)
            }
            onState("OBD okuma durdu")
        }
    }

    override fun focus(sensorKey: String?) {
        focusKey = sensorKey
        focusChanged.set(true)
    }

    fun setEnabledKeys(keys: Set<String>) {
        enabledKeys = keys
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
        odometerFailures = 0
        odometerFallback = false
    }

    private fun queryOne(definition: ObdPidDefinition, onSample: (SensorSample) -> Unit, onState: (String) -> Unit): Boolean {
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
            recordOdometerResult(definition.key, raw, success)
            if (success) {
                rawByKey[definition.key] = TimedRaw(raw, SystemClock.elapsedRealtime())
            } else {
                rawByKey.remove(definition.key)
            }
            onSample(sample(definition, raw, display ?: "Yanıt çözülemedi", started, success))
            if (success && definition.key in setOf("hv_voltage", "hv_current")) emitPowerIfReady(onSample, started)
            if (success && definition.key == "obd_soc") emitDisplaySoc(onSample, raw, started)
        } catch (e: Exception) {
            onSample(sample(definition, "", e.message ?: "OBD hatası", started, false))
            onState(e.message ?: "OBD okuma hatası")
            nextDue[definition.key] = SystemClock.elapsedRealtime() + ERROR_RETRY_DELAY_MS
            return false
        }
        val delayMs = if (querySucceeded) intervalMs(definition) else ERROR_RETRY_DELAY_MS
        nextDue[definition.key] = SystemClock.elapsedRealtime() + delayMs
        return true
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
            val focusedDependencies = when (focused) {
                "hv_power" -> setOf("hv_voltage", "hv_current")
                "soc_display" -> setOf("obd_soc")
                else -> setOf(focused)
            }
            return (discovered + ObdCatalog.confirmed).filter { it.key in focusedDependencies }
        }
        val selected = enabledKeys?.toMutableSet()?.apply {
            if ("hv_power" in this) addAll(listOf("hv_voltage", "hv_current"))
            if ("soc_display" in this) add("obd_soc")
        }
        return discovered + ObdCatalog.confirmed.filter {
            when {
                it.key == "odometer" -> !odometerFallback
                it.key == "odometer_11bit" -> odometerFallback
                else -> it.targetHz > 0f && (selected == null || it.key in selected)
            }
        }.filter { selected == null || it.key in selected || it.key in setOf("odometer", "odometer_11bit") && "odometer" in selected }
    }

    private fun intervalMs(def: ObdPidDefinition): Long {
        if (focusKey != null) return FOCUS_INTERVAL_MS
        val hz = def.targetHz
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
        val def = ObdPidDefinition("hv_power", "HV güç (türetilmiş)", "4803×4802", "kW", EcuContexts.BECM, 2f, com.kadireren.ex30sensorlab.model.ResearchStatus.CONFIRMED)
        onSample(sample(def, "4803 + 4802", String.format(Locale.US, "%.2f kW", power), started, true))
    }

    private fun emitDisplaySoc(onSample: (SensorSample) -> Unit, raw: String, started: Long) {
        val soc = ObdDecoders.derivedDisplaySoc(raw) ?: return
        val def = ObdPidDefinition("soc_display", "Gösterge batarya yüzdesi (OBD)", "4801 formülü", "%", EcuContexts.BECM, 1f, com.kadireren.ex30sensorlab.model.ResearchStatus.CONFIRMED)
        onSample(sample(def, raw, String.format(Locale.US, "%.3f %%", soc), started, true))
    }

    companion object {
        private const val ODOMETER_MAX_FAILURES = 3
        private const val MAX_DERIVED_SAMPLE_SKEW_MS = 1_000L
        private const val ERROR_RETRY_DELAY_MS = 1_000L
        private const val FOCUS_INTERVAL_MS = 33L
    }
}
