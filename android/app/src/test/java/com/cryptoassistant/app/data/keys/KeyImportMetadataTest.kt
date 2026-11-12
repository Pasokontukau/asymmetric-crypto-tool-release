package com.cryptoassistant.app.data.keys

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyImportMetadataTest {

    @Test
    fun `complete backup keeps original creation time`() {
        val imported = KeyImportMetadata.importedIdentity(
            displayName = "恢复",
            backupCreatedAt = 1_700_000_000_000L,
            publicKeyBase64 = "PUBLIC",
            fingerprint = "0123456789abcdef0123456789abcdef",
            keySize = 4096,
            importedAt = 1_800_000_000_000L,
        )

        assertEquals(1_700_000_000_000L, imported.createdAt)
        assertEquals(1_800_000_000_000L, imported.lastImportedAt)
        assertEquals("恢复", imported.displayName)
    }

    @Test
    fun `non positive backup time is normalized to unknown`() {
        listOf(0L, -1L, Long.MIN_VALUE).forEach { value ->
            val imported = KeyImportMetadata.importedIdentity(
                displayName = "x",
                backupCreatedAt = value,
                publicKeyBase64 = "PUBLIC",
                fingerprint = "fp",
                keySize = 2048,
                importedAt = 1L,
            )
            assertEquals(IdentityKey.UNKNOWN_CREATED_AT, imported.createdAt)
            assertEquals(1L, imported.lastImportedAt)
        }
    }
}
