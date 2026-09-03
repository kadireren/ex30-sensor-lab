package com.kadireren.ex30sensorlab.vhal

import android.car.Car
import android.car.VehiclePropertyIds

data class VhalDefinition(
    val id: Int,
    val name: String,
    val permission: String,
    val unit: String,
    val requestedHz: Float,
)

object VhalCatalog {
    val entries = listOf(
        VhalDefinition(VehiclePropertyIds.CURRENT_GEAR, "CURRENT_GEAR", Car.PERMISSION_POWERTRAIN, "enum", 0f),
        VhalDefinition(VehiclePropertyIds.GEAR_SELECTION, "GEAR_SELECTION", Car.PERMISSION_POWERTRAIN, "enum", 0f),
        VhalDefinition(VehiclePropertyIds.IGNITION_STATE, "IGNITION_STATE", Car.PERMISSION_POWERTRAIN, "enum", 0f),
        VhalDefinition(VehiclePropertyIds.PARKING_BRAKE_ON, "PARKING_BRAKE_ON", Car.PERMISSION_POWERTRAIN, "bool", 0f),
        VhalDefinition(VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, "ENV_OUTSIDE_TEMPERATURE", Car.PERMISSION_EXTERIOR_ENVIRONMENT, "°C", 0.5f),
        VhalDefinition(VehiclePropertyIds.NIGHT_MODE, "NIGHT_MODE", Car.PERMISSION_EXTERIOR_ENVIRONMENT, "bool", 0f),
        VhalDefinition(VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED, "EV_CHARGE_PORT_CONNECTED", Car.PERMISSION_ENERGY_PORTS, "bool", 0f),
        VhalDefinition(VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY, "INFO_EV_BATTERY_CAPACITY", Car.PERMISSION_CAR_INFO, "Wh", 0f),
        VhalDefinition(VehiclePropertyIds.EV_BATTERY_LEVEL, "EV_BATTERY_LEVEL", Car.PERMISSION_ENERGY, "Wh", 0.5f),
        VhalDefinition(VehiclePropertyIds.RANGE_REMAINING, "RANGE_REMAINING", Car.PERMISSION_ENERGY, "m", 0.5f),
        VhalDefinition(VehiclePropertyIds.PERF_VEHICLE_SPEED, "PERF_VEHICLE_SPEED", Car.PERMISSION_SPEED, "m/s", 10f),
        VhalDefinition(VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY, "PERF_VEHICLE_SPEED_DISPLAY", Car.PERMISSION_SPEED, "m/s", 10f),
        VhalDefinition(VehiclePropertyIds.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE, "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE", Car.PERMISSION_ENERGY, "mW", 30f),
        VhalDefinition(VehiclePropertyIds.WHEEL_TICK, "WHEEL_TICK", Car.PERMISSION_SPEED, "ticks[]", 10f),
    )
}
