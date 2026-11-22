package settings.storage;

import contacts.storage.ContactStorage;
import core.storage.AppPaths;
import core.storage.DataDirectoryConfig;
import core.storage.StorageManager;
import core.util.ExportCrypto;
import core.util.LogManager;
import history.storage.HistoryStorage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import keymanagement.storage.KeychainStorage;
import keymanagement.storage.KeyPairStorage;
import settings.model.VolumeLimitPreset;

/**
 * 桌面端设置存储。
 *
 * <p>设置写在程序根目录的 {@code config/settings.properties}，随便携包一起移动；
 * 程序目录不可写时回退到 {@code %APPDATA%\CryptoAssistant\settings.properties}。
 * 不再使用 Java Preferences，因此便携包不会写注册表。</p>
 */
public class SettingsStorage {

    /**
     * 设置文件位置固定为程序目录下的 config；只有该目录确实不可写时才用用户目录。
     * 不在 data 目录里，避免“data.dir 自己存在设置文件里”的循环依赖。
     */
    private static final String SETTINGS_DIR = "config";
    private static final String SETTINGS_FILE = "settings.properties";

    private static final Object LOCK = new Object();
    private static Properties settings;
    private static Path settingsFile;

    // 设置键名
    private static final String KEY_TEST_MODE = "testMode"; // 测试模式：不要求密码强度
    private static final String KEY_DATA_DIR = "data.dir"; // data 目录路径
    private static final String KEY_PASSWORD_CACHE_TIMEOUT = "passwordCacheTimeout"; // 密码缓存超时（分钟）
    private static final String KEY_SECURITY_LEVEL = "securityLevel"; // 安全级别：strict, balanced, convenient
    private static final String KEY_THREAD_POOL_SIZE = "threadPoolSize"; // 线程池大小：用于分卷处理
    private static final String KEY_VOLUME_LIMIT_PRESET = "volumeLimitPreset"; // 单个外部分卷的大小档位
    private static final String KEY_PACKAGE_MULTI_FILES = "packageMultiFiles"; // 多文件默认打包为一个加密包
    private static final String KEY_UPDATE_CHECK = "updateCheckEnabled"; // 允许「关于」页检查更新

    private static final Set<String> KNOWN_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            KEY_TEST_MODE,
            KEY_DATA_DIR,
            KEY_PASSWORD_CACHE_TIMEOUT,
            KEY_SECURITY_LEVEL,
            KEY_THREAD_POOL_SIZE,
            KEY_VOLUME_LIMIT_PRESET,
            KEY_PACKAGE_MULTI_FILES,
            KEY_UPDATE_CHECK)));

    // 安全级别常量
    public static final String SECURITY_LEVEL_STRICT = "strict";
    public static final String SECURITY_LEVEL_BALANCED = "balanced";
    public static final String SECURITY_LEVEL_CONVENIENT = "convenient";

    // 超时常量（分钟）
    public static final int TIMEOUT_5_MIN = 5;
    public static final int TIMEOUT_15_MIN = 15;
    public static final int TIMEOUT_30_MIN = 30;
    public static final int TIMEOUT_1_HOUR = 60;
    public static final int TIMEOUT_NEVER = -1;

    private SettingsStorage() {
    }

    /**
     * 设置文件路径。供导出/诊断使用；路径只在首次读取时解析一次。
     */
    static Path settingsPath() {
        synchronized (LOCK) {
            if (settingsFile == null) {
                settingsFile = resolveSettingsFile();
            }
            return settingsFile;
        }
    }

    private static Path resolveSettingsFile() {
        Path preferred = AppPaths.home().resolve(SETTINGS_DIR).resolve(SETTINGS_FILE);
        if (isWritableDirectory(preferred.getParent())) {
            return preferred;
        }

        Path fallback = AppPaths.userDataDir().resolve(SETTINGS_FILE);
        try {
            Files.createDirectories(fallback.getParent());
            return fallback;
        } catch (IOException | SecurityException e) {
            System.err.println("[SettingsStorage] 设置目录不可写，回退目录也不可用");
            return preferred;
        }
    }

    private static boolean isWritableDirectory(Path directory) {
        if (directory == null) {
            return false;
        }
        try {
            Files.createDirectories(directory);
            Path probe = Files.createTempFile(directory, ".settings-write", ".tmp");
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private static Properties settings() {
        synchronized (LOCK) {
            if (settings == null) {
                settings = loadSettings(settingsPath());
            }
            return settings;
        }
    }

    private static Properties loadSettings(Path path) {
        Properties loaded = new Properties();
        if (path == null || !Files.isRegularFile(path)) {
            return loaded;
        }
        try (InputStream input = Files.newInputStream(path)) {
            loaded.load(input);
        } catch (IOException | SecurityException e) {
            // 初始化阶段可能还没启动日志系统，先写 stderr；随后保存时会走 LogManager。
            System.err.println("[SettingsStorage] 读取设置失败，使用默认值");
        }
        return loaded;
    }

    private static String getString(String key, String defaultValue) {
        synchronized (LOCK) {
            return settings().getProperty(key, defaultValue);
        }
    }

    private static int getInt(String key, int defaultValue) {
        String raw = getString(key, null);
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean getBoolean(String key, boolean defaultValue) {
        String raw = getString(key, null);
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private static void put(String key, String value) {
        synchronized (LOCK) {
            Properties current = settings();
            if (value == null) {
                current.remove(key);
            } else {
                current.setProperty(key, value);
            }
            saveLocked(current);
        }
    }

    private static void saveLocked(Properties current) {
        Path target = settingsPath();
        try {
            writeProperties(target, current);
            return;
        } catch (IOException | SecurityException e) {
            Path fallback = AppPaths.userDataDir().resolve(SETTINGS_FILE);
            if (!fallback.equals(target)) {
                try {
                    writeProperties(fallback, current);
                    settingsFile = fallback;
                    LogManager.warning("设置目录不可写，设置已改存到用户目录");
                    return;
                } catch (IOException | SecurityException ignored) {
                    // 继续走下面的错误报告
                }
            }
            System.err.println("[SettingsStorage] 保存设置失败");
            LogManager.warning("保存设置失败，设置可能在下次重启后丢失", e);
        }
    }

    private static void writeProperties(Path path, Properties current) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temp)) {
                current.store(output, "CryptoAssistant settings");
            }
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    // 获取测试模式设置
    public static boolean isTestMode() {
        return getBoolean(KEY_TEST_MODE, false);
    }

    // 设置测试模式
    public static void setTestMode(boolean enabled) {
        put(KEY_TEST_MODE, Boolean.toString(enabled));
    }

    // 获取 data 目录路径
    public static String getDataDir() {
        return getString(KEY_DATA_DIR, null);
    }

    // 设置 data 目录路径
    public static void setDataDir(String path) {
        String normalized = null;
        if (path != null && !path.trim().isEmpty()) {
            normalized = AppPaths.resolvePath(path).toString();
        }
        put(KEY_DATA_DIR, normalized);
        DataDirectoryConfig.update(normalized);
    }

    // 获取密码缓存超时时间（分钟）
    public static int getPasswordCacheTimeout() {
        return getInt(KEY_PASSWORD_CACHE_TIMEOUT, TIMEOUT_30_MIN); // 默认30分钟
    }

    // 设置密码缓存超时时间
    public static void setPasswordCacheTimeout(int minutes) {
        put(KEY_PASSWORD_CACHE_TIMEOUT, Integer.toString(minutes));
    }

    // 获取安全级别
    public static String getSecurityLevel() {
        String level = getString(KEY_SECURITY_LEVEL, SECURITY_LEVEL_BALANCED); // 默认平衡模式

        // 底层防护：非Windows系统不能使用便捷模式
        if (SECURITY_LEVEL_CONVENIENT.equals(level) && !KeychainStorage.isAvailable()) {
            LogManager.warning("检测到非Windows系统配置了便捷模式，自动切换到平衡模式");
            level = SECURITY_LEVEL_BALANCED;
            put(KEY_SECURITY_LEVEL, level);
        }

        return level;
    }

    // 观察者列表：监听安全级别变更
    private static final java.util.List<SecurityLevelChangeListener> securityLevelListeners = new java.util.ArrayList<>();

    // 安全级别变更监听器
    public interface SecurityLevelChangeListener {
        void onSecurityLevelChanged(String newLevel, String oldLevel);
    }

    // 注册安全级别变更监听器
    public static void addSecurityLevelChangeListener(SecurityLevelChangeListener listener) {
        synchronized (securityLevelListeners) {
            if (listener != null && !securityLevelListeners.contains(listener)) {
                securityLevelListeners.add(listener);
            }
        }
    }

    // 注销安全级别变更监听器
    public static void removeSecurityLevelChangeListener(SecurityLevelChangeListener listener) {
        synchronized (securityLevelListeners) {
            securityLevelListeners.remove(listener);
        }
    }

    // 通知所有监听器
    private static void notifySecurityLevelChanged(String newLevel, String oldLevel) {
        synchronized (securityLevelListeners) {
            for (SecurityLevelChangeListener listener : new java.util.ArrayList<>(securityLevelListeners)) {
                try {
                    listener.onSecurityLevelChanged(newLevel, oldLevel);
                } catch (Exception e) {
                    LogManager.warning("通知安全级别变更监听器失败", e);
                }
            }
        }
    }

    // 设置安全级别
    public static void setSecurityLevel(String level) {
        String oldLevel = getSecurityLevel();

        // 底层防护：非Windows系统不能使用便捷模式
        if (SECURITY_LEVEL_CONVENIENT.equals(level) && !KeychainStorage.isAvailable()) {
            LogManager.warning("非Windows系统无法使用便捷模式，自动切换到平衡模式");
            level = SECURITY_LEVEL_BALANCED;
        }

        // 切到严格模式，或离开便捷模式：都要清掉持久化的系统密钥链密码。
        // 平衡模式不使用密钥链，继续保留旧值会让“已经关闭便捷模式”变成假象。
        boolean leavingConvenient = SECURITY_LEVEL_CONVENIENT.equals(oldLevel)
                && !SECURITY_LEVEL_CONVENIENT.equals(level);
        if (SECURITY_LEVEL_STRICT.equals(level) || leavingConvenient) {
            KeychainStorage.clearAllPasswords();
            KeyPairStorage.notifyClearAllPasswordCaches();
        }

        put(KEY_SECURITY_LEVEL, level);

        // 通知安全级别变更
        if (!level.equals(oldLevel)) {
            notifySecurityLevelChanged(level, oldLevel);
        }
    }

    // 获取线程池大小
    // 默认上限为 4 线程。每个分卷 200MB，实际处理时输入/输出缓冲区 + 加密工作缓冲区
    // 的内存开销远超 200MB，4 线程已接近多数 JVM 默认堆上限（约 1-2GB），
    // 即使物理内存充足（如 16GB），也会因堆内存不足而 OOM。
    // 用户可在设置中手动调高，但需同时增大 JVM 堆（-Xmx 参数）。
    public static int getThreadPoolSize() {
        return getInt(KEY_THREAD_POOL_SIZE, Math.min(Runtime.getRuntime().availableProcessors(), 4));
    }

    // 获取单个外部分卷的大小档位；未知/损坏值回落到默认 500 MB。
    public static VolumeLimitPreset getVolumeLimitPreset() {
        return VolumeLimitPreset.fromStored(getString(KEY_VOLUME_LIMIT_PRESET, null));
    }

    // 设置单个外部分卷的大小档位；传 null 视为恢复默认。
    public static void setVolumeLimitPreset(VolumeLimitPreset preset) {
        VolumeLimitPreset normalized = preset == null ? VolumeLimitPreset.DEFAULT : preset;
        put(KEY_VOLUME_LIMIT_PRESET, normalized.name());
    }

    // 便捷读取当前档位的字节数，供写入器与预览使用。
    public static long getVolumeLimitBytes() {
        return getVolumeLimitPreset().getBytes();
    }

    // 多个普通文件开始加密时是否默认打包成一个 ZIP；与 Android 默认一致为开。
    public static boolean isPackageMultiFilesDefault() {
        return getBoolean(KEY_PACKAGE_MULTI_FILES, true);
    }

    public static void setPackageMultiFilesDefault(boolean enabled) {
        put(KEY_PACKAGE_MULTI_FILES, Boolean.toString(enabled));
    }

    /** 是否允许「关于」页检查更新；默认关闭，关闭时不发任何网络请求。 */
    public static boolean isUpdateCheckEnabled() {
        return getBoolean(KEY_UPDATE_CHECK, false);
    }

    public static void setUpdateCheckEnabled(boolean enabled) {
        put(KEY_UPDATE_CHECK, Boolean.toString(enabled));
    }

    // 设置线程池大小
    public static void setThreadPoolSize(int size) {
        if (size < 1) {
            size = 1;
        } else if (size > 15) {
            size = 15;
        }
        put(KEY_THREAD_POOL_SIZE, Integer.toString(size));
    }

    // 清空所有数据（带确认参数）
    public static void clearAllData(boolean confirmed) {
        if (!confirmed) {
            throw new IllegalStateException("必须确认后才能清除所有数据");
        }

        LogManager.warning("开始清除所有数据...");

        // 先备份数据
        try {
            backupDataBeforeClear();
        } catch (IOException e) {
            LogManager.error("备份数据失败，继续清除操作", e);
        }

        // 清空系统密钥链中的密码
        KeychainStorage.clearAllPasswords();

        // 清空密钥对
        KeyPairStorage.clearAllKeyPairs();

        // 清空通讯录
        ContactStorage.clearAllContacts();

        // 清空历史记录
        HistoryStorage.clearHistory();

        // 获取当前 data 目录（StorageManager 已处理程序目录/用户目录回退）
        Path dataPath = java.nio.file.Paths.get(StorageManager.getDataDir());

        // 只允许清理程序自己的数据目录或用户目录回退位置，防止误删
        Path absoluteDataPath = dataPath.toAbsolutePath().normalize();
        if (!AppPaths.isManagedDataDir(absoluteDataPath)) {
            LogManager.error("数据目录不在程序目录或用户回退目录下，拒绝清除操作");
            throw new SecurityException("数据目录不在受管目录下，拒绝清除操作");
        }

        // 清空日志文件
        Path logsPath = dataPath.resolve("logs");
        if (Files.exists(logsPath)) {
            try {
                Files.walk(logsPath)
                     .sorted((a, b) -> b.compareTo(a)) // 先删除子目录，再删除父目录
                     .map(Path::toFile)
                     .forEach(file -> {
                         try {
                             Files.deleteIfExists(file.toPath());
                         } catch (IOException e) {
                             LogManager.warning("删除文件失败");
                         }
                     });
            } catch (IOException e) {
                LogManager.error("清空日志目录失败", e);
            }
        }

        // 清空 data 目录下的文件
        if (Files.exists(dataPath)) {
            try {
                Files.list(dataPath)
                     .forEach(path -> {
                         try {
                             if (Files.isDirectory(path)) {
                                 Files.walk(path)
                                      .sorted((a, b) -> b.compareTo(a))
                                      .map(Path::toFile)
                                      .forEach(file -> {
                                          try {
                                              Files.deleteIfExists(file.toPath());
                                          } catch (IOException e) {
                                              LogManager.warning("删除文件失败");
                                          }
                                      });
                                 Files.deleteIfExists(path);
                             } else {
                                 Files.deleteIfExists(path);
                             }
                         } catch (IOException e) {
                             LogManager.error("删除文件失败", e);
                         }
                     });
            } catch (IOException e) {
                LogManager.error("清空数据目录失败", e);
            }
        }

        // 清空设置文件中的内容（保留空文件，下次启动使用默认值）
        synchronized (LOCK) {
            Properties current = settings();
            current.clear();
            saveLocked(current);
        }

        LogManager.info("所有数据清除完成");
    }

    // 备份数据
    private static void backupDataBeforeClear() throws IOException {
        Path dataPath = java.nio.file.Paths.get(StorageManager.getDataDir());

        if (!Files.exists(dataPath)) {
            return;
        }

        // 备份目录放在数据目录旁边，便携包里就是程序根目录下。
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path backupPath = dataPath.resolveSibling("data_backup_" + timestamp);

        // 复制数据到备份目录
        try {
            copyDirectory(dataPath, backupPath);
            LogManager.info("数据已备份");
        } catch (IOException e) {
            throw new IOException("备份数据失败", e);
        }
    }

    // 复制目录
    private static void copyDirectory(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) {
            if (!Files.exists(target)) {
                Files.createDirectories(target);
            }
            Files.list(source).forEach(child -> {
                try {
                    copyDirectory(child, target.resolve(child.getFileName()));
                } catch (IOException e) {
                    LogManager.error("复制文件失败", e);
                }
            });
        } else {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 导出设置到密码加密的 {@code .aexp} 文件。
     *
     * <p>只导出已知配置键，避免把未来可能加入的临时字段带出去。导出内容先做 HMAC，
     * 再整体用 {@link ExportCrypto} 加密；磁盘上不出现任何键名或路径明文。</p>
     */
    public static void exportSettings(File exportFile, char[] password) throws IOException {
        byte[] plaintext = buildExportBytes();
        try {
            Files.write(exportFile.toPath(), ExportCrypto.encrypt(plaintext, password));
        } catch (GeneralSecurityException e) {
            throw new IOException(e.getMessage(), e);
        }
        LogManager.info("设置已加密导出");
    }

    /** 生成导出文件内部的属性字节（未加密），供测试和加密导出复用。 */
    static byte[] buildExportBytes() throws IOException {
        Properties props = new Properties();
        synchronized (LOCK) {
            Properties current = settings();
            for (String key : current.stringPropertyNames()) {
                if (KNOWN_KEYS.contains(key)) {
                    props.setProperty(key, current.getProperty(key));
                }
            }
        }

        String hmac = calculateSettingsHmac(props);
        props.setProperty("settings.hmac", hmac);
        props.setProperty("settings.exportTime", java.time.Instant.now().toString());

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        props.store(buffer, "Application Settings - Exported");
        return buffer.toByteArray();
    }

    public static boolean isEncryptedExport(File file) throws IOException {
        if (file == null || !file.isFile()) {
            return false;
        }
        byte[] data = Files.readAllBytes(file.toPath());
        return ExportCrypto.isEncrypted(data);
    }

    /** 导入设置；只接受当前加密导出文件（`.aexp`），不再读取旧明文 properties。 */
    public static void importSettings(File importFile, char[] password) throws IOException {
        byte[] raw = Files.readAllBytes(importFile.toPath());
        if (!ExportCrypto.isEncrypted(raw)) {
            throw new IOException("只支持加密设置文件（.aexp）；旧明文设置不再导入");
        }
        if (password == null || password.length == 0) {
            throw new IOException("该设置文件已加密，请输入导出时使用的密码");
        }

        byte[] plaintext;
        try {
            plaintext = ExportCrypto.decrypt(raw, password);
        } catch (GeneralSecurityException e) {
            throw new IOException(e.getMessage(), e);
        }

        Properties props = new Properties();
        try (InputStream input = new ByteArrayInputStream(plaintext)) {
            props.load(input);
        }

        // 1. 验证完整性 HMAC
        String storedHmac = props.getProperty("settings.hmac");
        String exportTime = props.getProperty("settings.exportTime");

        // 移除临时属性，重新计算 HMAC
        props.remove("settings.hmac");
        props.remove("settings.exportTime");

        String calculatedHmac = calculateSettingsHmac(props);

        if (storedHmac != null && !storedHmac.isEmpty() && !storedHmac.equals(calculatedHmac)) {
            throw new IOException("设置文件已被篡改或损坏，导入中止！");
        }

        LogManager.info("设置文件完整性验证通过");
        if (exportTime != null && !exportTime.isEmpty()) {
            LogManager.info("设置导出时间: " + exportTime);
        }

        // 2. 只导入已知设置键，避免把任意属性写进设置文件
        synchronized (LOCK) {
            Properties current = settings();
            current.clear();
            for (String key : props.stringPropertyNames()) {
                if (KNOWN_KEYS.contains(key)) {
                    current.setProperty(key, props.getProperty(key));
                }
            }
            saveLocked(current);
        }

        LogManager.info("设置导入成功");
    }

    /**
     * 计算设置 Properties 的 HMAC-SHA256 完整性验证码。
     */
    private static String calculateSettingsHmac(Properties props) {
        try {
            String hmacKey = "AsymmetricEncryptionTool_Settings_Verification_v2";

            // 1. 按键名排序，确保顺序一致
            java.util.SortedSet<String> sortedKeys = new TreeSet<>(props.stringPropertyNames());

            // 2. 构建待签名的内容
            StringBuilder sb = new StringBuilder();
            for (String key : sortedKeys) {
                sb.append(key).append("=").append(props.getProperty(key)).append("|");
            }

            // 3. 使用真正的 HMAC-SHA256
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    hmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8));

            // 4. 转换为十六进制字符串
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (Exception e) {
            LogManager.warning("计算设置 HMAC 失败", e);
            return "";
        }
    }

    // 测试钩子：把设置文件指向临时路径并重新加载；生产代码不调用。
    static void useSettingsFileForTests(Path path) {
        synchronized (LOCK) {
            settingsFile = path;
            settings = loadSettings(path);
        }
    }

    // 测试钩子：丢弃缓存，下次读取重新解析程序目录与设置文件。
    static void resetForTests() {
        synchronized (LOCK) {
            settings = null;
            settingsFile = null;
        }
    }
}
