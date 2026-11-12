package com.cryptoassistant.app.data.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyBundleTest {

    private val identity = IdentityKey(
        displayName = "测试身份",
        publicKeyBase64 = "PUBLIC_KEY_BASE64",
        fingerprint = "0123456789abcdef0123456789abcdef",
        keySize = 4096,
        createdAt = 1_700_000_000_000L,
    )

    @Test
    fun `完整备份往返保留公钥与私钥块`() {
        val sealed = "GCM1-PRIVATE-BLOCK"
        val text = KeyBundle.encode(identity, sealed)

        val decoded = requireNotNull(KeyBundle.decode(text))
        assertEquals(identity, decoded.identity)
        assertEquals(sealed, decoded.privateKeyBackup)
    }

    @Test
    fun `旧版纯 GCM1 文本不会被误判为完整备份`() {
        assertNull(KeyBundle.decode("QUJDREVGR0hJSktMTU5PUA=="))
        assertNull(KeyBundle.decode(""))
    }

    @Test
    fun `声明了格式但缺字段时明确失败`() {
        val broken = "format=aegix-key-bundle\nversion=1\nname=只有名字\n"
        val error = runCatching { KeyBundle.decode(broken) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty().contains("publicKey"))
    }

    @Test
    fun `未知创建时间可以往返且最近导入不写入备份`() {
        val source = identity.copy(
            createdAt = IdentityKey.UNKNOWN_CREATED_AT,
            lastImportedAt = 1_800_000_000_000L,
        )

        val decoded = requireNotNull(KeyBundle.decode(KeyBundle.encode(source, "BLOCK")))

        assertEquals(IdentityKey.UNKNOWN_CREATED_AT, decoded.identity.createdAt)
        assertNull(decoded.identity.lastImportedAt)
    }

    @Test
    fun `负的创建时间会被拒绝`() {
        val text = KeyBundle.encode(identity.copy(createdAt = -1L), "BLOCK")
        val error = runCatching { KeyBundle.decode(text) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty().contains("创建时间"))
    }

    @Test
    fun `不支持的版本会被拒绝`() {
        val text = KeyBundle.encode(identity, "BLOCK").replace("version=1", "version=9")
        val error = runCatching { KeyBundle.decode(text) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty().contains("版本"))
    }
}
