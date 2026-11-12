package com.cryptoassistant.app.feature.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityLockPolicyTest {

    @Test
    fun `lock disabled leaves key operations open`() {
        assertFalse(
            SecurityLockPolicy.requiresAuth(SecurityAction.EXPORT_BACKUP, lockEnabled = false),
        )
        assertFalse(
            SecurityLockPolicy.requiresAuth(SecurityAction.IMPORT_BACKUP, lockEnabled = false),
        )
        assertFalse(
            SecurityLockPolicy.requiresAuth(SecurityAction.DELETE_IDENTITY, lockEnabled = false),
        )
        assertFalse(
            SecurityLockPolicy.requiresAuth(SecurityAction.LOG_EXPORT, lockEnabled = false),
        )
        assertFalse(
            SecurityLockPolicy.requiresAuth(SecurityAction.LOG_CLEAR, lockEnabled = false),
        )
        assertFalse(
            SecurityLockPolicy.requiresAuth(SecurityAction.LOG_LEVEL_CHANGE, lockEnabled = false),
        )
    }

    @Test
    fun `lock enabled protects every sensitive operation`() {
        assertTrue(
            SecurityLockPolicy.requiresAuth(SecurityAction.EXPORT_BACKUP, lockEnabled = true),
        )
        assertTrue(
            SecurityLockPolicy.requiresAuth(SecurityAction.IMPORT_BACKUP, lockEnabled = true),
        )
        assertTrue(
            SecurityLockPolicy.requiresAuth(SecurityAction.DELETE_IDENTITY, lockEnabled = true),
        )
        assertTrue(
            SecurityLockPolicy.requiresAuth(SecurityAction.LOG_EXPORT, lockEnabled = true),
        )
        assertTrue(
            SecurityLockPolicy.requiresAuth(SecurityAction.LOG_CLEAR, lockEnabled = true),
        )
        assertTrue(
            SecurityLockPolicy.requiresAuth(SecurityAction.LOG_LEVEL_CHANGE, lockEnabled = true),
        )
    }

    @Test
    fun `enabling and disabling always require auth`() {
        assertTrue(
            SecurityLockPolicy.requiresAuth(SecurityAction.ENABLE_LOCK, lockEnabled = false),
        )
        assertTrue(
            SecurityLockPolicy.requiresAuth(SecurityAction.DISABLE_LOCK, lockEnabled = true),
        )
    }

    @Test
    fun `app lock actions always require auth`() {
        assertTrue(
            SecurityLockPolicy.requiresAuth(SecurityAction.APP_LOCK_ENABLE, lockEnabled = false),
        )
        assertTrue(
            SecurityLockPolicy.requiresAuth(SecurityAction.APP_LOCK_ENABLE, lockEnabled = true),
        )
        assertTrue(
            SecurityLockPolicy.requiresAuth(SecurityAction.APP_LOCK_DISABLE, lockEnabled = false),
        )
        assertTrue(
            SecurityLockPolicy.requiresAuth(SecurityAction.APP_LOCK_DISABLE, lockEnabled = true),
        )
        assertTrue(
            SecurityLockPolicy.requiresAuth(SecurityAction.UNLOCK_APP, lockEnabled = true),
        )
    }
}
