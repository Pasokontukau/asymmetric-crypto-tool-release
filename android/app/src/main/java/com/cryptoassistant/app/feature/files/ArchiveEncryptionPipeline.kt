package com.cryptoassistant.app.feature.files

import crypto.file.FileProgressListener
import crypto.volume.VolumePartWriter
import java.io.File
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 不依赖 Android 的归档加密编排：ZIP → 分卷计划 → 逐卷 VOLU。
 *
 * <p>输入是 [ArchiveSource]（InputStream + 相对路径），因此 JVM 测试可以覆盖
 * 单卷/多卷、卷名、整包 SHA 和失败清理；SAF 只留在 [FileTaskRunner]。</p>
 */
internal object ArchiveEncryptionPipeline {

    fun encrypt(
        sources: List<ArchiveSource>,
        rootName: String?,
        emptyDirectories: List<String>,
        sourceIsFolder: Boolean,
        privateKey: PrivateKey,
        recipient: PublicKey,
        recipientName: String?,
        directory: File,
        archiveRoot: File,
        taskId: String,
        volumeLimitBytes: Long,
        onArchivePhase: (ArchivePhase) -> Unit,
        onArchiveProgress: (Long, Int) -> Unit,
        onVolumeProgress: (Int, Int, Long, Long) -> Unit,
        cancel: AtomicBoolean,
        now: LocalDateTime = LocalDateTime.now(),
    ): FileTaskResult {
        val archiveRootName = rootName ?: FileTaskStorage.archiveBaseName(now)
        directory.mkdirs()
        val zipFile = ArchiveWorkspace.zipFile(archiveRoot, taskId)
        val createdParts = mutableListOf<File>()
        var success = false
        try {
            onArchivePhase(ArchivePhase.PACKAGING)
            val built = ArchiveBuilder.build(
                request = ArchiveBuildRequest(
                    output = zipFile,
                    rootName = archiveRootName,
                    sources = sources,
                    emptyDirectories = emptyDirectories,
                    sourceIsFolder = sourceIsFolder,
                    volumeLimitBytes = volumeLimitBytes,
                    rsaBits = (recipient as? RSAPublicKey)?.modulus?.bitLength() ?: 4096,
                ),
                cancelled = cancel,
                onProgress = onArchiveProgress,
            )

            onArchivePhase(ArchivePhase.WRITING)
            val plan = built.plan
            val setId = UUID.randomUUID().toString()
            val baseName = FileTaskStorage.encryptedFileBaseName(now)
            val names = ArchivePartPolicy.outputNames(baseName, setId, plan.partCount)
            for (partIndex in 1..plan.partCount) {
                onVolumeProgress(
                    partIndex,
                    plan.partCount,
                    0,
                    plan.getPartPlaintextBytes(partIndex),
                )
                val output = File(directory, names[partIndex - 1])
                val written = VolumePartWriter.writePart(
                    zipFile,
                    plan,
                    partIndex,
                    setId,
                    built.archiveName,
                    built.archiveSha256,
                    sourceIsFolder,
                    built.sourceFileCount,
                    output,
                    privateKey,
                    recipient,
                    FileProgressListener { _, processed, total ->
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
                sourceName = built.archiveName,
                recipientName = recipientName,
                volumeCount = plan.partCount,
                sourceIsFolder = sourceIsFolder && plan.partCount == 1,
                isArchive = true,
                archiveFileCount = built.sourceFileCount,
                parts = parts,
            )
        } finally {
            if (!success) {
                createdParts.forEach { part -> runCatching { part.delete() } }
            }
            ArchiveWorkspace.delete(archiveRoot, taskId)
        }
    }
}
