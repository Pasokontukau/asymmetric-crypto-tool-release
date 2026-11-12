package com.cryptoassistant.app.feature.files

import java.io.IOException

/** 归档空间不足；携带估算值，界面据此显示“需要/可用”。 */
internal class ArchiveSpaceException(
    val requiredBytes: Long,
    val availableBytes: Long,
) : IOException(
    "归档空间不足: required=$requiredBytes available=$availableBytes",
)

/**
 * 归档前的磁盘空间预检。
 *
 * <p>ZIP 与分卷会同时存在，峰值约为源文件大小的两倍；再为 ZIP 目录项、
 * VOLU 头和文件系统保留一点余量。源大小未知时跳过预检，由写入阶段的
 * ENOSPC/IO 错误兜底。</p>
 */
internal object ArchiveSpacePreflight {

    /** 2× 源大小之外的固定余量，覆盖 ZIP 元数据和最多 99 卷的 4 KiB 头部。 */
    internal const val HEADROOM_BYTES: Long = 16L * 1024 * 1024

    /**
     * 估算成功归档所需的可用空间：`2 × source + 16 MiB`。
     * 溢出时返回 [Long.MAX_VALUE]，让预检在正常设备上必然失败而不是回绕成负数。
     */
    fun estimateRequiredBytes(sourceBytes: Long): Long {
        require(sourceBytes >= 0) { "源文件总大小不能为负数" }
        val doubled = if (sourceBytes > Long.MAX_VALUE / 2) {
            Long.MAX_VALUE
        } else {
            sourceBytes * 2
        }
        return if (doubled > Long.MAX_VALUE - HEADROOM_BYTES) {
            Long.MAX_VALUE
        } else {
            doubled + HEADROOM_BYTES
        }
    }

    /**
     * @param availableBytes 目标文件系统可用字节；小于等于 0 视为“系统没有报告”，跳过预检。
     * @param sourceBytes 已知源总大小；null 表示有文件大小未知，跳过预检。
     * @throws ArchiveSpaceException 可用空间严格小于估算值。
     */
    fun check(availableBytes: Long, sourceBytes: Long?) {
        if (sourceBytes == null || availableBytes <= 0) return
        val required = estimateRequiredBytes(sourceBytes)
        if (availableBytes < required) {
            throw ArchiveSpaceException(requiredBytes = required, availableBytes = availableBytes)
        }
    }
}
