package securecomm.service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import securecomm.ui.util.EncryptedFileNaming;

/**
 * 加密产物的命名规则（解耦 DR-1）。
 *
 * <p>统一三件事，避免各处再手写：</p>
 * <ol>
 *   <li>基础名：{@code secure_file_<yyyyMMdd_HHmmss>}（与 Android 一致）。</li>
 *   <li>唯一名：首个文件不带序号，重名从 {@code _2} 开始（委托
 *       {@link EncryptedFileNaming}，保持既有实现与测试）。</li>
 *   <li>进度里显示的文件名截断：超过上限取前段加省略号。</li>
 * </ol>
 */
public final class OutputNaming {

    /** 加密产物基础名前缀。 */
    public static final String BASE_PREFIX = "secure_file_";

    /** 基础名里的时间戳格式。 */
    public static final String TIMESTAMP_PATTERN = "yyyyMMdd_HHmmss";

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN);

    private OutputNaming() {
    }

    /** 生成加密产物基础名，例如 {@code secure_file_20261112_153000}。 */
    public static String encryptedBaseName(LocalDateTime time) {
        return BASE_PREFIX + TIMESTAMP_FORMAT.format(time);
    }

    /** 在目录里取一个不冲突的加密文件名（扩展名固定 {@code .aegix}）。 */
    public static String uniqueEncryptedName(File directory, String baseName) {
        return EncryptedFileNaming.uniqueName(directory, baseName);
    }

    /** 进度文案里的文件名截断：超过 {@code max} 个字符时保留前 {@code max-3} 个加 {@code ...}。 */
    public static String truncateForDisplay(String name, int max) {
        if (name == null || name.length() <= max || max <= 3) {
            return name;
        }
        return name.substring(0, max - 3) + "...";
    }
}
