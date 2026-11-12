package com.cryptoassistant.app.feature.files

import java.io.File
import java.util.Locale

/** 归档中的一个可分享/保存分卷；[partIndex] 是 1-based 卷号。 */
data class ArchiveSharePart(
    val file: File,
    val partIndex: Int,
)

/**
 * 归档结果的分享计划：按卷号排序、判断单卷/多卷、给出 MIME 与总字节。
 *
 * <p>P2-C 已经保证 `FileTaskResult.parts` 按写入顺序排列；这里仍从文件名
 * `_volNNN` 解析卷号，避免未来数据来源变化后把卷顺序交给 UI。解析不到卷号时
 * 保持原列表顺序，不猜测。</p>
 *
 * <p>纯 JVM，不读写 Android Uri；Intent 与租约由 [FileShare] 负责。</p>
 */
data class ArchiveSharePlan(
    val parts: List<ArchiveSharePart>,
    val mimeType: String,
    val totalBytes: Long,
) {
    val isMultiVolume: Boolean get() = parts.size > 1
    val isEmpty: Boolean get() = parts.isEmpty()
    val partCount: Int get() = parts.size
    val files: List<File> get() = parts.map { it.file }

    companion object {
        const val MIME_TYPE = "application/octet-stream"

        private val VOLUME_NAME = Regex("""_vol(\d{3,})\.aegix$""", RegexOption.IGNORE_CASE)

        /** 从归档结果生成计划；没有 `parts` 的旧结果退化为单个 [FileTaskResult.file]。 */
        fun from(result: FileTaskResult): ArchiveSharePlan {
            val sourceFiles = result.parts.ifEmpty { listOf(result.file) }
            return fromFiles(sourceFiles)
        }

        /** 测试与未来批量结果复用；按文件名卷号稳定排序。 */
        fun fromFiles(files: List<File>): ArchiveSharePlan {
            val ordered = files.mapIndexed { index, file ->
                ArchiveSharePart(
                    file = file,
                    partIndex = volumeIndex(file.name) ?: (index + 1),
                )
            }.sortedWith(compareBy<ArchiveSharePart> { it.partIndex })
            return ArchiveSharePlan(
                parts = ordered,
                mimeType = MIME_TYPE,
                totalBytes = ordered.sumOf { it.file.length().coerceAtLeast(0L) },
            )
        }

        /** `secure_file_..._vol001.aegix` → 1；单卷文件名没有 `_vol` → null。 */
        fun volumeIndex(fileName: String): Int? =
            VOLUME_NAME.find(fileName)?.groupValues?.getOrNull(1)?.toIntOrNull()

        /**
         * 同一目录下不覆盖已有文件的名字；`report.pdf` → `report_2.pdf`，
         * `.gitignore` → `.gitignore_2`。比较时忽略大小写，避免大小写不敏感的文件系统冲突。
         */
        fun uniqueName(
            fileName: String,
            usedNames: MutableSet<String>,
        ): String {
            if (usedNames.add(fileName.lowercase(Locale.US))) return fileName
            var index = 2
            while (true) {
                val candidate = appendIndex(fileName, index)
                if (usedNames.add(candidate.lowercase(Locale.US))) return candidate
                index++
            }
        }

        private fun appendIndex(fileName: String, index: Int): String {
            val dot = fileName.lastIndexOf('.')
            return if (dot > 0) {
                fileName.substring(0, dot) + "_" + index + fileName.substring(dot)
            } else {
                fileName + "_" + index
            }
        }
    }
}
