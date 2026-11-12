package com.cryptoassistant.app.feature.files

import crypto.file.FileCryptoEngine
import crypto.key.KeyUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Properties
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 端读共享的单文件 `.aegix` 固定夹具。
 *
 * <p>夹具字节与共享内核、桌面端完全相同（`src/test/resources/single/` 是副本）。
 * 这里验证 Android 侧依赖的共享内核版本、资源打包与 `.aegix` 后缀约定没有漂移；
 * 真正的线格式断言在共享内核 `crypto.file.SingleFileFixtureTest`。</p>
 */
class SingleFileFixtureTest {

    @Test
    fun `decrypts the shared single file fixture`() {
        val properties = properties()
        val plaintext = Base64.getDecoder().decode(properties.getProperty("plain.base64"))
        val cipher = fixture(properties.getProperty("cipher.file"))

        assertTrue(
            "夹具必须是 Android 识别的 .aegix",
            properties.getProperty("cipher.file").endsWith(EncryptedFileFormat.EXTENSION),
        )

        val output = ByteArrayOutputStream()
        val result = FileCryptoEngine.decrypt(
            ByteArrayInputStream(cipher),
            KeyUtils.getPrivateKeyFromBytes(
                Base64.getDecoder().decode(properties.getProperty("recipient.private.pkcs8.b64")),
            ),
            listOf(
                KeyUtils.getPublicKeyFromBytes(
                    Base64.getDecoder().decode(properties.getProperty("sender.public.x509.b64")),
                ),
            ),
            output,
            null,
            null,
        )

        assertArrayEquals(plaintext, output.toByteArray())
        assertEquals(properties.getProperty("plain.name"), result.originalFileName)
        assertEquals(plaintext.size.toLong(), result.plaintextBytes)
        assertTrue(result.isSignatureValid)
        assertNotNull(result.matchedSender)
    }

    @Test
    fun `pinned header keeps the signature length first`() {
        val properties = properties()
        val cipher = fixture(properties.getProperty("cipher.file"))
        val expectedSignature = properties.getProperty("header.signature.bytes").toInt()

        assertEquals(cipher.size.toString(), properties.getProperty("cipher.size"))
        assertEquals(
            "单文件容器第一项是签名长度，没有魔数/版本字段",
            expectedSignature,
            readInt(cipher, 0),
        )
    }

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)

    private fun properties(): Properties {
        val input = javaClass.getResourceAsStream("/single/single-file-fixture.properties")
        assertNotNull("缺少夹具 /single/single-file-fixture.properties", input)
        return input!!.use { stream ->
            Properties().apply { load(stream) }
        }
    }

    private fun fixture(name: String): ByteArray {
        val input = javaClass.getResourceAsStream("/single/$name")
        assertNotNull("缺少夹具 /single/$name", input)
        return input!!.use { it.readBytes() }
    }
}
