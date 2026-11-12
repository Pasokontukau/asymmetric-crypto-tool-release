package com.cryptoassistant.app.feature.files

/**
 * 归档进度：ZIP 阶段按源字节（未知时按文件数），分卷阶段按“已完成卷 + 当前卷内进度”。
 * 返回 0..1；只在 UI 层使用，不改变任务状态。
 */
internal object ArchiveProgress {

    fun overall(
        phase: ArchivePhase?,
        filesDone: Int,
        filesTotal: Int,
        processed: Long,
        total: Long,
        volumeIndex: Int?,
        volumeTotal: Int?,
    ): Float = when (phase) {
        ArchivePhase.PACKAGING -> {
            if (total > 0) {
                (processed.toFloat() / total).coerceIn(0f, 1f)
            } else if (filesTotal > 0) {
                (filesDone.toFloat() / filesTotal).coerceIn(0f, 1f)
            } else {
                0f
            }
        }

        ArchivePhase.ENCRYPTING,
        ArchivePhase.WRITING,
        -> {
            val count = volumeTotal ?: 0
            if (count <= 0) {
                0f
            } else {
                val index = (volumeIndex ?: 1).coerceIn(1, count)
                val partFraction = if (total > 0) {
                    (processed.toFloat() / total).coerceIn(0f, 1f)
                } else {
                    0f
                }
                (((index - 1) + partFraction) / count).coerceIn(0f, 1f)
            }
        }

        null -> 0f
    }
}
