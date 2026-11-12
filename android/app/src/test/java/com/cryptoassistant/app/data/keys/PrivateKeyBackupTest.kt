package com.cryptoassistant.app.data.keys

import crypto.key.KeyUtils
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivateKeyBackupTest {

    private fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun password(): CharArray = "correct horse battery".toCharArray()

    @Test
    fun sealsAndOpensBackup() {
        val pair = generateKeyPair()
        val password = password()

        val backup = PrivateKeyBackup.seal(pair.private, password)
        val opened = PrivateKeyBackup.open(backup, password)

        assertArrayEquals(pair.private.encoded, opened.encoded)
    }

    @Test
    fun wrongPasswordCannotOpenBackup() {
        val pair = generateKeyPair()
        val password = password()
        val backup = PrivateKeyBackup.seal(pair.private, password)

        assertThrows(Exception::class.java) {
            PrivateKeyBackup.open(backup, "wrong password!".toCharArray())
        }
    }

    @Test
    fun derivesMatchingPublicKeyAndFingerprint() {
        val pair = generateKeyPair()

        val derived = PrivateKeyBackup.derivePublicKey(pair.private)

        assertArrayEquals(pair.public.encoded, derived.encoded)
        assertEquals(2048, PrivateKeyBackup.keySize(derived))
        assertEquals(
            KeyUtils.generateKeyFingerprint(pair.public),
            PrivateKeyBackup.fingerprint(derived),
        )
    }

    @Test
    fun shortPasswordIsRejected() {
        val pair = generateKeyPair()

        assertThrows(IllegalArgumentException::class.java) {
            PrivateKeyBackup.seal(pair.private, "1234567".toCharArray())
        }
    }

    @Test
    fun pastedBase64WithWhitespaceIsAccepted() {
        val pair = generateKeyPair()
        val encoded = Base64.getEncoder().encodeToString(pair.public.encoded)
        val pasted = encoded.chunked(64).joinToString("\n  ")

        assertArrayEquals(pair.public.encoded, PrivateKeyBackup.decodePublicKey(pasted).encoded)
    }
}
