package settings.model;

/**
 * 桌面端单卷大小档位，与 Android 的「文件处理 → 分卷大小」保持一致。
 *
 * <p>数值是十进制字节，不是 MiB：界面写「500 MB」时实际就是 500,000,000 B。
 * 五个档位同时用于单文件切片与文件夹 ZIP 切片，默认 500 MB。</p>
 */
public enum VolumeLimitPreset {

    MB_50(50_000_000L, "50 MB"),
    MB_100(100_000_000L, "100 MB"),
    MB_500(500_000_000L, "500 MB"),
    GB_1(1_000_000_000L, "1 GB"),
    GB_2(2_000_000_000L, "2 GB");

    /** 新安装与无法识别旧值时的默认档位。 */
    public static final VolumeLimitPreset DEFAULT = MB_500;

    private final long bytes;
    private final String label;

    VolumeLimitPreset(long bytes, String label) {
        this.bytes = bytes;
        this.label = label;
    }

    public long getBytes() {
        return bytes;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 解析持久化的枚举名；未知值、空值或旧格式一律回落到默认档。
     *
     * <p>当前没有需要迁移的旧设置；这里只保证设置文件被手工改坏时不会让应用起不来。</p>
     */
    public static VolumeLimitPreset fromStored(String stored) {
        if (stored == null || stored.trim().isEmpty()) {
            return DEFAULT;
        }
        for (VolumeLimitPreset preset : values()) {
            if (preset.name().equals(stored)) {
                return preset;
            }
        }
        return DEFAULT;
    }
}
