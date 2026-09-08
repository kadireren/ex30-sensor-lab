package com.kadireren.ex30sensorlab.scanner

import com.kadireren.ex30sensorlab.model.EcuContext
import com.kadireren.ex30sensorlab.model.ScanProfile
import com.kadireren.ex30sensorlab.model.ScanQuery
import com.kadireren.ex30sensorlab.obd.ElmCommandPolicy
import org.json.JSONObject

object ScanProfileParser {
    fun parse(text: String): ScanProfile {
        require(text.length <= ScanProfilePolicy.MAX_PROFILE_CHARS) { "Profil dosyası çok büyük" }
        val root = JSONObject(text)
        val version = root.getInt("profileVersion")
        require(version == 1) { "Desteklenmeyen profil sürümü: $version" }
        val array = root.getJSONArray("queries")
        require(array.length() <= ScanProfilePolicy.MAX_QUERY_COUNT) { "Profil en fazla ${ScanProfilePolicy.MAX_QUERY_COUNT} sorgu içerebilir" }
        val queries = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val service = item.getString("service").uppercase()
                val did = item.getString("did").uppercase()
                require(service == "01" || service == "22") { "Desteklenmeyen servis: $service" }
                require(ScanProfilePolicy.isValidQuery(service, did)) { "Geçersiz sorgu: $service$did" }
                ElmCommandPolicy.requireAllowed("$service$did")
                val ecuObject = item.optJSONObject("ecu")
                val ecu = ecuObject?.let {
                    require(it.getString("header").isNotBlank()) { "UDS profili için ECU header eksik" }
                    require(it.getString("rxFilter").isNotBlank()) { "UDS profili için RX filter eksik" }
                    require(it.getString("flowControlHeader").isNotBlank()) { "UDS profili için flow-control header eksik" }
                    val protocol = it.getInt("protocol")
                    val header = it.getString("header").uppercase()
                    val priority = it.optString("priority", "1D").uppercase()
                    val rxFilter = it.getString("rxFilter").uppercase()
                    val flowControlHeader = it.getString("flowControlHeader").uppercase()
                    val flowControlData = it.optString("flowControlData", "300000").uppercase()
                    val flowControlMode = it.optInt("flowControlMode", 1)
                    require(ScanProfilePolicy.isValidProtocol(protocol)) { "Geçersiz ELM protokolü: $protocol" }
                    require(ScanProfilePolicy.isValidHeader(header)) { "Geçersiz ECU header: $header" }
                    require(ScanProfilePolicy.isValidPriority(priority)) { "Geçersiz ECU priority: $priority" }
                    require(ScanProfilePolicy.isValidFilter(rxFilter)) { "Geçersiz RX filter: $rxFilter" }
                    require(ScanProfilePolicy.isValidFilter(flowControlHeader)) { "Geçersiz flow-control header: $flowControlHeader" }
                    require(ScanProfilePolicy.isValidFlowData(flowControlData)) { "Geçersiz flow-control verisi: $flowControlData" }
                    require(ScanProfilePolicy.isValidFlowMode(flowControlMode)) { "Geçersiz flow-control modu: $flowControlMode" }
                    EcuContext(
                        name = it.getString("name").take(ScanProfilePolicy.MAX_NAME_CHARS),
                        protocol = protocol,
                        header = header,
                        priority = priority,
                        rxFilter = rxFilter,
                        flowControlHeader = flowControlHeader,
                        flowControlData = flowControlData,
                        flowControlMode = flowControlMode,
                    )
                }
                require(service == "01" || ecu != null) { "UDS sorgusu için ECU bağlamı eksik" }
                add(
                    ScanQuery(
                        service = service,
                        did = did,
                        observedResponse = item.optString("observedResponse", "").take(ScanProfilePolicy.MAX_RESPONSE_CHARS),
                        observationCount = item.optInt("observationCount", 1).coerceIn(1, ScanProfilePolicy.MAX_OBSERVATION_COUNT),
                        ecu = ecu,
                    )
                )
            }
        }
        return ScanProfile(version, root.optString("sourceSession", "bilinmiyor").take(ScanProfilePolicy.MAX_NAME_CHARS), queries)
    }
}

internal object ScanProfilePolicy {
    private val MODE01_DID = Regex("^[0-9A-F]{2}$")
    private val UDS_DID = Regex("^(?:[0-9A-F]{4})+$")
    private val HEADER = Regex("^(?:[0-9A-F]{3}|[0-9A-F]{6})$")
    private val PRIORITY = Regex("^[0-9A-F]{0,2}$")
    private val FILTER = Regex("^[0-9A-F]{8}$")
    private val FLOW_DATA = Regex("^[0-9A-F]{6}$")
    const val MAX_PROFILE_CHARS = 1_000_000
    const val MAX_QUERY_COUNT = 500
    private const val MAX_UDS_DID_CHARS = 64
    const val MAX_RESPONSE_CHARS = 512
    const val MAX_NAME_CHARS = 200
    const val MAX_OBSERVATION_COUNT = 1_000_000

    fun isValidQuery(service: String, did: String): Boolean =
        (service == "01" && MODE01_DID.matches(did)) ||
            (service == "22" && UDS_DID.matches(did) && did.length <= MAX_UDS_DID_CHARS)

    fun isValidProtocol(protocol: Int): Boolean = protocol in 0..9
    fun isValidHeader(value: String): Boolean = HEADER.matches(value)
    fun isValidPriority(value: String): Boolean = PRIORITY.matches(value)
    fun isValidFilter(value: String): Boolean = FILTER.matches(value)
    fun isValidFlowData(value: String): Boolean = FLOW_DATA.matches(value)
    fun isValidFlowMode(value: Int): Boolean = value in 0..2
}
