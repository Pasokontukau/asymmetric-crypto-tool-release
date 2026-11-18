package settings.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import core.storage.StorageManager;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import settings.manager.SettingsManager;
import settings.model.VolumeLimitPreset;
import settings.repository.SettingsRepository;

/**
 * 设置仓库：只转发，键名与文件格式不变。
 *
 * <p>放在 storage 包内以复用 {@code useSettingsFileForTests}/{@code resetForTests} 测试钩子。</p>
 */
public class SettingsRepositoryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final SettingsRepository repository = new SettingsRepository();
    private String originalDataDir;

    @Before
    public void useTemporaryDataDir() throws Exception {
        originalDataDir = StorageManager.getDataDir();
        StorageManager.useDataDirForTests(temporaryFolder.newFolder("data").getAbsolutePath());
        SettingsStorage.resetForTests();
    }

    @After
    public void restoreDataDir() {
        StorageManager.useDataDirForTests(originalDataDir);
        SettingsStorage.resetForTests();
        // SettingsManager 缓存的是全局 Properties；恢复数据目录后必须重读，
        // 否则会把临时目录里的字体/主题留给后续测试。
        SettingsManager.init();
    }

    @Test
    public void storageBackedValuesRoundTripAndKeepExistingKeys() throws Exception {
        Path settingsFile = temporaryFolder.newFile("settings.properties").toPath();
        SettingsStorage.useSettingsFileForTests(settingsFile);

        repository.setVolumeLimitPreset(VolumeLimitPreset.GB_1);
        repository.setPackageMultiFilesDefault(false);
        repository.setPasswordCacheTimeout(15);
        repository.setSecurityLevel(SettingsStorage.SECURITY_LEVEL_CONVENIENT);

        SettingsStorage.useSettingsFileForTests(settingsFile);

        assertEquals(VolumeLimitPreset.GB_1, repository.getVolumeLimitPreset());
        assertFalse(repository.isPackageMultiFilesDefault());
        assertEquals(15, repository.getPasswordCacheTimeout());
        assertEquals(SettingsStorage.SECURITY_LEVEL_CONVENIENT, repository.getSecurityLevel());

        Properties saved = new Properties();
        try (InputStream input = Files.newInputStream(settingsFile)) {
            saved.load(input);
        }
        assertEquals("GB_1", saved.getProperty("volumeLimitPreset"));
        assertEquals("false", saved.getProperty("packageMultiFiles"));
        assertEquals("15", saved.getProperty("passwordCacheTimeout"));
        assertEquals("convenient", saved.getProperty("securityLevel"));
    }

    @Test
    public void managerBackedValuesRoundTripAcrossRestart() throws Exception {
        File outputDir = temporaryFolder.newFolder("output");
        SettingsManager.init();

        repository.setFontSize(24);
        repository.setFontFamily("Consolas");
        repository.setTheme("dark");
        repository.setDefaultOutputDirectory(outputDir.getAbsolutePath());

        SettingsManager.init();

        assertEquals(24, repository.getFontSize());
        assertEquals("Consolas", repository.getFontFamily());
        assertEquals("dark", repository.getTheme());
        assertEquals(outputDir.getAbsolutePath(), repository.getDefaultOutputDirectory());
    }

    @Test
    public void exportAndImportKeepEncryptedFormat() throws Exception {
        Path settingsFile = temporaryFolder.newFile("settings.properties").toPath();
        SettingsStorage.useSettingsFileForTests(settingsFile);
        repository.setVolumeLimitPreset(VolumeLimitPreset.MB_100);

        File export = temporaryFolder.newFile("settings.aexp");
        char[] password = "repo-pass-123".toCharArray();
        repository.exportSettings(export, password);
        assertTrue("导出必须是加密容器", repository.isEncryptedExport(export));

        repository.setVolumeLimitPreset(VolumeLimitPreset.DEFAULT);
        repository.importSettings(export, password);

        assertEquals("导入后应恢复导出时的档位",
                VolumeLimitPreset.MB_100, repository.getVolumeLimitPreset());
    }
}
