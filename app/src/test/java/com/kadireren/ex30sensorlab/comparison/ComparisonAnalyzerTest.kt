package com.kadireren.ex30sensorlab.comparison

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class ComparisonAnalyzerTest {
    @Test
    fun detectsDelayedMatchingSpeed() {
        val analyzer = ComparisonAnalyzer()
        for (time in 0L..20_000L step 100L) {
            val reference = 50.0 + 25.0 * sin(time / 1_500.0)
            analyzer.add(ComparisonSignal.VHAL_DISPLAY_SPEED, time, reference)
            if (time >= 500L && time % 200L == 0L) {
                val delayed = 50.0 + 25.0 * sin((time - 500L) / 1_500.0)
                analyzer.add(ComparisonSignal.OBD_VEHICLE_SPEED, time, delayed)
            }
        }

        val result = analyzer.metrics(ComparisonSignal.VHAL_DISPLAY_SPEED, ComparisonSignal.OBD_VEHICLE_SPEED)!!
        assertEquals(500L, result.lagMs)
        assertTrue(result.correlation > 0.99)
        assertTrue(result.mae < 0.01)
    }

    @Test
    fun reportsReversedPowerSignInRegression() {
        val analyzer = ComparisonAnalyzer()
        for (index in 0..100) {
            val time = index * 100L
            val obd = index / 2.0 - 25.0
            analyzer.add(ComparisonSignal.OBD_BECM_POWER, time, obd)
            analyzer.add(ComparisonSignal.VHAL_RAW_POWER, time, -obd + 2.0)
        }

        val result = analyzer.metrics(ComparisonSignal.VHAL_RAW_POWER, ComparisonSignal.OBD_BECM_POWER)!!
        assertTrue(result.correlation < -0.99)
        assertEquals(-1.0, result.slope, 0.001)
        assertEquals(2.0, result.intercept, 0.001)
    }
}
