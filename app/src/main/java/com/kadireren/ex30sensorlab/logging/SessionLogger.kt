package com.kadireren.ex30sensorlab.logging

import android.content.Context
import android.os.SystemClock
import com.kadireren.ex30sensorlab.model.ScanEvent
import com.kadireren.ex30sensorlab.model.SensorSample
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionLogger(context: Context) {
    private val directory = File(context.filesDir, "sessions").apply { mkdirs() }
    private var csvWriter: FileWriter? = null
    private var jsonlWriter: FileWriter? = null
    var csvFile: File? = null
        private set
    var jsonlFile: File? = null
        private set

    @Synchronized
    fun start(kind: String) {
        close()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        csvFile = File(directory, "${kind}_$stamp.csv")
        jsonlFile = File(directory, "${kind}_$stamp.jsonl")
        csvWriter = FileWriter(csvFile!!).apply {
            write("timestamp_ms,record_type,source_or_ecu,key,identifier_or_command,raw,display_or_success,latency_ms,actual_hz,status,detail\n")
            flush()
        }
        jsonlWriter = FileWriter(jsonlFile!!)
    }

    @Synchronized
    fun append(sample: SensorSample) {
        csvWriter?.apply {
            write(listOf(
                sample.monotonicTimestampMs, "sample", sample.definition.source, sample.definition.key,
                sample.definition.identifier, sample.rawValue, sample.displayValue,
                sample.latencyMs, sample.actualHz, sample.status, sample.detail,
            ).joinToString(",") { LogEncoding.csv(it) } + "\n")
            flush()
        }
        jsonlWriter?.apply {
            write("{" + listOf(
                "\"type\":\"sample\"",
                "\"timestampMs\":${sample.monotonicTimestampMs}",
                "\"source\":${LogEncoding.json(sample.definition.source)}",
                "\"key\":${LogEncoding.json(sample.definition.key)}",
                "\"identifier\":${LogEncoding.json(sample.definition.identifier)}",
                "\"raw\":${LogEncoding.json(sample.rawValue)}",
                "\"display\":${LogEncoding.json(sample.displayValue)}",
                "\"latencyMs\":${sample.latencyMs}",
                "\"actualHz\":${sample.actualHz}",
                "\"status\":${LogEncoding.json(sample.status)}",
                "\"detail\":${LogEncoding.json(sample.detail)}",
            ).joinToString(",") + "}\n")
            flush()
        }
    }

    @Synchronized
    fun append(event: ScanEvent) {
        val safeResponse = LogEncoding.redact(event.command, event.rawResponse)
        csvWriter?.apply {
            write(listOf(
                event.timestampMs, "scan", event.ecu, "", event.command,
                safeResponse, event.success, 0, 0, event.classification, "",
            ).joinToString(",") { LogEncoding.csv(it) } + "\n")
            flush()
        }
        jsonlWriter?.apply {
            write("{" + listOf(
                "\"type\":\"scan\"",
                "\"timestampMs\":${event.timestampMs}",
                "\"ecu\":${LogEncoding.json(event.ecu)}",
                "\"command\":${LogEncoding.json(event.command)}",
                "\"rawResponse\":${LogEncoding.json(safeResponse)}",
                "\"success\":${event.success}",
                "\"classification\":${LogEncoding.json(event.classification)}",
            ).joinToString(",") + "}\n")
            flush()
        }
    }

    @Synchronized
    fun appendProtocol(command: String, response: String) {
        val timestamp = SystemClock.elapsedRealtime()
        val safeResponse = LogEncoding.redact(command, response)
        csvWriter?.apply {
            write(listOf(
                timestamp, "protocol", "ELM327", "", command,
                safeResponse, "", 0, 0, "RAW", "",
            ).joinToString(",") { LogEncoding.csv(it) } + "\n")
            flush()
        }
        jsonlWriter?.apply {
            write("{" + listOf(
                "\"type\":\"protocol\"",
                "\"timestampMs\":$timestamp",
                "\"command\":${LogEncoding.json(command)}",
                "\"rawResponse\":${LogEncoding.json(safeResponse)}",
            ).joinToString(",") + "}\n")
            flush()
        }
    }

    @Synchronized
    fun close() {
        try { csvWriter?.close() } catch (_: Exception) {}
        try { jsonlWriter?.close() } catch (_: Exception) {}
        csvWriter = null
        jsonlWriter = null
    }

    fun ageMs(): Long = SystemClock.elapsedRealtime()
}
