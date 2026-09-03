package com.kadireren.ex30sensorlab.logging

import org.junit.Assert.assertEquals
import org.junit.Test

class LogEncodingTest {
    @Test fun escapesCsvAndJson() {
        assertEquals("\"a,\"\"b\"\"\"", LogEncoding.csv("a,\"b\""))
        assertEquals("\"a\\n\\\"b\"", LogEncoding.json("a\n\"b"))
    }

    @Test fun redactsVehicleIdentifiers() {
        assertEquals("[REDACTED]", LogEncoding.redact("22 F190", "62F190VIN"))
        assertEquals("624801A73A", LogEncoding.redact("224801", "624801A73A"))
    }
}
