package securecomm.service;

import java.io.File;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 选中文件的类型判定（解耦 DR-1）。
 *
 * <p>这里只回答"这个文件是什么"，不做任何 IO 写入、不依赖 Swing、不读密钥。
 * 判定口径与现有实现保持一致：
 * 加密产物统一是 {@code .aegix}；外部分卷名形如
 * {@code secure_file_&lt;时间戳&gt;_&lt;集合短码&gt;_vol001.aegix}（1-based 三位卷号）。</p>
 */
public final class FileTypeClassifier {

    /** 桌面端用户可见的加密文件唯一后缀。 */
    public static final String ENCRYPTED_EXTENSION = ".aegix";

    /** 分卷名：{@code _vol} + 三位 1-based 卷号 + 后缀。 */
    private static final Pattern EXTERNAL_PART_NAME =
            Pattern.compile("_vol\\d{3}\\.aegix$", Pattern.CASE_INSENSITIVE);

    /** 文件类别。 */
    public enum Kind {
        /** 单个 {@code .aegix} 容器（未分卷）。 */
        ENCRYPTED_SINGLE,
        /** 外部分卷中的一卷。 */
        EXTERNAL_PART,
        /** 普通文件（含文本文件）。 */
        PLAIN_FILE,
        /** 文件夹。 */
        FOLDER,
        /** 路径不存在或参数为空。 */
        MISSING
    }

    private FileTypeClassifier() {
    }

    /**
     * 文件名是否是加密产物（不区分大小写，只看后缀）。
     * 现有代码里散落的 {@code name.toLowerCase().endsWith(".aegix")} 统一走这里。
     */
    public static boolean isEncryptedFileName(String name) {
        return name != null
                && name.toLowerCase(Locale.ROOT).endsWith(ENCRYPTED_EXTENSION);
    }

    /** 文件名是否是外部分卷的一卷。 */
    public static boolean isExternalPartName(String name) {
        return isEncryptedFileName(name)
                && EXTERNAL_PART_NAME.matcher(name).find();
    }

    /** 判定一个已选中条目的类别。 */
    public static Kind classify(File file) {
        if (file == null || !file.exists()) {
            return Kind.MISSING;
        }
        if (file.isDirectory()) {
            return Kind.FOLDER;
        }
        String name = file.getName();
        if (isExternalPartName(name)) {
            return Kind.EXTERNAL_PART;
        }
        if (isEncryptedFileName(name)) {
            return Kind.ENCRYPTED_SINGLE;
        }
        return Kind.PLAIN_FILE;
    }
}
