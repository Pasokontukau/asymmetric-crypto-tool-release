package com.cryptoassistant.app.feature.open

import com.cryptoassistant.app.feature.files.FileTaskMode
import crypto.file.FileCryptoEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 文件流水线决策矩阵。
 *
 * 每条回退理由都必须有一个“只差这一项”的用例，避免以后调整顺序时悄悄
 * 把某类文件自动处理掉；两条 Auto 路径锁住加密/解密方向的正常入口。
 */
class FilePipelinePolicyTest {

    @Test
    fun `disabled falls back`() {
        assertEquals(
            PipelineDecision.Fallback(PipelineFallbackReason.DISABLED),
            decide(facts(enabled = false)),
        )
    }

    @Test
    fun `forced entry mode keeps the debug path and wins over disabled`() {
        assertEquals(
            PipelineDecision.Fallback(PipelineFallbackReason.FORCED_ENTRY_MODE),
            decide(facts(forcedEntryMode = true)),
        )
        assertEquals(
            PipelineDecision.Fallback(PipelineFallbackReason.FORCED_ENTRY_MODE),
            decide(facts(enabled = false, forcedEntryMode = true, quickMode = false)),
        )
    }

    @Test
    fun `standard mode never enters the pipeline`() {
        assertEquals(
            PipelineDecision.Fallback(PipelineFallbackReason.STANDARD_MODE),
            decide(facts(quickMode = false)),
        )
    }

    @Test
    fun `multiple files fall back to the file page`() {
        assertEquals(
            PipelineDecision.Fallback(PipelineFallbackReason.MULTIPLE_FILES),
            decide(facts(fileCount = 2)),
        )
    }

    @Test
    fun `mixed batch falls back instead of guessing`() {
        assertEquals(
            PipelineDecision.Fallback(PipelineFallbackReason.MIXED_BATCH),
            decide(facts(mixed = true)),
        )
    }

    @Test
    fun `missing read permission falls back`() {
        assertEquals(
            PipelineDecision.Fallback(PipelineFallbackReason.READ_DENIED),
            decide(facts(readGranted = false)),
        )
    }

    @Test
    fun `missing identity falls back`() {
        assertEquals(
            PipelineDecision.Fallback(PipelineFallbackReason.NO_IDENTITY),
            decide(facts(identityExists = false)),
        )
    }

    @Test
    fun `encrypt without a recipient falls back`() {
        assertEquals(
            PipelineDecision.Fallback(PipelineFallbackReason.NO_RECIPIENT),
            decide(facts(mode = FileTaskMode.ENCRYPT, recipientAvailable = false)),
        )
    }

    @Test
    fun `encrypt refuses already encrypted files`() {
        assertEquals(
            PipelineDecision.Fallback(PipelineFallbackReason.ALREADY_ENCRYPTED),
            decide(facts(mode = FileTaskMode.ENCRYPT, encryptFileLooksEncrypted = true)),
        )
    }

    @Test
    fun `encrypt with unknown size falls back`() {
        assertEquals(
            PipelineDecision.Fallback(PipelineFallbackReason.SIZE_UNKNOWN),
            decide(facts(mode = FileTaskMode.ENCRYPT, encryptSize = null)),
        )
        assertEquals(
            PipelineDecision.Fallback(PipelineFallbackReason.SIZE_UNKNOWN),
            decide(facts(mode = FileTaskMode.ENCRYPT, encryptSize = -1)),
        )
    }

    @Test
    fun `encrypt above the plaintext limit falls back`() {
        assertEquals(
            PipelineDecision.Fallback(PipelineFallbackReason.TOO_LARGE),
            decide(
                facts(
                    mode = FileTaskMode.ENCRYPT,
                    encryptSize = FileCryptoEngine.MAX_PLAINTEXT_BYTES + 1,
                ),
            ),
        )
    }

    @Test
    fun `no external target falls back`() {
        assertEquals(
            PipelineDecision.Fallback(PipelineFallbackReason.NO_TARGET_APP),
            decide(facts(targetAvailable = false)),
        )
    }

    @Test
    fun `single decrypt file is auto`() {
        assertEquals(
            PipelineDecision.Auto(FileTaskMode.DECRYPT),
            decide(facts(mode = FileTaskMode.DECRYPT)),
        )
    }

    @Test
    fun `single encrypt file at the limit is auto`() {
        assertEquals(
            PipelineDecision.Auto(FileTaskMode.ENCRYPT),
            decide(
                facts(
                    mode = FileTaskMode.ENCRYPT,
                    encryptSize = FileCryptoEngine.MAX_PLAINTEXT_BYTES,
                ),
            ),
        )
    }

    private fun decide(facts: PipelineFacts): PipelineDecision =
        FilePipelinePolicy.decide(facts)

    private fun facts(
        enabled: Boolean = true,
        quickMode: Boolean = true,
        forcedEntryMode: Boolean = false,
        fileCount: Int = 1,
        mixed: Boolean = false,
        mode: FileTaskMode = FileTaskMode.DECRYPT,
        readGranted: Boolean = true,
        identityExists: Boolean = true,
        recipientAvailable: Boolean = true,
        encryptFileLooksEncrypted: Boolean = false,
        encryptSize: Long? = 1_024,
        targetAvailable: Boolean = true,
    ): PipelineFacts = PipelineFacts(
        enabled = enabled,
        quickMode = quickMode,
        forcedEntryMode = forcedEntryMode,
        fileCount = fileCount,
        mixed = mixed,
        mode = mode,
        readGranted = readGranted,
        identityExists = identityExists,
        recipientAvailable = recipientAvailable,
        encryptFileLooksEncrypted = encryptFileLooksEncrypted,
        encryptSize = encryptSize,
        targetAvailable = targetAvailable,
    )
}
