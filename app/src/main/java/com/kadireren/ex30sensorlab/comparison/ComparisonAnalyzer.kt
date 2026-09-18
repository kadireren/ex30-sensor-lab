package com.kadireren.ex30sensorlab.comparison

import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

enum class ComparisonSignal(val labelTr: String, val labelEn: String, val unit: String) {
    VHAL_DISPLAY_SPEED("VHAL gösterge hızı", "VHAL display speed", "km/h"),
    OBD_VEHICLE_SPEED("OBD araç hızı F40D", "OBD vehicle speed F40D", "km/h"),
    OBD_WHEEL_FL("OBD ön sol teker 2B06", "OBD front-left wheel 2B06", "km/h"),
    OBD_WHEEL_FR("OBD ön sağ teker 2B07", "OBD front-right wheel 2B07", "km/h"),
    OBD_WHEEL_RL("OBD arka sol teker 2B08", "OBD rear-left wheel 2B08", "km/h"),
    OBD_WHEEL_RR("OBD arka sağ teker 2B09", "OBD rear-right wheel 2B09", "km/h"),
    VHAL_RAW_POWER("VHAL ham batarya gücü", "VHAL raw battery power", "kW"),
    OBD_BECM_POWER("OBD BECM 4803×4802", "OBD BECM 4803×4802", "kW"),
    OBD_IEM_POWER("OBD 4803×IEM E301", "OBD 4803×IEM E301", "kW"),
    OBD_MECHANICAL_POWER("OBD ERAD RPM×tork", "OBD ERAD RPM×torque", "kW"),
}

data class ComparisonPoint(val timestampMs: Long, val value: Double)

data class ComparisonMetrics(
    val pairs: Int,
    val lagMs: Long,
    val bias: Double,
    val mae: Double,
    val rmse: Double,
    val maxAbsError: Double,
    val correlation: Double,
    val slope: Double,
    val intercept: Double,
    val referenceStdDev: Double,
)

class ComparisonAnalyzer {
    private val samples = ComparisonSignal.entries.associateWith { mutableListOf<ComparisonPoint>() }

    fun add(signal: ComparisonSignal, timestampMs: Long, value: Double) {
        if (value.isFinite()) samples.getValue(signal).add(ComparisonPoint(timestampMs, value))
    }

    fun latest(signal: ComparisonSignal): ComparisonPoint? = samples.getValue(signal).lastOrNull()

    fun points(signal: ComparisonSignal): List<ComparisonPoint> = samples.getValue(signal).toList()

    fun metrics(reference: ComparisonSignal, candidate: ComparisonSignal): ComparisonMetrics? {
        val referencePoints = samples.getValue(reference)
        val candidatePoints = samples.getValue(candidate)
        if (referencePoints.size < MIN_PAIRS || candidatePoints.size < MIN_PAIRS) return null
        val lagCandidates = (-MAX_LAG_MS..MAX_LAG_MS step LAG_STEP_MS).map { it.toLong() }
        val bestLag = lagCandidates.maxByOrNull { lag ->
            paired(referencePoints, candidatePoints, lag)
                .takeIf { it.size >= MIN_PAIRS }
                ?.let(::correlation)
                ?.let(::abs) ?: -1.0
        } ?: 0L
        val pairs = paired(referencePoints, candidatePoints, bestLag)
        if (pairs.size < MIN_PAIRS) return null
        val errors = pairs.map { (ref, candidateValue) -> candidateValue - ref }
        val refValues = pairs.map { it.first }
        val candidateValues = pairs.map { it.second }
        val regression = regression(candidateValues, refValues)
        return ComparisonMetrics(
            pairs = pairs.size,
            lagMs = bestLag,
            bias = errors.average(),
            mae = errors.sumOf(::abs) / errors.size,
            rmse = sqrt(errors.sumOf { it * it } / errors.size),
            maxAbsError = errors.maxOf(::abs),
            correlation = correlation(pairs),
            slope = regression.first,
            intercept = regression.second,
            referenceStdDev = standardDeviation(refValues),
        )
    }

    fun report(durationMs: Long, english: Boolean): String {
        val speedCandidates = listOf(
            ComparisonSignal.OBD_VEHICLE_SPEED,
            ComparisonSignal.OBD_WHEEL_FL,
            ComparisonSignal.OBD_WHEEL_FR,
            ComparisonSignal.OBD_WHEEL_RL,
            ComparisonSignal.OBD_WHEEL_RR,
        )
        val powerCandidates = listOf(
            ComparisonSignal.OBD_BECM_POWER,
            ComparisonSignal.OBD_IEM_POWER,
            ComparisonSignal.OBD_MECHANICAL_POWER,
        )
        return buildString {
            appendLine(if (english) "EX30 VHAL–OBD comparison report" else "EX30 VHAL–OBD karşılaştırma raporu")
            appendLine("=".repeat(48))
            appendLine(if (english) "Test duration: ${durationMs / 1000} s" else "Test süresi: ${durationMs / 1000} sn")
            appendLine()
            appendLine(if (english) "SAMPLE SUMMARY" else "ÖRNEK ÖZETİ")
            ComparisonSignal.entries.forEach { signal ->
                val points = samples.getValue(signal)
                val hz = actualHz(points)
                val range = if (points.isEmpty()) "—" else "${f(points.minOf { it.value })}…${f(points.maxOf { it.value })} ${signal.unit}"
                appendLine("• ${label(signal, english)}: ${points.size} ${if (english) "samples" else "örnek"}, ${f(hz)} Hz, $range")
            }
            appendLine()
            appendLine(if (english) "SPEED — reference: VHAL display speed" else "HIZ — referans: VHAL gösterge hızı")
            val speedResults = speedCandidates.map { it to metrics(ComparisonSignal.VHAL_DISPLAY_SPEED, it) }
            speedResults.forEach { (signal, result) -> appendMetrics(signal, result, english) }
            appendLine(bestMatch(speedResults, english, "speed"))
            appendLine()
            appendLine(if (english) "POWER — reference: raw VHAL battery power" else "GÜÇ — referans: ham VHAL batarya gücü")
            appendLine(if (english) "The regression is VHAL ≈ slope × OBD + offset; a negative slope means reversed sign." else "Regresyon VHAL ≈ katsayı × OBD + ofset biçimindedir; negatif katsayı işaretin ters olduğunu gösterir.")
            val powerResults = powerCandidates.map { it to metrics(ComparisonSignal.VHAL_RAW_POWER, it) }
            powerResults.forEach { (signal, result) -> appendMetrics(signal, result, english) }
            appendLine(bestMatch(powerResults, english, "power"))
            appendLine()
            appendLine(if (english) "Interpretation limits" else "Yorum sınırları")
            appendLine(if (english) "• A useful result requires acceleration, steady speed and regeneration/deceleration; a stationary or nearly constant trace cannot identify correspondence." else "• Anlamlı sonuç için hızlanma, sabit hız ve rejenerasyon/yavaşlama gerekir; duran veya hemen hemen sabit kayıt eşleşmeyi belirleyemez.")
            appendLine(if (english) "• OBD values are sequential BLE/ELM queries, so reported lag includes polling order and transport delay." else "• OBD değerleri sıralı BLE/ELM sorgularıdır; raporlanan gecikmeye polling sırası ve aktarım gecikmesi dahildir.")
            appendLine(if (english) "• Correlation alone does not prove two signals have the same physical meaning; slope, offset and absolute error must also agree." else "• Tek başına korelasyon aynı fiziksel anlamı kanıtlamaz; katsayı, ofset ve mutlak hata da uyuşmalıdır.")
        }
    }

    private fun StringBuilder.appendMetrics(signal: ComparisonSignal, result: ComparisonMetrics?, english: Boolean) {
        appendLine("\n${label(signal, english)}")
        if (result == null) {
            appendLine(if (english) "  Insufficient changing/paired samples." else "  Yeterli değişken/eşleşmiş örnek yok.")
            return
        }
        appendLine("  ${if (english) "paired" else "eşleşen"}: ${result.pairs} · ${if (english) "estimated lag" else "tahmini gecikme"}: ${result.lagMs} ms")
        appendLine("  bias (OBD−VHAL): ${f(result.bias)} ${signal.unit} · MAE: ${f(result.mae)} · RMSE: ${f(result.rmse)} · max |fark|: ${f(result.maxAbsError)}")
        appendLine("  korelasyon: ${f(result.correlation)} · VHAL ≈ ${f(result.slope)} × OBD ${signed(result.intercept)}")
        appendLine("  ${verdict(result, english)}")
    }

    private fun bestMatch(results: List<Pair<ComparisonSignal, ComparisonMetrics?>>, english: Boolean, kind: String): String {
        val viable = results.mapNotNull { (signal, metrics) -> metrics?.let { signal to it } }
            .filter { (_, metrics) -> metrics.referenceStdDev >= if (kind == "speed") 2.0 else 3.0 }
        val best = viable.maxByOrNull { (_, metrics) -> abs(metrics.correlation) - metrics.rmse / 1000.0 }
            ?: return if (english) "Result: insufficient variation for a reliable match." else "Sonuç: güvenilir eşleşme için yeterli değişim yok."
        return if (english) "Best statistical candidate: ${label(best.first, true)} (must be confirmed with slope/error)."
        else "En güçlü istatistiksel aday: ${label(best.first, false)} (katsayı/hata ile birlikte doğrulanmalı)."
    }

    private fun verdict(result: ComparisonMetrics, english: Boolean): String = when {
        result.referenceStdDev < 2.0 -> if (english) "Insufficient signal variation." else "Sinyal değişimi yetersiz."
        abs(result.correlation) >= 0.95 && abs(abs(result.slope) - 1.0) <= 0.15 ->
            if (english) "Strong correspondence candidate." else "Güçlü karşılık adayı."
        abs(result.correlation) >= 0.80 -> if (english) "Related, but scale/offset must be checked." else "İlişkili görünüyor; ölçek/ofset kontrol edilmeli."
        else -> if (english) "No reliable correspondence in this run." else "Bu kayıtta güvenilir karşılık görünmüyor."
    }

    private fun paired(reference: List<ComparisonPoint>, candidate: List<ComparisonPoint>, lagMs: Long): List<Pair<Double, Double>> =
        candidate.mapNotNull { point ->
            nearest(reference, point.timestampMs - lagMs)?.takeIf { abs(it.timestampMs - (point.timestampMs - lagMs)) <= MAX_PAIR_GAP_MS }
                ?.let { it.value to point.value }
        }

    private fun nearest(points: List<ComparisonPoint>, timestampMs: Long): ComparisonPoint? {
        if (points.isEmpty()) return null
        val index = points.binarySearchBy(timestampMs) { it.timestampMs }
        if (index >= 0) return points[index]
        val insertion = -index - 1
        return listOfNotNull(points.getOrNull(insertion - 1), points.getOrNull(insertion))
            .minByOrNull { abs(it.timestampMs - timestampMs) }
    }

    private fun correlation(pairs: List<Pair<Double, Double>>): Double {
        if (pairs.size < 2) return 0.0
        val xs = pairs.map { it.first }
        val ys = pairs.map { it.second }
        val xm = xs.average()
        val ym = ys.average()
        val numerator = xs.indices.sumOf { (xs[it] - xm) * (ys[it] - ym) }
        val denominator = sqrt(xs.sumOf { (it - xm) * (it - xm) } * ys.sumOf { (it - ym) * (it - ym) })
        return if (denominator <= 1e-9) 0.0 else numerator / denominator
    }

    private fun regression(x: List<Double>, y: List<Double>): Pair<Double, Double> {
        val xm = x.average()
        val ym = y.average()
        val variance = x.sumOf { (it - xm) * (it - xm) }
        if (variance <= 1e-9) return 0.0 to ym
        val slope = x.indices.sumOf { (x[it] - xm) * (y[it] - ym) } / variance
        return slope to (ym - slope * xm)
    }

    private fun standardDeviation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private fun actualHz(points: List<ComparisonPoint>): Double {
        if (points.size < 2) return 0.0
        return (points.size - 1) * 1000.0 / (points.last().timestampMs - points.first().timestampMs).coerceAtLeast(1L)
    }

    private fun label(signal: ComparisonSignal, english: Boolean) = if (english) signal.labelEn else signal.labelTr
    private fun f(value: Double) = String.format(Locale.US, "%.3f", value)
    private fun signed(value: Double) = if (value < 0) "− ${f(abs(value))}" else "+ ${f(value)}"

    companion object {
        private const val MIN_PAIRS = 8
        private const val MAX_LAG_MS = 2_000
        private const val LAG_STEP_MS = 100
        private const val MAX_PAIR_GAP_MS = 180L
    }
}
