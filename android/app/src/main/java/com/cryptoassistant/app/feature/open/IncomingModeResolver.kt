package com.cryptoassistant.app.feature.open

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.cryptoassistant.app.feature.files.EncryptedFileFormat
import com.cryptoassistant.app.feature.files.FileMime
import com.cryptoassistant.app.feature.files.FileTaskMode
import crypto.message.FileFormatHeader
import java.io.InputStream
import java.nio.ByteBuffer

/** 判型置信度；只有 [LOW] 会触发「加密 / 解密」二选一。 */
internal enum class EntryConfidence { HIGH, MEDIUM, LOW }

/** 文件头探测的结论；[UNREADABLE] 表示读不到，**不等于**「不是密文」。 */
internal enum class ProbeOutcome { VOLUME, SINGLE_FILE, NOT_CIPHER, UNREADABLE }

/**
 * 一次入口判型的结果。
 *
 * @property mode 为 null 表示没有足够信号，需要用户二选一。
 * @property reason 只含枚举式短标记（`name:aegix` / `head:struct` 等），可安全写日志。
 */
internal data class EntryDecision(
    val mode: FileTaskMode?,
    val confidence: EntryConfidence,
    val reason: String,
)

/**
 * 系统「用其他应用打开」单一入口的判型器。
 *
 * <p>只回答一件事：这个 content:// 更像本应用的密文，还是普通明文。顺序按
 * `docs/单一文件入口与内部路由评估.md` §4.3 短路，先看便宜且可靠的信号（后缀），
 * 再看内容结构，最后才回落到 MIME 与后缀推断。</p>
 *
 * <p>不创建任何文件、不写任何内容、不申请持久化授权；只读文件头最多
 * `4 + 10240 + 4 + 256 + 4` 字节。</p>
 */
internal object IncomingModeResolver {

    /** 单文件密文正文之前必须先出现 `encKeyLen` / `ivLen` / `ciphertextLen` 三个长度字段。 */
    private const val MIN_TAIL_BYTES = 4L
    private const val UNKNOWN_SIZE = -1L

    // 与 crypto-core 的 SignedAndEncryptedData.readHeaderFromStream 校验一致。
    // 这三个常量在 crypto-core 里是 private，本模块不能引用，故在此镜像一份并保持不收紧。
    private const val MAX_SIGNATURE_BYTES = 10_240
    private const val MAX_FILE_NAME_IV_BYTES = 256
    private const val MAX_ENCRYPTED_NAME_BYTES = 4_096

    /** 明确声明「这是密文」的 MIME；`application/octet-stream` 不算，它只是「不知道」。 */
    private val CIPHER_MIMES = setOf(
        EncryptedFileFormat.MIME,
        EncryptedFileFormat.MIME_X_AEGIX,
    )

    /** 与 Manifest VIEW filter 白名单保持同一份清单（类型族用前缀匹配）。 */
    private val PLAINTEXT_MIME_WHITELIST = setOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/zip",
        "application/x-zip-compressed",
    )

    private val PLAINTEXT_MIME_PREFIXES = listOf("text/", "image/", "video/", "audio/")

    /** 文件名来源；只用于诊断，不记录文件名本身。 */
    internal enum class EntryNameSource { DISPLAY, URI, TITLE, NONE }

    /** 一次 URI 探测的原始事实；策略层再决定模式。 */
    internal data class UriFacts(
        val uri: Uri,
        val name: String?,
        val nameSource: EntryNameSource,
        val systemType: String?,
        val size: Long?,
        val probe: ProbeOutcome?,
    )

    /**
     * 查询元数据并按需探测文件头；不决定模式。
     *
     * @param titleHint 发送方给的 `EXTRA_TITLE`，只在看起来像 `.aegix` 文件名时采用。
     * @param probeAllowed 批量时只对“没有名字/octet-stream”的文件开启，避免 200 个文件逐个读头。
     */
    fun inspect(
        context: Context,
        uri: Uri,
        readGranted: Boolean,
        titleHint: String?,
        probeAllowed: Boolean,
    ): UriFacts {
        val resolver = context.contentResolver
        val meta = queryMeta(resolver, uri)
        val systemType = runCatching { resolver.getType(uri) }.getOrNull()
        val (name, source) = chooseName(meta.displayName, uri, titleHint)
        val probe = if (readGranted && probeAllowed) {
            probeStructure(resolver, uri, meta.size)
        } else {
            null
        }
        return UriFacts(uri, name, source, systemType, meta.size, probe)
    }

    /** 单独探测文件头；批量策略在第二轮按预算调用。 */
    fun probe(context: Context, uri: Uri, knownSize: Long?): ProbeOutcome =
        probeStructure(context.contentResolver, uri, knownSize)

    /** 旧入口：单 URI、总是探测；保留给测试/兼容调用。 */
    fun resolve(context: Context, uri: Uri, readGranted: Boolean): EntryDecision {
        val facts = inspect(
            context = context,
            uri = uri,
            readGranted = readGranted,
            titleHint = null,
            probeAllowed = true,
        )
        return decideEntryMode(
            displayName = facts.name,
            systemType = facts.systemType,
            probe = facts.probe ?: ProbeOutcome.UNREADABLE,
            readGranted = readGranted,
        )
    }

    /**
     * 名称优先级：DISPLAY_NAME > 看起来像密文的 URI 末段 > 看起来像密文的 EXTRA_TITLE。
     *
     * <p>不把任意 `uri.lastPathSegment` 当文件名：很多 provider 的末段只是数字 ID，拿它做后缀推断
     * 反而会误判。这里只接受 `.aegix` 结尾的末段/标题，作为后缀判型的补充。</p>
     */
    private fun chooseName(
        displayName: String?,
        uri: Uri,
        titleHint: String?,
    ): Pair<String?, EntryNameSource> {
        val display = displayName?.trim()?.takeIf { it.isNotEmpty() }
        if (display != null) return display to EntryNameSource.DISPLAY

        val segment = runCatching {
            uri.lastPathSegment?.let { Uri.decode(it) }?.trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (segment != null && hasCipherExtension(segment)) {
            return segment to EntryNameSource.URI
        }

        val title = titleHint?.trim()?.takeIf { it.isNotEmpty() }
        if (title != null && hasCipherExtension(title)) {
            return title to EntryNameSource.TITLE
        }
        return null to EntryNameSource.NONE
    }

    private fun hasCipherExtension(value: String): Boolean =
        value.endsWith(EncryptedFileFormat.EXTENSION, ignoreCase = true)

    /**
     * 纯函数判型，供单测直接覆盖。
     *
     * @param probe null 表示没有做内容探测（未读文件头）。
     */
    fun decideEntryMode(
        displayName: String?,
        systemType: String?,
        probe: ProbeOutcome?,
        readGranted: Boolean,
    ): EntryDecision {
        val name = displayName?.trim().orEmpty()
        val mime = systemType?.trim()?.lowercase()?.substringBefore(';').orEmpty()

        if (name.endsWith(EncryptedFileFormat.EXTENSION, ignoreCase = true)) {
            return EntryDecision(FileTaskMode.DECRYPT, EntryConfidence.HIGH, "name:aegix")
        }
        if (mime.isNotEmpty() && mime in CIPHER_MIMES) {
            return EntryDecision(FileTaskMode.DECRYPT, EntryConfidence.MEDIUM, "mime:cipher")
        }
        when (probe) {
            ProbeOutcome.VOLUME ->
                return EntryDecision(FileTaskMode.DECRYPT, EntryConfidence.HIGH, "head:volu")

            ProbeOutcome.SINGLE_FILE ->
                return EntryDecision(FileTaskMode.DECRYPT, EntryConfidence.MEDIUM, "head:struct")

            ProbeOutcome.NOT_CIPHER, ProbeOutcome.UNREADABLE, null -> Unit
        }
        if (mime.isNotEmpty() && isPlaintextMime(mime)) {
            return EntryDecision(FileTaskMode.ENCRYPT, EntryConfidence.HIGH, "mime:plain")
        }
        if (FileMime.infer(displayName, systemType) != FileMime.FALLBACK) {
            val fromSuffix = name.substringAfterLast('.', "").isNotEmpty()
            return EntryDecision(
                FileTaskMode.ENCRYPT,
                EntryConfidence.MEDIUM,
                if (fromSuffix) "suffix:plain" else "type:plain",
            )
        }
        return EntryDecision(
            mode = null,
            confidence = EntryConfidence.LOW,
            reason = if (readGranted) "none" else "unreadable",
        )
    }

    private fun isPlaintextMime(mime: String): Boolean =
        mime in PLAINTEXT_MIME_WHITELIST || PLAINTEXT_MIME_PREFIXES.any(mime::startsWith)

    private data class UriMeta(val displayName: String?, val size: Long?)

    private fun queryMeta(resolver: ContentResolver, uri: Uri): UriMeta {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        name = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        }
        return UriMeta(name, size)
    }

    /** 探测失败一律当作「无法判断」，不能当作「不是密文」。 */
    private fun probeStructure(
        resolver: ContentResolver,
        uri: Uri,
        knownSize: Long?,
    ): ProbeOutcome = runCatching {
        val input = resolver.openInputStream(uri) ?: return@runCatching ProbeOutcome.UNREADABLE
        input.use { probeHeader(it, knownSize) }
    }.getOrDefault(ProbeOutcome.UNREADABLE)

    /**
     * 读文件头判断结构；不读正文。
     *
     * <p>单文件格式没有魔数，只能验证「三段长度字段都合理，且后面还有正文」。
     * 随机二进制同时满足这些约束的概率很低，但并非为零——命中时按中置信处理。</p>
     */
    fun probeHeader(input: InputStream, knownSize: Long?): ProbeOutcome {
        val reader = HeaderReader(input, knownSize)
        val signatureLength = reader.readInt() ?: return ProbeOutcome.NOT_CIPHER
        if (signatureLength == FileFormatHeader.MAGIC) return ProbeOutcome.VOLUME
        if (signatureLength !in 1..MAX_SIGNATURE_BYTES) return ProbeOutcome.NOT_CIPHER

        if (!reader.skip(signatureLength)) return ProbeOutcome.NOT_CIPHER
        val fileNameIvLength = reader.readInt() ?: return ProbeOutcome.NOT_CIPHER
        if (fileNameIvLength !in 0..MAX_FILE_NAME_IV_BYTES) return ProbeOutcome.NOT_CIPHER

        if (!reader.skip(fileNameIvLength)) return ProbeOutcome.NOT_CIPHER
        val encryptedNameLength = reader.readInt() ?: return ProbeOutcome.NOT_CIPHER
        if (encryptedNameLength !in 0..MAX_ENCRYPTED_NAME_BYTES) return ProbeOutcome.NOT_CIPHER

        if (!reader.skip(encryptedNameLength)) return ProbeOutcome.NOT_CIPHER
        val tailEnough = if (reader.remaining == UNKNOWN_SIZE) {
            reader.readInt() != null
        } else {
            reader.remaining >= MIN_TAIL_BYTES
        }
        return if (tailEnough) ProbeOutcome.SINGLE_FILE else ProbeOutcome.NOT_CIPHER
    }

    /** 带上限的读指针：已知总长时提前判断「剩余够不够」，未知时只依赖实际读取结果。 */
    private class HeaderReader(private val input: InputStream, knownSize: Long?) {
        var remaining: Long = if (knownSize != null && knownSize >= 0) knownSize else UNKNOWN_SIZE
            private set

        fun readInt(): Int? {
            val bytes = readFully(input, 4) ?: return null
            if (remaining != UNKNOWN_SIZE) remaining -= 4
            return ByteBuffer.wrap(bytes).int
        }

        fun skip(count: Int): Boolean {
            if (count == 0) return true
            if (remaining != UNKNOWN_SIZE && remaining < count) return false
            if (!skipFully(input, count)) return false
            if (remaining != UNKNOWN_SIZE) remaining -= count
            return true
        }
    }
}

private fun readFully(input: InputStream, count: Int): ByteArray? {
    val bytes = ByteArray(count)
    var read = 0
    while (read < count) {
        val current = input.read(bytes, read, count - read)
        if (current <= 0) return null
        read += current
    }
    return bytes
}

/** 逐段丢弃文件头；单次缓冲不超过 8 KiB，不整体载入内存。 */
private fun skipFully(input: InputStream, count: Int): Boolean {
    var left = count
    val buffer = ByteArray(minOf(left, 8 * 1024))
    while (left > 0) {
        val read = input.read(buffer, 0, minOf(left, buffer.size))
        if (read <= 0) return false
        left -= read
    }
    return true
}
