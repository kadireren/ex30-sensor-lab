package com.kadireren.ex30sensorlab.vhal

data class SafetyState(
    val speedKmh: Float? = null,
    val parkingBrakeOn: Boolean? = null,
    val ignitionState: Int? = null,
) {
    val isReadyOrOn: Boolean get() = ignitionState == 4 || ignitionState == 5
    val isStationary: Boolean get() = speedKmh != null && kotlin.math.abs(speedKmh) < 0.5f
    val scannerAllowed: Boolean get() = isReadyOrOn && isStationary && parkingBrakeOn == true

    fun denialReason(): String = when {
        !isReadyOrOn -> "Kontak READY/ON değil veya veri okunamıyor"
        !isStationary -> "Araç sabit değil veya hız verisi okunamıyor"
        parkingBrakeOn != true -> "Park freni aktif değil veya veri okunamıyor"
        else -> "Hazır"
    }
}
