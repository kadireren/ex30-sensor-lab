package com.kadireren.ex30sensorlab.discovery

import com.kadireren.ex30sensorlab.model.EcuContext
import org.json.JSONObject

enum class DiscoveryDecodeType(val id: String) {
    RAW_HEX("raw_hex"),
    U8("u8"),
    U16("u16"),
    U16_DIV100("u16_div100"),
    S16("s16"),
    S16_DIV10("s16_div10"),
    ;

    companion object {
        fun fromId(id: String): DiscoveryDecodeType =
            entries.firstOrNull { it.id == id } ?: RAW_HEX
    }
}

data class DiscoveredSensorConfig(
    val target: DiscoveryTarget,
    val key: String,
    val name: String,
    val service: String,
    val did: String,
    val ecu: EcuContext?,
    val decodeType: DiscoveryDecodeType,
    val unit: String,
    val targetHz: Float,
    val confirmedAtMs: Long,
    val notes: String = "",
)

internal object DiscoveryJson {
    fun ecuToJson(ecu: EcuContext): JSONObject = JSONObject().apply {
        put("name", ecu.name)
        put("protocol", ecu.protocol)
        put("header", ecu.header)
        put("priority", ecu.priority)
        put("rxFilter", ecu.rxFilter)
        put("flowControlHeader", ecu.flowControlHeader)
        put("flowControlData", ecu.flowControlData)
        put("flowControlMode", ecu.flowControlMode)
    }

    fun ecuFromJson(obj: JSONObject?): EcuContext? {
        if (obj == null) return null
        return EcuContext(
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

    fun queryToJson(record: DiscoveredQueryRecord): JSONObject = JSONObject().apply {
        put("service", record.service)
        put("did", record.did)
        put("lastRawResponse", record.lastRawResponse.take(512))
        put("discoveredAtMs", record.discoveredAtMs)
        put("sourceSession", record.sourceSession)
        record.ecu?.let { put("ecu", ecuToJson(it)) }
    }

    fun queryFromJson(obj: JSONObject): DiscoveredQueryRecord = DiscoveredQueryRecord(
        service = obj.getString("service"),
        did = obj.getString("did"),
        ecu = ecuFromJson(obj.optJSONObject("ecu")),
        lastRawResponse = obj.optString("lastRawResponse", ""),
        discoveredAtMs = obj.optLong("discoveredAtMs"),
        sourceSession = obj.optString("sourceSession", ""),
    )

    fun sensorToJson(config: DiscoveredSensorConfig): JSONObject = JSONObject().apply {
        put("target", config.target.id)
        put("key", config.key)
        put("name", config.name)
        put("service", config.service)
        put("did", config.did)
        put("decodeType", config.decodeType.id)
        put("unit", config.unit)
        put("targetHz", config.targetHz.toDouble())
        put("confirmedAtMs", config.confirmedAtMs)
        put("notes", config.notes)
        config.ecu?.let { put("ecu", ecuToJson(it)) }
    }

    fun sensorFromJson(obj: JSONObject): DiscoveredSensorConfig = DiscoveredSensorConfig(
        target = DiscoveryTarget.fromId(obj.getString("target")) ?: DiscoveryTarget.THROTTLE,
        key = obj.getString("key"),
        name = obj.getString("name"),
        service = obj.getString("service"),
        did = obj.getString("did"),
        ecu = ecuFromJson(obj.optJSONObject("ecu")),
        decodeType = DiscoveryDecodeType.fromId(obj.optString("decodeType", DiscoveryDecodeType.RAW_HEX.id)),
        unit = obj.getString("unit"),
        targetHz = obj.optDouble("targetHz", 2.0).toFloat(),
        confirmedAtMs = obj.optLong("confirmedAtMs"),
        notes = obj.optString("notes", ""),
    )
}
