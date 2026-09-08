package com.kadireren.ex30sensorlab.discovery

import com.kadireren.ex30sensorlab.obd.ObdDecoders
import kotlin.math.abs

enum class CalibrationPhase { REST, PEDAL, REST_AGAIN }

data class CalibrationSample(
    val phase: CalibrationPhase,
    val queryKey: String,
    val numericValue: Float?,
    val rawResponse: String,
)

data class CalibrationCandidate(
    val record: DiscoveredQueryRecord,
    val score: Float,
    val restMedian: Float?,
    val pedalMedian: Float?,
    val suggestedDecode: DiscoveryDecodeType,
    val summary: String,
)

object CalibrationScorer {
    private val brakeDidPrefixes = setOf("FD00", "FD01", "FD02", "FD03")

    fun rankCandidates(
        pending: List<DiscoveredQueryRecord>,
        samples: List<CalibrationSample>,
    ): List<CalibrationCandidate> {
        val byKey = samples.groupBy { it.queryKey }
        return pending.mapNotNull { record ->
            val key = record.stableKey
            if (isLikelyBrake(record.did)) return@mapNotNull null
            val rows = byKey[key].orEmpty().filter { it.numericValue != null }
            if (rows.size < 4) return@mapNotNull null
            val restValues = rows.filter { it.phase == CalibrationPhase.REST || it.phase == CalibrationPhase.REST_AGAIN }
                .mapNotNull { it.numericValue }
            val pedalValues = rows.filter { it.phase == CalibrationPhase.PEDAL }.mapNotNull { it.numericValue }
            if (restValues.isEmpty() || pedalValues.isEmpty()) return@mapNotNull null
            val restMedian = median(restValues)
            val pedalMedian = median(pedalValues)
            val delta = pedalMedian - restMedian
            val noise = medianAbsoluteDeviation(restValues)
            val monotonicBonus = if (delta > noise * 2f) 2f else if (delta > 0f) 1f else 0f
            val score = abs(delta) / (noise.coerceAtLeast(0.5f)) + monotonicBonus
            val decode = guessDecode(restValues + pedalValues)
            CalibrationCandidate(
                record = record,
                score = score,
                restMedian = restMedian,
                pedalMedian = pedalMedian,
                suggestedDecode = decode,
                summary = "dinlenme≈${fmt(restMedian)} pedal≈${fmt(pedalMedian)} Δ=${fmt(delta)} skor=${"%.1f".format(score)}",
            )
        }.sortedByDescending { it.score }
    }

    fun numericFromRaw(did: String, raw: String, decodeType: DiscoveryDecodeType): Float? {
        val data = ObdDecoders.extractData(raw, did.take(4)) ?: ObdDecoders.cleanHex(raw).takeLast(8).takeIf { it.isNotEmpty() }
        if (data.isNullOrEmpty()) return null
        return try {
            when (decodeType) {
                DiscoveryDecodeType.U8 -> data.take(2).toInt(16).toFloat()
                DiscoveryDecodeType.U16, DiscoveryDecodeType.U16_DIV100 ->
                    data.take(4).toInt(16).let { if (decodeType == DiscoveryDecodeType.U16_DIV100) it / 100f else it.toFloat() }
                DiscoveryDecodeType.S16, DiscoveryDecodeType.S16_DIV10 -> {
                    val u = data.take(4).toInt(16)
                    val s = if (u and 0x8000 != 0) u - 0x10000 else u
                    if (decodeType == DiscoveryDecodeType.S16_DIV10) s / 10f else s.toFloat()
                }
                DiscoveryDecodeType.RAW_HEX -> data.take(4).toInt(16).toFloat()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun guessDecode(values: List<Float>): DiscoveryDecodeType {
        val max = values.maxOrNull() ?: return DiscoveryDecodeType.U16_DIV100
        return when {
            max <= 255f -> DiscoveryDecodeType.U8
            max <= 100f -> DiscoveryDecodeType.U16_DIV100
            max <= 6500f -> DiscoveryDecodeType.U16
            else -> DiscoveryDecodeType.S16
        }
    }

    private fun isLikelyBrake(did: String): Boolean {
        val head = did.take(4).uppercase()
        return brakeDidPrefixes.any { head.startsWith(it) } || did.uppercase().contains("FD00")
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
    }

    private fun medianAbsoluteDeviation(values: List<Float>): Float {
        if (values.isEmpty()) return 1f
        val med = median(values)
        return median(values.map { abs(it - med) }).coerceAtLeast(0.01f)
    }

    private fun fmt(value: Float): String = String.format("%.2f", value)
}
