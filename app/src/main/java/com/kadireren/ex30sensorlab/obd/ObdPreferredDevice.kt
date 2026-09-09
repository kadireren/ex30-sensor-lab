package com.kadireren.ex30sensorlab.obd

import android.content.Context

object ObdPreferredDevice {
    const val DISPLAY_NAME = "Android-Vlink"
    private const val PREFS = "lab"
    private const val KEY_ADDRESS = "obd_preferred_address"
    private const val KEY_NAME = "obd_preferred_name"

    fun matchesName(name: String): Boolean =
        name.equals(DISPLAY_NAME, ignoreCase = true) ||
            name.contains(DISPLAY_NAME, ignoreCase = true)

    fun isPreferred(entry: ObdDeviceEntry): Boolean = matchesName(entry.name)

    fun resolveBonded(bonded: List<ObdDeviceEntry>): ObdDeviceEntry? =
        bonded.firstOrNull { isPreferred(it) }

    fun resolveBonded(scanner: BluetoothObdDeviceScanner): ObdDeviceEntry? =
        resolveBonded(scanner.bondedDevices())

    fun resolveSaved(context: Context): ObdDeviceEntry? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val address = prefs.getString(KEY_ADDRESS, null)?.takeIf { it.isNotBlank() } ?: return null
        val name = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: DISPLAY_NAME
        if (!matchesName(name)) return null
        return ObdDeviceEntry(name = name, address = address, bonded = false)
    }

    fun resolve(context: Context, scanner: BluetoothObdDeviceScanner): ObdDeviceEntry? =
        resolveBonded(scanner) ?: resolveSaved(context)

    fun remember(context: Context, entry: ObdDeviceEntry) {
        if (!isPreferred(entry)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ADDRESS, entry.address)
            .putString(KEY_NAME, entry.name)
            .apply()
    }
}
