package com.cryptoassistant.app.data.log

import com.cryptoassistant.app.feature.files.DirectoryLimitException
import com.cryptoassistant.app.feature.files.DirectoryScanException
import crypto.file.FileCancelledException
import crypto.file.FileFormatException
import crypto.file.UnsupportedVolumeFileException
import crypto.file.WrongRecipientException
import crypto.volume.UnsupportedVolumeVersionException
import crypto.volume.VolumeFormatException
import crypto.volume.VolumeIntegrityException
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException

/**
 * 错误类别映射（日志计划 §5.7）。
 *
 * <p>日志里只写 `kind=` 与异常类名，不写 `Throwable.message`：SAF/IO 异常的原始消息
 * 经常携带路径或文件名，一旦写进去就突破了隐私边界。可预期的用户/文件/权限错误
 * 用 WARN，只有未知异常或内部不变量失败才用 ERROR。</p>
 */
object LogErrorKind {

    const val CANCELLED = "cancelled"
    const val FORMAT = "format"
    const val WRONG_RECIPIENT = "wrong_recipient"
    const val INTEGRITY = "integrity"
    const val VOLUME = "volume"
    const val LIMIT = "limit"
    const val SCAN = "scan"
    const val PERMISSION = "permission"
    const val IO = "io"
    const val CRYPTO = "crypto"
    const val INPUT = "input"
    const val KEY = "key"
    const val STATE = "state"
    const val WINDOW = "window"
    const val UNKNOWN = "unknown"

    /**
     * 异常/场景对应的稳定类别名。
     *
     * <p>顺序敏感：音量/错接收方/取消都是 `FileFormatException` 或 `IOException` 的
     * 子类，必须先判定子类，否则会被父类分支吃掉。</p>
     */
    fun of(error: Throwable): String = when (error) {
        is CancellationException,
        is FileCancelledException -> CANCELLED

        is WrongRecipientException -> WRONG_RECIPIENT
        is VolumeIntegrityException -> INTEGRITY

        is UnsupportedVolumeVersionException,
        is VolumeFormatException -> VOLUME

        is UnsupportedVolumeFileException,
        is FileFormatException -> FORMAT

        is DirectoryLimitException -> LIMIT
        is DirectoryScanException -> SCAN
        is SecurityException -> PERMISSION
        is IOException -> IO
        // 文本解密失败多是「不是发给本机」或「被篡改」，属于预期拒绝；
        // 缺少密钥的 IllegalStateException 仍算内部状态失败（ERROR）。
        is GeneralSecurityException -> CRYPTO
        is IllegalArgumentException -> INPUT
        is IllegalStateException -> STATE
        else -> UNKNOWN
    }

    /** 预期内的拒绝（格式/权限/错接收方/超限/取消/密码学拒绝）用 WARN；其余用 ERROR。 */
    fun isExpected(error: Throwable): Boolean = when (of(error)) {
        CANCELLED, FORMAT, WRONG_RECIPIENT, INTEGRITY, VOLUME, LIMIT, SCAN,
        PERMISSION, IO, CRYPTO, INPUT,
        -> true

        else -> false
    }

    /** 结构化错误字段：类别 + 异常类名（不含消息）。 */
    fun detail(error: Throwable): String =
        "kind=${of(error)} class=${error.javaClass.simpleName}"
}

/**
 * 统一的失败事件记录：预期内用 WARN，未知异常用 ERROR；字段只含 kind 与异常类名。
 *
 * <p>调用方不再自己拼 `Throwable.message`，这是日志计划 §7.3 的硬边界。</p>
 */
fun logFailure(event: String, error: Throwable, detail: String? = null) {
    val fields = listOfNotNull(detail?.takeIf { it.isNotBlank() }, LogErrorKind.detail(error))
        .joinToString(" ")
    if (LogErrorKind.isExpected(error)) {
        AppLog.warn(event, fields)
    } else {
        AppLog.error(event, error, fields)
    }
}

/** 日志用的粗粒度字段，避免写入精确长度、MIME 全串等可识别内容。 */
object LogFields {

    /** 文本长度桶：INFO/DEBUG 只用桶，不写精确长度（日志计划 §5.3）。 */
    fun lengthBucket(length: Int): String = when {
        length <= 0 -> "0"
        length <= 16 -> "1-16"
        length <= 64 -> "17-64"
        length <= 256 -> "65-256"
        else -> ">256"
    }

    /** MIME 大类：只写族名，不写发送方给的完整串（它可能带文件名）。 */
    fun mimeFamily(mime: String?): String {
        val value = mime?.trim()?.lowercase().orEmpty()
        if (value.isEmpty()) return "unknown"
        return when {
            value == "application/octet-stream" -> "octet-stream"
            value == "application/zip" -> "zip"
            value.startsWith("image/") -> "image"
            value.startsWith("text/") -> "text"
            value.startsWith("audio/") -> "audio"
            value.startsWith("video/") -> "video"
            value.contains("aegix") || value.contains("encrypted") -> "encrypted"
            else -> "other"
        }
    }
}
