package com.kadireren.ex30sensorlab.logging

object LogEncoding {
    fun csv(value: Any?): String {
        val text = value?.toString() ?: ""
        return "\"${text.replace("\"", "\"\"")}\""
    }

    fun json(value: Any?): String {
        val text = value?.toString() ?: ""
        return buildString {
            append('"')
            text.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
                }
            }
            append('"')
        }
    }

    fun redact(command: String, response: String): String {
        val normalized = command.filterNot(Char::isWhitespace).uppercase()
        return if (normalized == "0902" || normalized == "22F190") "[REDACTED]" else response
    }
}
