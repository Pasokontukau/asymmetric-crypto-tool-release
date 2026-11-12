package com.cryptoassistant.app.feature.files

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.cryptoassistant.app.data.keys.ContactKey
import com.cryptoassistant.app.data.keys.KeyRepository
import com.cryptoassistant.app.data.keys.LocalKeyUnavailableException
import com.cryptoassistant.app.data.keys.PrivateKeyBackup
import crypto.file.FileCancelledException
import crypto.file.FileCryptoEngine
import crypto.file.FileDecryptResult
import crypto.file.FileProgressListener
import crypto.message.FileFormatHeader
import crypto.volume.VolumeCryptoEngine
import crypto.volume.VolumeDecryptResult
import crypto.volume.VolumeInfo
import crypto.volume.VolumePartSource
import crypto.volume.VolumeProgressListener
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 单文件任务开始前需要解封的本机私钥、加密接收方与解密候选发送者。 */
internal data class PreparedFileTaskKeys(
    val privateKey: PrivateKey,
    val recipient: PublicKey?,
    val knownSenders: List<Pair<String, PublicKey>>,
)

/**
 * 单文件加解密执行体（无 UI 状态）。
 *
 * <p>从 [FileTaskViewModel] 抽出：文件页与后续文件流水线共用同一套加密/解密、
 * 分卷判型、临时文件收尾与错误翻译。调用方负责选择文件、创建任务目录、
 * 持久化权限和 [FileTaskRuntime] 登记；这里只做纯执行，不读写界面状态。</p>
 *
 * <p>抽取期间保持既有语义不变：不改变 `crypto-core` 参数、文件命名、VOLU 分支
 * 与租约判定，也不把整个文件读进内存。</p>
 */
internal class FileTaskRunner(
    private val resolver: ContentResolver,
    private val keyRepository: KeyRepository,
) {

    /** 解封本机私钥，并按方向解析接收方或候选发送者。 */
    suspend fun prepareKeys(
        mode: FileTaskMode,
        contact: ContactKey?,
    ): PreparedFileTaskKeys = withContext(Dispatchers.IO) {
        val privateKey = keyRepository.currentPrivateKey()
            ?: throw LocalKeyUnavailableException(
                missing = keyRepository.snapshot.value.identity == null,
            )
        val recipient = if (mode == FileTaskMode.ENCRYPT) {
            contact?.let { PrivateKeyBackup.decodePublicKey(it.publicKeyBase64) }
        } else {
            null
        }
        val known = if (mode == FileTaskMode.DECRYPT) knownSenders() else emptyList()
        PreparedFileTaskKeys(privateKey, recipient, known)
    }

    suspend fun encrypt(
        source: SelectedFile,
        privateKey: PrivateKey,
        recipient: PublicKey,
        recipientName: String?,
        directory: File,
        reservedNames: MutableSet<String>,
        onProgress: FileProgressListener,
        cancel: AtomicBoolean,
    ): FileTaskResult = withContext(Dispatchers.IO) {
        val size = source.size ?: error("系统没有提供文件大小")
        val outputName = FileTaskStorage.encryptedOutputName(
            directory = directory,
            reservedNames = reservedNames,
        )
        reservedNames += outputName
        val outputFile = File(directory, outputName)
        val signatureInput = OpeningInputStream { openInput(source.uri) }
        val plaintextInput = OpeningInputStream { openInput(source.uri) }
        try {
            outputFile.outputStream().buffered().use { output ->
                FileCryptoEngine.encrypt(
                    signatureInput,
                    plaintextInput,
                    size,
                    source.displayName,
                    privateKey,
                    recipient,
                    output,
                    onProgress,
                    cancel,
                )
            }
        } finally {
            signatureInput.close()
            plaintextInput.close()
        }
        FileTaskResult(
            mode = FileTaskMode.ENCRYPT,
            file = outputFile,
            displayName = outputFile.name,
            bytes = outputFile.length(),
            sourceName = source.displayName,
            recipientName = recipientName,
        )
    }

    /**
     * 把一组源文件打成 ZIP，再按单卷上限写成独立的 VOLU。
     *
     * <p>ZIP 只落在 [ArchiveWorkspace]；成功后立即删除。分卷留在 [directory]，
     * 由 ViewModel 的 taskDirectories/租约统一收尾。任一失败会删除已创建的分卷，
     * 调用方仍应删除整个任务目录。</p>
     */
    suspend fun encryptArchive(
        sources: List<SelectedFile>,
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
    ): FileTaskResult = withContext(Dispatchers.IO) {
        val archiveSources = sources.map { source ->
            ArchiveSource(
                relativePath = source.relativePath ?: source.displayName,
                size = source.size,
                open = { openInput(source.uri) },
            )
        }
        ArchiveEncryptionPipeline.encrypt(
            sources = archiveSources,
            rootName = rootName,
            emptyDirectories = emptyDirectories,
            sourceIsFolder = sourceIsFolder,
            privateKey = privateKey,
            recipient = recipient,
            recipientName = recipientName,
            directory = directory,
            archiveRoot = archiveRoot,
            taskId = taskId,
            volumeLimitBytes = volumeLimitBytes,
            onArchivePhase = onArchivePhase,
            onArchiveProgress = onArchiveProgress,
            onVolumeProgress = onVolumeProgress,
            cancel = cancel,
        )
    }

    /**
     * 单个原文件的外部分卷：直接读 SAF 流，不先复制成临时文件，也不生成 ZIP。
     *
     * <p>调用方已确认文件超过当前单卷上限；这里先算整文件 SHA-256，再逐卷写 VOLU。
     * 失败时只删除本方法写出的分卷，任务目录仍由调用方统一收尾。</p>
     */
    suspend fun encryptSingleVolume(
        source: SelectedFile,
        privateKey: PrivateKey,
        recipient: PublicKey,
        recipientName: String?,
        directory: File,
        volumeLimitBytes: Long,
        onHashProgress: (Long, Long) -> Unit,
        onVolumeProgress: (Int, Int, Long, Long) -> Unit,
        cancel: AtomicBoolean,
    ): FileTaskResult = withContext(Dispatchers.IO) {
        val size = source.size ?: throw IOException("系统没有提供文件大小")
        val safeName = FileTaskStorage.sanitizeFileName(source.displayName, "file.bin")
        val partSource = UriVolumePartSource(resolver, source.uri, size, cancel)
        SingleFileVolumePipeline.encrypt(
            sourceName = safeName,
            source = partSource,
            privateKey = privateKey,
            recipient = recipient,
            recipientName = recipientName,
            directory = directory,
            volumeLimitBytes = volumeLimitBytes,
            onHashProgress = onHashProgress,
            onVolumeProgress = onVolumeProgress,
            cancel = cancel,
        )
    }

    suspend fun decrypt(
        source: SelectedFile,
        privateKey: PrivateKey,
        knownSenders: List<Pair<String, PublicKey>>,
        directory: File,
        onProgress: FileProgressListener,
        onVolumeProgress: VolumeProgressListener,
        cancel: AtomicBoolean,
    ): FileTaskResult = withContext(Dispatchers.IO) {
        val temporary = File(directory, "payload.part")
        val outcome = resolver.openInputStream(source.uri)?.use { raw ->
            val input = PushbackInputStream(raw, 4)
            val magic = readMagic(input)
            temporary.outputStream().buffered().use { output ->
                if (magic == FileFormatHeader.MAGIC) {
                    val volume = VolumeCryptoEngine.decrypt(
                        input,
                        privateKey,
                        knownSenders.map { it.second },
                        output,
                        onVolumeProgress,
                        cancel,
                    )
                    DecryptOutcome(volume = volume)
                } else {
                    val single = FileCryptoEngine.decrypt(
                        input,
                        privateKey,
                        knownSenders.map { it.second },
                        output,
                        onProgress,
                        cancel,
                    )
                    DecryptOutcome(single = single)
                }
            }
        } ?: throw IOException("无法读取这个文件，访问权限可能已失效")

        val volume = outcome.volume
        if (volume != null) {
            // 文件夹分卷解出的是 zip；metadata 通常带 .zip，缺失时补上后缀，避免接收方无法识别。
            var rawName = volume.originalFileName
            if (rawName.isNullOrBlank()) {
                rawName = if (volume.isSourceIsFolder) "folder.zip" else
                    fallbackDecryptedName(source.displayName)
            }
            if (volume.isSourceIsFolder && !rawName.endsWith(".zip", ignoreCase = true)) {
                rawName += ".zip"
            }
            val finalName = FileTaskStorage.sanitizeFileName(
                rawName,
                if (volume.isSourceIsFolder) "folder.zip" else "decrypted.bin",
            )
            val finalFile = finishTemporary(temporary, File(directory, finalName))
            FileTaskResult(
                mode = FileTaskMode.DECRYPT,
                file = finalFile,
                displayName = finalFile.name,
                bytes = finalFile.length(),
                sourceName = source.displayName,
                signatureValid = volume.isSignatureValid,
                senderName = senderLabel(volume.matchedSender, knownSenders),
                volumeCount = volume.volumeCount,
                contentHashVerified = volume.isContentHashVerified,
                partNamesVerified = volume.isPartNamesVerified,
                sourceIsFolder = volume.isSourceIsFolder,
                isArchive = volume.sourceIsArchive == true,
                archiveFileCount = volume.sourceFileCount,
            )
        } else {
            val decrypted = outcome.single ?: throw IOException("解密结果为空")
            val finalName = FileTaskStorage.sanitizeFileName(
                decrypted.originalFileName,
                fallbackDecryptedName(source.displayName),
            )
            val finalFile = finishTemporary(temporary, File(directory, finalName))
            FileTaskResult(
                mode = FileTaskMode.DECRYPT,
                file = finalFile,
                displayName = finalFile.name,
                bytes = finalFile.length(),
                sourceName = source.displayName,
                signatureValid = decrypted.isSignatureValid,
                senderName = senderLabel(decrypted, knownSenders),
            )
        }
    }

    /**
     * 本机公钥与联系人公钥都可能签过这个文件；返回「公钥 Base64 → 显示名」，
     * 引擎只返回匹配的公钥，再由这里映射回名字。
     */
    /** 只读 VOLU 元数据，不读 payload；非 VOLU 文件返回 null。 */
    suspend fun readVolumeInfo(uri: Uri, privateKey: PrivateKey): VolumeInfo? =
        withContext(Dispatchers.IO) {
            openInput(uri).use { raw ->
                val input = PushbackInputStream(raw, 4)
                val magic = readMagic(input)
                if (magic != FileFormatHeader.MAGIC) return@withContext null
                VolumeCryptoEngine.readInfo(input, privateKey)
            }
        }

    /**
     * 把齐套的外部分卷合并成一个 ZIP。
     *
     * <p>集合判定由调用方用 `VolumeSetPlan` 完成；这里只负责打开每卷的 SAF 流、
     * 逐卷解密到同一个输出，并把整包校验交给 [ArchivePartReader]。</p>
     */
    suspend fun mergeArchiveParts(
        parts: List<ExternalPartInput>,
        descriptor: VolumeInfo,
        privateKey: PrivateKey,
        knownSenders: List<Pair<String, PublicKey>>,
        directory: File,
        onVolumeProgress: VolumeProgressListener,
        cancel: AtomicBoolean,
    ): FileTaskResult = withContext(Dispatchers.IO) {
        val labelOf = { key: PublicKey? -> senderLabel(key, knownSenders) }
        ArchivePartReader.merge(
            parts = parts,
            descriptor = descriptor,
            privateKey = privateKey,
            senderCandidates = knownSenders.map { it.second },
            directory = directory,
            temporaryName = if (descriptor.sourceIsArchive) "merged.zip" else "merged.part",
            onProgress = { index, count, processed, total ->
                onVolumeProgress.onVolumeProgress(index, count, processed, total)
            },
            cancel = cancel,
            senderLabel = labelOf,
        )
    }

    fun knownSenders(): List<Pair<String, PublicKey>> {
        val snapshot = keyRepository.snapshot.value
        val senders = mutableListOf<Pair<String, PublicKey>>()
        snapshot.identity?.let { identity ->
            runCatching {
                PrivateKeyBackup.decodePublicKey(identity.publicKeyBase64)
            }.getOrNull()?.let { senders.add(identity.displayName to it) }
        }
        snapshot.contacts.forEach { contact ->
            runCatching {
                PrivateKeyBackup.decodePublicKey(contact.publicKeyBase64)
            }.getOrNull()?.let { senders.add(contact.name to it) }
        }
        return senders
    }

    private class DecryptOutcome(
        val single: FileDecryptResult? = null,
        val volume: VolumeDecryptResult? = null,
    )

    private fun readMagic(input: PushbackInputStream): Int {
        val bytes = ByteArray(4)
        var read = 0
        while (read < 4) {
            val count = input.read(bytes, read, 4 - read)
            if (count < 0) throw IOException("文件头不完整")
            if (count > 0) read += count
        }
        input.unread(bytes)
        return ByteBuffer.wrap(bytes).int
    }

    private fun finishTemporary(temporary: File, finalFile: File): File {
        if (!temporary.renameTo(finalFile)) {
            temporary.inputStream().use { input ->
                finalFile.outputStream().buffered().use { output ->
                    input.copyTo(output, DEFAULT_COPY_BUFFER)
                }
            }
            temporary.delete()
        }
        return finalFile
    }

    private fun senderLabel(
        result: FileDecryptResult,
        known: List<Pair<String, PublicKey>>,
    ): String? = senderLabel(result.matchedSender, known)

    private fun senderLabel(
        matched: PublicKey?,
        known: List<Pair<String, PublicKey>>,
    ): String? {
        if (matched == null) return null
        val encoded = Base64.getEncoder().encodeToString(matched.encoded)
        return known.firstOrNull { (_, key) ->
            Base64.getEncoder().encodeToString(key.encoded) == encoded
        }?.first
    }

    private fun fallbackDecryptedName(sourceName: String): String {
        val base = sourceName.substringBeforeLast('.', sourceName)
        return if (base.isBlank() || base == sourceName) "decrypted.bin" else base
    }

    private fun openInput(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("无法读取这个文件，访问权限可能已失效")

    /**
     * SAF 源文件的 [VolumePartSource] 实现。
     *
     * <p>优先用可定位的文件描述符；provider 返回管道或不支持 position 时退回
     * 从头打开再跳过 offset。两条流只用于签名和加密，不会把整个分卷读进内存。</p>
     */
    private class UriVolumePartSource(
        private val resolver: ContentResolver,
        private val uri: Uri,
        private val size: Long,
        private val cancel: AtomicBoolean,
    ) : VolumePartSource {

        override fun length(): Long = size

        override fun openAt(offset: Long): InputStream {
            if (offset <= 0L) return open()
            openSeekable(offset)?.let { return it }
            val input = open()
            try {
                skipFully(input, offset)
            } catch (error: Exception) {
                runCatching { input.close() }
                throw error
            }
            return input
        }

        private fun openSeekable(offset: Long): InputStream? {
            val descriptor = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
                ?: return null
            val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            return try {
                stream.channel.position(offset)
                stream
            } catch (error: Exception) {
                runCatching { stream.close() }
                null
            }
        }

        private fun open(): InputStream =
            resolver.openInputStream(uri)
                ?: throw IOException("无法读取这个文件，访问权限可能已失效")

        private fun skipFully(input: InputStream, offset: Long) {
            var remaining = offset
            val buffer = ByteArray(DEFAULT_COPY_BUFFER)
            while (remaining > 0) {
                if (cancel.get() || Thread.currentThread().isInterrupted) {
                    throw FileCancelledException("已取消单文件分卷")
                }
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                    continue
                }
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("源文件在分卷切片前提前结束")
                remaining -= read
            }
        }
    }

    /**
     * 第一次读取时才打开底层流，读到 EOF 后立即关闭，确保签名阶段不会和加密阶段
     * 同时占用两个 provider 文件描述符。
     */
    private class OpeningInputStream(private val opener: () -> InputStream) : InputStream() {
        private var delegate: InputStream? = null

        private fun stream(): InputStream =
            delegate ?: opener().also { delegate = it }

        override fun read(): Int {
            val value = stream().read()
            if (value == -1) close()
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val value = stream().read(buffer, offset, length)
            if (value == -1) close()
            return value
        }

        override fun close() {
            val current = delegate ?: return
            delegate = null
            runCatching { current.close() }
        }
    }

    private companion object {
        const val DEFAULT_COPY_BUFFER = 64 * 1024
    }
}

/**
 * 加密前的单文件预检；返回 null 表示可以开始。
 *
 * <p>与 `FileTaskViewModel.start()` 原有的两条拒绝理由逐字一致，文件流水线
 * 也用它，避免两处判断漂移。</p>
 */
internal fun encryptPreflightError(size: Long?): String? = when {
    size == null || size < 0 ->
        "系统没有提供这个文件的大小，无法流式加密；" +
            "请先把文件保存到本机（例如“下载”目录）后再选择。"

    size > FileCryptoEngine.MAX_PLAINTEXT_BYTES ->
        "文件超过 2 GB，当前单文件格式无法容纳"

    else -> null
}

// 错误文案已迁到 FileTaskError + 资源；FileTaskRunner 只保留异常类型与日志。
