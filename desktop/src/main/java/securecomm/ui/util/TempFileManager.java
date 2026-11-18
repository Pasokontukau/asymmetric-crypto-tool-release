package securecomm.ui.util;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import core.util.FolderZipUtils;

/**
 * 临时文件管理器
 * 管理加密文件夹时生成的临时 ZIP 文件，确保程序退出时能正确清理。
 */
public class TempFileManager {

    /** 临时 ZIP 文件列表 */
    private static final List<File> tempZipFiles = Collections.synchronizedList(new ArrayList<>());
    /** 文件夹临时 ZIP 对应的原文件夹名；只放内存，不拼进临时文件名。 */
    private static final Map<File, String> folderNames = new HashMap<>();
    /** 多文件打包 ZIP 对应的归档名（`archive_<时间戳>.zip`）。 */
    private static final Map<File, String> archiveNames = new HashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(TempFileManager::cleanAll));
    }

    private TempFileManager() {
    }

    public static void registerZipFile(File file) {
        synchronized (tempZipFiles) {
            tempZipFiles.add(file);
        }
    }

    /** 登记文件夹临时 ZIP，并记住原文件夹名；临时文件名本身仍只含固定前缀。 */
    public static void registerFolderZip(File file, String folderName) {
        registerZipFile(file);
        if (file != null && folderName != null && !folderName.trim().isEmpty()) {
            synchronized (tempZipFiles) {
                folderNames.put(file, folderName.trim());
            }
        }
    }

    /** 登记多文件打包 ZIP，并记住归档名（ZIP 根目录名去掉 `.zip`）。 */
    public static void registerArchiveZip(File file, String archiveName) {
        registerZipFile(file);
        if (file != null && archiveName != null && !archiveName.trim().isEmpty()) {
            synchronized (tempZipFiles) {
                archiveNames.put(file, archiveName.trim());
            }
        }
    }

    public static void unregisterZipFile(File file) {
        synchronized (tempZipFiles) {
            tempZipFiles.remove(file);
            folderNames.remove(file);
            archiveNames.remove(file);
        }
    }

    /** 只有文件夹临时 ZIP 才返回 true；多文件打包 ZIP 返回 false。 */
    public static boolean isFolderZip(File file) {
        synchronized (tempZipFiles) {
            return folderNames.containsKey(file);
        }
    }

    public static String getFolderName(File file) {
        synchronized (tempZipFiles) {
            return folderNames.get(file);
        }
    }

    /** 优先取登记时的原文件夹名；只有旧调用点没有登记时才从临时名兜底。 */
    public static String resolveFolderName(File zipFile) {
        String registered = getFolderName(zipFile);
        if (registered != null) {
            return registered;
        }
        String tempName = zipFile.getName();
        String folderName = null;
        if (tempName.contains("_")) {
            int lastUnderscore = tempName.lastIndexOf('_');
            folderName = tempName.substring(0, lastUnderscore);
        }
        if (folderName == null || folderName.isEmpty()) {
            folderName = tempName.replace(".zip", "");
        }
        return folderName;
    }

    public static String getArchiveName(File file) {
        synchronized (tempZipFiles) {
            return archiveNames.get(file);
        }
    }

    public static boolean isZipFile(File file) {
        synchronized (tempZipFiles) {
            return tempZipFiles.contains(file);
        }
    }

    public static void cleanAll() {
        synchronized (tempZipFiles) {
            for (File tempFile : tempZipFiles) {
                FolderZipUtils.safeDelete(tempFile);
            }
            tempZipFiles.clear();
            folderNames.clear();
            archiveNames.clear();
        }
    }
}
