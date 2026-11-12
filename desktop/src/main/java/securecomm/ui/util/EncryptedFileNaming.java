package securecomm.ui.util;

import java.io.File;

/**
 * 加密产物命名：桌面端单文件输出统一使用 {@code .aegix} 容器后缀。
 *
 * <p>分卷输出由 {@code ExternalVolumeWriter} 负责（同样是 {@code .aegix}），
 * 这里只负责不分卷的单文件，避免各处再手写扩展名。</p>
 */
public final class EncryptedFileNaming {

    /** 桌面端用户可见的加密文件唯一后缀。 */
    public static final String EXTENSION = ".aegix";

    private EncryptedFileNaming() {
    }

    /**
     * 在目录中取一个不冲突的加密文件名，扩展名固定为 {@code .aegix}。
     */
    public static String uniqueName(File directory, String baseName) {
        return SecureTextFileUtils.getUniqueFileName(directory, baseName, EXTENSION);
    }
}
