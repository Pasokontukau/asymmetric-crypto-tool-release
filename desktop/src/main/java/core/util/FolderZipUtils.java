package core.util;

import core.util.LogManager;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class FolderZipUtils {

    /** 与 Android 归档解压保持同一组上限。 */
    public static final int MAX_ENTRIES = 5000;
    public static final long MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024;
    private static final int MAX_PATH_BYTES = 1024;
    private static final int MAX_SEGMENTS = 32;
    private static final int MAX_SEGMENT_CHARS = 255;

    public interface ProgressListener {
        void onProgress(int current, int total, String currentItem);
    }

    /**
     * 压缩文件夹 - 优化版本
     */
    public static void zipFolder(File sourceFolder, File zipFile, ProgressListener progressListener) throws Exception {
        if (!sourceFolder.isDirectory()) {
            throw new IllegalArgumentException("源不是文件夹: " + sourceFolder);
        }

        // 使用自定义的文件列表收集，避免两次遍历；根目录和空目录也要进 ZIP。
        FileEntryCollector collector = new FileEntryCollector();
        collector.addDirectory(sourceFolder.getName());
        collectFiles(sourceFolder, sourceFolder.getName(), collector);

        int totalFiles = collector.getFileCount();
        int[] currentFile = {0};

        try (FileOutputStream fos = new FileOutputStream(zipFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos, 65536);  // 更大的缓冲区
             ZipOutputStream zos = new ZipOutputStream(bos)) {
            
            // 设置更快的压缩级别（不影响安全性，只是压缩速度）
            zos.setLevel(Deflater.BEST_SPEED);
            zos.setMethod(ZipOutputStream.DEFLATED);  // 明确使用DEFLATED
            
            zipFolderWithEntries(collector.getEntries(), zos, progressListener, totalFiles, currentFile);
        }
    }

    /**
     * 把多个普通文件打包成一个 ZIP；条目名只保留文件名，重名自动加 _2。
     *
     * <p>与 Android 的多文件归档一致：所有条目放在 {@code rootName/} 根目录下，
     * 接收方解压后得到一个以归档名命名的目录。{@code rootName} 不带 {@code .zip}。</p>
     */
    public static void zipFiles(
            List<File> files,
            File zipFile,
            String rootName,
            ProgressListener progressListener
    ) throws Exception {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("没有可打包的文件");
        }
        String root = archiveRootName(rootName);
        List<File> readable = new ArrayList<>();
        for (File file : files) {
            if (file != null && file.isFile()) {
                readable.add(file);
            }
        }
        if (readable.isEmpty()) {
            throw new IllegalArgumentException("没有可打包的文件");
        }

        Set<String> usedNames = new HashSet<>();
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos, 65536);
             ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.setLevel(Deflater.BEST_SPEED);
            zos.setMethod(ZipOutputStream.DEFLATED);

            zos.putNextEntry(new ZipEntry(root + "/"));
            zos.closeEntry();

            byte[] buffer = new byte[65536];
            int done = 0;
            for (File file : readable) {
                String entryName = uniqueEntryName(file.getName(), usedNames);
                if (progressListener != null) {
                    progressListener.onProgress(done + 1, readable.size(), entryName);
                }
                zos.putNextEntry(new ZipEntry(root + "/" + entryName));
                try (FileInputStream fis = new FileInputStream(file);
                     BufferedInputStream bis = new BufferedInputStream(fis, 65536)) {
                    int bytesRead;
                    while ((bytesRead = bis.read(buffer)) != -1) {
                        zos.write(buffer, 0, bytesRead);
                    }
                }
                zos.closeEntry();
                done++;
            }
        }
    }

    /** 归档根目录只允许一个安全段；带 `.zip` 时自动去掉，避免生成 `x.zip/`。 */
    private static String archiveRootName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            name = name.substring(0, name.length() - 4);
        }
        if (name.isEmpty()
                || ".".equals(name)
                || "..".equals(name)
                || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0
                || name.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("归档根目录名不安全: " + rawName);
        }
        for (int index = 0; index < name.length(); index++) {
            if (Character.isISOControl(name.charAt(index))) {
                throw new IllegalArgumentException("归档根目录名包含控制字符");
            }
        }
        return name;
    }

    /** 同名文件插 _2/_3，避免 ZIP 内条目重复。 */
    private static String uniqueEntryName(String rawName, Set<String> used) {
        String name = rawName == null || rawName.trim().isEmpty() ? "file" : rawName;
        if (used.add(name.toLowerCase(Locale.ROOT))) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int suffix = 2;
        while (true) {
            String candidate = base + "_" + suffix + ext;
            if (used.add(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
            suffix++;
        }
    }

    /**
     * 使用已收集的文件列表进行压缩
     */
    private static void zipFolderWithEntries(FileEntry[] entries, ZipOutputStream zos,
                                              ProgressListener progressListener, int totalFiles, int[] currentFile) throws IOException {
        byte[] buffer = new byte[65536];  // 更大的缓冲区
        
        for (FileEntry entry : entries) {
            if (entry.isDirectory) {
                ZipEntry zipEntry = new ZipEntry(entry.entryPath + "/");
                zos.putNextEntry(zipEntry);
                zos.closeEntry();
            } else {
                currentFile[0]++;
                if (progressListener != null) {
                    progressListener.onProgress(currentFile[0], totalFiles, entry.file.getName());
                }

                ZipEntry zipEntry = new ZipEntry(entry.entryPath);
                zos.putNextEntry(zipEntry);

                try (FileInputStream fis = new FileInputStream(entry.file);
                     BufferedInputStream bis = new BufferedInputStream(fis, 65536)) {
                    int bytesRead;
                    while ((bytesRead = bis.read(buffer)) != -1) {
                        zos.write(buffer, 0, bytesRead);
                    }
                }

                zos.closeEntry();
            }
        }
    }

    /**
     * 内部类：收集文件列表
     */
    private static class FileEntryCollector {
        private java.util.List<FileEntry> entries = new java.util.ArrayList<>();
        private int fileCount = 0;

        void addFile(File file, String entryPath) {
            entries.add(new FileEntry(file, entryPath, false));
            fileCount++;
        }

        void addDirectory(String entryPath) {
            entries.add(new FileEntry(null, entryPath, true));
        }

        FileEntry[] getEntries() {
            return entries.toArray(new FileEntry[0]);
        }

        int getFileCount() {
            return fileCount;
        }
    }

    /**
     * 单个文件条目
     */
    private static class FileEntry {
        final File file;
        final String entryPath;
        final boolean isDirectory;

        FileEntry(File file, String entryPath, boolean isDirectory) {
            this.file = file;
            this.entryPath = entryPath;
            this.isDirectory = isDirectory;
        }
    }

    /**
     * 一次性收集所有文件（避免两次遍历）
     */
    private static void collectFiles(File folder, String entryPath, FileEntryCollector collector) {
        File[] files = folder.listFiles();
        if (files == null) return;

        for (File file : files) {
            String newEntryPath = entryPath + "/" + file.getName();
            if (file.isDirectory()) {
                collector.addDirectory(newEntryPath);
                collectFiles(file, newEntryPath, collector);
            } else {
                collector.addFile(file, newEntryPath);
            }
        }
    }

    /**
     * 解压缩文件夹。ZIP 是不可信输入：限制条目数/解压总量/路径形状，
     * 同名条目或目标目录中已有文件时追加 `_2`，不覆盖用户文件。
     */
    public static void unzipFolder(File zipFile, File destFolder, ProgressListener progressListener) throws Exception {
        if (destFolder == null) {
            throw new IOException("解压目录不能为空");
        }
        if (!destFolder.exists() && !destFolder.mkdirs() && !destFolder.isDirectory()) {
            throw new IOException("无法创建解压目录: " + destFolder);
        }

        try (FileInputStream fis = new FileInputStream(zipFile);
             BufferedInputStream bis = new BufferedInputStream(fis, 65536);
             ZipInputStream zis = new ZipInputStream(bis)) {

            ZipEntry entry;
            int currentEntry = 0;
            long totalBytes = 0L;
            byte[] buffer = new byte[65536];
            
            while ((entry = zis.getNextEntry()) != null) {
                currentEntry++;
                if (currentEntry > MAX_ENTRIES) {
                    throw new IOException("ZIP 条目超过 " + MAX_ENTRIES + " 个");
                }
                String entryName = safeEntryName(entry);
                File destFile = validateZipEntry(entryName, destFolder);

                if (progressListener != null) {
                    progressListener.onProgress(currentEntry, -1, entryName);
                }

                if (entry.isDirectory()) {
                    uniqueDirectory(destFile).mkdirs();
                } else {
                    File target = uniqueFile(destFile);
                    File parentDir = target.getParentFile();
                    if (parentDir != null && !parentDir.exists()
                            && !parentDir.mkdirs() && !parentDir.isDirectory()) {
                        throw new IOException("无法创建解压目录: " + parentDir);
                    }

                    try (FileOutputStream fos = new FileOutputStream(target);
                         BufferedOutputStream bos = new BufferedOutputStream(fos, 65536)) {
                        int bytesRead;
                        while ((bytesRead = zis.read(buffer)) != -1) {
                            totalBytes += bytesRead;
                            if (totalBytes > MAX_TOTAL_BYTES) {
                                throw new IOException("解压总量超过 4 GiB");
                            }
                            bos.write(buffer, 0, bytesRead);
                        }
                    }
                }

                zis.closeEntry();
            }
        }
    }

    /** 校验 ZIP 条目名，拒绝绝对路径、盘符、反斜杠、控制字符和过深路径。 */
    private static String safeEntryName(ZipEntry entry) throws IOException {
        String raw = entry.getName();
        if (raw == null) {
            throw new IOException("ZIP 条目名为空");
        }
        String name = entry.isDirectory() ? raw.substring(0, Math.max(0, raw.length() - 1)) : raw;
        if (name.isEmpty()) {
            throw new IOException("ZIP 条目名为空");
        }
        if (name.startsWith("/") || name.startsWith("\\")) {
            throw new IOException("ZIP 条目不能是绝对路径: " + name);
        }
        if (name.indexOf('\\') >= 0 || name.indexOf('\u0000') >= 0) {
            throw new IOException("ZIP 条目名包含非法字符");
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES) {
            throw new IOException("ZIP 条目路径超过 " + MAX_PATH_BYTES + " 字节");
        }
        for (int index = 0; index < name.length(); index++) {
            if (Character.isISOControl(name.charAt(index))) {
                throw new IOException("ZIP 条目名包含控制字符");
            }
        }

        String[] segments = name.split("/", -1);
        if (segments.length > MAX_SEGMENTS) {
            throw new IOException("ZIP 条目路径超过 " + MAX_SEGMENTS + " 段");
        }
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("ZIP 条目包含空段或相对路径段");
            }
            if (segment.length() > MAX_SEGMENT_CHARS) {
                throw new IOException("ZIP 条目路径段过长");
            }
            if (index == 0 && segment.length() >= 2
                    && segment.charAt(1) == ':'
                    && Character.isLetter(segment.charAt(0))) {
                throw new IOException("ZIP 条目不能包含盘符");
            }
        }
        return name;
    }

    /** 目标目录已有同名文件/目录时，选一个不覆盖的 `_2`、`_3` 名字。 */
    private static File uniqueFile(File target) throws IOException {
        if (!target.exists()) {
            return target;
        }
        String name = target.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int index = 2; index < 10000; index++) {
            File candidate = new File(target.getParentFile(), base + "_" + index + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        throw new IOException("无法为同名条目分配输出名: " + name);
    }

    private static File uniqueDirectory(File target) throws IOException {
        if (!target.exists()) {
            return target;
        }
        if (target.isDirectory()) {
            return target;
        }
        for (int index = 2; index < 10000; index++) {
            File candidate = new File(target.getParentFile(), target.getName() + "_" + index);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        throw new IOException("无法为同名目录分配输出名: " + target.getName());
    }

    public static void copyFolder(File sourceFolder, File destFolder) throws IOException {
        if (!sourceFolder.isDirectory()) {
            throw new IllegalArgumentException("源不是文件夹: " + sourceFolder);
        }

        if (!destFolder.exists()) {
            destFolder.mkdirs();
        }

        File[] files = sourceFolder.listFiles();
        if (files == null) return;

        for (File file : files) {
            File destFile = new File(destFolder, file.getName());
            if (file.isDirectory()) {
                copyFolder(file, destFile);
            } else {
                Files.copy(file.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static File validateZipEntry(String entryName, File destDir) throws IOException {
        File destFile = new File(destDir, entryName);
        String canonicalDestPath = destFile.getCanonicalPath();
        String canonicalDestDirPath = destDir.getCanonicalPath() + File.separator;

        if (!canonicalDestPath.startsWith(canonicalDestDirPath)) {
            throw new IOException("检测到潜在的路径遍历攻击: " + entryName);
        }

        return destFile;
    }

    /**
     * 计算文件夹大小（优化版，减少不必要的遍历）
     */
    public static long calculateFolderSize(File folder) {
        if (!folder.exists()) {
            return 0;
        }

        if (!folder.isDirectory()) {
            return folder.length();
        }

        long size = 0;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                size += calculateFolderSize(file);
            }
        }
        return size;
    }

    public static boolean safeDelete(File fileOrFolder) {
        if (!fileOrFolder.exists()) {
            return true;
        }

        if (fileOrFolder.isDirectory()) {
            File[] files = fileOrFolder.listFiles();
            if (files != null) {
                for (File file : files) {
                    safeDelete(file);
                }
            }
        }

        boolean deleted = false;
        int attempts = 0;
        while (!deleted && attempts < 3) {
            try {
                deleted = fileOrFolder.delete();
            } catch (Exception e) {
                LogManager.warning("删除失败，准备重试", e);
            }
            attempts++;
            if (!deleted) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return deleted;
    }
}
