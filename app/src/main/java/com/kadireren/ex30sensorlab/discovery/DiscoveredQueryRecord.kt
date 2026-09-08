package com.kadireren.ex30sensorlab.discovery

import com.kadireren.ex30sensorlab.model.EcuContext
import com.kadireren.ex30sensorlab.model.ScanQuery
import org.json.JSONObject

data class DiscoveredQueryRecord(
    val service: String,
    val did: String,
    val ecu: EcuContext?,
    val lastRawResponse: String,
    val discoveredAtMs: Long,
    val sourceSession: String = "",
) {
    val stableKey: String
        get() = "${ecu?.header ?: "MODE01"}:$service:$did"

    fun toJson(): JSONObject = JSONObject().apply {
        put("service", service)
        put("did", did)
        put("lastRawResponse", lastRawResponse.take(512))
        put("discoveredAtMs", discoveredAtMs)
        put("sourceSession", sourceSession)
        ecu?.let { put("ecu", ecuToJson(it)) }
    }

    companion object {
        fun fromScanQuery(query: ScanQuery, raw: String, sourceSession: String, nowMs: Long): DiscoveredQueryRecord =
            DiscoveredQueryRecord(
                service = query.service,
                did = query.did,
                ecu = query.ecu,
                lastRawResponse = raw,
                discoveredAtMs = nowMs,
                sourceSession = sourceSession,
            )

        fun fromJson(obj: JSONObject): DiscoveredQueryRecord {
            val ecuObj = obj.optJSONObject("ecu")
            val ecu = ecuObj?.let { jsonToEcu(it) }
            return DiscoveredQueryRecord(
                service = obj.getString("service"),
                did = obj.getString("did"),
                ecu = ecu,
                lastRawResponse = obj.optString("lastRawResponse", ""),
                discoveredAtMs = obj.optLong("discoveredAtMs"),
                sourceSession = obj.optString("sourceSession", ""),
            )
        }

        private fun ecuToJson(ecu: EcuContext): JSONObject = JSONObject().apply {
            put("name", ecu.name)
            put("protocol", ecu.protocol)
            put("header", ecu.header)
            put("priority", ecu.priority)
            put("rxFilter", ecu.rxFilter)
            put("flowControlHeader", ecu.flowControlHeader)
            put("flowControlData", ecu.flowControlData)
            put("flowControlMode", ecu.flowControlMode)
        }

        private fun jsonToEcu(obj: JSONObject): EcuContext = EcuContext(
            name = obj.getString("name"),
            protocol = obj.getInt("protocol"),
            header = obj.getString("header"),
            priority = obj.optString("priority", "1D"),
            rxFilter = obj.getString("rxFilter"),
            flowControlHeader = obj.getString("flowControlHeader"),
            flowControlData = obj.optString("flowControlData", "300000"),
            flowControlMode = obj.optInt("flowControlMode", 1),
        )
    }
}
