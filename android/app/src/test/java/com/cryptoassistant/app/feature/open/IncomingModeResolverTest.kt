package com.cryptoassistant.app.feature.open

import com.cryptoassistant.app.feature.files.EncryptedFileFormat
import com.cryptoassistant.app.feature.files.FileTaskMode
import crypto.message.FileFormatHeader
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 单一入口判型：后缀 → 密文 MIME → 文件头结构 → 明文 MIME/后缀 → 让用户选。
 *
 * <p>只覆盖纯函数与 [IncomingModeResolver.probeHeader]；content:// 元数据查询与真实
 * 提供方行为依赖设备，留给 androidTest 与真机验收。</p>
 */
class IncomingModeResolverTest {

    @Test
    fun `aegix suffix wins over everything else`() {
        val decision = IncomingModeResolver.decideEntryMode(
            displayName = "合同.AEGIX",
            systemType = "image/jpeg",
            probe = ProbeOutcome.NOT_CIPHER,
            readGranted = true,
        )
        assertEquals(FileTaskMode.DECRYPT, decision.mode)
        assertEquals(EntryConfidence.HIGH, decision.confidence)
        assertEquals("name:aegix", decision.reason)
    }

    @Test
    fun `enc suffix is no longer treated as ciphertext`() {
        val decision = IncomingModeResolver.decideEntryMode(
            displayName = "old.enc",
            systemType = "application/zip",
            probe = ProbeOutcome.NOT_CIPHER,
            readGranted = true,
        )
        assertEquals(FileTaskMode.ENCRYPT, decision.mode)
        assertEquals(EntryConfidence.HIGH, decision.confidence)
        assertEquals("mime:plain", decision.reason)
    }

    @Test
    fun `cipher mime is enough without a file name`() {
        val decision = IncomingModeResolver.decideEntryMode(
            displayName = null,
            systemType = EncryptedFileFormat.MIME,
            probe = null,
            readGranted = true,
        )
        assertEquals(FileTaskMode.DECRYPT, decision.mode)
        assertEquals(EntryConfidence.MEDIUM, decision.confidence)
        assertEquals("mime:cipher", decision.reason)
    }

    @Test
    fun `octet stream is not treated as cipher by mime alone`() {
        // 提供方把未知文件统一报成 octet-stream；不能因此直接进解密页。
        val decision = IncomingModeResolver.decideEntryMode(
            displayName = null,
            systemType = EncryptedFileFormat.MIME_OCTET_STREAM,
            probe = ProbeOutcome.NOT_CIPHER,
            readGranted = true,
        )
        assertNull(decision.mode)
        assertEquals(EntryConfidence.LOW, decision.confidence)
        assertEquals("none", decision.reason)
    }

    @Test
    fun `plain mime goes to encrypt page with high confidence`() {
        val decision = IncomingModeResolver.decideEntryMode(
            displayName = "IMG_0001",
            systemType = "image/jpeg",
            probe = ProbeOutcome.NOT_CIPHER,
            readGranted = true,
        )
        assertEquals(FileTaskMode.ENCRYPT, decision.mode)
        assertEquals(EntryConfidence.HIGH, decision.confidence)
        assertEquals("mime:plain", decision.reason)
    }

    @Test
    fun `suffix inference catches plaintext the provider does not type`() {
        val decision = IncomingModeResolver.decideEntryMode(
            displayName = "说明书.pdf",
            systemType = EncryptedFileFormat.MIME_OCTET_STREAM,
            probe = ProbeOutcome.NOT_CIPHER,
            readGranted = true,
        )
        assertEquals(FileTaskMode.ENCRYPT, decision.mode)
        assertEquals(EntryConfidence.MEDIUM, decision.confidence)
        assertEquals("suffix:plain", decision.reason)
    }

    @Test
    fun `unreadable content asks the user instead of guessing`() {
        val decision = IncomingModeResolver.decideEntryMode(
            displayName = null,
            systemType = null,
            probe = ProbeOutcome.UNREADABLE,
            readGranted = false,
        )
        assertNull(decision.mode)
        assertEquals(EntryConfidence.LOW, decision.confidence)
        assertEquals("unreadable", decision.reason)
    }

    @Test
    fun `volume magic is detected as ciphertext`() {
        val header = ByteBuffer.allocate(8)
            .putInt(FileFormatHeader.MAGIC)
            .putInt(0)
            .array()
        assertEquals(ProbeOutcome.VOLUME, probe(header))

        val decision = IncomingModeResolver.decideEntryMode(
            displayName = "vault",
            systemType = null,
            probe = ProbeOutcome.VOLUME,
            readGranted = true,
        )
        assertEquals(FileTaskMode.DECRYPT, decision.mode)
        assertEquals(EntryConfidence.HIGH, decision.confidence)
        assertEquals("head:volu", decision.reason)
    }

    @Test
    fun `single file header layout is detected`() {
        assertEquals(ProbeOutcome.SINGLE_FILE, probe(singleFileBytes()))
        // 加密文件名叫空也算合法结构（空名由解密层兜底）。
        assertEquals(
            ProbeOutcome.SINGLE_FILE,
            probe(singleFileBytes(encryptedNameLength = 0)),
        )
    }

    @Test
    fun `broken headers are not mistaken for ciphertext`() {
        assertEquals(ProbeOutcome.NOT_CIPHER, probe(ByteArray(0)))
        assertEquals(ProbeOutcome.NOT_CIPHER, probe(ByteArray(3)))
        assertEquals(ProbeOutcome.NOT_CIPHER, probe(singleFileBytes(signatureLength = 0)))
        assertEquals(ProbeOutcome.NOT_CIPHER, probe(singleFileBytes(fileNameIvLength = 300)))
        assertEquals(ProbeOutcome.NOT_CIPHER, probe(singleFileBytes(encryptedNameLength = 5_000)))
        // 元数据声明总长远小于头部声明：已知总长时直接判定不成立。
        assertEquals(
            ProbeOutcome.NOT_CIPHER,
            IncomingModeResolver.probeHeader(
                ByteArrayInputStream(singleFileBytes()),
                20L,
            ),
        )
    }

    @Test
    fun `single file structure without a name still reaches decrypt`() {
        val decision = IncomingModeResolver.decideEntryMode(
            displayName = null,
            systemType = EncryptedFileFormat.MIME_OCTET_STREAM,
            probe = ProbeOutcome.SINGLE_FILE,
            readGranted = true,
        )
        assertEquals(FileTaskMode.DECRYPT, decision.mode)
        assertEquals(EntryConfidence.MEDIUM, decision.confidence)
        assertEquals("head:struct", decision.reason)
    }

    private fun probe(bytes: ByteArray): ProbeOutcome =
        IncomingModeResolver.probeHeader(ByteArrayInputStream(bytes), bytes.size.toLong())

    /**
     * 构造只含头部的单文件密文：`sigLen|sig|nameIvLen|nameIv|encNameLen|encName|tail`。
     * tail 代表 encKey/iv/ciphertext 三段，长度只需 ≥ 4 字节。
     */
    private fun singleFileBytes(
        signatureLength: Int = 16,
        fileNameIvLength: Int = 12,
        encryptedNameLength: Int = 20,
        tailLength: Int = 64,
    ): ByteArray {
        val size = 4 + signatureLength + 4 + fileNameIvLength + 4 + encryptedNameLength + tailLength
        return ByteBuffer.allocate(size)
            .putInt(signatureLength)
            .put(ByteArray(signatureLength))
            .putInt(fileNameIvLength)
            .put(ByteArray(fileNameIvLength))
            .putInt(encryptedNameLength)
            .put(ByteArray(encryptedNameLength))
            .put(ByteArray(tailLength))
            .array()
    }
}
