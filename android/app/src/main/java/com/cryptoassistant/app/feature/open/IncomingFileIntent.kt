package com.cryptoassistant.app.feature.open

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import androidx.core.content.IntentCompat
import com.cryptoassistant.app.feature.files.FileImportLimits
import com.cryptoassistant.app.feature.files.FileTaskMode

/**
 * 从系统入口收到的一次文件请求。
 *
 * @property action 原始的 ACTION_VIEW / ACTION_SEND / ACTION_SEND_MULTIPLE。
 * @property mode 这次请求应当进入的模式：由用户点选的入口决定；没有入口信息时
 *   VIEW 按解密、SEND 按加密兜底。页面只让与自身路由一致的那一页消费它。
 * @property uris 只包含 `content://`，已去重；VIEW 最多 1 个，SEND 可多个。
 * @property readGranted 是否每个 URI 都拿到了读权限；单项失败仍会进入队列并给出逐项原因。
 * @property persistable 发送方是否给了可持久化授权位（微信通常不给）。
 */
data class IncomingFiles(
    val action: String,
    val mode: FileTaskMode,
    val uris: List<Uri>,
    val readGranted: Boolean,
    val persistable: Boolean,
) {
    val isView: Boolean get() = action == Intent.ACTION_VIEW
    val isSend: Boolean
        get() = action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
}

/**
 * 解析结果：成功或明确拒绝。
 *
 * <p>[diagnostic] 只含 action、数量、MIME、categories、flags、URI 的 scheme/authority
 * 与可用时的调用方包名；不包含完整 URI、路径、文件名或任何内容。</p>
 */
sealed interface IncomingParseResult {

    data class Accepted(
        val request: IncomingFiles,
        val diagnostic: String,
    ) : IncomingParseResult

    data class Rejected(
        val reason: String,
        val message: String,
        val diagnostic: String,
    ) : IncomingParseResult
}

/**
 * 把所有「外部传入文件」的 Intent 形态收敛到一个入口：
 * `Intent.data`、`EXTRA_STREAM`、`ClipData`。
 *
 * <p>只接受 `content://`：`file://` 在 targetSdk 30+ 上发送方基本不再使用，且本应用
 * 没有存储权限也读不到；`http(s)://` 更不该被当成下载器。不认识的 action 返回 null。</p>
 *
 * <p>数量上限在解析层先拦一次；已知总大小上限由 ViewModel 在读取元数据时再拦一次。</p>
 */
fun parseIncomingFiles(
    context: Context,
    intent: Intent?,
    callerPackage: String? = null,
    requestedMode: FileTaskMode? = null,
): IncomingParseResult? {
    val action = intent?.action ?: return null
    if (action != Intent.ACTION_VIEW &&
        action != Intent.ACTION_SEND &&
        action != Intent.ACTION_SEND_MULTIPLE
    ) {
        return null
    }

    val rawUris = intent.collectUris(action)
    val contentUris = rawUris
        .filter { it.scheme == "content" }
        .distinctBy { it.toString() }

    // VIEW 的语义是「打开一个文件」；即使发送方误塞了多个，也只取第一个。
    val acceptedUris = if (action == Intent.ACTION_VIEW) {
        contentUris.take(1)
    } else {
        contentUris
    }

    if (acceptedUris.isEmpty()) {
        val reason = if (rawUris.isEmpty()) "no_uri" else "not_content"
        val message = when (action) {
            Intent.ACTION_VIEW -> "没有收到可读取的文件；请重新选择「用其他应用打开」"
            else -> "这次分享没有包含文件；请选择文件或图片后再分享"
        }
        return IncomingParseResult.Rejected(
            reason = reason,
            message = message,
            diagnostic = importDiagnostic(intent, callerPackage, 0, false),
        )
    }

    if (FileImportLimits.isTooManyFiles(acceptedUris.size)) {
        return IncomingParseResult.Rejected(
            reason = "too_many",
            message = "一次最多导入 ${FileImportLimits.MAX_FILES} 个文件，请分批分享",
            diagnostic = importDiagnostic(
                intent,
                callerPackage,
                acceptedUris.size,
                false,
            ),
        )
    }

    val readGranted = acceptedUris.all { intent.hasReadPermission(context, it) }
    val mode = requestedMode ?: if (action == Intent.ACTION_VIEW) {
        FileTaskMode.DECRYPT
    } else {
        FileTaskMode.ENCRYPT
    }
    return IncomingParseResult.Accepted(
        request = IncomingFiles(
            action = action,
            mode = mode,
            uris = acceptedUris,
            readGranted = readGranted,
            persistable = intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0,
        ),
        diagnostic = importDiagnostic(
            intent,
            callerPackage,
            acceptedUris.size,
            readGranted,
        ),
    )
}

private fun Intent.collectUris(action: String): List<Uri> {
    val collected = mutableListOf<Uri>()
    when (action) {
        Intent.ACTION_VIEW -> {
            data?.let { collected += it }
            collected += streamUris()
            collected += clipDataUris()
        }

        else -> {
            // SEND 的标准位置是 EXTRA_STREAM；data 只作为部分发送方的兜底。
            collected += streamUris()
            collected += clipDataUris()
            data?.let { collected += it }
        }
    }
    return collected
}

private fun Intent.streamUris(): List<Uri> = buildList {
    runCatching {
        IntentCompat.getParcelableExtra(this@streamUris, Intent.EXTRA_STREAM, Uri::class.java)
    }.getOrNull()?.let(::add)
    runCatching {
        IntentCompat.getParcelableArrayListExtra(
            this@streamUris,
            Intent.EXTRA_STREAM,
            Uri::class.java,
        )
    }.getOrNull()?.filterIsInstance<Uri>()?.let(::addAll)
}

private fun Intent.clipDataUris(): List<Uri> {
    val clip = clipData ?: return emptyList()
    return buildList {
        for (index in 0 until clip.itemCount) {
            clip.getItemAt(index).uri?.let(::add)
        }
    }
}

private fun Intent.hasReadPermission(context: Context, uri: Uri): Boolean =
    flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 ||
        context.checkUriPermission(
            uri,
            Process.myPid(),
            Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED

private fun importDiagnostic(
    intent: Intent,
    callerPackage: String?,
    acceptedCount: Int,
    readGranted: Boolean,
): String {
    val action = intent.action
    val rawUris = if (action == null) emptyList() else intent.collectUris(action)
    val first = rawUris.firstOrNull { it.scheme == "content" } ?: rawUris.firstOrNull()
    val scheme = first?.scheme?.take(20) ?: "-"
    val authority = first?.authority?.take(60)?.ifEmpty { null } ?: "-"
    val mime = intent.type?.trim()?.take(60)?.ifEmpty { null } ?: "-"
    val categories = intent.categories
        ?.map { it.substringAfterLast('.') }
        ?.sorted()
        ?.joinToString("|")
        ?.ifEmpty { null }
        ?: "-"
    val from = callerPackage?.trim()?.take(60)?.ifEmpty { null } ?: "?"
    val persist = if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) 1 else 0
    return buildString {
        append("act=").append(shortAction(action))
        append(" n=").append(acceptedCount)
        append(" from=").append(from)
        append(" scheme=").append(scheme)
        append(" auth=").append(authority)
        append(" mime=").append(mime)
        append(" cat=").append(categories)
        append(" flg=0x").append(Integer.toHexString(intent.flags))
        append(" clip=").append(intent.clipData?.itemCount ?: 0)
        append(" stream=").append(intent.streamUris().size)
        append(" read=").append(if (readGranted) 1 else 0)
        append(" persist=").append(persist)
    }
}

private fun shortAction(action: String?): String = when (action) {
    Intent.ACTION_VIEW -> "VIEW"
    Intent.ACTION_SEND -> "SEND"
    Intent.ACTION_SEND_MULTIPLE -> "SEND_MULTIPLE"
    null -> "?"
    else -> action.substringAfterLast('.')
}
