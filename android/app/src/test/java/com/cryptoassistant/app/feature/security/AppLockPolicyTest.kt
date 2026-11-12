package com.cryptoassistant.app.feature.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {

    private fun shouldLock(
        enabled: Boolean = true,
        coldStart: Boolean = false,
        awayMs: Long = 0,
        timeoutMinutes: Int = 10,
        externalFlow: Boolean = false,
    ): Boolean = AppLockPolicy.shouldLock(
        enabled = enabled,
        coldStart = coldStart,
        awayMs = awayMs,
        timeoutMinutes = timeoutMinutes,
        externalFlow = externalFlow,
    )

    @Test
    fun `disabled never locks`() {
        assertFalse(shouldLock(enabled = false, coldStart = true))
        assertFalse(shouldLock(enabled = false, awayMs = 60 * 60_000L))
    }

    @Test
    fun `cold start locks when enabled`() {
        assertTrue(shouldLock(coldStart = true))
    }

    @Test
    fun `staying under the timeout does not lock`() {
        assertFalse(shouldLock(awayMs = 10 * 60_000L - 1))
        assertFalse(shouldLock(awayMs = 0))
        assertFalse(shouldLock(awayMs = -1))
    }

    @Test
    fun `reaching the timeout locks`() {
        assertTrue(shouldLock(awayMs = 10 * 60_000L))
        assertTrue(shouldLock(awayMs = 10 * 60_000L + 1))
    }

    @Test
    fun `immediate timeout locks on any return`() {
        assertTrue(shouldLock(awayMs = 0, timeoutMinutes = 0))
        assertTrue(shouldLock(awayMs = 1, timeoutMinutes = 0))
        assertTrue(shouldLock(awayMs = 10 * 60_000L, timeoutMinutes = 0))
    }

    @Test
    fun `legacy minute values are normalized to ten minutes`() {
        assertFalse(shouldLock(awayMs = 59_999, timeoutMinutes = 1))
        assertTrue(shouldLock(awayMs = 10 * 60_000L, timeoutMinutes = 1))

        assertFalse(shouldLock(awayMs = 9 * 60_000L, timeoutMinutes = 5))
        assertTrue(shouldLock(awayMs = 10 * 60_000L, timeoutMinutes = 5))

        assertFalse(shouldLock(awayMs = 9 * 60_000L, timeoutMinutes = 30))
        assertTrue(shouldLock(awayMs = 10 * 60_000L, timeoutMinutes = 30))
    }

    @Test
    fun `external flow never locks`() {
        assertFalse(shouldLock(coldStart = true, externalFlow = true))
        assertFalse(shouldLock(awayMs = 60 * 60_000L, externalFlow = true))
    }

    @Test
    fun `normalization only keeps immediate or ten minutes`() {
        assertEquals(0, AppLockPolicy.normalizeTimeoutMinutes(0))
        assertEquals(10, AppLockPolicy.normalizeTimeoutMinutes(1))
        assertEquals(10, AppLockPolicy.normalizeTimeoutMinutes(5))
        assertEquals(10, AppLockPolicy.normalizeTimeoutMinutes(10))
        assertEquals(10, AppLockPolicy.normalizeTimeoutMinutes(30))
        assertEquals(10, AppLockPolicy.normalizeTimeoutMinutes(-1))
    }

    @Test
    fun `timeout options stay in sync with the settings page`() {
        assertEquals(listOf(0, 10), AppLockPolicy.TIMEOUT_OPTIONS_MINUTES)
        assertEquals(10, AppLockPolicy.DEFAULT_TIMEOUT_MINUTES)
    }
}
