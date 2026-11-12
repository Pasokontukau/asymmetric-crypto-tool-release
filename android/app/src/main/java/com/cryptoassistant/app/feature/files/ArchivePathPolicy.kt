package com.cryptoassistant.app.feature.files

/**
 * 归档层内部错误：路径越界、ZIP 超限、输出已存在等。
 *
 * <p>纯 JVM 层不依赖 Android 资源；P2-C 负责把消息映射成已本地化的
 * [FileTaskError]，这里只保留可测试的技术原因。</p>
 */
class ArchiveBuildException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * ZIP 条目路径规则（修订 3 §5.3）。
 *
 * <p>SAF 返回的 `relativePath` 是原始显示名，不能直接拼进 ZIP：每段都要单独清洗，
 * 禁止绝对路径、`..`/`.` 段、反斜杠、控制字符和 NUL。清洗后的段保留前导点，
 * 因此 `.gitignore` 不会被吞掉；尾随空格/点会去掉，避免 Windows 解压时被截断。</p>
 */
object ArchivePathPolicy {

    const val MAX_SEGMENT_LENGTH = 120
    const val MAX_SEGMENTS = 32
    const val MAX_PATH_BYTES = 1024

    private const val MAX_EXTENSION_LENGTH = 16
    private const val FALLBACK = "_"
    private const val FALLBACK_ROOT = "archive"

    /** 文件夹/多选归档的根段；`Photos.zip` 与 `Photos` 都得到 `Photos`。 */
    fun rootSegment(rootName: String): String {
        val withoutZip = if (rootName.endsWith(".zip", ignoreCase = true)) {
            rootName.dropLast(4)
        } else {
            rootName
        }
        if (withoutZip.isBlank()) return FALLBACK_ROOT
        return sanitizeSegment(withoutZip)
    }

    /** 归档文件名，例如 `Photos.zip`。 */
    fun archiveName(rootName: String): String = rootSegment(rootName) + ".zip"

    /**
     * 清洗单个路径段。
     *
     * <p>与 [FileTaskStorage.sanitizeFileName] 的关键区别：这里只去尾随空格/点，
     * 保留前导点，供 `.gitignore`/`.nomedia` 这类文件使用。</p>
     */
    fun sanitizeSegment(raw: String?): String {
        val replaced = buildString {
            raw.orEmpty().forEach { character ->
                append(
                    if (character == '/' || character == '\\' || character.isISOControl()) {
                        '_'
                    } else {
                        character
                    },
                )
            }
        }
        // 只去掉前导空格，保留前导点（.gitignore）；尾随空格/点全部去掉。
        val cleaned = replaced.trimStart(' ').trimEnd(' ', '.')
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return FALLBACK
        if (cleaned.length <= MAX_SEGMENT_LENGTH) return cleaned

        val dot = cleaned.lastIndexOf('.')
        val extension = if (dot > 0 && cleaned.length - dot <= MAX_EXTENSION_LENGTH) {
            cleaned.substring(dot)
        } else {
            ""
        }
        return cleaned.take(MAX_SEGMENT_LENGTH - extension.length) + extension
    }

    /**
     * 清洗相对路径；每段单独处理，返回使用 `/` 的安全路径。
     *
     * @throws ArchiveBuildException 段数或 UTF-8 字节数超过上限。
     */
    fun sanitizeRelativePath(raw: String?): String {
        val value = raw.orEmpty()
        val rawSegments = if (value.isEmpty()) listOf(FALLBACK) else value.split('/')
        if (rawSegments.size > MAX_SEGMENTS) {
            throw ArchiveBuildException("归档路径超过 $MAX_SEGMENTS 段")
        }
        val path = rawSegments.joinToString("/") { sanitizeSegment(it) }
        if (path.toByteArray(Charsets.UTF_8).size > MAX_PATH_BYTES) {
            throw ArchiveBuildException("归档路径超过 $MAX_PATH_BYTES 字节")
        }
        return path
    }

    /** 按“根段 + 相对路径”再校验整条路径的段数和 UTF-8 字节数。 */
    fun validateEntryPath(root: String, relative: String) {
        val full = "$root/$relative"
        if (full.split('/').size > MAX_SEGMENTS) {
            throw ArchiveBuildException("归档路径超过 $MAX_SEGMENTS 段")
        }
        if (full.toByteArray(Charsets.UTF_8).size > MAX_PATH_BYTES) {
            throw ArchiveBuildException("归档路径超过 $MAX_PATH_BYTES 字节")
        }
    }
}
