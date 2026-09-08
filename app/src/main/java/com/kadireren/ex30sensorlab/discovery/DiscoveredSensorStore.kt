package com.kadireren.ex30sensorlab.discovery

import org.json.JSONArray
import org.json.JSONObject

data class DiscoveryStoreState(
    val pendingQueries: List<DiscoveredQueryRecord> = emptyList(),
    val confirmedSensors: List<DiscoveredSensorConfig> = emptyList(),
    val lastProfileSession: String = "",
    val updatedAtMs: Long = 0L,
)

object DiscoveredSensorStore {
    const val FILE_NAME = "discovered_sensors.json"
    private const val VERSION = 1

    @Volatile
    private var cached: DiscoveryStoreState = DiscoveryStoreState()

    fun snapshot(): DiscoveryStoreState = cached

    fun load(text: String): DiscoveryStoreState {
        if (text.isBlank()) {
            cached = DiscoveryStoreState()
            return cached
        }
        val root = JSONObject(text)
        require(root.getInt("version") == VERSION) { "Desteklenmeyen keşif dosyası sürümü" }
        val pending = buildList {
            val array = root.optJSONArray("pendingQueries") ?: JSONArray()
            for (i in 0 until array.length()) add(DiscoveredQueryRecord.fromJson(array.getJSONObject(i)))
        }
        val confirmed = buildList {
            val array = root.optJSONArray("confirmedSensors") ?: JSONArray()
            for (i in 0 until array.length()) add(DiscoveryJson.sensorFromJson(array.getJSONObject(i)))
        }
        cached = DiscoveryStoreState(
            pendingQueries = pending,
            confirmedSensors = confirmed,
            lastProfileSession = root.optString("lastProfileSession", ""),
            updatedAtMs = root.optLong("updatedAtMs"),
        )
        return cached
    }

    fun toJson(state: DiscoveryStoreState = cached): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("updatedAtMs", state.updatedAtMs)
        root.put("lastProfileSession", state.lastProfileSession)
        root.put(
            "pendingQueries",
            JSONArray().apply { state.pendingQueries.forEach { put(it.toJson()) } },
        )
        root.put(
            "confirmedSensors",
            JSONArray().apply { state.confirmedSensors.forEach { put(DiscoveryJson.sensorToJson(it)) } },
        )
        return root.toString(2) + "\n"
    }

    fun mergeReplayDiscoveries(
        records: List<DiscoveredQueryRecord>,
        profileSession: String,
        nowMs: Long,
    ): DiscoveryStoreState {
        val merged = linkedMapOf<String, DiscoveredQueryRecord>()
        cached.pendingQueries.forEach { merged[it.stableKey] = it }
        records.forEach { merged[it.stableKey] = it }
        cached = cached.copy(
            pendingQueries = merged.values.sortedBy { it.stableKey },
            lastProfileSession = profileSession,
            updatedAtMs = nowMs,
        )
        return cached
    }

    fun confirmSensor(config: DiscoveredSensorConfig, nowMs: Long): DiscoveryStoreState {
        val withoutTarget = cached.confirmedSensors.filter { it.target != config.target }
        val withoutKey = withoutTarget.filter { it.key != config.key }
        cached = cached.copy(
            confirmedSensors = (withoutKey + config).sortedBy { it.target.ordinal },
            pendingQueries = cached.pendingQueries.filter { it.stableKey != "${config.ecu?.header ?: "MODE01"}:${config.service}:${config.did}" },
            updatedAtMs = nowMs,
        )
        return cached
    }

    fun removeConfirmed(target: DiscoveryTarget, nowMs: Long): DiscoveryStoreState {
        cached = cached.copy(
            confirmedSensors = cached.confirmedSensors.filter { it.target != target },
            updatedAtMs = nowMs,
        )
        return cached
    }

    fun reportText(state: DiscoveryStoreState = cached): String = buildString {
        appendLine("EX30 Sensor Lab — keşif raporu")
        appendLine("Güncelleme: ${state.updatedAtMs}")
        appendLine("Son HCI oturumu: ${state.lastProfileSession.ifBlank { "—" }}")
        appendLine()
        appendLine("Onaylı motor sensörleri (${state.confirmedSensors.size}):")
        if (state.confirmedSensors.isEmpty()) appendLine("  (henüz yok)")
        state.confirmedSensors.forEach { sensor ->
            appendLine("  • ${sensor.target.labelTr}: ${sensor.name}")
            appendLine("    ${sensor.service}${sensor.did} @ ${sensor.ecu?.name ?: "ELM"} · ${sensor.decodeType.id} · ${sensor.unit}")
        }
        appendLine()
        appendLine("Bekleyen pozitif sorgular (${state.pendingQueries.size}):")
        state.pendingQueries.take(40).forEach { q ->
            appendLine("  • ${q.service}${q.did} @ ${q.ecu?.name ?: "MODE01"}")
        }
        if (state.pendingQueries.size > 40) appendLine("  … +${state.pendingQueries.size - 40} daha")
    }
}
