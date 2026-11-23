package com.cryptoassistant.app.feature.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import crypto.file.FileCancelledException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** 目标目录里的一个既有条目；[actualName] 是 provider 返回的真实名字。 */
data class SinkInfo(val actualName: String, val isDirectory: Boolean)

/** 新建目录的结果；[path] 是后续 list/create 使用的目标路径。 */
data class SinkDirectory(val path: String, val actualName: String)

/** 新建文件的结果；[output] 由调用方关闭。 */
data class SinkFile(val path: String, val actualName: String, val output: OutputStream)

/**
 * 解压目标抽象（修订 3 §5.7）。
 *
 * <p>生产实现是 [SafArchiveSink]（DocumentsContract + tree URI），JVM 测试用
 * [FileArchiveSink]（临时目录）。路径是逻辑路径，不是字符串文件路径；调用方必须使用
 * [SinkDirectory.path] / [SinkFile.path] 继续操作，不能自己拼接期望名。</p>
 */
interface ArchiveSink {
    /** key = lowercase(actualName)；value 带实际名/类型。 */
    fun list(parentPath: String): Map<String, SinkInfo>

    fun createDirectory(parentPath: String, name: String): SinkDirectory

    fun createFile(parentPath: String, name: String): SinkFile

    /** 只删本次创建的文件/目录；失败返回 false，不抛异常。 */
    fun delete(path: String): Boolean
}

/** 解压的结构性失败；[reason] 由 [fileTaskErrorFor] 映射成本地化文案。 */
class ArchiveExtractException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Reason {
        UNSAFE_PATH,
        CONFLICT,
        LIMIT,
        TARGET,
        EXTRACT,
    }
}

/**
 * 目标 provider 拒绝了单个条目的名字或无法为它打开输出流。
 *
 * <p>只影响这一个条目：跳过并计入 `skipped`，不中止整次解压。系统级 IO/ENOSPC/权限
 * 错误仍按 IOException/SecurityException 向上抛出并中止。</p>
 */
class ArchiveSinkSkipException(message: String) : Exception(message)

data class ArchiveExtractProgress(
    val filesDone: Int,
    val filesTotal: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
)

data class ArchiveExtractResult(
    val files: Int,
    val directories: Int,
    val renamed: Int,
    val skipped: Int,
    val bytes: Long,
    val failedDeletes: Int,
)

/**
 * 纯 JVM 的安全解压器：ZIP 是**不可信输入**。
 *
 * <p>对应修订 3 §5.7 / 修订 4 §5.4：先预检条目数、声明大小、压缩方法与路径，
 * 再在目标目录建树；同名项用 `_2` 避让，不覆盖用户已有文件；取消或失败只删除本次
 * 创建的文件/目录，反向清理，删除失败如实计数。</p>
 */
object ArchiveExtractor {

    const val MAX_ENTRIES = 5000
    const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_ENTRY_BYTES = MAX_TOTAL_BYTES

    private const val MAX_PATH_BYTES = 1024
    private const val MAX_SEGMENTS = 32
    private const val MAX_SEGMENT_CHARS = 255
    private const val BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_STEP_BYTES = 1L * 1024 * 1024

    /**
     * 解压 [zipFile] 到 [sink] 根目录。
     *
     * @throws ArchiveExtractException 路径不安全、条目冲突、超限或目标目录不可用
     * @throws FileCancelledException 取消
     * @throws IOException 系统级读写/空间错误；调用方按既有映射显示
     * @throws SecurityException 目标目录权限丢失
     */
    fun extract(
        zipFile: File,
        sink: ArchiveSink,
        onProgress: (ArchiveExtractProgress) -> Unit = {},
        cancel: AtomicBoolean = AtomicBoolean(false),
    ): ArchiveExtractResult {
        checkCancelled(cancel)
        val zip = try {
            ZipFile(zipFile)
        } catch (error: ZipException) {
            throw ArchiveExtractException(
                ArchiveExtractException.Reason.EXTRACT,
                "ZIP 无法打开",
                error,
            )
        }

        zip.use {
            val entries = mutableListOf<ZipEntry>()
            val enumeration = it.entries()
            while (enumeration.hasMoreElements()) {
                entries += enumeration.nextElement()
                if (entries.size > MAX_ENTRIES) {
                    throw ArchiveExtractException(
                        ArchiveExtractException.Reason.LIMIT,
                        "ZIP 条目超过 $MAX_ENTRIES",
                    )
                }
            }

            val declaredBytes = preflight(entries)
            val parsed = entries.map { entry -> parseEntry(entry) }
            val nodes = buildTree(parsed)
            val fileNodes = nodes.filter { !it.isDirectory }
            val directoryNodes = nodes.filter { it.isDirectory }
                .sortedWith(compareBy<EntryNode> { it.depth }.thenBy { it.key })
            val filesTotal = fileNodes.size

            onProgress(
                ArchiveExtractProgress(
                    filesDone = 0,
                    filesTotal = filesTotal,
                    bytesDone = 0,
                    bytesTotal = declaredBytes,
                ),
            )

            val actualPaths = mutableMapOf("" to "")
            val created = mutableListOf<CreatedItem>()
            var directories = 0
            var renamed = 0
            var skipped = 0
            var failedDeletes = 0
            var filesDone = 0
            var bytesDone = 0L
            var success = false

            try {
                directoryNodes.forEach { node ->
                    checkCancelled(cancel)
                    val parentPath = actualPaths[node.parentKey] ?: ""
                    val ensured = ensureDirectory(sink, parentPath, node.name)
                    actualPaths[node.key] = ensured.directory.path
                    if (ensured.created) {
                        created += CreatedItem(ensured.directory.path, isDirectory = true)
                    }
                    directories++
                    if (ensured.directory.actualName != node.name) renamed++
                }

                fileNodes.sortedBy { it.key }.forEach { node ->
                    checkCancelled(cancel)
                    val parentPath = actualPaths[node.parentKey] ?: ""
                    val createdFile = try {
                        createFile(sink, parentPath, node.name)
                    } catch (skippedEntry: ArchiveSinkSkipException) {
                        skipped++
                        return@forEach
                    }
                    if (createdFile.actualName != node.name) renamed++
                    created += CreatedItem(createdFile.path, isDirectory = false)

                    var entryInput: java.io.InputStream? = null
                    try {
                        entryInput = try {
                            zip.getInputStream(node.entry)
                        } catch (error: ZipException) {
                            // 加密条目或压缩结构异常：单条跳过，不让整包失败。
                            runCatching { createdFile.output.close() }
                            deleteCreated(sink, created, createdFile.path)
                            skipped++
                            return@forEach
                        }

                        entryInput.use { input ->
                            createdFile.output.use { output ->
                                // 边写边算 CRC：ZipFile 不替我们校验内容，损坏的包
                                // 会被“成功”解成一堆坏文件。
                                val crc = java.util.zip.CRC32()
                                val checked = java.util.zip.CheckedOutputStream(output, crc)
                                val copied = copyEntry(
                                    input = input,
                                    output = checked,
                                    bytesDone = bytesDone,
                                    filesDone = filesDone,
                                    filesTotal = filesTotal,
                                    declaredBytes = declaredBytes,
                                    cancel = cancel,
                                    onProgress = onProgress,
                                )
                                checked.flush()
                                node.entry?.size?.let { expected ->
                                    if (expected >= 0 && copied != expected) {
                                        throw ArchiveExtractException(
                                            ArchiveExtractException.Reason.EXTRACT,
                                            "ZIP 条目大小不符",
                                        )
                                    }
                                }
                                node.entry?.crc?.let { expected ->
                                    if (expected >= 0 && crc.value != expected) {
                                        throw ArchiveExtractException(
                                            ArchiveExtractException.Reason.EXTRACT,
                                            "ZIP 条目校验失败（CRC 不符）",
                                        )
                                    }
                                }
                                bytesDone += copied
                            }
                        }
                    } finally {
                        entryInput?.let { runCatching { it.close() } }
                    }

                    filesDone++
                    onProgress(
                        ArchiveExtractProgress(
                            filesDone = filesDone,
                            filesTotal = filesTotal,
                            bytesDone = bytesDone,
                            bytesTotal = declaredBytes,
                        ),
                    )
                }

                success = true
                return ArchiveExtractResult(
                    files = filesDone,
                    directories = directories,
                    renamed = renamed,
                    skipped = skipped,
                    bytes = bytesDone,
                    failedDeletes = failedDeletes,
                )
            } finally {
                if (!success) {
                    failedDeletes = cleanup(sink, created)
                }
            }
        }
    }

    private fun preflight(entries: List<ZipEntry>): Long {
        var total = 0L
        entries.forEach { entry ->
            if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) {
                throw ArchiveExtractException(
                    ArchiveExtractException.Reason.EXTRACT,
                    "不支持的 ZIP 压缩方法",
                )
            }
            val size = entry.size
            if (size > MAX_ENTRY_BYTES) {
                throw ArchiveExtractException(
                    ArchiveExtractException.Reason.LIMIT,
                    "单个条目超过 4 GiB",
                )
            }
            if (size > 0) {
                if (total > MAX_TOTAL_BYTES - size) {
                    throw ArchiveExtractException(
                        ArchiveExtractException.Reason.LIMIT,
                        "ZIP 声明大小超过 4 GiB",
                    )
                }
                total += size
            }
        }
        return total
    }

    private fun parseEntry(entry: ZipEntry): ParsedEntry {
        val raw = entry.name.orEmpty()
        if (raw.isEmpty()) {
            throw unsafe("ZIP 条目名为空")
        }
        val isDirectory = entry.isDirectory
        val name = if (isDirectory) raw.dropLast(1) else raw
        if (name.isEmpty()) throw unsafe("ZIP 条目名为空")
        if (name.startsWith("/") || name.startsWith("\\")) throw unsafe("绝对路径")
        if (name.indexOf('\\') >= 0) throw unsafe("反斜杠路径")
        if (name.indexOf('\u0000') >= 0) throw unsafe("NUL 字符")
        if (name.any { it.isISOControl() }) throw unsafe("控制字符")
        if (name.toByteArray(Charsets.UTF_8).size > MAX_PATH_BYTES) {
            throw unsafe("路径超过 $MAX_PATH_BYTES 字节")
        }

        val segments = name.split('/')
        if (segments.size > MAX_SEGMENTS) throw unsafe("路径超过 $MAX_SEGMENTS 段")
        segments.forEachIndexed { index, segment ->
            if (segment.isEmpty() || segment == "." || segment == "..") {
                throw unsafe("空段或相对路径段")
            }
            if (segment.length > MAX_SEGMENT_CHARS) throw unsafe("路径段过长")
            if (index == 0 && segment.length >= 2 && segment[1] == ':' &&
                segment[0].isLetter()
            ) {
                throw unsafe("盘符路径")
            }
        }
        return ParsedEntry(
            entry = entry,
            segments = segments,
            isDirectory = isDirectory,
        )
    }

    private fun buildTree(parsed: List<ParsedEntry>): List<EntryNode> {
        val nodes = mutableMapOf<String, EntryNode>()
        val all = mutableListOf<EntryNode>()

        parsed.forEach { entry ->
            val segments = entry.segments
            for (depth in 1 until segments.size) {
                val key = keyOf(segments, depth)
                val existing = nodes[key]
                if (existing != null) {
                    if (!existing.isDirectory) throw conflict("文件与目录路径冲突")
                } else {
                    val node = EntryNode(
                        key = key,
                        parentKey = parentKeyOf(segments, depth),
                        name = segments[depth - 1],
                        depth = depth,
                        isDirectory = true,
                        entry = null,
                    )
                    nodes[key] = node
                    all += node
                }
            }

            val key = keyOf(segments, segments.size)
            val existing = nodes[key]
            if (entry.isDirectory) {
                if (existing != null && !existing.isDirectory) {
                    throw conflict("文件与目录路径冲突")
                }
                if (existing == null) {
                    val node = EntryNode(
                        key = key,
                        parentKey = parentKeyOf(segments, segments.size),
                        name = segments.last(),
                        depth = segments.size,
                        isDirectory = true,
                        entry = entry.entry,
                    )
                    nodes[key] = node
                    all += node
                }
            } else {
                if (existing != null) throw conflict("重复文件路径")
                val node = EntryNode(
                    key = key,
                    parentKey = parentKeyOf(segments, segments.size),
                    name = segments.last(),
                    depth = segments.size,
                    isDirectory = false,
                    entry = entry.entry,
                )
                nodes[key] = node
                all += node
            }
        }
        return all
    }

    private fun ensureDirectory(
        sink: ArchiveSink,
        parentPath: String,
        name: String,
    ): EnsuredDirectory {
        val existing = sink.list(parentPath)
        val found = existing[name.lowercase(Locale.US)]
        if (found != null && found.isDirectory) {
            return EnsuredDirectory(
                directory = SinkDirectory(
                    path = joinPath(parentPath, found.actualName),
                    actualName = found.actualName,
                ),
                created = false,
            )
        }
        val used = existing.keys.toMutableSet()
        val candidate = if (found == null) name else ArchiveSharePlan.uniqueName(name, used)
        val created = try {
            sink.createDirectory(parentPath, candidate)
        } catch (error: ArchiveSinkSkipException) {
            throw ArchiveExtractException(
                ArchiveExtractException.Reason.TARGET,
                "无法创建目标目录",
                error,
            )
        }
        return EnsuredDirectory(directory = created, created = true)
    }

    private fun createFile(
        sink: ArchiveSink,
        parentPath: String,
        name: String,
    ): SinkFile {
        val existing = sink.list(parentPath)
        val found = existing[name.lowercase(Locale.US)]
        val used = existing.keys.toMutableSet()
        val candidate = if (found == null) name else ArchiveSharePlan.uniqueName(name, used)
        return sink.createFile(parentPath, candidate)
    }

    private fun copyEntry(
        input: java.io.InputStream,
        output: OutputStream,
        bytesDone: Long,
        filesDone: Int,
        filesTotal: Int,
        declaredBytes: Long,
        cancel: AtomicBoolean,
        onProgress: (ArchiveExtractProgress) -> Unit,
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        var lastProgress = 0L
        while (true) {
            checkCancelled(cancel)
            val read = input.read(buffer)
            if (read < 0) break
            if (bytesDone + copied + read > MAX_TOTAL_BYTES) {
                throw ArchiveExtractException(
                    ArchiveExtractException.Reason.LIMIT,
                    "解压总量超过 4 GiB",
                )
            }
            output.write(buffer, 0, read)
            copied += read
            if (copied - lastProgress >= PROGRESS_STEP_BYTES) {
                lastProgress = copied
                onProgress(
                    ArchiveExtractProgress(
                        filesDone = filesDone,
                        filesTotal = filesTotal,
                        bytesDone = bytesDone + copied,
                        bytesTotal = declaredBytes,
                    ),
                )
            }
        }
        return copied
    }

    private fun deleteCreated(sink: ArchiveSink, created: MutableList<CreatedItem>, path: String) {
        runCatching { sink.delete(path) }
        created.removeAll { it.path == path }
    }

    private fun cleanup(sink: ArchiveSink, created: List<CreatedItem>): Int {
        var failed = 0
        created.asReversed().forEach { item ->
            val deleted = runCatching { sink.delete(item.path) }.getOrDefault(false)
            if (!deleted) failed++
        }
        return failed
    }

    private fun checkCancelled(cancel: AtomicBoolean) {
        if (cancel.get()) throw FileCancelledException("已取消")
    }

    private fun unsafe(message: String) = ArchiveExtractException(
        ArchiveExtractException.Reason.UNSAFE_PATH,
        message,
    )

    private fun conflict(message: String) = ArchiveExtractException(
        ArchiveExtractException.Reason.CONFLICT,
        message,
    )

    private fun keyOf(segments: List<String>, count: Int): String =
        segments.take(count).joinToString("/").lowercase(Locale.US)

    private fun parentKeyOf(segments: List<String>, count: Int): String =
        if (count <= 1) "" else keyOf(segments, count - 1)

    private fun joinPath(parentPath: String, name: String): String =
        if (parentPath.isEmpty()) name else "$parentPath/$name"

    private data class ParsedEntry(
        val entry: ZipEntry,
        val segments: List<String>,
        val isDirectory: Boolean,
    )

    private data class EntryNode(
        val key: String,
        val parentKey: String,
        val name: String,
        val depth: Int,
        val isDirectory: Boolean,
        val entry: ZipEntry?,
    )

    private data class CreatedItem(val path: String, val isDirectory: Boolean)

    private data class EnsuredDirectory(val directory: SinkDirectory, val created: Boolean)
}

/**
 * JVM 测试与未来本地目录解压使用的目标实现。
 *
 * <p>只写 [root] 子树；路径在写入前再次做 canonical 检查，防止调用方传入越界路径。</p>
 */
internal class FileArchiveSink(root: File) : ArchiveSink {

    private val root: File = root.canonicalFile
    private val rootPath = this.root.toPath()

    init {
        if (!this.root.isDirectory && !this.root.mkdirs()) {
            throw IOException("无法创建解压目标目录")
        }
    }

    override fun list(parentPath: String): Map<String, SinkInfo> {
        val parent = resolve(parentPath)
        val children = parent.listFiles().orEmpty()
        val result = LinkedHashMap<String, SinkInfo>()
        children.forEach { child ->
            result[child.name.lowercase(Locale.US)] = SinkInfo(
                actualName = child.name,
                isDirectory = child.isDirectory,
            )
        }
        return result
    }

    override fun createDirectory(parentPath: String, name: String): SinkDirectory {
        requireSafeName(name)
        val parent = resolve(parentPath)
        val target = File(parent, name)
        if (target.exists() || !target.mkdirs()) {
            throw IOException("无法创建解压目录")
        }
        return SinkDirectory(path = childPath(parentPath, name), actualName = name)
    }

    override fun createFile(parentPath: String, name: String): SinkFile {
        requireSafeName(name)
        val parent = resolve(parentPath)
        val target = File(parent, name)
        if (target.exists()) throw IOException("解压目标已存在")
        return SinkFile(
            path = childPath(parentPath, name),
            actualName = name,
            output = FileOutputStream(target),
        )
    }

    override fun delete(path: String): Boolean {
        val target = runCatching { resolve(path) }.getOrNull() ?: return false
        return target.delete()
    }

    private fun resolve(path: String): File {
        if (path.isEmpty()) return root
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw ArchiveExtractException(
                ArchiveExtractException.Reason.UNSAFE_PATH,
                "目标路径不安全",
            )
        }
        val file = File(root, path).canonicalFile
        if (!file.toPath().startsWith(rootPath)) {
            throw ArchiveExtractException(
                ArchiveExtractException.Reason.UNSAFE_PATH,
                "目标路径越界",
            )
        }
        return file
    }

    private fun childPath(parentPath: String, name: String): String =
        if (parentPath.isEmpty()) name else "$parentPath/$name"

    private fun requireSafeName(name: String) {
        if (name.isEmpty() || name == "." || name == ".." ||
            name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 ||
            name.any { it.isISOControl() }
        ) {
            throw ArchiveExtractException(
                ArchiveExtractException.Reason.UNSAFE_PATH,
                "目标名称不安全",
            )
        }
    }
}

/**
 * SAF 目标实现：直接使用 `DocumentsContract`，不引入 `androidx.documentfile`。
 *
 * <p>每个父目录只 query 一次并缓存 documentId；createDocument 返回的实际显示名可能
 * 与请求名不同（provider 自动改名），后续子项必须使用实际名对应的路径。</p>
 */
internal class SafArchiveSink(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : ArchiveSink {

    private val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    private val documentIds = mutableMapOf("" to rootDocumentId)
    private val listCache = mutableMapOf<String, MutableMap<String, SinkInfo>>()

    override fun list(parentPath: String): Map<String, SinkInfo> {
        listCache[parentPath]?.let { return it }

        val parentUri = documentUri(parentPath)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            documentIds[parentPath] ?: throw IOException("目标目录已失效"),
        )
        val cursor = resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        ) ?: throw IOException("无法读取目标目录")

        val result = LinkedHashMap<String, SinkInfo>()
        cursor.use {
            val idIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) {
                throw IOException("目标提供方不支持目录浏览")
            }
            while (it.moveToNext()) {
                val id = it.getString(idIndex) ?: continue
                val name = safeActualName(it.getString(nameIndex), null) ?: continue
                val mime = it.getString(mimeIndex).orEmpty()
                val childPath = joinPath(parentPath, name)
                documentIds[childPath] = id
                result[name.lowercase(Locale.US)] = SinkInfo(
                    actualName = name,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
        }
        listCache[parentPath] = result
        return result
    }

    override fun createDirectory(parentPath: String, name: String): SinkDirectory {
        val parentUri = documentUri(parentPath)
        val document = try {
            DocumentsContract.createDocument(
                resolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            )
        } catch (error: IllegalArgumentException) {
            throw ArchiveSinkSkipException("目标提供方拒绝目录名")
        } ?: throw ArchiveSinkSkipException("目标提供方拒绝目录名")

        val actualName = safeActualName(displayName(document), name) ?: name
        val path = joinPath(parentPath, actualName)
        documentIds[path] = DocumentsContract.getDocumentId(document)
        cachePut(parentPath, actualName, isDirectory = true)
        return SinkDirectory(path = path, actualName = actualName)
    }

    override fun createFile(parentPath: String, name: String): SinkFile {
        val parentUri = documentUri(parentPath)
        val document = try {
            DocumentsContract.createDocument(
                resolver,
                parentUri,
                "application/octet-stream",
                name,
            )
        } catch (error: IllegalArgumentException) {
            throw ArchiveSinkSkipException("目标提供方拒绝文件名")
        } ?: throw ArchiveSinkSkipException("目标提供方拒绝文件名")

        val actualName = safeActualName(displayName(document), name) ?: name
        val path = joinPath(parentPath, actualName)
        documentIds[path] = DocumentsContract.getDocumentId(document)
        val output = try {
            resolver.openOutputStream(document)
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, document) }
            documentIds.remove(path)
            throw error
        }
        if (output == null) {
            runCatching { DocumentsContract.deleteDocument(resolver, document) }
            documentIds.remove(path)
            throw ArchiveSinkSkipException("目标提供方无法打开输出流")
        }
        cachePut(parentPath, actualName, isDirectory = false)
        return SinkFile(path = path, actualName = actualName, output = output)
    }

    override fun delete(path: String): Boolean {
        val id = documentIds[path] ?: return false
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
        val deleted = runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            .getOrDefault(false)
        if (deleted) {
            documentIds.remove(path)
            val parentPath = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            listCache[parentPath]?.remove(name.lowercase(Locale.US))
        }
        return deleted
    }

    private fun documentUri(path: String): Uri {
        val id = documentIds[path] ?: throw IOException("目标目录已失效")
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
    }

    private fun displayName(document: Uri): String? = runCatching {
        resolver.query(
            document,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
        }
    }.getOrNull()

    private fun safeActualName(actual: String?, fallback: String?): String? {
        val value = actual?.trim().orEmpty()
        if (value.isEmpty() || value == "." || value == ".." ||
            value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 ||
            value.any { it.isISOControl() }
        ) {
            return fallback?.takeIf { it.isNotEmpty() }
        }
        return value
    }

    private fun cachePut(parentPath: String, name: String, isDirectory: Boolean) {
        val map = listCache.getOrPut(parentPath) { LinkedHashMap() }
        map[name.lowercase(Locale.US)] = SinkInfo(name, isDirectory)
    }

    private fun joinPath(parentPath: String, name: String): String =
        if (parentPath.isEmpty()) name else "$parentPath/$name"
}
