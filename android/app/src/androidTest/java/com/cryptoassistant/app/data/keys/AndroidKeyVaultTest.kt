package com.cryptoassistant.app.data.keys

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeyVaultTest {

    private fun vault(alias: String = "test.vault." + System.nanoTime()) = AndroidKeyVault(alias)

    @Test
    fun wrapsAndUnwrapsPrivateKeyBytes() {
        val vault = vault()
        val plain = "PKCS#8 私钥字节".toByteArray(Charsets.UTF_8)

        val wrapped = vault.wrap(plain)

        assertArrayEquals(plain, vault.unwrap(wrapped))
    }

    @Test
    fun tamperedBlobIsRejected() {
        val vault = vault()
        val wrapped = vault.wrap("secret".toByteArray())
        wrapped[wrapped.lastIndex] = (wrapped.last().toInt() xor 0x01).toByte()

        assertThrows(IllegalStateException::class.java) {
            vault.unwrap(wrapped)
        }
    }

    @Test
    fun anotherAliasCannotUnwrap() {
        val wrapped = vault().wrap("secret".toByteArray())

        assertThrows(IllegalStateException::class.java) {
            vault().unwrap(wrapped)
        }
    }
}
