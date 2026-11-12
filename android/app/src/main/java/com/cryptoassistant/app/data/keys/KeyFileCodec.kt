package com.cryptoassistant.app.data.keys

import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/**
 * 密钥文件的纯文本编解码（不依赖 Android），便于 JVM 单元测试。
 *
 * 身份文件只保存公钥与 Keystore 包装后的私钥；联系人文件只保存公钥。
 */
object KeyFileCodec {

    private const val VERSION = "1"
    private const val MAX_NAME_LENGTH = 40

    fun encodeIdentity(stored: StoredIdentity): String {
        val identity = stored.identity
        val properties = Properties().apply {
            setProperty("version", VERSION)
            setProperty("name", identity.displayName)
            setProperty("publicKey", identity.publicKeyBase64)
            setProperty("fingerprint", identity.fingerprint)
            setProperty("keySize", identity.keySize.toString())
            setProperty("createdAt", identity.createdAt.toString())
            identity.lastImportedAt?.let { setProperty("lastImportedAt", it.toString()) }
            setProperty("wrappedPrivateKey", stored.wrappedPrivateKeyBase64)
        }
        return store(properties)
    }

    fun decodeIdentity(text: String): StoredIdentity {
        val properties = load(text)
        require(properties.getProperty("version") == VERSION) {
            "不支持的密钥文件版本"
        }
        val name = cleanName(properties.getProperty("name"))
        val publicKey = required(properties, "publicKey")
        val fingerprint = required(properties, "fingerprint")
        val keySize = required(properties, "keySize").toIntOrNull()
            ?: throw IllegalArgumentException("密钥长度字段无效")
        val createdAt = required(properties, "createdAt").toLongOrNull()
            ?: throw IllegalArgumentException("创建时间字段无效")
        // 旧身份文件没有这个可选字段；0/损坏值按“未知”处理，不影响密钥材料。
        val lastImportedAt = properties.getProperty("lastImportedAt")
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
        val wrapped = required(properties, "wrappedPrivateKey")
        return StoredIdentity(
            identity = IdentityKey(
                displayName = name,
                publicKeyBase64 = publicKey,
                fingerprint = fingerprint,
                keySize = keySize,
                createdAt = createdAt,
                lastImportedAt = lastImportedAt,
            ),
            wrappedPrivateKeyBase64 = wrapped,
        )
    }

    fun encodeContacts(contacts: List<ContactKey>): String {
        val properties = Properties().apply {
            setProperty("version", VERSION)
            setProperty("count", contacts.size.toString())
        }
        contacts.forEachIndexed { index, contact ->
            properties.setProperty("contact.$index.id", contact.id)
            properties.setProperty("contact.$index.name", contact.name)
            properties.setProperty("contact.$index.publicKey", contact.publicKeyBase64)
            properties.setProperty("contact.$index.fingerprint", contact.fingerprint)
            properties.setProperty("contact.$index.addedAt", contact.addedAt.toString())
        }
        return store(properties)
    }

    fun decodeContacts(text: String): List<ContactKey> {
        val properties = load(text)
        if (properties.getProperty("version") != VERSION) return emptyList()
        val count = properties.getProperty("count")?.toIntOrNull() ?: return emptyList()
        if (count <= 0) return emptyList()
        return (0 until count).mapNotNull { index ->
            val id = properties.getProperty("contact.$index.id") ?: return@mapNotNull null
            val name = properties.getProperty("contact.$index.name")?.trim().orEmpty()
            val publicKey = properties.getProperty("contact.$index.publicKey").orEmpty()
            val fingerprint = properties.getProperty("contact.$index.fingerprint").orEmpty()
            val addedAt = properties.getProperty("contact.$index.addedAt")?.toLongOrNull() ?: 0L
            if (name.isEmpty() || publicKey.isEmpty() || fingerprint.isEmpty()) {
                null
            } else {
                ContactKey(id, name.take(MAX_NAME_LENGTH), publicKey, fingerprint, addedAt)
            }
        }
    }

    fun cleanName(raw: String?): String {
        val name = raw?.trim().orEmpty()
        require(name.isNotEmpty()) { "名称不能为空" }
        require(name.length <= MAX_NAME_LENGTH) { "名称不能超过 $MAX_NAME_LENGTH 个字符" }
        return name
    }

    private fun required(properties: Properties, key: String): String =
        properties.getProperty(key)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("密钥文件缺少字段：$key")

    private fun store(properties: Properties): String =
        StringWriter().also { writer -> properties.store(writer, null) }.toString()

    private fun load(text: String): Properties =
        Properties().also { properties ->
            properties.load(StringReader(text))
        }
}
