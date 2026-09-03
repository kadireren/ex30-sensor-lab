package com.kadireren.ex30sensorlab.vhal

import com.kadireren.ex30sensorlab.model.SensorSample

interface VhalReader {
    fun start(onSample: (SensorSample) -> Unit, onSafety: (SafetyState) -> Unit)
    fun stop()
}
