package settings.repository;

import java.io.File;
import java.io.IOException;
import settings.manager.SettingsManager;
import settings.model.VolumeLimitPreset;
import settings.storage.SettingsStorage;

/**
 * 设置读写的薄仓库层（解耦 DR-4）。
 *
 * <p>设置页只依赖这里，不再直接调用 {@link SettingsManager} 与 {@link SettingsStorage}。
 * 本类只做转发，不改键名、不改文件格式；主题与字体也走同一入口。</p>
 */
public class SettingsRepository {

    private static final String KEY_THEME = "theme";
    private static final String DEFAULT_THEME = "light";

    public String getTheme() {
        return SettingsManager.getProperty(KEY_THEME, DEFAULT_THEME);
    }

    public void setTheme(String theme) {
        SettingsManager.setProperty(KEY_THEME, theme);
    }

    public int getFontSize() {
        return SettingsManager.getFontSize();
    }

    public void setFontSize(int fontSize) {
        SettingsManager.setFontSize(fontSize);
    }

    public String getFontFamily() {
        return SettingsManager.getFontFamily();
    }

    public void setFontFamily(String fontFamily) {
        SettingsManager.setFontFamily(fontFamily);
    }

    public String getDefaultOutputDirectory() {
        return SettingsManager.getDefaultOutputDirectory();
    }

    public void setDefaultOutputDirectory(String outputDir) {
        SettingsManager.setDefaultOutputDirectory(outputDir);
    }

    public String getSecurityLevel() {
        return SettingsStorage.getSecurityLevel();
    }

    public void setSecurityLevel(String level) {
        SettingsStorage.setSecurityLevel(level);
    }

    public int getPasswordCacheTimeout() {
        return SettingsStorage.getPasswordCacheTimeout();
    }

    public void setPasswordCacheTimeout(int minutes) {
        SettingsStorage.setPasswordCacheTimeout(minutes);
    }

    public VolumeLimitPreset getVolumeLimitPreset() {
        return SettingsStorage.getVolumeLimitPreset();
    }

    public void setVolumeLimitPreset(VolumeLimitPreset preset) {
        SettingsStorage.setVolumeLimitPreset(preset);
    }

    public boolean isPackageMultiFilesDefault() {
        return SettingsStorage.isPackageMultiFilesDefault();
    }

    public void setPackageMultiFilesDefault(boolean enabled) {
        SettingsStorage.setPackageMultiFilesDefault(enabled);
    }

    public boolean isUpdateCheckEnabled() {
        return SettingsStorage.isUpdateCheckEnabled();
    }

    public void setUpdateCheckEnabled(boolean enabled) {
        SettingsStorage.setUpdateCheckEnabled(enabled);
    }

    public String getDataDir() {
        return SettingsStorage.getDataDir();
    }

    public void setDataDir(String path) {
        SettingsStorage.setDataDir(path);
    }

    public void exportSettings(File exportFile, char[] password) throws IOException {
        SettingsStorage.exportSettings(exportFile, password);
    }

    public boolean isEncryptedExport(File file) throws IOException {
        return SettingsStorage.isEncryptedExport(file);
    }

    public void importSettings(File importFile, char[] password) throws IOException {
        SettingsStorage.importSettings(importFile, password);
    }

    public void clearAllData(boolean confirmed) {
        SettingsStorage.clearAllData(confirmed);
    }
}
