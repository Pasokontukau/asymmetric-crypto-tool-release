package com.cryptoassistant.app.feature.ball

import com.cryptoassistant.app.data.keys.ContactKey
import com.cryptoassistant.app.data.keys.PrivateKeyBackup
import core.model.SignedAndEncryptedData
import crypto.cipher.Decryptor
import crypto.key.KeyUtils
import crypto.message.SecureMessage
import crypto.signature.SignatureManager
import java.security.PrivateKey
import java.security.PublicKey

/**
 * 剪贴板内容识别结果。
 *
 * 识别只做结构校验，不解密、不修改剪贴板；遇到非本工具文本一律返回 NotOurs，
 * 由 UI 显示轻提示（架构文档 7.2：不得尝试无休止的解密）。
 */
sealed interface ClipContent {
    data object Empty : ClipContent
    data object NotOurs : ClipContent
    data class Cipher(val data: SignedAndEncryptedData) : ClipContent
}

/** 解密结果：明文 + 签名匹配到的联系人（null = 发送者不在联系人中）。 */
data class DecryptedMessage(
    val plaintext: String,
    val sender: ContactKey?,
)

/**
 * 悬浮球文字加解密的唯一入口（架构文档 7.3 的 FloatingBallActionUseCase）。
 *
 * - 加密：先用本机私钥签名，再用接收方公钥混合加密，输出桌面端同款 Base64 文本；
 * - 解密：先用本机私钥解密，再用联系人公钥逐个验签以标明发送者；
 * - 明文只在内存中传递，不写日志、不落盘。
 */
object TextActionUseCase {

    /** RSA 签名与 RSA 封装的字节长度：2048/3072/4096 位。用于排除随机 Base64 文本。 */
    private val RSA_BYTE_SIZES = setOf(256, 384, 512)

    private const val GCM_IV_BYTES = 12

    /**
     * 识别剪贴板文本是否为本工具密文。
     * 聊天软件常把长 Base64 折行，因此先去掉全部空白再解码。
     */
    fun inspect(raw: String?): ClipContent {
        val text = raw.orEmpty()
        if (text.isBlank()) return ClipContent.Empty
        val cleaned = text.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return ClipContent.Empty

        val bytes = runCatching { KeyUtils.decodeBase64(cleaned) }.getOrNull()
            ?: return ClipContent.NotOurs
        val data = runCatching { SignedAndEncryptedData.fromBytes(bytes) }.getOrNull()
            ?: return ClipContent.NotOurs

        // 结构再校验：签名与 RSA 封装必须是合法模长，IV 必须是本协议的 GCM IV，
        // 避免把碰巧合法的长 Base64（如公钥）误判为密文。
        val encrypted = data.encryptedData
        if (data.signature.size !in RSA_BYTE_SIZES) return ClipContent.NotOurs
        if (encrypted.encryptedAesKey.size !in RSA_BYTE_SIZES) return ClipContent.NotOurs
        if (encrypted.iv.size != GCM_IV_BYTES) return ClipContent.NotOurs

        return ClipContent.Cipher(data)
    }

    /**
     * 签名并加密文本，返回与桌面端一致的 Base64 密文。
     * @throws Exception 任何加密失败（密钥不匹配等）
     */
    fun encrypt(plaintext: String, senderPrivate: PrivateKey, receiverPublic: PublicKey): String {
        require(plaintext.isNotBlank()) { "没有可加密的文字" }
        val data = SecureMessage.signAndEncryptText(plaintext, senderPrivate, receiverPublic)
        return KeyUtils.encodeBase64(data.toBytes())
    }

    /**
     * 用本机私钥解密，并在 [candidates]（联系人公钥）中找出验签通过的发送者。
     * 明文字节数组在转换后立即清零；字符串本身只应留在界面状态里。
     *
     * @throws Exception 密文不是发给本机的，或内容已被修改
     */
    fun decrypt(
        content: ClipContent.Cipher,
        receiverPrivate: PrivateKey,
        candidates: List<ContactKey>,
    ): DecryptedMessage {
        val plain = Decryptor.decryptData(content.data.encryptedData, receiverPrivate)
        val sender = candidates.firstOrNull { contact ->
            val publicKey: PublicKey = runCatching {
                PrivateKeyBackup.decodePublicKey(contact.publicKeyBase64)
            }.getOrNull() ?: return@firstOrNull false
            runCatching {
                SignatureManager.verifyBytes(plain, content.data.signature, publicKey)
            }.getOrDefault(false)
        }
        val text = String(plain, Charsets.UTF_8)
        plain.fill(0)
        return DecryptedMessage(text, sender)
    }
}
