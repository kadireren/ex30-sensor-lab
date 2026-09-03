package com.kadireren.ex30sensorlab.obd

import java.util.Locale

object ElmCommandPolicy {
    private val atPattern = Regex("^AT[A-Z0-9]+$")
    private val mode01Pattern = Regex("^01[0-9A-F]{2}$")
    private val udsReadPattern = Regex("^22(?:[0-9A-F]{4})+$")

    fun normalize(command: String): String = command.filterNot(Char::isWhitespace).uppercase(Locale.US)

    fun isAllowed(command: String): Boolean {
        val normalized = normalize(command)
        return atPattern.matches(normalized) || mode01Pattern.matches(normalized) || udsReadPattern.matches(normalized)
    }

    fun requireAllowed(command: String): String {
        val normalized = normalize(command)
        require(isAllowed(normalized)) { "Salt-okunur olmayan OBD komutu engellendi: $normalized" }
        return normalized
    }
}
