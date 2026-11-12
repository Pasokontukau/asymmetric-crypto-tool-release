package com.cryptoassistant.app.feature.files

import crypto.file.FileCancelledException
import crypto.volume.VolumeCryptoEngine
import crypto.volume.VolumeDecryptResult
import crypto.volume.VolumeInfo
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 外部加密分卷的单卷输入。只暴露打开流的能力，便于 JVM 测试用临时文件或内存流替换 SAF。
 *
 * @property info 由 `VolumeCryptoEngine.readInfo` 解出的单卷元数据
 * @property displayName 选择时看到的名字，只用于日志/错误
 * @property open 每次调用都返回一个从头开始的新流
 */
internal class ExternalPartInput(
    val info: VolumeInfo,
    val displayName: String,
    val open: () -> InputStream,
)

/**
 * 把 `VolumeSetPlan` 判定为齐套的 N 个单卷 VOLU 依次解密，拼成同一个 ZIP 并做整包校验。
 *
 * <p>关键约束（对应规划 §5.2）：</p>
 * - 只写一个输出流，不生成每卷明文分片；峰值 ≈ ZIP + 当前缓冲；
 * - 每卷解密后比对 `plaintextBytes`、`setId/partIndex/partCount`，不一致立即失败；
 * - 全部卷完成后校验总字节与整包 SHA-256；任一失败删除输出，不留下半成品；
 * - 元数据不可信，真正的安全保障仍是每卷 GCM/签名与本卷 SHA，以及最后的整包 SHA。
 *
 * <p>本类不依赖 Android，调用方负责提供 [ExternalPartInput] 与输出目录。</p>
 */
internal object ArchivePartReader {

    /**
     * 合并分卷。
     *
     * @param parts 已按 `partIndex` 升序排列且判定齐套的分卷
     * @param descriptor 集合描述（取任意一卷的集合字段）；用于期望值与输出命名
     * @param privateKey 本机私钥
     * @param senderCandidates 已知联系人公钥，用于验签
     * @param directory 输出目录（`files/file-tasks/<task-id>/`）
     * @param temporaryName 合并中的临时文件名
     * @param onProgress (partNumber 1-based, partTotal, doneBytes, totalBytes)
     * @param cancel 取消标记
     */
    fun merge(
        parts: List<ExternalPartInput>,
        descriptor: VolumeInfo,
        privateKey: PrivateKey,
        senderCandidates: List<PublicKey>,
        directory: File,
        temporaryName: String,
        onProgress: (Int, Int, Long, Long) -> Unit,
        cancel: AtomicBoolean,
        senderLabel: (PublicKey?) -> String? = { null },
    ): FileTaskResult {
        require(parts.isNotEmpty()) { "没有可合并的分卷" }
        val archiveSize = descriptor.archiveSize
            ?: throw IllegalArgumentException("分卷缺少整包大小")
        val archiveSha256 = descriptor.archiveSha256
            ?.trim()
            ?.lowercase()
            ?: throw IllegalArgumentException("分卷缺少整包哈希")
        require(archiveSha256.length == 64) { "整包 SHA-256 长度不正确" }
        val partCount = descriptor.partCount ?: parts.size
        require(parts.size == partCount) { "分卷数量与元数据不一致" }
        require(parts.map { it.info.partIndex }.distinct().size == partCount) {
            "分卷序号重复"
        }

        directory.mkdirs()
        val temporary = File(directory, temporaryName)
        val digest = MessageDigest.getInstance("SHA-256")
        var completedBytes = 0L
        var signaturesValid = true
        var partNamesVerified = true
        var matchedSender: PublicKey? = null
        var success = false

        try {
            temporary.outputStream().buffered().use { fileOut ->
                val digestOut = DigestOutputStream(fileOut, digest)
                parts.forEachIndexed { position, part ->
                    if (cancel.get()) throw FileCancelledException("已取消")
                    val partNumber = position + 1
                    onProgress(partNumber, partCount, completedBytes, archiveSize)
                    val result = openAndDecrypt(
                        part = part,
                        position = position,
                        partCount = partCount,
                        privateKey = privateKey,
                        senderCandidates = senderCandidates,
                        output = digestOut,
                        completedBytes = completedBytes,
                        archiveSize = archiveSize,
                        onProgress = onProgress,
                        cancel = cancel,
                    )
                    completedBytes += result.plaintextBytes
                    signaturesValid = signaturesValid && result.isSignatureValid
                    partNamesVerified = partNamesVerified && result.isPartNamesVerified
                    if (matchedSender == null) matchedSender = result.matchedSender
                }
            }

            if (cancel.get()) throw FileCancelledException("已取消")
            if (temporary.length() != archiveSize) {
                throw ArchiveMergeException(
                    "合并后的 ZIP 长度不符：期望 $archiveSize，实际 ${temporary.length()}",
                )
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualSha.equals(archiveSha256, ignoreCase = true)) {
                throw ArchiveMergeException("合并后的 ZIP 整包校验失败")
            }

            val sourceIsArchive = descriptor.sourceIsArchive == true
            val finalName = FileTaskStorage.sanitizeFileName(
                descriptor.archiveName ?: if (sourceIsArchive) "archive.zip" else "decrypted.bin",
                if (sourceIsArchive) "archive.zip" else "decrypted.bin",
            )
            val finalFile = File(directory, finalName)
            if (finalFile.canonicalPath != temporary.canonicalPath) {
                if (finalFile.exists() && !finalFile.delete()) {
                    throw ArchiveMergeException("无法覆盖同名输出文件")
                }
                if (!temporary.renameTo(finalFile)) {
                    throw ArchiveMergeException("无法重命名合并结果")
                }
            }
            success = true

            return FileTaskResult(
                mode = FileTaskMode.DECRYPT,
                file = finalFile,
                displayName = finalFile.name,
                bytes = finalFile.length(),
                sourceName = descriptor.archiveName ?: finalFile.name,
                signatureValid = signaturesValid,
                senderName = senderLabel(matchedSender),
                volumeCount = partCount,
                contentHashVerified = true,
                partNamesVerified = partNamesVerified,
                sourceIsFolder = sourceIsArchive && descriptor.archiveSourceIsFolder == true,
                isArchive = sourceIsArchive,
                archiveFileCount = if (sourceIsArchive) descriptor.sourceFileCount else null,
                mergedPartCount = partCount,
            )
        } finally {
            if (!success) {
                runCatching { temporary.delete() }
            }
        }
    }

    private fun openAndDecrypt(
        part: ExternalPartInput,
        position: Int,
        partCount: Int,
        privateKey: PrivateKey,
        senderCandidates: List<PublicKey>,
        output: OutputStream,
        completedBytes: Long,
        archiveSize: Long,
        onProgress: (Int, Int, Long, Long) -> Unit,
        cancel: AtomicBoolean,
    ): VolumeDecryptResult {
        val expected = part.info
        val result = part.open().use { input ->
            VolumeCryptoEngine.decrypt(
                input,
                privateKey,
                senderCandidates,
                output,
                { partNumber, partTotal, processed, total ->
                    // 每卷自身只有一个内部卷；进度换算成整包口径，避免 UI 跳动。
                    val base = completedBytes
                    val mapped = if (total <= 0) {
                        processed
                    } else {
                        processed.coerceIn(0, total)
                    }
                    onProgress(
                        position + 1,
                        partCount,
                        (base + mapped).coerceAtMost(archiveSize),
                        archiveSize,
                    )
                },
                cancel,
            )
        }

        if (result.plaintextBytes != expected.originalFileSize) {
            throw ArchiveMergeException(
                "第 ${position + 1} 卷明文长度不符：期望 ${expected.originalFileSize}，" +
                    "实际 ${result.plaintextBytes}",
            )
        }
        val expectedIndex = expected.partIndex
        if (expectedIndex != null && result.partIndex != expectedIndex) {
            throw ArchiveMergeException("第 ${position + 1} 卷的卷号与集合元数据不一致")
        }
        val expectedSetId = expected.setId
        if (expectedSetId != null && result.setId != null && result.setId != expectedSetId) {
            throw ArchiveMergeException("第 ${position + 1} 卷属于另一个分卷包")
        }
        if (result.partCount != null && expected.partCount != null &&
            result.partCount != expected.partCount
        ) {
            throw ArchiveMergeException("第 ${position + 1} 卷的总卷数与集合元数据不一致")
        }
        return result
    }
}

/** 合并阶段的结构性失败；与单卷格式错误区分，便于映射文案。 */
internal class ArchiveMergeException(message: String) : Exception(message)
