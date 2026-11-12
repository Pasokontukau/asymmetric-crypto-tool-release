package com.cryptoassistant.app.data.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFileCodecTest {

    private val identity = IdentityKey(
        displayName = "小明",
        publicKeyBase64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A",
        fingerprint = "0011223344556677",
        keySize = 4096,
        createdAt = 1_700_000_000_000L,
    )

    @Test
    fun identityRoundTrips() {
        val encoded = KeyFileCodec.encodeIdentity(
            StoredIdentity(identity, wrappedPrivateKeyBase64 = "d3JhcHBlZA=="),
        )

        val decoded = KeyFileCodec.decodeIdentity(encoded)

        assertEquals(identity, decoded.identity)
        assertEquals("d3JhcHBlZA==", decoded.wrappedPrivateKeyBase64)
        assertNull(decoded.identity.lastImportedAt)
    }

    @Test
    fun optionalLastImportedAtRoundTrips() {
        val source = identity.copy(lastImportedAt = 1_800_000_000_000L)
        val decoded = KeyFileCodec.decodeIdentity(
            KeyFileCodec.encodeIdentity(StoredIdentity(source, "d3JhcHBlZA==")),
        )

        assertEquals(source, decoded.identity)
        assertEquals(1_800_000_000_000L, decoded.identity.lastImportedAt)
    }

    @Test
    fun unknownCreatedAtRoundTrips() {
        val source = identity.copy(createdAt = IdentityKey.UNKNOWN_CREATED_AT)
        val decoded = KeyFileCodec.decodeIdentity(
            KeyFileCodec.encodeIdentity(StoredIdentity(source, "d3JhcHBlZA==")),
        )

        assertEquals(IdentityKey.UNKNOWN_CREATED_AT, decoded.identity.createdAt)
        assertTrue(!decoded.identity.createdAtKnown)
    }

    @Test
    fun rejectsUnknownVersion() {
        val encoded = KeyFileCodec.encodeIdentity(StoredIdentity(identity, "d3JhcHBlZA=="))
            .replace("version=1", "version=2")

        assertThrows(IllegalArgumentException::class.java) {
            KeyFileCodec.decodeIdentity(encoded)
        }
    }

    @Test
    fun rejectsMissingWrappedPrivateKey() {
        val encoded = KeyFileCodec.encodeIdentity(StoredIdentity(identity, "d3JhcHBlZA=="))
            .lineSequence()
            .filterNot { it.startsWith("wrappedPrivateKey=") }
            .joinToString("\n")

        assertThrows(IllegalArgumentException::class.java) {
            KeyFileCodec.decodeIdentity(encoded)
        }
    }

    @Test
    fun nameIsTrimmedAndLimited() {
        assertEquals("小明", KeyFileCodec.cleanName("  小明  "))
        assertThrows(IllegalArgumentException::class.java) {
            KeyFileCodec.cleanName("   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyFileCodec.cleanName("x".repeat(41))
        }
    }

    @Test
    fun contactsRoundTripInOrder() {
        val contacts = listOf(
            ContactKey("0011223344556677", "小明", "MIIB小明", "0011223344556677", 1L),
            ContactKey("8899aabbccddeeff", "Alice", "MIIBAlice", "8899aabbccddeeff", 2L),
        )

        val decoded = KeyFileCodec.decodeContacts(KeyFileCodec.encodeContacts(contacts))

        assertEquals(contacts, decoded)
    }

    @Test
    fun malformedContactEntriesAreSkipped() {
        val text = """
            version=1
            count=2
            contact.0.id=abc
            contact.0.name=缺少公钥
            contact.1.id=def
            contact.1.name=完整
            contact.1.publicKey=MIIB
            contact.1.fingerprint=def
            contact.1.addedAt=5
        """.trimIndent()

        val decoded = KeyFileCodec.decodeContacts(text)

        assertEquals(1, decoded.size)
        assertEquals("完整", decoded.first().name)
        assertTrue(decoded.first().fingerprint == "def")
    }
}
