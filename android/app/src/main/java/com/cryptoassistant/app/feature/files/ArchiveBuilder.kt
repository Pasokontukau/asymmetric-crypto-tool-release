package com.cryptoassistant.app.feature.files

import crypto.file.FileCancelledException
import crypto.volume.VolumePartPlan
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 一个待归档条目。SAF 的 Uri/ContentResolver 只留在 P2-C，
 * 这里用 `InputStream` + 相对路径，JVM 测试可以直接构造内存流。
 */
data class ArchiveSource(
    val relativePath: String,
    val size: Long? = null,
    val open: () -> InputStream,
)

data class ArchiveBuildRequest(
    val output: File,
    val rootName: String,
    val sources: List<ArchiveSource>,
    val emptyDirectories: List<String> = emptyList(),
    /** 文件夹归档为 true（按路径排序）；多选文件为 false（保留选择顺序）。 */
    val sourceIsFolder: Boolean,
    val volumeLimitBytes: Long,
    val rsaBits: Int = 4096,
    val packedAtMillis: Long = System.currentTimeMillis(),
)

data class ArchiveBuildResult(
    val zipFile: File,
    val archiveName: String,
    val archiveSize: Long,
    val archiveSha256: String,
    val sourceFileCount: Int,
    val sourceIsFolder: Boolean,
    val plan: VolumePartPlan,
    /** 与 `plan` 的卷号一一对应，1-based；第 0 项是第 1 卷。 */
    val partSha256: List<String>,
)

/**
 * 纯 JVM 的归档构建器：写一个 ZIP，同时算整包 SHA-256；写完后再按
 * [ArchivePartPolicy] 计算每卷边界与每卷 SHA-256。
 *
 * <p>不生成任何“明文分片”文件：ZIP 是唯一明文临时产物，由
 * [ArchiveWorkspace] 管理并在 P2-C 收尾删除。分卷 SHA 通过分块读取 ZIP 计算。</p>
 */
object ArchiveBuilder {

    private const val BUFFER_SIZE = 64 * 1024

    /**
     * @param cancelled 每写一个文件/每个数据块检查一次；取消时删除未完成的 ZIP。
     * @param onProgress `(已读源字节, 已写文件数)`，不承诺总进度百分比。
     */
    fun build(
        request: ArchiveBuildRequest,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: ((Long, Int) -> Unit)? = null,
    ): ArchiveBuildResult {
        require(request.volumeLimitBytes > 0) { "单卷上限必须大于 0" }
        if (request.output.exists()) {
            throw ArchiveBuildException("归档输出已存在: ${request.output.name}")
        }
        val parent = request.output.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("无法创建归档目录: $parent")
        }
        checkCancelled(cancelled)

        val root = ArchivePathPolicy.rootSegment(request.rootName)
        val archiveName = "$root.zip"
        val digest = MessageDigest.getInstance("SHA-256")
        var bytesWritten = 0L
        var filesWritten = 0
        var success = false

        try {
            FileOutputStream(request.output).use { raw ->
                ZipOutputStream(DigestOutputStream(raw, digest)).use { zip ->
                    zip.setLevel(Deflater.BEST_SPEED)
                    writeDirectory(zip, "$root/", request.packedAtMillis)

                    val usedPaths = mutableSetOf<String>()
                    request.emptyDirectories.forEach { rawDirectory ->
                        checkCancelled(cancelled)
                        val raw = rawDirectory.trimEnd('/')
                        if (raw.isBlank()) return@forEach
                        val relative = ArchivePathPolicy.sanitizeRelativePath(raw)
                        ArchivePathPolicy.validateEntryPath(root, relative)
                        if (!usedPaths.add(relative.lowercase(Locale.US))) return@forEach
                        writeDirectory(zip, "$root/$relative/", request.packedAtMillis)
                    }

                    val ordered = if (request.sourceIsFolder) {
                        request.sources.sortedWith(
                            compareBy(String.CASE_INSENSITIVE_ORDER) { it.relativePath },
                        )
                    } else {
                        request.sources
                    }

                    ordered.forEach { source ->
                        checkCancelled(cancelled)
                        val relative = ArchivePathPolicy.sanitizeRelativePath(source.relativePath)
                        ArchivePathPolicy.validateEntryPath(root, relative)
                        val safeRelative = uniqueRelative(relative, usedPaths)
                        ArchivePathPolicy.validateEntryPath(root, safeRelative)
                        val entry = ZipEntry("$root/$safeRelative")
                        entry.time = request.packedAtMillis
                        zip.putNextEntry(entry)
                        source.open().use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                checkCancelled(cancelled)
                                bytesWritten += read
                                if (bytesWritten > ArchivePartPolicy.MAX_ZIP_BYTES) {
                                    throw ArchiveBuildException("ZIP 超过 4 GiB 上限")
                                }
                                zip.write(buffer, 0, read)
                            }
                        }
                        zip.closeEntry()
                        filesWritten++
                        onProgress?.invoke(bytesWritten, filesWritten)
                    }
                }
            }

            val archiveSize = request.output.length()
            val plan = ArchivePartPolicy.plan(
                zipBytes = archiveSize,
                volumeLimitBytes = request.volumeLimitBytes,
                archiveName = archiveName,
                rsaBits = request.rsaBits,
            )
            val partSha = computePartSha(request.output, plan, cancelled)
            val archiveSha = hex(digest.digest())
            success = true
            return ArchiveBuildResult(
                zipFile = request.output,
                archiveName = archiveName,
                archiveSize = archiveSize,
                archiveSha256 = archiveSha,
                sourceFileCount = filesWritten,
                sourceIsFolder = request.sourceIsFolder,
                plan = plan,
                partSha256 = partSha,
            )
        } finally {
            if (!success) request.output.delete()
        }
    }

    private fun writeDirectory(zip: ZipOutputStream, name: String, time: Long) {
        val entry = ZipEntry(if (name.endsWith('/')) name else "$name/")
        entry.time = time
        zip.putNextEntry(entry)
        zip.closeEntry()
    }

    /**
     * 大小写不敏感去重；冲突时在最后一段扩展名前追加 `_2`、`_3`…
     * `report.pdf` → `report_2.pdf`，`.gitignore` → `.gitignore_2`。
     */
    private fun uniqueRelative(relative: String, used: MutableSet<String>): String {
        if (used.add(relative.lowercase(Locale.US))) return relative
        var index = 2
        while (true) {
            val candidate = appendIndex(relative, index)
            if (used.add(candidate.lowercase(Locale.US))) return candidate
            index++
        }
    }

    private fun appendIndex(path: String, index: Int): String {
        val slash = path.lastIndexOf('/')
        val directory = if (slash >= 0) path.substring(0, slash + 1) else ""
        val name = if (slash >= 0) path.substring(slash + 1) else path
        val dot = name.lastIndexOf('.')
        return if (dot > 0) {
            directory + name.substring(0, dot) + "_" + index + name.substring(dot)
        } else {
            directory + name + "_" + index
        }
    }

    private fun checkCancelled(cancelled: AtomicBoolean) {
        if (cancelled.get()) throw FileCancelledException("已取消归档")
    }

    /**
     * 一次分块顺序读取 ZIP，按 [VolumePartPlan] 的容量切段，避免为每卷重新打开文件。
     */
    private fun computePartSha(
        zipFile: File,
        plan: VolumePartPlan,
        cancelled: AtomicBoolean,
    ): List<String> {
        val partCount = plan.partCount
        val digests = List(partCount) { MessageDigest.getInstance("SHA-256") }
        var partIndex = 0
        var remaining = plan.getPartPlaintextBytes(1)

        FileInputStream(zipFile).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                checkCancelled(cancelled)
                val read = input.read(buffer)
                if (read < 0) break
                var offset = 0
                while (offset < read) {
                    if (partIndex >= digests.size) {
                        throw ArchiveBuildException("ZIP 分卷边界超出计划")
                    }
                    val take = minOf((read - offset).toLong(), remaining).toInt()
                    if (take <= 0) {
                        throw ArchiveBuildException("ZIP 分卷边界计算失败")
                    }
                    digests[partIndex].update(buffer, offset, take)
                    offset += take
                    remaining -= take
                    if (remaining == 0L) {
                        partIndex++
                        if (partIndex < digests.size) {
                            remaining = plan.getPartPlaintextBytes(partIndex + 1)
                        }
                    }
                }
            }
        }

        if (partIndex != digests.size) {
            throw ArchiveBuildException("ZIP 分卷边界与计划不一致")
        }
        return digests.map { hex(it.digest()) }
    }

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(digits[value ushr 4])
                append(digits[value and 0x0f])
            }
        }
    }
}
