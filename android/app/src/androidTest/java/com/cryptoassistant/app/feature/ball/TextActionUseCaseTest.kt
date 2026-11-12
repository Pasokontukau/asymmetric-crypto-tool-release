package com.cryptoassistant.app.feature.ball

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cryptoassistant.app.data.keys.ContactKey
import com.cryptoassistant.app.data.keys.PrivateKeyBackup
import crypto.key.KeyPairManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPair

/**
 * 在真实 Android 运行时上验证悬浮球文字动作的核心逻辑：
 * 往返加解密、签名发送者识别、折行容错，以及不误判普通/公钥文本。
 */
@RunWith(AndroidJUnit4::class)
class TextActionUseCaseTest {

    @Test
    fun blankClipboardIsEmpty() {
        assertEquals(ClipContent.Empty, TextActionUseCase.inspect(null))
        assertEquals(ClipContent.Empty, TextActionUseCase.inspect(""))
        assertEquals(ClipContent.Empty, TextActionUseCase.inspect("  \n\t "))
    }

    @Test
    fun plainTextIsNotTreatedAsCipher() {
        assertEquals(ClipContent.NotOurs, TextActionUseCase.inspect("明天下午三点开会，记得带电脑。"))
        assertEquals(ClipContent.NotOurs, TextActionUseCase.inspect("SGVsbG8gV29ybGQ="))
    }

    @Test
    fun publicKeyIsNotMistakenForCipher() {
        val key = PrivateKeyBackup.encodePublicKey(receiver.public)
        assertEquals(ClipContent.NotOurs, TextActionUseCase.inspect(key))
    }

    @Test
    fun wrappedBase64StillDetected() {
        val cipher = encryptForReceiver("折行的密文也要能识别", sender, receiver)
        val wrapped = cipher.chunked(64).joinToString("\n")
        assertTrue(TextActionUseCase.inspect(wrapped) is ClipContent.Cipher)
    }

    @Test
    fun roundTripIdentifiesSignatureSender() {
        val cipher = encryptForReceiver("给同事的机密消息", sender, receiver)
        val content = TextActionUseCase.inspect(cipher) as ClipContent.Cipher

        val result = TextActionUseCase.decrypt(content, receiver.private, listOf(senderContact, strangerContact))

        assertEquals("给同事的机密消息", result.plaintext)
        assertEquals(senderContact.id, result.sender?.id)
    }

    @Test
    fun unknownSenderStillDecrypts() {
        val cipher = encryptForReceiver("签名对不上也要先解开", stranger, receiver)
        val content = TextActionUseCase.inspect(cipher) as ClipContent.Cipher

        val result = TextActionUseCase.decrypt(content, receiver.private, listOf(senderContact))

        assertEquals("签名对不上也要先解开", result.plaintext)
        assertNull(result.sender)
    }

    private fun encryptForReceiver(plaintext: String, from: KeyPair, to: KeyPair): String =
        TextActionUseCase.encrypt(plaintext, from.private, to.public)

    private companion object {
        // 4096 位与产品默认一致；密钥生成较慢，整个测试类共用一份。
        val sender: KeyPair by lazy { KeyPairManager.generateKeyPair(4096) }
        val receiver: KeyPair by lazy { KeyPairManager.generateKeyPair(4096) }
        val stranger: KeyPair by lazy { KeyPairManager.generateKeyPair(4096) }

        val senderContact: ContactKey by lazy {
            val fingerprint = PrivateKeyBackup.fingerprint(sender.public)
            ContactKey(
                id = fingerprint,
                name = "同事",
                publicKeyBase64 = PrivateKeyBackup.encodePublicKey(sender.public),
                fingerprint = fingerprint,
                addedAt = 0L,
            )
        }
        val strangerContact: ContactKey by lazy {
            val fingerprint = PrivateKeyBackup.fingerprint(stranger.public)
            ContactKey(
                id = fingerprint,
                name = "陌生人",
                publicKeyBase64 = PrivateKeyBackup.encodePublicKey(stranger.public),
                fingerprint = fingerprint,
                addedAt = 0L,
            )
        }
    }
}
