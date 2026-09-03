package com.kadireren.ex30sensorlab.obd

import android.os.SystemClock
import com.kadireren.ex30sensorlab.model.ObdPidDefinition
import com.kadireren.ex30sensorlab.model.SampleStatus
import com.kadireren.ex30sensorlab.model.SensorDefinition
import com.kadireren.ex30sensorlab.model.SensorSample
import com.kadireren.ex30sensorlab.model.SensorSource
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ObdPollingController(private val protocol: ElmProtocol) : PollingScheduler {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val nextDue = mutableMapOf<String, Long>()
    private val timestamps = mutableMapOf<String, ArrayDeque<Long>>()
    private val rawByKey = mutableMapOf<String, String>()
    @Volatile private var focusKey: String? = null
    private var brakeFailures = 0
    private var brakeFallback = false

    override fun start(onSample: (SensorSample) -> Unit, onState: (String) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            onState("OBD okuma başladı")
            while (running.get()) {
                val now = SystemClock.elapsedRealtime()
                val definitions = selectedDefinitions()
                val due = definitions
                    .filter { now >= (nextDue[it.key] ?: 0L) }
                    .sortedWith(compareByDescending<ObdPidDefinition> { it.targetHz }.thenBy { it.ecu?.name ?: "" })
                    .firstOrNull()
                if (due == null) {
                    SystemClock.sleep(10L)
                    continue
                }
                val started = SystemClock.elapsedRealtime()
                try {
                    val command = if (due.did.startsWith("AT")) due.did else "22${due.did}"
                    val raw = protocol.query(due.ecu, command)
                    val display = ObdDecoders.decode(due.key, due.did, raw)
                    val success = display != null
                    if (due.key == "brake_multi") {
                        brakeFailures = if (success) 0 else brakeFailures + 1
                        if (brakeFailures >= 5) brakeFallback = true
                    }
                    rawByKey[due.key] = raw
                    onSample(sample(due, raw, display ?: "Yanıt çözülemedi", started, success))
                    emitPowerIfReady(onSample, started)
                } catch (e: Exception) {
                    if (due.key == "brake_multi" && ++brakeFailures >= 5) brakeFallback = true
                    onSample(sample(due, "", e.message ?: "OBD hatası", started, false))
                    onState(e.message ?: "OBD okuma hatası")
                }
                nextDue[due.key] = started + intervalMs(due)
            }
            onState("OBD okuma durdu")
        }
    }

    override fun focus(sensorKey: String?) {
        focusKey = sensorKey
        nextDue.clear()
    }

    override fun stop() {
        running.set(false)
        focusKey = null
        nextDue.clear()
        timestamps.clear()
    }

    fun close() {
        stop()
        executor.shutdownNow()
    }

    private fun selectedDefinitions(): List<ObdPidDefinition> {
        val focused = focusKey
        if (focused != null) return ObdCatalog.confirmed.filter { it.key == focused }
        return ObdCatalog.confirmed.filter {
            when {
                it.key == "brake_multi" -> !brakeFallback
                it.key.startsWith("brake_") -> brakeFallback && it.key != "brake_multi"
                else -> it.targetHz > 0f
            }
        }
    }

    private fun intervalMs(def: ObdPidDefinition): Long {
        if (focusKey != null) return 25L
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
        val power = ObdDecoders.derivedPowerKw(voltage, current) ?: return
        val def = ObdPidDefinition("hv_power", "HV güç (türetilmiş)", "4801×4802", "kW", EcuContexts.BECM, 2f, com.kadireren.ex30sensorlab.model.ResearchStatus.CONFIRMED)
        onSample(sample(def, "4801 + 4802", String.format(Locale.US, "%.2f kW", power), started, true))
    }
}
