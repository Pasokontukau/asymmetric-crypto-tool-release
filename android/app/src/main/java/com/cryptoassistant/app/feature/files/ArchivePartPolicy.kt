package com.cryptoassistant.app.feature.files

import crypto.volume.VolumePartPlan
import java.util.Locale

/**
 * app 侧的外部分卷策略。
 *
 * <p>[VolumePartPlan] 只做纯预算；这里补上 app 约定的命名、setCode 和 4 GiB/99 卷边界。
 * 所有方法都不读 DataStore、不碰文件系统，设置值由 P2-C 的 `start(bytes)` 传入。</p>
 */
object ArchivePartPolicy {

    /** 单卷上限的五档来自 [ArchiveVolumeSize]；这里只限制最终计划。 */
    val MAX_ZIP_BYTES: Long = VolumePartPlan.MAX_ARCHIVE_BYTES

    val MAX_PARTS: Int = VolumePartPlan.MAX_PARTS

    const val EXTENSION = ".aegix"

    /** 内部文件名的保守上界：`archive.zip.part999.part1` 的长度。 */
    private const val PART_NAME_SUFFIX = ".part999.part1"

    /**
     * 按 ZIP 实际字节数生成计划。
     *
     * <p>ZIP 必须在写完后再算卷边界，因此调用顺序固定为：写 ZIP → 取 `length()` →
     * [plan] → 逐卷写入。4 GiB+1、>99 卷都会在这里抛出
     * [IllegalArgumentException]，此时还没有产生任何分卷。</p>
     */
    fun plan(
        zipBytes: Long,
        volumeLimitBytes: Long,
        archiveName: String,
        rsaBits: Int = 4096,
    ): VolumePartPlan {
        val partNameBytes = archiveName.toByteArray(Charsets.UTF_8).size +
            PART_NAME_SUFFIX.toByteArray(Charsets.UTF_8).size
        return VolumePartPlan.create(zipBytes, volumeLimitBytes, partNameBytes, rsaBits)
    }

    /**
     * 单个原文件的分卷计划。
     *
     * <p>与 [plan] 共用卷大小、命名和 99 卷边界，但不再套 4 GiB 的 ZIP 上限；
     * 文件总大小可以超过 4 GiB，具体上限由单卷档位 × 99 决定。</p>
     */
    fun planForFile(
        fileBytes: Long,
        volumeLimitBytes: Long,
        fileName: String,
        rsaBits: Int = 4096,
    ): VolumePartPlan {
        val partNameBytes = fileName.toByteArray(Charsets.UTF_8).size +
            PART_NAME_SUFFIX.toByteArray(Charsets.UTF_8).size
        return VolumePartPlan.createForFile(
            fileBytes,
            volumeLimitBytes,
            partNameBytes,
            rsaBits,
        )
    }

    /** 便捷方法：只关心卷数时使用；失败语义与 [plan] 相同。 */
    fun partCount(
        zipBytes: Long,
        volumeLimitBytes: Long,
        archiveName: String,
        rsaBits: Int = 4096,
    ): Int = plan(zipBytes, volumeLimitBytes, archiveName, rsaBits).partCount

    fun partCountForFile(
        fileBytes: Long,
        volumeLimitBytes: Long,
        fileName: String,
        rsaBits: Int = 4096,
    ): Int = planForFile(fileBytes, volumeLimitBytes, fileName, rsaBits).partCount

    /** 取任务 ID 的前 6 位作为同一批次的可读标识；大小写统一为小写。 */
    fun setCode(setId: String): String {
        require(setId.length >= 6) { "setId 至少需要 6 位" }
        return setId.take(6).lowercase(Locale.US)
    }

    /**
     * 单卷输出名：`secure_file_<ts>.aegix`；多卷：
     * `secure_file_<ts>_<setCode>_volNNN.aegix`。
     *
     * <p>[baseName] 由 [FileTaskStorage.encryptedFileBaseName] 生成，不含扩展名。</p>
     */
    fun outputName(
        baseName: String,
        setId: String,
        partIndex: Int,
        partCount: Int,
    ): String {
        require(partCount in 1..MAX_PARTS) { "分卷数超出范围: $partCount" }
        require(partIndex in 1..partCount) { "分卷号超出范围: $partIndex" }
        if (partCount == 1) return baseName + EXTENSION
        return buildString {
            append(baseName)
            append('_')
            append(setCode(setId))
            append("_vol")
            append(partIndex.toString().padStart(3, '0'))
            append(EXTENSION)
        }
    }

    fun outputNames(
        baseName: String,
        setId: String,
        partCount: Int,
    ): List<String> = (1..partCount).map { partIndex ->
        outputName(baseName, setId, partIndex, partCount)
    }
}
