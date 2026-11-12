package com.cryptoassistant.app.data.keys

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 私钥落盘前的包装层。抽成接口便于测试时替换。 */
interface KeyVault {
    fun wrap(data: ByteArray): ByteArray
    fun unwrap(blob: ByteArray): ByteArray
}

/**
 * 用 Android Keystore 中的 AES-256-GCM 密钥包装私钥。
 *
 * 私钥本身不会以明文写入应用目录；包装密钥由系统 Keystore 保管（模拟器上为软件实现，
 * 支持 StrongBox 的设备可进一步要求硬件隔离）。普通加密操作不需要用户输入口令，
 * 导出备份时才使用 GCM1 口令格式。
 */
class AndroidKeyVault(
    private val alias: String = DEFAULT_ALIAS,
) : KeyVault {

    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
    private val random = SecureRandom()
    private val lock = Any()

    override fun wrap(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(data)
        return ByteArray(MAGIC.size + iv.size + ciphertext.size).also { out ->
            MAGIC.copyInto(out, 0)
            iv.copyInto(out, MAGIC.size)
            ciphertext.copyInto(out, MAGIC.size + iv.size)
        }
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        require(blob.size > MAGIC.size + IV_LENGTH) { "私钥包装数据不完整" }
        require(blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "不支持的私钥包装格式"
        }
        val iv = blob.copyOfRange(MAGIC.size, MAGIC.size + IV_LENGTH)
        val ciphertext = blob.copyOfRange(MAGIC.size + IV_LENGTH, blob.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (error: Exception) {
            throw IllegalStateException("无法解封本机私钥（Keystore 密钥可能已被清除）", error)
        }
    }

    private fun secretKey(): SecretKey = synchronized(lock) {
        if (!keyStore.containsAlias(alias)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generator.generateKey()
        }
        keyStore.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("Keystore 中的包装密钥不可用")
    }

    companion object {
        const val DEFAULT_ALIAS = "cryptoassistant.identity.wrap.v1"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val IV_LENGTH = 12
        private val MAGIC = byteArrayOf('C'.code.toByte(), 'K'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    }
}
