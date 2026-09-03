package com.kadireren.ex30sensorlab.model

enum class SensorSource { VHAL, OBD, SCANNER }

enum class SampleStatus { WAITING, LIVE, STALE, UNSUPPORTED, PERMISSION_DENIED, ERROR }

data class SensorDefinition(
    val key: String,
    val name: String,
    val source: SensorSource,
    val identifier: String,
    val unit: String,
    val targetHz: Float,
)

data class SensorSample(
    val definition: SensorDefinition,
    val rawValue: String,
    val displayValue: String,
    val monotonicTimestampMs: Long,
    val latencyMs: Long = 0L,
    val actualHz: Float = 0f,
    val status: SampleStatus = SampleStatus.LIVE,
    val detail: String = "",
)

data class EcuContext(
    val name: String,
    val protocol: Int,
    val header: String,
    val priority: String,
    val rxFilter: String,
    val flowControlHeader: String,
    val flowControlData: String = "300000",
    val flowControlMode: Int = 1,
)

enum class ResearchStatus { CONFIRMED, CANDIDATE, RESPONDS, CHANGES }

data class ObdPidDefinition(
    val key: String,
    val name: String,
    val did: String,
    val unit: String,
    val ecu: EcuContext?,
    val targetHz: Float,
    val status: ResearchStatus,
)

data class ScanQuery(
    val service: String,
    val did: String,
    val observedResponse: String,
    val observationCount: Int,
    val ecu: EcuContext?,
)

data class ScanProfile(
    val profileVersion: Int,
    val sourceSession: String,
    val queries: List<ScanQuery>,
)

data class ScanEvent(
    val timestampMs: Long,
    val ecu: String,
    val command: String,
    val rawResponse: String,
    val success: Boolean,
    val classification: String,
)
