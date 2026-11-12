package com.cryptoassistant.app.feature.open

import android.content.Context
import android.net.Uri
import com.cryptoassistant.app.feature.files.EncryptedFileFormat
import com.cryptoassistant.app.feature.files.FileTaskMode

/**
 * 一个外部文件的判型结果。
 *
 * @property by 只含枚举式短标记（`name:aegix` / `mime:cipher` / `default`…），可安全写日志。
 * @property nameSource 文件名来自哪里；只用于诊断，不记录文件名本身。
 */
internal data class EntryFile(
    val uri: Uri,
    val mode: FileTaskMode,
    val by: String,
    val confidence: EntryConfidence,
    val nameSource: IncomingModeResolver.EntryNameSource,
    /** 系统提供的显示名（未脱敏，只在内存里用于展示/写回），不写日志。 */
    val displayName: String? = null,
    val size: Long? = null,
    val mimeType: String? = null,
    /** 文件头探测结论；单文件入口总是探测，供流水线判断“能否确认是普通文件”。 */
    val probe: ProbeOutcome? = null,
)

/** 一次入口请求的整体判型；混合时由界面让用户选一组处理。 */
internal data class EntryPlan(
    val decrypt: List<EntryFile>,
    val encrypt: List<EntryFile>,
    val diagnostic: String,
) {
    val isMixed: Boolean get() = isMixed(decrypt.size, encrypt.size)

    val total: Int get() = decrypt.size + encrypt.size

    companion object {
        internal fun isMixed(decrypt: Int, encrypt: Int): Boolean = decrypt > 0 && encrypt > 0
    }
}

/**
 * 所有外部入口（VIEW / SEND / SEND_MULTIPLE）共用的模式策略。
 *
 * <p>规则：文件名 `.aegix` → 解密；其余 → 加密。没有 LOW 二选一：
 * 判不出来时按“否则加密”兜底，避免入口把用户挡在对话框前。</p>
 *
 * <p>文件头探测只作为补充：单文件总是探测；批量只对“没有名字 / octet-stream 且不是密文后缀”
 * 的文件探测，最多 [MAX_BATCH_PROBES] 个，避免一次分享 200 个文件逐个打开。</p>
 */
internal object IncomingEntryPolicy {

    private const val MAX_BATCH_PROBES = 8

    fun plan(context: Context, incoming: IncomingFiles, titleHint: String?): EntryPlan {
        val multi = incoming.uris.size > 1
        val facts = incoming.uris.map { uri ->
            IncomingModeResolver.inspect(
                context = context,
                uri = uri,
                readGranted = incoming.readGranted,
                titleHint = titleHint,
                probeAllowed = !multi,
            )
        }

        var probes = 0
        val entries = facts.map { fact ->
            val cipherName = fact.name
                ?.let { EncryptedFileFormat.isEncryptedFileName(it) }
                ?: false
            val needsProbe = !cipherName &&
                fact.probe == null &&
                incoming.readGranted &&
                probes < MAX_BATCH_PROBES &&
                (fact.name == null || isOctetStream(fact.systemType))
            val probe = if (needsProbe) {
                probes += 1
                IncomingModeResolver.probe(context, fact.uri, fact.size)
            } else {
                fact.probe
            }
            val decision = IncomingModeResolver.decideEntryMode(
                displayName = fact.name,
                systemType = fact.systemType,
                probe = probe,
                readGranted = incoming.readGranted,
            )
            val mode = modeFor(decision)
            EntryFile(
                uri = fact.uri,
                mode = mode,
                by = if (decision.mode == null) "default" else decision.reason,
                confidence = if (decision.mode == null) EntryConfidence.LOW else decision.confidence,
                nameSource = fact.nameSource,
                displayName = fact.name,
                size = fact.size,
                mimeType = fact.systemType,
                probe = probe,
            )
        }

        val decrypt = entries.filter { it.mode == FileTaskMode.DECRYPT }
        val encrypt = entries.filter { it.mode == FileTaskMode.ENCRYPT }
        val diagnostic = buildString {
            append("act=").append(incoming.action.substringAfterLast('.'))
            append(" n=").append(entries.size)
            append(" d=").append(decrypt.size)
            append(" e=").append(encrypt.size)
            append(" probe=").append(probes)
        }
        return EntryPlan(decrypt, encrypt, diagnostic)
    }

    /** 统一的兜底：判不出来时按“否则加密”，不再弹二选一。 */
    internal fun modeFor(decision: EntryDecision): FileTaskMode =
        decision.mode ?: FileTaskMode.ENCRYPT

    private fun isOctetStream(type: String?): Boolean =
        type?.trim()?.lowercase() == EncryptedFileFormat.MIME_OCTET_STREAM
}
