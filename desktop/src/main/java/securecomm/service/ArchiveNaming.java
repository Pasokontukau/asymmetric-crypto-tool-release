package securecomm.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 多文件归档的命名规则（DR-3 步骤 3.2）。
 *
 * <p>与 {@link OutputNaming} 分开：后者负责单文件 {@code secure_file_} 产物，这里负责
 * “打包成一个 ZIP”时的根名与来源展示名。规则与 Android 端保持一致。</p>
 */
public final class ArchiveNaming {

    /** 归档根名前缀，不包含任何用户文件名。 */
    public static final String ARCHIVE_PREFIX = "archive_";

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private ArchiveNaming() {
    }

    /** 归档根名：{@code archive_<yyyyMMdd_HHmmss>}。 */
    public static String newArchiveRootName(LocalDateTime now) {
        return ARCHIVE_PREFIX + now.format(TIMESTAMP);
    }

    public static String newArchiveRootName() {
        return newArchiveRootName(LocalDateTime.now());
    }

    /**
     * 分卷时写进卷头的来源归档名。
     *
     * @param folderZip         来源是文件夹打的临时 ZIP
     * @param folderName        登记的原文件夹名（{@code folderZip} 为 true 时使用）
     * @param registeredArchive 已登记的归档名，可为 {@code null}
     * @param fileName          原始文件名
     */
    public static String archiveNameFor(boolean folderZip, String folderName,
                                        String registeredArchive, String fileName) {
        if (folderZip) {
            return folderName + ".zip";
        }
        return registeredArchive != null ? registeredArchive : fileName;
    }

    /** 预览里展示来源用的名字：文件夹标“（文件夹）”，其余用文件名。 */
    public static String displayNameFor(boolean folderZip, String folderName, String fileName) {
        return folderZip ? folderName + "（文件夹）" : fileName;
    }
}
