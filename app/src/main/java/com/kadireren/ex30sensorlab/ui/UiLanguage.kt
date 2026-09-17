package com.kadireren.ex30sensorlab.ui

import android.content.Context
import com.kadireren.ex30sensorlab.model.SensorSource

enum class UiLanguage { TURKISH, ENGLISH }

object UiLanguagePreferences {
    private const val PREFS = "lab"
    private const val KEY = "ui_language"

    fun current(context: Context): UiLanguage = runCatching {
        UiLanguage.valueOf(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, UiLanguage.TURKISH.name)!!,
        )
    }.getOrDefault(UiLanguage.TURKISH)

    fun save(context: Context, language: UiLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, language.name)
            .apply()
    }
}

object UiLanguageText {
    val visibleSensorSources = listOf(SensorSource.VHAL, SensorSource.OBD)

    private val englishSensorNames = mapOf(
        "CURRENT_GEAR" to "Current gear",
        "GEAR_SELECTION" to "Gear selection",
        "IGNITION_STATE" to "Ignition state",
        "PARKING_BRAKE_ON" to "Parking brake",
        "ENV_OUTSIDE_TEMPERATURE" to "Outside temperature",
        "NIGHT_MODE" to "Night mode",
        "EV_CHARGE_PORT_CONNECTED" to "Charge port",
        "INFO_EV_BATTERY_CAPACITY" to "Battery capacity",
        "EV_BATTERY_LEVEL" to "Battery energy",
        "RANGE_REMAINING" to "Remaining range",
        "PERF_VEHICLE_SPEED" to "Vehicle speed (PERF)",
        "PERF_VEHICLE_SPEED_DISPLAY" to "Display speed",
        "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE" to "Battery power",
        "WHEEL_TICK" to "Wheel ticks",
        "BATTERY_SOC_PERCENT" to "Display battery percentage",
        "INSTANT_CONSUMPTION" to "Instant consumption",
        "pedal_pwm" to "Accelerator pedal PWM signal",
        "erad_motor_speed" to "ERAD motor speed",
        "erad_actual_torque" to "ERAD actual torque",
        "cell_min_soc" to "Minimum cell SOC",
        "hv_inlet_coolant_temp" to "Battery inlet coolant temperature",
        "battery_12v" to "12 V battery voltage",
        "charge_power_limit" to "Charge power limit",
        "discharge_power_limit" to "Discharge power limit",
        "ecu_supply_voltage" to "BECM ECU supply voltage",
        "cell_voltage_sum" to "Cell voltage sum",
        "cell_max_voltage" to "Maximum cell voltage",
        "cell_min_voltage" to "Minimum cell voltage",
        "hv_soh_secondary" to "HV battery SOH (secondary channel)",
        "igm_fuse_voltage" to "IGM fuse voltage",
        "dc_connector_temp" to "DC charge connector temperature",
        "ac_temp" to "A/C temperature",
        "ac_pressure" to "A/C pressure",
        "ac_temp_chiller" to "Temperature after chiller",
        "ac_temp_inner_condenser" to "Temperature after inner condenser",
        "ac_temp_evaporator" to "Temperature after evaporator",
        "ac_temp_compressor" to "Temperature after compressor",
        "powertrain_coolant_temp_vcu" to "Powertrain coolant temperature (VCU)",
        "cooling_request" to "Cooling fan/pump request",
        "coolant_pump_chamber_request" to "Pump chamber requested speed",
        "cooling_valve_actual" to "HV cooling valve actual position",
        "cooling_valve_requested" to "HV cooling valve requested position",
        "erad_wheel_speed" to "ERAD wheel/motor output speed",
        "iem_hv_current" to "IEM HV system current",
        "erad_motor_temp" to "ERAD motor temperature",
        "powertrain_coolant_temp_iem" to "Electric powertrain coolant temperature",
        "obd_soc" to "BECM battery SOC",
        "hv_voltage" to "HV pack voltage",
        "hv_current" to "HV pack current",
        "hv_temp_avg" to "HV battery average temperature",
        "hv_temp_max" to "HV battery maximum temperature",
        "hv_soh" to "HV battery SOH",
        "odometer" to "Odometer",
        "odometer_11bit" to "Odometer (11-bit)",
        "vehicle_speed" to "Vehicle speed",
        "wheel_fl" to "Front-left wheel speed",
        "wheel_fr" to "Front-right wheel speed",
        "wheel_rl" to "Rear-left wheel speed",
        "wheel_rr" to "Rear-right wheel speed",
        "voltage_12v" to "12 V supply",
        "hv_power" to "HV power (derived)",
        "soc_display" to "Display battery percentage (OBD)",
    )

    fun sensorName(key: String, turkishName: String, language: UiLanguage): String =
        if (language == UiLanguage.ENGLISH) englishSensorNames[key] ?: turkishName else turkishName

    fun hasEnglishSensorName(key: String): Boolean = key in englishSensorNames
}
