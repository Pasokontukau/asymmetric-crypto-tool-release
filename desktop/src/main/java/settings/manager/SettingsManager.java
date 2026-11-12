package settings.manager;

import core.storage.AppPaths;
import core.storage.StorageManager;
import core.util.LogManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Properties;

public class SettingsManager {
    
    private static String getSettingsFilePath() {
        return StorageManager.getDataDir() + File.separator + "settings.properties";
    }

    /**
     * 程序目录内的路径存相对值，避免设置文件写入安装位置/用户名；
     * 程序目录外的路径必须保留绝对值。
     */
    private static String portablePath(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return raw;
        }
        Path path = new File(raw.trim()).toPath();
        if (!path.isAbsolute()) {
            path = AppPaths.home().resolve(path);
        }
        path = path.toAbsolutePath().normalize();
        Path home = AppPaths.home().toAbsolutePath().normalize();
        if (path.startsWith(home)) {
            return home.relativize(path).toString();
        }
        return path.toString();
    }

    /** 把设置里的相对/绝对路径统一还原为绝对路径。 */
    private static String absolutePath(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return getDefaultOutputDirValue();
        }
        Path path = new File(raw.trim()).toPath();
        if (!path.isAbsolute()) {
            path = AppPaths.home().resolve(path);
        }
        return path.toAbsolutePath().normalize().toString();
    }
    
    private static final String DEFAULT_FONT_SIZE = "21";
    private static final String DEFAULT_FONT_FAMILY = ""; // 空字符串表示自动检测
    
    private static Properties settings;
    
    /**
     * 获取默认输出目录的默认值（程序根目录/output）
     */
    private static String getDefaultOutputDirValue() {
        return AppPaths.defaultOutputDir().toString();
    }
    
    /**
     * 初始化设置管理器
     */
    public static void init() {
        settings = new Properties();
        
        // 加载默认设置
        settings.setProperty("font_size", DEFAULT_FONT_SIZE);
        settings.setProperty("font_family", DEFAULT_FONT_FAMILY);
        settings.setProperty("theme", "light");
        settings.setProperty("default_output_dir", getDefaultOutputDirValue());
        
        // 加载现有设置（覆盖默认设置）
        String settingsFilePath = getSettingsFilePath();
        File settingsFile = new File(settingsFilePath);
        if (settingsFile.exists()) {
            try (InputStream input = new FileInputStream(settingsFile)) {
                settings.load(input);
                core.util.LogManager.info("设置文件加载成功");
            } catch (IOException e) {
                core.util.LogManager.error("加载设置文件失败", e);
            }
        } else {
            // 首次运行，保存默认设置
            saveSettings();
        }
    }
    
    /**
     * 获取默认输出目录
     * @return 默认输出目录
     */
    public static String getDefaultOutputDirectory() {
        String dir = absolutePath(settings.getProperty("default_output_dir", ""));
        // 确保目录存在
        try {
            java.io.File outputDir = new java.io.File(dir);
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
        } catch (Exception e) {
            core.util.LogManager.warning("创建默认输出目录失败", e);
        }
        return dir;
    }
    
    /**
     * 设置默认输出目录
     * @param outputDir 默认输出目录
     */
    public static void setDefaultOutputDirectory(String outputDir) {
        settings.setProperty("default_output_dir", portablePath(outputDir));
        saveSettings();
    }
    
    /**
     * 保存设置
     */
    public static void saveSettings() {
        try {
            String outputDir = settings.getProperty("default_output_dir");
            if (outputDir != null && !outputDir.trim().isEmpty()) {
                settings.setProperty("default_output_dir", portablePath(outputDir));
            }
            String settingsFilePath = getSettingsFilePath();
            // 确保data目录存在
            File dataDir = new File(StorageManager.getDataDir());
            if (!dataDir.exists()) {
                dataDir.mkdirs();
            }
            
            try (OutputStream output = new FileOutputStream(settingsFilePath)) {
                settings.store(output, "应用程序设置");
                core.util.LogManager.info("设置已保存");
            }
        } catch (IOException e) {
            core.util.LogManager.error("保存设置失败", e);
        }
    }
    
    /**
     * 获取字体大小
     * @return 字体大小
     */
    public static int getFontSize() {
        String fontSizeStr = settings.getProperty("font_size", DEFAULT_FONT_SIZE);
        try {
            return Integer.parseInt(fontSizeStr);
        } catch (NumberFormatException e) {
            core.util.LogManager.error("无效的字体大小设置：" + fontSizeStr, e);
            return Integer.parseInt(DEFAULT_FONT_SIZE);
        }
    }
    
    /**
     * 设置字体大小
     * @param fontSize 字体大小
     */
    public static void setFontSize(int fontSize) {
        settings.setProperty("font_size", String.valueOf(fontSize));
        saveSettings();
    }
    
    /**
     * 获取字体族
     * @return 字体族名称，空字符串表示自动检测
     */
    public static String getFontFamily() {
        return settings.getProperty("font_family", DEFAULT_FONT_FAMILY);
    }
    
    /**
     * 设置字体族
     * @param fontFamily 字体族名称，空字符串表示自动检测
     */
    public static void setFontFamily(String fontFamily) {
        settings.setProperty("font_family", fontFamily);
        saveSettings();
    }
    
    /**
     * 获取设置属性
     * @param key 属性键
     * @param defaultValue 默认值
     * @return 属性值
     */
    public static String getProperty(String key, String defaultValue) {
        return settings.getProperty(key, defaultValue);
    }
    
    /**
     * 设置属性值
     * @param key 属性键
     * @param value 属性值
     */
    public static void setProperty(String key, String value) {
        settings.setProperty(key, value);
        saveSettings();
    }
}
