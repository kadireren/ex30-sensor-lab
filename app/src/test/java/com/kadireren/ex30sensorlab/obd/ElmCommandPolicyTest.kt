package com.kadireren.ex30sensorlab.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmCommandPolicyTest {
    @Test fun allowsOnlyAdapterAndReadServices() {
        assertTrue(ElmCommandPolicy.isAllowed("ATSP7"))
        assertTrue(ElmCommandPolicy.isAllowed("ATSHD01635"))
        assertTrue(ElmCommandPolicy.isAllowed("ATFCSH1DD01635"))
        assertTrue(ElmCommandPolicy.isAllowed("ATRV"))
        assertTrue(ElmCommandPolicy.isAllowed("22 FD00 FD01 FD02 FD03"))
        assertTrue(ElmCommandPolicy.isAllowed("010D"))
        listOf("1003", "1101", "14FFFFFF", "2E123400", "3101ABCD", "04").forEach {
            assertFalse(it, ElmCommandPolicy.isAllowed(it))
        }
    }
}
