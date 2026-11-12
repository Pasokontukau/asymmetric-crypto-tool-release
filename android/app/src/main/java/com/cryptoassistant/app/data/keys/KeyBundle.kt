package com.cryptoassistant.app.data.keys

import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/**
 * 完整密钥备份的文本封装：公钥与身份元数据用明文，私钥仍是口令加密的 GCM1 块。
 *
 * <p>格式刻意保持可读、可手抄、可用 `Properties` 解析；内层 [privateKeyBackup] 原样保留
 * GCM1 口令加密块。导入只接受带 `aegix-key-bundle` 头的完整备份，不再识别旧版纯
 * GCM1 文本。</p>
 *
 * <p>公钥不是秘密；它随备份一起保存只是为了恢复后能直接核对指纹与密钥长度。
 * 导入时会用私钥重新推导公钥，两者不一致就拒绝，避免导入被篡改或拼接的备份。</p>
 */
object KeyBundle {

    const val FORMAT = "aegix-key-bundle"
    private const val VERSION = "1"

    data class Bundle(
        val identity: IdentityKey,
        val privateKeyBackup: String,
    )

    fun encode(identity: IdentityKey, privateKeyBackup: String): String {
        require(privateKeyBackup.isNotBlank()) { "私钥备份内容为空" }
        val properties = Properties().apply {
            setProperty("format", FORMAT)
            setProperty("version", VERSION)
            setProperty("name", identity.displayName)
            setProperty("publicKey", identity.publicKeyBase64)
            setProperty("fingerprint", identity.fingerprint)
            setProperty("keySize", identity.keySize.toString())
            setProperty("createdAt", identity.createdAt.toString())
            setProperty("privateKeyBackup", privateKeyBackup)
        }
        return StringWriter().also { writer -> properties.store(writer, null) }.toString()
    }

    /**
     * 尝试把文本解析为完整备份。
     *
     * @return 不是完整备份（例如旧版纯 GCM1）时返回 null；声明了格式但字段损坏时抛异常。
     */
    fun decode(text: String): Bundle? {
        if (!text.contains("format=")) return null
        val properties = runCatching {
            Properties().also { it.load(StringReader(text)) }
        }.getOrNull() ?: return null
        if (properties.getProperty("format") != FORMAT) return null
        require(properties.getProperty("version") == VERSION) {
            "不支持的备份版本"
        }
        val identity = IdentityKey(
            displayName = KeyFileCodec.cleanName(properties.getProperty("name")),
            publicKeyBase64 = required(properties, "publicKey"),
            fingerprint = required(properties, "fingerprint"),
            keySize = required(properties, "keySize").toIntOrNull()
                ?: throw IllegalArgumentException("备份中的密钥长度无效"),
            createdAt = required(properties, "createdAt").toLongOrNull()
                ?.takeIf { it >= IdentityKey.UNKNOWN_CREATED_AT }
                ?: throw IllegalArgumentException("备份中的创建时间无效"),
        )
        return Bundle(identity, required(properties, "privateKeyBackup"))
    }

    private fun required(properties: Properties, key: String): String =
        properties.getProperty(key)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("备份缺少字段：$key")
}
