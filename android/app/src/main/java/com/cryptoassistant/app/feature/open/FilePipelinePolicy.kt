package com.cryptoassistant.app.feature.open

import com.cryptoassistant.app.feature.files.FileTaskMode
import crypto.file.FileCryptoEngine

/**
 * 一次外部文件请求里，流水线决策需要的全部事实。
 *
 * <p>这里刻意不出现 `Uri`、`Context` 或 `Intent`：文件流水线的判断必须在 JVM 上
 * 可直接测试，入口 Activity 只负责收集事实。所有字段都不含文件名、路径或内容。</p>
 */
internal data class PipelineFacts(
    /** 「文件快捷处理」开关；阶段 1 默认关闭。 */
    val enabled: Boolean,
    /** 当前是否是便捷模式；标准模式永远走现有文件页。 */
    val quickMode: Boolean,
    /** 调用方显式指定了模式（adb/测试用的 `entry_mode`），保持现有调试路径。 */
    val forcedEntryMode: Boolean,
    val fileCount: Int,
    /** 判型结果里同时出现解密与加密文件。 */
    val mixed: Boolean,
    /** 这一批统一的方向；[mixed] 为真时无意义。 */
    val mode: FileTaskMode,
    val readGranted: Boolean,
    val identityExists: Boolean,
    /** 加密方向：`lastContactId` 指向的联系人仍然存在。 */
    val recipientAvailable: Boolean,
    /** 加密方向：入口无法排除这个文件已经是密文（判型命中或读头失败）。 */
    val encryptFileLooksEncrypted: Boolean,
    /** 加密方向：系统提供的文件大小；未知为 null。 */
    val encryptSize: Long?,
    /** 系统里至少有一个应用能接收打开/分享的请求（排除本应用）。 */
    val targetAvailable: Boolean,
)

internal sealed interface PipelineDecision {

    data class Auto(val mode: FileTaskMode) : PipelineDecision

    data class Fallback(val reason: PipelineFallbackReason) : PipelineDecision
}

/**
 * 所有回退理由都是枚举，便于日志与测试；顺序即判断优先级。
 */
internal enum class PipelineFallbackReason {
    FORCED_ENTRY_MODE,
    DISABLED,
    STANDARD_MODE,
    MULTIPLE_FILES,
    MIXED_BATCH,
    READ_DENIED,
    NO_IDENTITY,
    NO_RECIPIENT,
    ALREADY_ENCRYPTED,
    SIZE_UNKNOWN,
    TOO_LARGE,
    NO_TARGET_APP,
}

/**
 * 便捷模式文件流水线的唯一决策点。
 *
 * <p>只回答“这次能不能自动处理”，不执行任何 IO，也不决定 HUD 怎么画。
 * 任一条不满足就回退到现有文件页；回退时原 Intent 原样转给 `MainActivity`，
 * 所以拒绝路径的行为与迁移前一致。</p>
 *
 * <p>解密结果的签名/发送者检查发生在解密之后，不在这里；那一步由 HUD 在
 * 阶段 1 第 3 小批里处理。</p>
 */
internal object FilePipelinePolicy {

    fun decide(
        facts: PipelineFacts,
        maxPlaintextBytes: Long = FileCryptoEngine.MAX_PLAINTEXT_BYTES,
    ): PipelineDecision {
        if (facts.forcedEntryMode) {
            return PipelineDecision.Fallback(PipelineFallbackReason.FORCED_ENTRY_MODE)
        }
        if (!facts.enabled) {
            return PipelineDecision.Fallback(PipelineFallbackReason.DISABLED)
        }
        if (!facts.quickMode) {
            return PipelineDecision.Fallback(PipelineFallbackReason.STANDARD_MODE)
        }
        if (facts.fileCount > 1) {
            return PipelineDecision.Fallback(PipelineFallbackReason.MULTIPLE_FILES)
        }
        if (facts.mixed) {
            return PipelineDecision.Fallback(PipelineFallbackReason.MIXED_BATCH)
        }
        if (!facts.readGranted) {
            return PipelineDecision.Fallback(PipelineFallbackReason.READ_DENIED)
        }
        if (!facts.identityExists) {
            return PipelineDecision.Fallback(PipelineFallbackReason.NO_IDENTITY)
        }

        if (facts.mode == FileTaskMode.ENCRYPT) {
            if (!facts.recipientAvailable) {
                return PipelineDecision.Fallback(PipelineFallbackReason.NO_RECIPIENT)
            }
            // 已经判型为密文的文件不会走到加密方向；这里防的是判型漏掉的二次加密。
            if (facts.encryptFileLooksEncrypted) {
                return PipelineDecision.Fallback(PipelineFallbackReason.ALREADY_ENCRYPTED)
            }
            val size = facts.encryptSize
            if (size == null || size < 0) {
                return PipelineDecision.Fallback(PipelineFallbackReason.SIZE_UNKNOWN)
            }
            if (size > maxPlaintextBytes) {
                return PipelineDecision.Fallback(PipelineFallbackReason.TOO_LARGE)
            }
        }

        if (!facts.targetAvailable) {
            return PipelineDecision.Fallback(PipelineFallbackReason.NO_TARGET_APP)
        }
        return PipelineDecision.Auto(facts.mode)
    }
}
