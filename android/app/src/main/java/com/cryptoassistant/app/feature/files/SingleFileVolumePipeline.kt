package com.cryptoassistant.app.feature.files

import crypto.file.FileCancelledException
import crypto.volume.VolumePartSource
import crypto.volume.VolumePartWriter
import java.io.File
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单个原文件的外部分卷写入：原文 SHA-256 → 分卷计划 → 逐卷 VOLU。
 *
 * <p>与归档路径不同，这里不生成 ZIP：接收端按集合元数据合并后直接得到原文件。
 * 每卷仍是完整的单卷 VOLU，可单独交给共享内核解密；整文件 SHA-256 和每卷
 * GCM/签名共同保证顺序与完整性。源文件通过 [VolumePartSource] 按偏移读取，
 * 因此 Android 的 SAF 内容不需要先复制成临时文件。</p>
 */
internal object SingleFileVolumePipeline {

    private const val BUFFER_SIZE = 64 * 1024

    fun encrypt(
        sourceName: String,
        source: VolumePartSource,
        privateKey: PrivateKey,
        recipient: PublicKey,
        recipientName: String?,
        directory: File,
        volumeLimitBytes: Long,
        onHashProgress: (Long, Long) -> Unit,
        onVolumeProgress: (Int, Int, Long, Long) -> Unit,
        cancel: AtomicBoolean,
        now: LocalDateTime = LocalDateTime.now(),
    ): FileTaskResult {
        val sourceLength = source.length()
        require(sourceLength > 0) { "源文件必须大于 0 字节" }
        directory.mkdirs()

        val plan = ArchivePartPolicy.planForFile(
            fileBytes = sourceLength,
            volumeLimitBytes = volumeLimitBytes,
            fileName = sourceName,
            rsaBits = (recipient as? RSAPublicKey)?.modulus?.bitLength() ?: 4096,
        )
        val sourceSha256 = sha256(source, sourceLength, onHashProgress, cancel)
        val setId = UUID.randomUUID().toString()
        val baseName = FileTaskStorage.encryptedFileBaseName(now)
        val names = ArchivePartPolicy.outputNames(baseName, setId, plan.partCount)
        val createdParts = mutableListOf<File>()
        var success = false
        try {
            for (partIndex in 1..plan.partCount) {
                checkCancelled(cancel)
                onVolumeProgress(
                    partIndex,
                    plan.partCount,
                    0,
                    plan.getPartPlaintextBytes(partIndex),
                )
                val output = File(directory, names[partIndex - 1])
                val written = VolumePartWriter.writePartFromSource(
                    source,
                    plan,
                    partIndex,
                    setId,
                    sourceName,
                    sourceSha256,
                    false,
                    false,
                    1,
                    output,
                    privateKey,
                    recipient,
                    { _, processed, total ->
                        onVolumeProgress(partIndex, plan.partCount, processed, total)
                    },
                    cancel,
                )
                createdParts += written
            }
            val parts = createdParts.toList()
            val first = parts.first()
            success = true
            return FileTaskResult(
                mode = FileTaskMode.ENCRYPT,
                file = first,
                displayName = first.name,
                bytes = parts.sumOf { it.length() },
                sourceName = sourceName,
                recipientName = recipientName,
                volumeCount = plan.partCount,
                sourceIsFolder = false,
                isArchive = false,
                parts = parts,
            )
        } finally {
            if (!success) {
                createdParts.forEach { part -> runCatching { part.delete() } }
            }
        }
    }

    private fun sha256(
        source: VolumePartSource,
        expectedLength: Long,
        onProgress: (Long, Long) -> Unit,
        cancel: AtomicBoolean,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var processed = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        source.openAt(0).use { input ->
            while (processed < expectedLength) {
                checkCancelled(cancel)
                val toRead = minOf(buffer.size.toLong(), expectedLength - processed).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read < 0) {
                    throw java.io.EOFException("源文件在计算整文件哈希时提前结束")
                }
                if (read == 0) continue
                digest.update(buffer, 0, read)
                processed += read
                onProgress(processed, expectedLength)
            }
            if (input.read() >= 0) {
                throw java.io.IOException("源文件大小与系统提供的信息不一致")
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun checkCancelled(cancel: AtomicBoolean) {
        if (cancel.get() || Thread.currentThread().isInterrupted) {
            throw FileCancelledException("已取消单文件分卷")
        }
    }
}
