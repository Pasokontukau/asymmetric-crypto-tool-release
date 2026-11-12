package com.cryptoassistant.app.data.log

/**
 * 只用于异常/崩溃消息的脱敏器。
 *
 * <p>日志的结构化字段由调用方负责保持安全；这里额外删掉 URI、绝对路径、
 * 长 Base64、PEM 与控制字符，避免异常消息把文件位置或密钥样式内容带进去。</p>
 */
object LogSanitizer {

    const val MAX_MESSAGE_CHARS = 300

    private val pem = Regex("""-----BEGIN [^-]+-----[\s\S]*?-----END [^-]+-----""")
    private val uri = Regex("""(?i)\b(?:content|file|https?)://\S+""")
    private val windowsPath = Regex("""(?i)\b[A-Za-z]:\\[^\s]+""")
    // 至少包含“/目录/文件”两段，避免把 MIME 通配符 */*、image/* 误判成路径。
    private val posixPath = Regex("""(?<![\w:])/(?:[^\s/]+/)+[^\s]*""")
    private val longBase64 = Regex("""\b[A-Za-z0-9+/]{64,}={0,2}\b""")
    private val whitespace = Regex("""\s+""")

    fun sanitize(message: String?, maxChars: Int = MAX_MESSAGE_CHARS): String? {
        val raw = message?.takeIf { it.isNotBlank() } ?: return null
        val cleaned = raw
            .replace(pem, "<redacted>")
            .replace(uri, "<uri>")
            .replace(windowsPath, "<path>")
            .replace(posixPath, "<path>")
            .replace(longBase64, "<redacted>")
            .replace(whitespace, " ")
            .trim()
        if (cleaned.isBlank()) return null
        return cleaned.take(maxChars.coerceAtLeast(1))
    }
}
