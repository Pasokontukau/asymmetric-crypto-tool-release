package com.cryptoassistant.app.feature.files

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 临时结果目录的只读统计；字节/文件数不包含 `.export-lease` 元数据。 */
data class FileTaskCacheUsage(
    val bytes: Long = 0L,
    val files: Int = 0,
    val directories: Int = 0,
    val activeLeases: Int = 0,
)

/** 手动全清的结果；失败项仍在目录里，UI 必须如实显示。 */
data class FileTaskCacheClearResult(
    val deletedDirectories: Int,
    val deletedFiles: Int,
    val failedEntries: Int,
    val freedBytes: Long,
    val remaining: FileTaskCacheUsage,
)

/**
 * 文件任务的临时目录约定：所有输出都在 `files/file-tasks/<task-id>/`，
 * FileProvider 只暴露这一棵子树，页面销毁或应用下次启动时整体清理。
 *
 * <p>例外是「交给其他应用打开」之后的目录：第三方可能在稍后才真正去读这个 URI
 * （WPS 的最近文件、图片查看器的重开），所以外发时留一个 [LEASE_FILE_NAME]
 * 记录过期时间，[cleanupStale] 在租约到期前不会删它。</p>
 */
object FileTaskStorage {

    const val DIRECTORY_NAME = "file-tasks"

    /** 导出后的保留时长：够对方再从「最近文件」打开一次，又不至于让明文留过夜。 */
    val EXPORT_LEASE_MILLIS: Long = TimeUnit.HOURS.toMillis(6)

    private const val LEASE_FILE_NAME = ".export-lease"

    /** 与桌面端一致的密文文件名前缀：文件名本身不携带任何原始信息。 */
    const val ENCRYPTED_NAME_PREFIX = "secure_file_"

    const val ARCHIVE_NAME_PREFIX = "archive_"

    private val ENCRYPTED_NAME_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)

    /**
     * 清理应用上次异常退出留下的任务目录。
     *
     * <p>单个目录删除失败不能让冷启动崩掉（磁盘/权限异常时否则会把整个启动带崩），
     * 所以每个子目录单独 [runCatching]；仍在外发租约内的目录跳过。</p>
     *
     * @return 实际删掉的目录数，供 `app.cleanup` 事件记录（不写目录名）。
     */
    fun cleanupStale(root: File, now: Long = System.currentTimeMillis()): Int {
        val children = root.listFiles() ?: return 0
        var deleted = 0
        children.forEach { child ->
            if (child.isDirectory && hasActiveLease(child, now)) return@forEach
            if (runCatching { deleteRecursively(child) }.getOrDefault(false)) deleted++
        }
        return deleted
    }

    /**
     * 递归删除并返回真实结果；单个子项失败不影响其他子项，但不会再假装成功。
     * 文件本来就不存在时视为已清理（幂等），方便重试。
     */
    fun deleteRecursively(file: File): Boolean {
        var childrenDeleted = true
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                if (!deleteRecursively(child)) childrenDeleted = false
            }
        }
        if (!file.exists()) return childrenDeleted
        val deleted = runCatching { file.delete() }.getOrDefault(false)
        return deleted && childrenDeleted
    }

    /**
     * 统计 `file-tasks/` 的直接子目录、递归文件数与字节数。
     * `.export-lease` 只是元数据，不计入“文件数/大小”，但活跃租约数量单独返回。
     */
    fun inspect(root: File, now: Long = System.currentTimeMillis()): FileTaskCacheUsage {
        val children = root.listFiles() ?: return FileTaskCacheUsage()
        var bytes = 0L
        var files = 0
        var directories = 0
        var activeLeases = 0
        children.forEach { child ->
            if (child.isDirectory) {
                directories++
                if (hasActiveLease(child, now)) activeLeases++
            }
            val measured = measure(child)
            bytes += measured.first
            files += measured.second
        }
        return FileTaskCacheUsage(
            bytes = bytes,
            files = files,
            directories = directories,
            activeLeases = activeLeases,
        )
    }

    /**
     * 用户主动“清除缓存”：删除 `root` 下的全部直接子项，**包括仍在租约期的目录**。
     * 只动这一个目录，不碰 keys/logs/SharedPreferences；[delete] 可在测试中注入失败。
     */
    fun clearAll(
        root: File,
        now: Long = System.currentTimeMillis(),
        delete: (File) -> Boolean = ::deleteRecursively,
    ): FileTaskCacheClearResult {
        val before = inspect(root, now)
        val children = root.listFiles()
            ?: return FileTaskCacheClearResult(0, 0, 0, 0L, before)
        var deletedDirectories = 0
        var deletedFiles = 0
        var failedEntries = 0
        children.forEach { child ->
            val isDirectory = child.isDirectory
            val deleted = runCatching { delete(child) }.getOrDefault(false)
            if (!deleted) {
                failedEntries++
            } else if (isDirectory) {
                deletedDirectories++
            } else {
                deletedFiles++
            }
        }
        val after = inspect(root, now)
        return FileTaskCacheClearResult(
            deletedDirectories = deletedDirectories,
            deletedFiles = deletedFiles,
            failedEntries = failedEntries,
            freedBytes = (before.bytes - after.bytes).coerceAtLeast(0L),
            remaining = after,
        )
    }

    private fun measure(file: File): Pair<Long, Int> {
        if (file.isFile) {
            if (file.name == LEASE_FILE_NAME) return 0L to 0
            return file.length().coerceAtLeast(0L) to 1
        }
        val children = file.listFiles() ?: return 0L to 0
        var bytes = 0L
        var files = 0
        children.forEach { child ->
            val measured = measure(child)
            bytes += measured.first
            files += measured.second
        }
        return bytes to files
    }

    /**
     * 记录「这个任务目录里的结果已经交给别的应用了」。
     * 只写一个过期时间戳，不写文件名、不写明文。
     */
    fun markExported(taskDirectory: File, now: Long = System.currentTimeMillis()) {
        runCatching {
            taskDirectory.mkdirs()
            File(taskDirectory, LEASE_FILE_NAME).writeText((now + EXPORT_LEASE_MILLIS).toString())
        }
    }

    fun hasActiveLease(taskDirectory: File, now: Long = System.currentTimeMillis()): Boolean {
        val lease = File(taskDirectory, LEASE_FILE_NAME)
        if (!lease.isFile) return false
        val expiresAt = runCatching { lease.readText().trim().toLong() }.getOrNull() ?: return false
        return expiresAt > now
    }

    /**
     * 密文文件名：`secure_file_<yyyyMMdd_HHmmss>.aegix`。
     *
     * <p>原文件名（含后缀）只以加密形式保存在文件头里（`FileCryptoEngine.encrypt` 的
     * `originalFileName`），落盘、分享、发微信时看到的永远只有这个脱敏名字。
     * 输出统一使用 [EncryptedFileFormat.EXTENSION]；旧 `.enc` 不再被识别为密文。</p>
     */
    fun encryptedFileBaseName(time: LocalDateTime): String =
        ENCRYPTED_NAME_PREFIX + ENCRYPTED_NAME_STAMP.format(time)

    /**
     * 多选文件打包时的 ZIP 根名：`archive_<yyyyMMdd_HHmmss>`。
     * 文件夹归档使用文件夹原名，不调用这里。
     */
    fun archiveBaseName(time: LocalDateTime): String =
        ARCHIVE_NAME_PREFIX + ENCRYPTED_NAME_STAMP.format(time)

    fun encryptedOutputName(
        directory: File,
        time: LocalDateTime = LocalDateTime.now(),
        reservedNames: Set<String> = emptySet(),
    ): String = uniqueFileName(
        directory,
        encryptedFileBaseName(time),
        EncryptedFileFormat.EXTENSION,
        reservedNames,
    )

    /**
     * 桌面端 `getUniqueFileName` 的等价物：重名时追加 `_2`、`_3`…
     *
     * <p>[reservedNames] 供批量任务使用：同一批里的每个文件写进各自的任务目录，
     * 只靠目录内的文件存在性无法发现“另一个目录已经用了同一秒的时间戳名”。
     * 调用方把已用过的名字传进来，保证分享到聊天应用时不会出现两个同名文件。</p>
     */
    fun uniqueFileName(
        directory: File,
        baseName: String,
        extension: String,
        reservedNames: Set<String> = emptySet(),
    ): String {
        val first = baseName + extension
        if (!File(directory, first).exists() && first !in reservedNames) return first
        var index = 2
        while (true) {
            val candidate = baseName + "_" + index + extension
            if (!File(directory, candidate).exists() && candidate !in reservedNames) return candidate
            index++
        }
    }

    /**
     * 系统返回的文件名可能包含路径分隔符或控制字符；只保留安全字符，
     * 并保留扩展名，避免超出文件系统长度限制。
     */
    fun sanitizeFileName(raw: String?, fallback: String): String {
        val candidate = raw?.trim().orEmpty().ifEmpty { fallback }
        val cleaned = buildString {
            candidate.forEach { character ->
                append(
                    if (character == '/' || character == '\\' || character.isISOControl()) {
                        '_'
                    } else {
                        character
                    },
                )
            }
        // 保留 .gitignore/.nomedia 这类前导点；只清前导空格和尾随空格/点。
        }.trimStart(' ').trimEnd(' ', '.')
        val safe = cleaned.ifEmpty { fallback }
        if (safe.length <= MAX_NAME_LENGTH) return safe

        val dot = safe.lastIndexOf('.')
        val extension = if (dot > 0 && safe.length - dot <= MAX_EXTENSION_LENGTH) {
            safe.substring(dot)
        } else {
            ""
        }
        return safe.take(MAX_NAME_LENGTH - extension.length) + extension
    }

    private const val MAX_NAME_LENGTH = 120
    private const val MAX_EXTENSION_LENGTH = 16
}
