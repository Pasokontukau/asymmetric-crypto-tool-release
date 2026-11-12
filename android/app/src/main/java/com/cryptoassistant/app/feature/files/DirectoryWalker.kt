package com.cryptoassistant.app.feature.files

/**
 * 目录递归扫描的纯逻辑层。
 *
 * <p>Android 侧只负责把 SAF 的目录查询包装成 [DirectoryEntry]；递归、过滤、排序、
 * 数量/大小/深度限制都在这里完成，因此可以用 JVM 单测覆盖边界，不需要真实 SAF 树。</p>
 *
 * <p>安全边界：只读取目录结构、文件名、大小与 MIME，不打开文件内容；
 * 文件内容仍由现有队列在真正处理时按需流式读取。</p>
 */
data class DirectoryEntry(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long? = null,
)

data class DirectoryFile(
    val id: String,
    val name: String,
    /** 相对所选根目录的展示路径，例如 `季度/报表.xlsx`；只用于 UI，不写进密文文件头。 */
    val relativePath: String,
    val size: Long?,
)

data class DirectoryScanResult(
    val files: List<DirectoryFile>,
    /** 解密模式下被跳过的非 `.aegix` 文件数；加密模式恒为 0。 */
    val skippedNonMatching: Int,
    /** 加密模式下需要写进 ZIP 的空目录相对路径；解密模式恒为空。 */
    val emptyDirectories: List<String> = emptyList(),
)

class DirectoryScanException(
    message: String,
    /** 目录本身能读，但没有任何可处理的文件；界面据此给出与“读取失败”不同的提示。 */
    val isEmpty: Boolean = false,
) : Exception(message)

class DirectoryLimitException(message: String) : Exception(message)

object DirectoryWalker {

    /** 单批目录上限：避免一次误选整个相册/下载目录后长时间占用设备。 */
    const val MAX_FILES = FileImportLimits.MAX_FILES

    /** 已知大小的文件总上限；大小未知的文件不参与该判断，交给加密预检单独拦截。 */
    const val MAX_TOTAL_BYTES = FileImportLimits.MAX_TOTAL_BYTES

    /** 目录条目总数上限（含目录），防止超大目录把扫描拖死。 */
    const val MAX_ENTRIES = 5_000

    const val MAX_DEPTH = 20

    /**
     * 从 [rootDocumentId] 开始递归收集文件。[listChildren] 由平台层注入，
     * 返回同一目录下的直接子项；异常由调用方统一翻译成用户提示。
     */
    fun collect(
        rootDocumentId: String,
        mode: FileTaskMode,
        listChildren: (String) -> List<DirectoryEntry>,
        maxFiles: Int = MAX_FILES,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
        maxEntries: Int = MAX_ENTRIES,
        maxDepth: Int = MAX_DEPTH,
    ): DirectoryScanResult {
        require(maxFiles > 0) { "maxFiles 必须大于 0" }
        require(maxTotalBytes > 0) { "maxTotalBytes 必须大于 0" }
        require(maxEntries > 0) { "maxEntries 必须大于 0" }
        require(maxDepth >= 0) { "maxDepth 不能为负" }

        val files = mutableListOf<DirectoryFile>()
        val emptyDirectories = mutableListOf<String>()
        val visitedDirectories = mutableSetOf(rootDocumentId)
        var seenEntries = 0
        var knownBytes = 0L
        var skipped = 0

        fun walk(documentId: String, prefix: String, depth: Int): Boolean {
            if (depth > maxDepth) {
                throw DirectoryLimitException("目录层级超过 $maxDepth 层，请选择更浅的目录")
            }
            val children = listChildren(documentId)
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            var hasFile = false

            for (child in children) {
                seenEntries++
                if (seenEntries > maxEntries) {
                    throw DirectoryLimitException(
                        "目录里的条目超过 $maxEntries 个，请选择更小的目录",
                    )
                }

                if (child.isDirectory) {
                    // 个别 provider 可能返回指向祖先的目录；用 docId 去重防止无限递归。
                    if (!visitedDirectories.add(child.id)) continue
                    val nextPrefix = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                    val childHasFile = walk(child.id, nextPrefix, depth + 1)
                    if (mode == FileTaskMode.ENCRYPT && !childHasFile) {
                        emptyDirectories += nextPrefix
                    }
                    hasFile = hasFile || childHasFile
                    continue
                }

                // 解密模式只收 .aegix，避免把相册里的图片等无关文件全部送进解密队列。
                if (mode == FileTaskMode.DECRYPT &&
                    !EncryptedFileFormat.isEncryptedFileName(child.name)
                ) {
                    skipped++
                    continue
                }

                if (files.size >= maxFiles) {
                    throw DirectoryLimitException(
                        "目录里的文件超过 $maxFiles 个，请选择更小的目录",
                    )
                }

                val size = child.size?.takeIf { it >= 0 }
                if (size != null) {
                    knownBytes += size
                    if (knownBytes > maxTotalBytes) {
                        throw DirectoryLimitException(
                            "目录里的文件总大小超过 ${maxTotalBytes / (1024L * 1024L * 1024L)} GB，" +
                                "请选择更小的目录",
                        )
                    }
                }

                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                files += DirectoryFile(
                    id = child.id,
                    name = child.name,
                    relativePath = path,
                    size = size,
                )
                hasFile = true
            }
            return hasFile
        }

        walk(rootDocumentId, "", 0)

        if (files.isEmpty()) {
            throw DirectoryScanException(
                message = if (mode == FileTaskMode.DECRYPT) {
                    "这个目录里没有找到 .aegix 文件（解密模式只处理 .aegix）"
                } else {
                    "这个目录里没有可处理的文件"
                },
                isEmpty = true,
            )
        }

        return DirectoryScanResult(
            files = files.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.relativePath }),
            skippedNonMatching = skipped,
            emptyDirectories = emptyDirectories.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it },
            ),
        )
    }
}
