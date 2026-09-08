package com.kadireren.ex30sensorlab.discovery

enum class DiscoveryTarget(val id: String, val labelTr: String, val unitHint: String) {
    THROTTLE("throttle", "Gaz pedalı", "%"),
    MOTOR_RPM("motor_rpm", "Motor devri (RPM)", "rpm"),
    ACTUAL_TORQUE("actual_torque", "Actual torque", "Nm"),
    ;

    companion object {
        fun fromId(id: String): DiscoveryTarget? = entries.firstOrNull { it.id == id }
    }
}
