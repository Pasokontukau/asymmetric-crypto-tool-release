package core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * 解析桌面程序的安装根目录与默认数据目录。
 *
 * <p>开发环境从 {@code user.dir} 启动；jpackage 生成的便携包由启动器传入
 * {@code -Dapp.home=$BINDIR}，因此即使从桌面、开始菜单或任意工作目录启动，
 * 数据也不会跟着当前工作目录乱跑。</p>
 */
public final class AppPaths {

    public static final String APP_HOME_PROPERTY = "app.home";
    public static final String JPACKAGE_APP_PATH_PROPERTY = "jpackage.app-path";

    private static final String APP_DIR_NAME = "CryptoAssistant";
    private static final String TEMP_DIR_PROPERTY = "java.io.tmpdir";
    private static final String JNA_TEMP_DIR_PROPERTY = "jna.tmpdir";

    private AppPaths() {
    }

    /**
     * 程序根目录：exe 所在目录（便携包）或开发时的项目目录。
     */
    public static Path home() {
        Path configured = toPath(System.getProperty(APP_HOME_PROPERTY));
        if (configured != null) {
            return configured.isAbsolute() ? configured : workingDir().resolve(configured).normalize();
        }

        Path launcher = launcherPath();
        if (launcher != null && launcher.getParent() != null) {
            return launcher.getParent();
        }

        return workingDir();
    }

    /**
     * jpackage launcher 的完整路径；开发环境返回 {@code null}。
     */
    public static Path launcherPath() {
        Path path = toPath(System.getProperty(JPACKAGE_APP_PATH_PROPERTY));
        if (path == null) {
            return null;
        }
        path = path.isAbsolute() ? path : workingDir().resolve(path).normalize();
        return Files.isRegularFile(path) ? path : null;
    }

    public static Path defaultDataDir() {
        return home().resolve("data").toAbsolutePath().normalize();
    }

    public static Path defaultOutputDir() {
        return home().resolve("output").toAbsolutePath().normalize();
    }

    /**
     * 本机（不漫游）数据目录：Windows 为 {@code %LOCALAPPDATA%\CryptoAssistant}，
     * 其它平台与 {@link #userDataDir()} 相同。
     */
    public static Path localDataDir() {
        Path base = toPath(System.getenv("LOCALAPPDATA"));
        if (base == null) {
            return userDataDir();
        }
        return base.toAbsolutePath().normalize().resolve(APP_DIR_NAME);
    }

    /**
     * 确保 JNA/FlatLaf 解压原生库和应用临时文件有一个可写目录。
     *
     * <p>原生库默认解压到 {@code java.io.tmpdir}；当该目录不可写（例如被系统策略指到
     * 只读位置，或用户把 TEMP 指到只读程序目录）时，JNA 会抛 {@code UnsatisfiedLinkError}，
     * DPAPI 随之不可用。这里在加载任何原生库之前探测并回退到应用自己的临时目录，
     * 同时设置 {@code jna.tmpdir}，让 JNA 与 FlatLaf 使用同一位置。</p>
     *
     * @return 实际可用的临时目录；所有候选都不可写时返回 {@code null}，不改系统属性
     */
    public static Path ensureWritableTempDir() {
        return ensureWritableTempDir(toPath(System.getProperty(TEMP_DIR_PROPERTY)), defaultTempFallbacks());
    }

    static Path ensureWritableTempDir(Path current, List<Path> fallbacks) {
        Path chosen = chooseWritableTempDir(current, fallbacks);
        if (chosen != null && !chosen.equals(current)) {
            System.setProperty(TEMP_DIR_PROPERTY, chosen.toString());
            System.setProperty(JNA_TEMP_DIR_PROPERTY, chosen.toString());
        }
        return chosen;
    }

    static Path chooseWritableTempDir(Path current, List<Path> fallbacks) {
        if (isWritableTempDir(current)) {
            return current;
        }
        if (fallbacks == null) {
            return null;
        }
        for (Path candidate : fallbacks) {
            if (candidate == null) {
                continue;
            }
            try {
                Files.createDirectories(candidate);
            } catch (IOException | SecurityException e) {
                continue;
            }
            if (isWritableTempDir(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Path> defaultTempFallbacks() {
        return Arrays.asList(
                localDataDir().resolve("temp"),
                userDataDir().resolve("temp"),
                Paths.get(System.getProperty("user.home", "."), ".cryptoassistant", "temp")
                        .toAbsolutePath().normalize());
    }

    private static boolean isWritableTempDir(Path dir) {
        if (dir == null) {
            return false;
        }
        try {
            if (!Files.isDirectory(dir)) {
                return false;
            }
            Path probe = Files.createTempFile(dir, "aegix-temp-", ".tmp");
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /**
     * 程序目录不可写时的回退目录：Windows 为 {@code %APPDATA%\CryptoAssistant}，
     * 其它平台为 {@code ~/.local/share/CryptoAssistant}。
     */
    public static Path userDataDir() {
        String appData = System.getenv("APPDATA");
        Path base = toPath(appData);
        if (base == null) {
            String userHome = System.getProperty("user.home", ".");
            if (isWindows()) {
                base = Paths.get(userHome, "AppData", "Roaming");
            } else {
                String xdgDataHome = System.getenv("XDG_DATA_HOME");
                base = toPath(xdgDataHome);
                if (base == null) {
                    base = Paths.get(userHome, ".local", "share");
                }
            }
        }
        return base.toAbsolutePath().normalize().resolve(APP_DIR_NAME);
    }

    /**
     * 把配置里的数据/输出目录统一成绝对路径；相对路径按程序根目录解析。
     */
    public static Path resolvePath(String rawPath) {
        Path path = toPath(rawPath);
        if (path == null) {
            return defaultDataDir();
        }
        if (!path.isAbsolute()) {
            path = home().resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static Path workingDir() {
        Path dir = toPath(System.getProperty("user.dir"));
        if (dir == null) {
            return Paths.get(".").toAbsolutePath().normalize();
        }
        return dir.isAbsolute() ? dir : dir.toAbsolutePath().normalize();
    }

    /**
     * 只允许清理程序自己的数据目录或用户目录回退位置，避免误删任意路径。
     */
    public static boolean isManagedDataDir(Path path) {
        if (path == null) {
            return false;
        }
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(home()) || normalized.startsWith(userDataDir());
    }

    private static Path toPath(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Paths.get(raw.trim()).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
