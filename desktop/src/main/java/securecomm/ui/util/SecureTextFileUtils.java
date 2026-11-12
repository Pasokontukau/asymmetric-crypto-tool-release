package securecomm.ui.util;

import java.io.File;

/**
 * 安全文本面板的文件工具类
 */
public class SecureTextFileUtils {

    private SecureTextFileUtils() {}

    /**
     * 生成唯一的文件名。如果 baseName + extension 已存在，
     * 则在文件名后添加 _2、_3 等序号直到找到不存在的文件名。
     *
     * <p>与 Android `FileTaskStorage.uniqueFileName` 一致：首个文件不加序号，
     * 重名从 `_2` 开始，避免两边同一批文件出现不同的命名习惯。</p>
     */
    public static String getUniqueFileName(File directory, String baseName, String extension) {
        String nameWithoutExt = baseName;
        int lastDot = baseName.lastIndexOf('.');
        if (lastDot > 0) {
            nameWithoutExt = baseName.substring(0, lastDot);
        }

        String candidateName = nameWithoutExt + extension;
        File candidate = new File(directory, candidateName);

        if (!candidate.exists()) {
            return candidateName;
        }

        int counter = 2;
        while (true) {
            candidateName = nameWithoutExt + "_" + counter + extension;
            candidate = new File(directory, candidateName);
            if (!candidate.exists()) {
                return candidateName;
            }
            counter++;
            if (counter > 9999) {
                return nameWithoutExt + "_" + System.currentTimeMillis() + extension;
            }
        }
    }
}
