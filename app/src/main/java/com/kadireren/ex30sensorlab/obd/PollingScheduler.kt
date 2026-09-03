package com.kadireren.ex30sensorlab.obd

import com.kadireren.ex30sensorlab.model.SensorSample

interface PollingScheduler {
    fun start(onSample: (SensorSample) -> Unit, onState: (String) -> Unit)
    fun focus(sensorKey: String?)
    fun stop()
}
