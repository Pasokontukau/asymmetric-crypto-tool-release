package com.cryptoassistant.app.data.keys

import crypto.key.KeyPairManager
import crypto.key.KeyUtils
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * 私钥备份：沿用桌面端的 GCM1（PBKDF2 + AES-256-GCM）口令加密格式，
 * 这样导出的备份可以直接在桌面端打开，反之亦然。
 */
object PrivateKeyBackup {

    const val MIN_PASSWORD_LENGTH = 8

    fun seal(privateKey: PrivateKey, password: CharArray): String {
        require(password.size >= MIN_PASSWORD_LENGTH) {
            "备份口令至少 $MIN_PASSWORD_LENGTH 位"
        }
        val encrypted = KeyPairManager.encryptPrivateKey(privateKey.encoded, password)
        return Base64.getEncoder().encodeToString(encrypted)
    }

    fun open(backupBase64: String, password: CharArray): PrivateKey {
        val bytes = decodeLenient(backupBase64)
        return KeyUtils.getPrivateKeyFromBytes(
            KeyPairManager.decryptPrivateKey(bytes, password),
        )
    }

    /** 从私钥推导公钥：导入备份后无需再让用户粘贴公钥。 */
    fun derivePublicKey(privateKey: PrivateKey): PublicKey {
        val rsa = privateKey as? RSAPrivateCrtKey
            ?: throw IllegalArgumentException("只支持 RSA 私钥")
        return java.security.KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(rsa.modulus, rsa.publicExponent))
    }

    fun fingerprint(publicKey: PublicKey): String = KeyUtils.generateKeyFingerprint(publicKey)

    fun keySize(publicKey: PublicKey): Int =
        (publicKey as? java.security.interfaces.RSAPublicKey)?.modulus?.bitLength()
            ?: throw IllegalArgumentException("不是 RSA 公钥")

    fun decodePublicKey(text: String): PublicKey =
        KeyUtils.getPublicKeyFromBytes(decodeLenient(text))

    fun encodePublicKey(publicKey: PublicKey): String =
        Base64.getEncoder().encodeToString(publicKey.encoded)

    /** 粘贴内容可能带换行或空格，解码前统一清理。 */
    fun decodeLenient(text: String): ByteArray {
        val cleaned = text.filterNot { it.isWhitespace() }
        require(cleaned.isNotEmpty()) { "内容为空" }
        return KeyUtils.decodeBase64(cleaned)
    }

    /** 指纹按 4 位分组，便于口头核对。 */
    fun formatFingerprint(fingerprint: String): String =
        fingerprint.chunked(4).joinToString(" ")

    fun clear(password: CharArray) {
        password.fill('\u0000')
    }
}
