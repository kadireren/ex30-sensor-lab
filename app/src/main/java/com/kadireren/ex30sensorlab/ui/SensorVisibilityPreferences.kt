package com.kadireren.ex30sensorlab.ui

import android.content.Context
import com.kadireren.ex30sensorlab.model.SensorSource

object SensorVisibilityPreferences {
    private const val PREFS = "lab"
    private const val VHAL_KEY = "visible_vhal_sensors"
    private const val OBD_KEY = "visible_obd_sensors"

    fun selected(
        context: Context,
        source: SensorSource,
        availableKeys: Set<String>,
        defaultKeys: Set<String> = availableKeys,
    ): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = preferenceKey(source)
        if (!prefs.contains(key)) return defaultKeys.intersect(availableKeys)
        return prefs.getStringSet(key, emptySet()).orEmpty().intersect(availableKeys)
    }

    fun save(context: Context, source: SensorSource, selectedKeys: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(preferenceKey(source), selectedKeys.toSet())
            .apply()
    }

    private fun preferenceKey(source: SensorSource): String = when (source) {
        SensorSource.VHAL -> VHAL_KEY
        SensorSource.OBD -> OBD_KEY
        SensorSource.SCANNER -> error("Scanner görünürlüğü desteklenmiyor")
    }
}
