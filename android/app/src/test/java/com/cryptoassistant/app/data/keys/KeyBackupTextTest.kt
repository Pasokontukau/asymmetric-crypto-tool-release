package com.cryptoassistant.app.data.keys

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyBackupTextTest {

    private val identity = IdentityKey(
        displayName = "文件备份",
        publicKeyBase64 = "PUBLIC_KEY_BASE64",
        fingerprint = "0123456789abcdef0123456789abcdef",
        keySize = 4096,
        createdAt = 1_700_000_000_000L,
    )

    @Test
    fun `UTF-8 BOM 会被去掉后解析为完整备份`() {
        val text = KeyBundle.encode(identity, "GCM1-PRIVATE-BLOCK")
        val decoded = KeyBackupText.decode(("\uFEFF" + text).toByteArray(Charsets.UTF_8)).getOrThrow()

        assertTrue(!decoded.startsWith("\uFEFF"))
        val kind = KeyBackupText.inspect(decoded)
        assertTrue(kind is KeyBackupText.Kind.Bundle)
        assertEquals("文件备份", (kind as KeyBackupText.Kind.Bundle).name)
        assertEquals(4096, kind.keySize)
    }

    @Test
    fun `旧版纯 GCM1 Base64 不再被当成备份`() {
        val legacy = java.util.Base64.getEncoder().encodeToString(ByteArray(512) { it.toByte() })
        val kind = KeyBackupText.inspect(legacy)
        assertTrue(kind is KeyBackupText.Kind.Invalid)
        assertEquals(KeyError.BACKUP_NOT_OURS, (kind as KeyBackupText.Kind.Invalid).reason)
    }

    @Test
    fun `随机文本不会被当成备份`() {
        val kind = KeyBackupText.inspect("这是一段普通笔记，不是备份。")
        assertTrue(kind is KeyBackupText.Kind.Invalid)
        assertEquals(KeyError.BACKUP_NOT_OURS, (kind as KeyBackupText.Kind.Invalid).reason)
    }

    @Test
    fun `损坏的完整备份给出字段级提示`() {
        val broken = "format=aegix-key-bundle\nversion=1\nname=只有名字\n"
        val kind = KeyBackupText.inspect(broken)
        assertTrue(kind is KeyBackupText.Kind.Invalid)
        assertEquals(KeyError.BACKUP_FORMAT, (kind as KeyBackupText.Kind.Invalid).reason)
        assertTrue(kind.detail.orEmpty().contains("publicKey"))
    }

    @Test
    fun `超过上限的文件被拒绝`() {
        val bytes = ByteArray(KeyBackupText.MAX_BYTES + 1) { 'a'.code.toByte() }
        val error = KeyBackupText.decode(bytes).exceptionOrNull()
        assertEquals(KeyError.BACKUP_TOO_LARGE, (error as KeyOperationException).reason)
    }

    @Test
    fun `非法 UTF-8 与二进制内容被拒绝`() {
        val invalidUtf8 = byteArrayOf(0xC3.toByte(), 0x28)
        assertTrue(KeyBackupText.decode(invalidUtf8).isFailure)

        val binary = "format=aegix-key-bundle\n\u0000".toByteArray(Charsets.UTF_8)
        assertTrue(KeyBackupText.decode(binary).isFailure)
    }

    @Test
    fun `readAtMost 在超限时停止读取`() {
        val stream = ByteArrayInputStream(ByteArray(4096) { 'a'.code.toByte() })
        val error = runCatching { KeyBackupText.readAtMost(stream, limit = 1024) }.exceptionOrNull()
        assertTrue(error is KeyOperationException)
    }

    @Test
    fun `readAtMost 正常读取小文件`() {
        val payload = "hello".toByteArray(Charsets.UTF_8)
        val bytes = KeyBackupText.readAtMost(ByteArrayInputStream(payload), limit = 1024)
        assertEquals("hello", bytes.toString(Charsets.UTF_8))
    }
}
