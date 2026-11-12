package com.cryptoassistant.app.feature.files

/**
 * 开始加密前的卷数预览。
 *
 * <p>单文件按实际字节数精确计算；归档只能在 ZIP 写完后才知道压缩后大小，
 * 这里用“源字节 + 每条目固定开销”的保守上界估算，界面必须标“预计”，
 * 不能把估算值当成实际写入卷数。</p>
 */
data class VolumePreviewLine(
    val name: String,
    val partCount: Int,
    val packaged: Boolean,
)

data class VolumeStartPreview(
    val volumeLimitLabel: String,
    val lines: List<VolumePreviewLine>,
    val totalParts: Int,
) {
    /** 与桌面端一致：超过 20 卷改用警告语气。 */
    val tooMany: Boolean get() = totalParts > 20
}

/** 不读文件、不写盘的纯预算；与真正写入共用 [ArchivePartPolicy]。 */
object VolumeStartPreviewPlanner {

    /** ZIP 中央目录 + 本地文件头的保守估计，只用于分卷预览。 */
    private const val ZIP_ENTRY_OVERHEAD_BYTES = 512L
    private const val ZIP_FIXED_OVERHEAD_BYTES = 512L

    /** 单文件直接切片：大小已知，卷数精确。 */
    fun singleFile(
        fileName: String,
        fileBytes: Long,
        volumeLimitBytes: Long,
        volumeLimitLabel: String,
    ): VolumeStartPreview? {
        if (volumeLimitBytes <= 0 || fileBytes <= volumeLimitBytes) return null
        val parts = ArchivePartPolicy.partCountForFile(
            fileBytes = fileBytes,
            volumeLimitBytes = volumeLimitBytes,
            fileName = fileName,
        )
        if (parts <= 1) return null
        return VolumeStartPreview(
            volumeLimitLabel = volumeLimitLabel,
            lines = listOf(VolumePreviewLine(fileName, parts, packaged = false)),
            totalParts = parts,
        )
    }

    /**
     * 多文件/文件夹打包：返回 null 表示按估算不会拆卷。
     *
     * @throws IllegalArgumentException 估算已超过 4 GiB 或 99 卷；调用方应提示减少文件
     * 或增大单卷上限，而不是继续开始。
     */
    fun archive(
        displayName: String,
        sourceBytes: Long,
        fileCount: Int,
        archiveRootName: String,
        volumeLimitBytes: Long,
        volumeLimitLabel: String,
    ): VolumeStartPreview? {
        if (volumeLimitBytes <= 0 || fileCount <= 0) return null
        val estimatedZipBytes = sourceBytes +
            fileCount * ZIP_ENTRY_OVERHEAD_BYTES +
            ZIP_FIXED_OVERHEAD_BYTES
        if (estimatedZipBytes > ArchivePartPolicy.MAX_ZIP_BYTES) {
            throw IllegalArgumentException("archive exceeds 4 GiB")
        }
        val parts = ArchivePartPolicy.partCount(
            zipBytes = estimatedZipBytes,
            volumeLimitBytes = volumeLimitBytes,
            archiveName = ArchivePathPolicy.archiveName(archiveRootName),
        )
        if (parts <= 1) return null
        return VolumeStartPreview(
            volumeLimitLabel = volumeLimitLabel,
            lines = listOf(VolumePreviewLine(displayName, parts, packaged = true)),
            totalParts = parts,
        )
    }
}
