package com.kadireren.ex30sensorlab.scanner

import android.os.SystemClock
import com.kadireren.ex30sensorlab.model.EcuContext
import com.kadireren.ex30sensorlab.model.ScanEvent
import com.kadireren.ex30sensorlab.model.ScanProfile
import com.kadireren.ex30sensorlab.obd.EcuContexts
import com.kadireren.ex30sensorlab.obd.ElmProtocol
import com.kadireren.ex30sensorlab.obd.ObdCatalog
import com.kadireren.ex30sensorlab.obd.ObdDecoders
import com.kadireren.ex30sensorlab.vhal.SafetyState
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScannerController(
    private val protocol: ElmProtocol,
    private val safetyState: () -> SafetyState,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val lastPositiveResponse = mutableMapOf<String, String>()

    fun stop() {
        running.set(false)
    }

    fun close() {
        stop()
        executor.shutdownNow()
    }

    fun watchCandidates(onEvent: (ScanEvent) -> Unit, onState: (String) -> Unit) = launch(onState) {
        while (running.get()) {
            for ((index, candidate) in ObdCatalog.candidates.withIndex()) {
                if (!running.get()) break
                requireSafe()
                onState("Aday izleme ${index + 1}/${ObdCatalog.candidates.size}")
                query(candidate.ecu!!, "22${candidate.did}", candidate.did, onEvent)
                SystemClock.sleep(333L)
            }
        }
    }

    fun scanKnownEcuCandidates(onEvent: (ScanEvent) -> Unit, onState: (String) -> Unit) = launch(onState) {
        val candidates = listOf(0x1640, 0x1645, 0x1660, 0x1665, 0x1670, 0x1680, 0x16E0, 0x16F0, 0x1700, 0x1710, 0x1720, 0x1780, 0x1790, 0x17A0)
        for ((index, header) in candidates.withIndex()) {
            if (!running.get()) break
            requireSafe()
            onState("ECU taraması ${index + 1}/${candidates.size}")
            query(EcuContexts.deriveProbe(header), "22F190", "F190", onEvent)
            SystemClock.sleep(333L)
        }
    }

    fun scanDidPage(ecu: EcuContext, start: Int, endInclusive: Int, onEvent: (ScanEvent) -> Unit, onState: (String) -> Unit) {
        require(start in 0..0xFFFF && endInclusive in start..0xFFFF && endInclusive - start < 256) { "Aralık en fazla 256 DID olabilir" }
        launch(onState) {
            val total = endInclusive - start + 1
            for ((index, did) in (start..endInclusive).withIndex()) {
                if (!running.get()) break
                requireSafe()
                onState("DID taraması ${index + 1}/$total")
                val hex = "%04X".format(did)
                query(ecu, "22$hex", hex, onEvent)
                SystemClock.sleep(333L)
            }
        }
    }

    fun replayProfile(profile: ScanProfile, onEvent: (ScanEvent) -> Unit, onState: (String) -> Unit) = launch(onState) {
        for ((index, query) in profile.queries.withIndex()) {
            if (!running.get()) break
            requireSafe()
            onState("Profil oynatma ${index + 1}/${profile.queries.size}")
            query(query.ecu, "${query.service}${query.did}", query.did, onEvent)
            SystemClock.sleep(333L)
        }
    }

    private fun launch(onState: (String) -> Unit, block: () -> Unit) {
        if (!running.compareAndSet(false, true)) {
            onState("Başka bir tarama çalışıyor")
            return
        }
        executor.execute {
            try {
                requireSafe()
                onState("Salt-okunur tarama başladı")
                block()
                onState(if (running.get()) "Tarama tamamlandı" else "Tarama durduruldu")
            } catch (e: Exception) {
                onState("Tarama durdu: ${e.message}")
            } finally {
                running.set(false)
            }
        }
    }

    private fun requireSafe() {
        val state = safetyState()
        check(state.scannerAllowed) { state.denialReason() }
    }

    private fun query(ecu: EcuContext?, command: String, did: String, onEvent: (ScanEvent) -> Unit) {
        val raw = protocol.query(ecu, command)
        val positive = ObdDecoders.isPositiveResponse(raw, did)
        val key = "${ecu?.header ?: "MODE01"}:$did"
        val previous = lastPositiveResponse[key]
        val confirmed = ObdCatalog.confirmed.any { it.ecu?.header == ecu?.header && it.did == did }
        val nrc = ObdDecoders.negativeResponseCode(raw)
        val classification = when {
            positive && confirmed -> "doğrulandı"
            positive && previous != null && previous != raw -> "değişiyor"
            positive -> "yanıt veriyor"
            nrc != null -> "NRC $nrc"
            else -> "aday"
        }
        if (positive) lastPositiveResponse[key] = raw
        onEvent(ScanEvent(SystemClock.elapsedRealtime(), ecu?.name ?: "Standart OBD", command, raw, positive, classification))
    }
}
