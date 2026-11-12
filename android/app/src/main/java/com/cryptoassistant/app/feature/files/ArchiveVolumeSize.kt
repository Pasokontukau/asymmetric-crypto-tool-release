package com.cryptoassistant.app.feature.files

import androidx.annotation.StringRes
import com.cryptoassistant.app.R

/**
 * 单个外部分卷的最终大小上限。
 *
 * <p>数值是十进制字节，不是 MiB：UI 写“50 MB”时实际就是 50,000,000 B。
 * 旧版本可能存 20/200 MB、OFF 或按 1024 计算的数值；读取时统一归一到
 * 500 MB，旧的 50/100/500 分别保留。</p>
 */
enum class ArchiveVolumeSize(
    val bytes: Long,
    @get:StringRes val labelRes: Int,
) {
    MB_50(50_000_000L, R.string.archive_volume_50mb),
    MB_100(100_000_000L, R.string.archive_volume_100mb),
    MB_500(500_000_000L, R.string.archive_volume_500mb),
    GB_1(1_000_000_000L, R.string.archive_volume_1gb),
    GB_2(2_000_000_000L, R.string.archive_volume_2gb),
    ;

    companion object {
        /** 默认 500 MB，与设置页默认选中项一致。 */
        val DEFAULT: ArchiveVolumeSize = MB_500

        private const val MIB = 1024L * 1024L
        private const val GIB = MIB * 1024L

        /**
         * 把旧 DataStore 里可能出现的字节数归一到当前五档。
         *
         * <p>20/200 MB、0/OFF 和任意未知值都落到默认 500 MB；旧 50/100/500
         * 无论按十进制还是按 1024 存储都保留；1 GB/2 GB 也兼容旧的 GiB 值。</p>
         */
        fun normalizeBytes(stored: Long?): ArchiveVolumeSize = when (stored) {
            MB_50.bytes, 50L * MIB -> MB_50
            MB_100.bytes, 100L * MIB -> MB_100
            MB_500.bytes, 500L * MIB -> MB_500
            GB_1.bytes, 1L * GIB -> GB_1
            GB_2.bytes, 2L * GIB -> GB_2
            else -> DEFAULT
        }

        /**
         * 解析 DataStore 里的字符串。优先按枚举名，其次按旧数字/旧档位名；
         * 无记录、损坏值、OFF、20/200 都返回默认 500 MB。
         */
        fun fromStored(stored: String?): ArchiveVolumeSize {
            entries.firstOrNull { it.name == stored }?.let { return it }
            return when (stored) {
                // 旧版本可能存过 20/200 MB 或 OFF；本版本不再提供这些档位。
                "MB_20", "MB_200", "OFF" -> DEFAULT
                // 兼容曾经按“MB 数值”而不是枚举名写入的旧记录。
                "50" -> MB_50
                "100" -> MB_100
                "500" -> MB_500
                "1000" -> GB_1
                "2000" -> GB_2
                else -> normalizeBytes(stored?.toLongOrNull())
            }
        }
    }
}
