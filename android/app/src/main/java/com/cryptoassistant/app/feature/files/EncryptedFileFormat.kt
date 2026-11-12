package com.cryptoassistant.app.feature.files

/**
 * 加密文件扩展名与 MIME 的唯一来源。
 *
 * <p>当前只使用 [EXTENSION]；旧 `.enc` 后缀不再识别、不再展示，也不再有旧 MIME。
 * 扩展名不参与明文 MIME 推断，避免把密文交给“能打开”的应用。</p>
 */
object EncryptedFileFormat {

    const val EXTENSION = ".aegix"

    /** 规范 MIME；外部应用不一定使用，但 Manifest 接收过滤器需要它。 */
    const val MIME = "application/vnd.aegix"
    const val MIME_X_AEGIX = "application/x-aegix"
    const val MIME_OCTET_STREAM = "application/octet-stream"

    fun isEncryptedFileName(name: String): Boolean =
        name.endsWith(EXTENSION, ignoreCase = true)

    /** [extension] 可以带点，也可以不带点；大小写不敏感。 */
    fun isEncryptedExtension(extension: String): Boolean =
        extension.lowercase().removePrefix(".") == EXTENSION.removePrefix(".")
}
