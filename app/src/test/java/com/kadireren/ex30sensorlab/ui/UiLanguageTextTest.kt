package com.kadireren.ex30sensorlab.ui

import com.kadireren.ex30sensorlab.model.SensorSource
import com.kadireren.ex30sensorlab.obd.ObdCatalog
import com.kadireren.ex30sensorlab.vhal.VhalCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UiLanguageTextTest {
    @Test
    fun driveVisibilityUsesOnlySupportedSources() {
        assertEquals(listOf(SensorSource.VHAL, SensorSource.OBD), UiLanguageText.visibleSensorSources)
        assertFalse(SensorSource.SCANNER in UiLanguageText.visibleSensorSources)
    }

    @Test
    fun sensorNamesSwitchToEnglishAndFallBackSafely() {
        assertEquals("HV pack voltage", UiLanguageText.sensorName("hv_voltage", "HV paket voltajı", UiLanguage.ENGLISH))
        assertEquals("Bilinmeyen", UiLanguageText.sensorName("unknown", "Bilinmeyen", UiLanguage.ENGLISH))
        assertEquals("HV paket voltajı", UiLanguageText.sensorName("hv_voltage", "HV paket voltajı", UiLanguage.TURKISH))
    }

    @Test
    fun everySelectableSensorHasAnEnglishName() {
        val selectableKeys = VhalCatalog.displayChoices.map { it.first } + ObdCatalog.displayChoices.map { it.first }
        assertEquals(emptyList<String>(), selectableKeys.filterNot(UiLanguageText::hasEnglishSensorName))
    }
}
