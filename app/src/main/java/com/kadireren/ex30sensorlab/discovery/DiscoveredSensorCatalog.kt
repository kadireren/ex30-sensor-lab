package com.kadireren.ex30sensorlab.discovery

import com.kadireren.ex30sensorlab.model.ObdPidDefinition
import com.kadireren.ex30sensorlab.model.ResearchStatus

object DiscoveredSensorCatalog {
    fun confirmedDefinitions(state: DiscoveryStoreState = DiscoveredSensorStore.snapshot()): List<ObdPidDefinition> =
        state.confirmedSensors.map { config ->
            ObdPidDefinition(
                key = config.key,
                name = config.name,
                did = config.did,
                unit = config.unit,
                ecu = config.ecu,
                targetHz = config.targetHz,
                status = ResearchStatus.CONFIRMED,
            )
        }

    fun decode(config: DiscoveredSensorConfig, raw: String): String? =
        DiscoveryDecoders.format(config, raw)

    fun findConfig(key: String, state: DiscoveryStoreState = DiscoveredSensorStore.snapshot()): DiscoveredSensorConfig? =
        state.confirmedSensors.firstOrNull { it.key == key }
}

object DiscoveryDecoders {
    fun format(config: DiscoveredSensorConfig, raw: String): String? {
        val value = CalibrationScorer.numericFromRaw(config.did, raw, config.decodeType) ?: return null
        return when (config.unit) {
            "%" -> String.format("%.1f %%", value)
            "rpm" -> String.format("%.0f rpm", value)
            "Nm" -> String.format("%.1f Nm", value)
            else -> String.format("%.2f %s", value, config.unit)
        }
    }
}
