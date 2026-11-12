package com.cryptoassistant.app.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import core.model.SignedAndEncryptedData
import crypto.key.KeyPairManager
import crypto.key.KeyUtils
import crypto.message.SecureMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Properties

/**
 * 在真实 Android 运行时（Conscrypt）上验证与桌面端的互操作：
 * - 桌面端生成的签名+加密消息可以在设备上解密并验签；
 * - 桌面端写出的 GCM1 私钥文件可以在设备上读取；
 * - 设备端生成的密文写入外部私有目录，供主机端用桌面代码解密验证。
 */
@RunWith(AndroidJUnit4::class)
class AndroidInteropTest {

    private lateinit var fixture: Properties

    @Before
    fun loadFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fixture = Properties().apply {
            instrumentation.context.assets.open("interop/desktop-fixture.properties").use { load(it) }
        }
    }

    @Test
    fun desktopFixtureDecryptsAndVerifiesOnDevice() {
        val data = SignedAndEncryptedData.fromBytes(decode(fixture.getProperty("desktop.signedEncrypted.b64")))
        val result = SecureMessage.decryptAndVerify(
            data,
            KeyUtils.getPublicKeyFromBytes(decode(fixture.getProperty("sender.public.x509.b64"))),
            KeyUtils.getPrivateKeyFromBytes(decode(fixture.getProperty("receiver.private.pkcs8.b64"))),
        )

        assertTrue("设备端验签失败", result.isSignatureValid)
        assertArrayEquals(expectedPlaintext(), result.plaintext)
        assertEquals(String(expectedPlaintext(), StandardCharsets.UTF_8), result.plaintextAsString)
    }

    @Test
    fun desktopPrivateKeyFileIsReadableOnDevice() {
        val password = fixture.getProperty("privateKey.password").toCharArray()
        val encryptedFile = decode(fixture.getProperty("receiver.privateKey.encryptedFile.b64"))

        val decrypted = KeyPairManager.decryptPrivateKey(encryptedFile, password)
        assertArrayEquals(decode(fixture.getProperty("receiver.private.pkcs8.b64")), decrypted)
        assertTrue(KeyPairManager.verifyPassword(encryptedFile, password))
        decrypted.fill(0)
        password.fill('\u0000')
    }

    @Test
    fun tamperedSignatureIsRejectedOnDevice() {
        val tampered = decode(fixture.getProperty("desktop.signedEncrypted.b64")).clone()
        tampered[4] = (tampered[4].toInt() xor 0x01).toByte()

        val result = SecureMessage.decryptAndVerify(
            SignedAndEncryptedData.fromBytes(tampered),
            KeyUtils.getPublicKeyFromBytes(decode(fixture.getProperty("sender.public.x509.b64"))),
            KeyUtils.getPrivateKeyFromBytes(decode(fixture.getProperty("receiver.private.pkcs8.b64"))),
        )

        assertFalse("篡改签名仍被判定有效", result.isSignatureValid)
    }

    @Test
    fun writesAndroidCiphertextForDesktopVerification() {
        val plaintext = "Android 设备 → 桌面端 互操作测试：RSA-OAEP + AES-256-GCM + SHA256withRSA ✓ 2026-10-20"
        val data = SecureMessage.signAndEncryptText(
            plaintext,
            KeyUtils.getPrivateKeyFromBytes(decode(fixture.getProperty("sender.private.pkcs8.b64"))),
            KeyUtils.getPublicKeyFromBytes(decode(fixture.getProperty("receiver.public.x509.b64"))),
        )

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val outputDir = requireNotNull(targetContext.getExternalFilesDir(null)) {
            "设备没有可用的外部私有目录"
        }
        val output = File(outputDir, "android-to-desktop.b64")
        output.writeText(encode(data.toBytes()), StandardCharsets.US_ASCII)

        assertTrue(output.isFile)
        assertTrue(output.length() > 100)
        // 明文仅作为测试输入，不写入设备文件；输出文件只包含密文与签名。
    }

    @Test
    fun writesAndroidEncryptedPrivateKeyForDesktopVerification() {
        val password = fixture.getProperty("privateKey.password").toCharArray()
        val privateKeyBytes = decode(fixture.getProperty("receiver.private.pkcs8.b64"))
        val encrypted = KeyPairManager.encryptPrivateKey(privateKeyBytes, password)
        assertTrue(KeyPairManager.verifyPassword(encrypted, password))
        privateKeyBytes.fill(0)
        password.fill('\u0000')

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val outputDir = requireNotNull(targetContext.getExternalFilesDir(null))
        File(outputDir, "android-private-key.b64").writeText(
            encode(encrypted),
            StandardCharsets.US_ASCII,
        )
    }

    private fun expectedPlaintext(): ByteArray =
        decode(fixture.getProperty("plaintext.b64"))

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    private fun encode(value: ByteArray): String = Base64.getEncoder().encodeToString(value)
}
