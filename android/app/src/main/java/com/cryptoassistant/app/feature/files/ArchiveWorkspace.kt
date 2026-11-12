package com.cryptoassistant.app.feature.files

import java.io.File
import java.io.IOException

/**
 * 打包工作目录：`files/archive-work/<task-id>/archive.zip`。
 *
 * <p>ZIP 是明文，只允许短暂存在；P2-C 在加密成功/失败后都必须调用 [delete]。
 * [cleanup] 是冷启动兜底入口，P2-C 再把它接到 `CryptoAssistantApplication`。</p>
 */
object ArchiveWorkspace {

    const val DIRECTORY_NAME = "archive-work"
    const val ZIP_NAME = "archive.zip"

    fun create(root: File, taskId: String): File {
        val safeTaskId = ArchivePathPolicy.sanitizeSegment(taskId)
        val directory = File(File(root, DIRECTORY_NAME), safeTaskId)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("无法创建归档工作目录: $directory")
        }
        return directory
    }

    /** 新任务目录里的 ZIP 路径；目录不存在时创建。 */
    fun zipFile(root: File, taskId: String): File = File(create(root, taskId), ZIP_NAME)

    /** 删除单个任务目录；幂等，返回真实结果。 */
    fun delete(root: File, taskId: String): Boolean {
        val safeTaskId = ArchivePathPolicy.sanitizeSegment(taskId)
        return FileTaskStorage.deleteRecursively(File(File(root, DIRECTORY_NAME), safeTaskId))
    }

    /**
     * 冷启动清理：只清 `archive-work/` 下的子目录，不碰 `file-tasks/`、keys 或日志。
     *
     * @return 实际删掉的子目录数。
     */
    fun cleanup(root: File): Int {
        val base = File(root, DIRECTORY_NAME)
        val children = base.listFiles() ?: return 0
        var deleted = 0
        children.forEach { child ->
            if (FileTaskStorage.deleteRecursively(child)) deleted++
        }
        return deleted
    }
}
