package com.kadireren.ex30sensorlab.scanner

import com.kadireren.ex30sensorlab.model.EcuContext
import com.kadireren.ex30sensorlab.model.ScanProfile
import com.kadireren.ex30sensorlab.model.ScanQuery
import com.kadireren.ex30sensorlab.obd.ElmCommandPolicy
import org.json.JSONObject

object ScanProfileParser {
    fun parse(text: String): ScanProfile {
        val root = JSONObject(text)
        val version = root.getInt("profileVersion")
        require(version == 1) { "Desteklenmeyen profil sürümü: $version" }
        val array = root.getJSONArray("queries")
        val queries = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val service = item.getString("service").uppercase()
                val did = item.getString("did").uppercase()
                ElmCommandPolicy.requireAllowed("$service$did")
                val ecuObject = item.optJSONObject("ecu")
                val ecu = ecuObject?.let {
                    require(it.getString("header").isNotBlank()) { "UDS profili için ECU header eksik" }
                    require(it.getString("rxFilter").isNotBlank()) { "UDS profili için RX filter eksik" }
                    require(it.getString("flowControlHeader").isNotBlank()) { "UDS profili için flow-control header eksik" }
                    EcuContext(
                        name = it.getString("name"),
                        protocol = it.getInt("protocol"),
                        header = it.getString("header"),
                        priority = it.optString("priority", "1D"),
                        rxFilter = it.getString("rxFilter"),
                        flowControlHeader = it.getString("flowControlHeader"),
                        flowControlData = it.optString("flowControlData", "300000"),
                        flowControlMode = it.optInt("flowControlMode", 1),
                    )
                }
                require(service == "01" || ecu != null) { "UDS sorgusu için ECU bağlamı eksik" }
                add(
                    ScanQuery(
                        service = service,
                        did = did,
                        observedResponse = item.optString("observedResponse", ""),
                        observationCount = item.optInt("observationCount", 1),
                        ecu = ecu,
                    )
                )
            }
        }
        return ScanProfile(version, root.optString("sourceSession", "bilinmiyor"), queries)
    }
}
