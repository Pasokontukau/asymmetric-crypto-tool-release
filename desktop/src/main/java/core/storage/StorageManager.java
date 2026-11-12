package core.storage;

import java.io.File;
import java.io.IOException;
import java.lang.RuntimeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class StorageManager {
    
    private static String dataDir;
    private static String keypairsDir;
    private static String contactsDir;
    private static String configDir;
    private static boolean fallbackDataDir;
    private static String unavailableDataDir;
    
    static {
        initDataDir();
        createDirectories();
    }
    
    private static void initDataDir() {
        applyDataDir(Paths.get(DataDirectoryConfig.get()));
    }

    private static void applyDataDir(Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        dataDir = normalized.toString();
        keypairsDir = normalized.resolve("keypairs").toString();
        contactsDir = normalized.resolve("contacts").toString();
        configDir = normalized.resolve("config").toString();
    }
    
    public static String getDataDir() {
        return dataDir;
    }

    /** 测试钩子：临时切换数据目录；生产代码不要调用。调用方负责恢复原目录。 */
    public static void useDataDirForTests(String dir) {
        applyDataDir(Paths.get(dir));
    }

    public static boolean isUsingFallbackDataDir() {
        return fallbackDataDir;
    }

    /**
     * 回退前尝试使用的数据目录；未回退时为 {@code null}。
     */
    public static String getUnavailableDataDir() {
        return unavailableDataDir;
    }

    private static void createDirectories() {
        Path preferred = Paths.get(dataDir);
        try {
            ensureWritableDataDir(preferred);
            return;
        } catch (IOException e) {
            System.err.println("[StorageManager] Data directory is not writable");
        }

        Path fallback = AppPaths.userDataDir().resolve("data").toAbsolutePath().normalize();
        if (fallback.equals(preferred.toAbsolutePath().normalize())) {
            throw new RuntimeException("Failed to create data directory: " + preferred);
        }

        applyDataDir(fallback);
        fallbackDataDir = true;
        unavailableDataDir = preferred.toString();
        try {
            ensureWritableDataDir(fallback);
        } catch (IOException fallbackError) {
            throw new RuntimeException("Failed to create fallback data directory: " + fallback, fallbackError);
        }
        System.err.println("[StorageManager] Using fallback data directory");
    }

    private static void ensureWritableDataDir(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("keypairs"));
        Files.createDirectories(dir.resolve("contacts"));
        Files.createDirectories(dir.resolve("config"));

        // createDirectories 对已存在的只读目录不会报错，这里用临时文件确认真的可写。
        Path probe = Files.createTempFile(dir, ".write-test", ".tmp");
        Files.deleteIfExists(probe);
    }
    
    public static String getKeypairsDir() {
        return keypairsDir;
    }
    
    public static String getContactsDir() {
        return contactsDir;
    }
    
    public static String getConfigDir() {
        return configDir;
    }
    
    public static void writeFile(String filePath, byte[] data) throws IOException {
        Path path = Paths.get(filePath);
        Files.write(path, data);
    }
    
    public static void writeTextFile(String filePath, String content) throws IOException {
        Path path = Paths.get(filePath);
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] readFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        return Files.readAllBytes(path);
    }

    public static String readTextFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
    
    public static List<String> listFiles(String directory, String extension) {
        List<String> fileList = new ArrayList<>();
        File dir = new File(directory);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && (extension == null || file.getName().endsWith(extension))) {
                    fileList.add(file.getName());
                }
            }
        }
        return fileList;
    }
    
    public static boolean deleteFile(String filePath) {
        Path path = Paths.get(filePath);
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            return false;
        }
    }
    
    public static void ensureDirectoryExists(Path directoryPath) throws IOException {
        if (!Files.exists(directoryPath)) {
            Files.createDirectories(directoryPath);
        }
    }
}