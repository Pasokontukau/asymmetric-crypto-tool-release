package com.cryptoassistant.app.data.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogLevelTest {

    @Test
    fun `threshold includes current and more severe levels`() {
        assertTrue(LogLevel.ERROR.allows(LogLevel.ERROR))
        assertTrue(LogLevel.WARN.allows(LogLevel.ERROR))
        assertTrue(LogLevel.INFO.allows(LogLevel.WARN))
        assertTrue(LogLevel.DEBUG.allows(LogLevel.INFO))
        assertTrue(LogLevel.VERBOSE.allows(LogLevel.DEBUG))
        assertTrue(LogLevel.VERBOSE.allows(LogLevel.ERROR))
    }

    @Test
    fun `threshold excludes more detailed levels`() {
        assertFalse(LogLevel.ERROR.allows(LogLevel.WARN))
        assertFalse(LogLevel.WARN.allows(LogLevel.INFO))
        assertFalse(LogLevel.INFO.allows(LogLevel.DEBUG))
        assertFalse(LogLevel.DEBUG.allows(LogLevel.VERBOSE))
    }

    @Test
    fun `off allows nothing`() {
        LogLevel.entries.forEach { event ->
            assertFalse(LogLevel.OFF.allows(event))
        }
    }

    @Test
    fun `debug builds default to verbose and release to info`() {
        assertEquals(LogLevel.VERBOSE, LogLevel.defaultFor(debugBuild = true))
        assertEquals(LogLevel.INFO, LogLevel.defaultFor(debugBuild = false))
    }

    @Test
    fun `wire names are case insensitive and unknown names are rejected`() {
        assertEquals(LogLevel.DEBUG, LogLevel.fromWire("DEBUG"))
        assertEquals(LogLevel.OFF, LogLevel.fromWire(" off "))
        assertEquals(null, LogLevel.fromWire("trace"))
        assertEquals(null, LogLevel.fromWire(null))
    }
}
