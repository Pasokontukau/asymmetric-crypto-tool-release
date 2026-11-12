package com.cryptoassistant.app.feature.files

import android.content.Context
import android.net.Uri

/**
 * 明文外发时的 MIME 推断。
 *
 * <p>只回答一件事：这份**明文**该以什么类型交给别的应用。`.aegix` 密文不参与推断，
 * 它对外始终是 `application/octet-stream`。</p>
 *
 * <p>顺序：本地补丁表（跨 ROM 结果稳定）→ 系统给出的具体类型 → `application/octet-stream`。
 * 系统表是 `MimeTypeMap`，各 ROM 裁剪程度不同（Markdown / 日志 / 代码经常缺），
 * 所以常用后缀以这里的表为准。</p>
 */
object FileMime {

    const val FALLBACK = "application/octet-stream"

    private val overrides: Map<String, String> = mapOf(
        // 文本 / 代码 / 配置
        "txt" to "text/plain",
        "log" to "text/plain",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "json" to "application/json",
        "csv" to "text/csv",
        "yaml" to "text/yaml",
        "yml" to "text/yaml",
        "ini" to "text/plain",
        "conf" to "text/plain",
        "xml" to "text/xml",
        "kt" to "text/plain",
        "java" to "text/plain",
        "py" to "text/x-python",
        "js" to "text/javascript",
        // 图片
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        // 文档
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        // 压缩包 / 安装包
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "gz" to "application/gzip",
        "tar" to "application/x-tar",
        "apk" to "application/vnd.android.package-archive",
        // 音视频
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
    )

    /** 纯函数版本，单测直接覆盖；[systemType] 由调用方从 ContentResolver 取。 */
    fun infer(displayName: String?, systemType: String?): String {
        val extension = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (extension.isNotEmpty()) {
            overrides[extension]?.let { return it }
            // 密文永远不猜：.aegix 的任何"可打开"猜测都会把用户引向失败的应用。
            if (EncryptedFileFormat.isEncryptedExtension(extension)) return FALLBACK
        }
        val system = systemType?.trim().orEmpty()
        return if (system.isNotEmpty() && system != FALLBACK) system else FALLBACK
    }

    /** 对 FileProvider URI 而言 `resolver.getType()` 就是 `FileProvider.getType()`（按后缀查 MimeTypeMap）。 */
    fun forResult(context: Context, uri: Uri, displayName: String?): String {
        val systemType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return infer(displayName, systemType)
    }
}
