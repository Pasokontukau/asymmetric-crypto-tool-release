package settings.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import core.storage.AppPaths;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import settings.model.VolumeLimitPreset;

/** 设置随包持久化：不依赖 Java Preferences，文件位于程序目录 config/ 下。 */
public class SettingsStorageTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private String originalAppHome;

    @Before
    public void pointAppHomeAtTemporaryFolder() throws Exception {
        originalAppHome = System.getProperty(AppPaths.APP_HOME_PROPERTY);
        System.setProperty(AppPaths.APP_HOME_PROPERTY, temporaryFolder.getRoot().getAbsolutePath());
        SettingsStorage.resetForTests();
    }

    @After
    public void restoreAppHome() {
        if (originalAppHome == null) {
            System.clearProperty(AppPaths.APP_HOME_PROPERTY);
        } else {
            System.setProperty(AppPaths.APP_HOME_PROPERTY, originalAppHome);
        }
        SettingsStorage.resetForTests();
    }

    @Test
    public void writesSettingsToPackageConfigFile() throws Exception {
        SettingsStorage.setVolumeLimitPreset(VolumeLimitPreset.GB_1);
        SettingsStorage.setPackageMultiFilesDefault(false);
        SettingsStorage.setTestMode(true);

        Path file = temporaryFolder.getRoot().toPath().resolve("config/settings.properties");
        assertTrue("设置应写在程序目录 config/settings.properties", Files.exists(file));

        Properties saved = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            saved.load(input);
        }
        assertEquals("GB_1", saved.getProperty("volumeLimitPreset"));
        assertEquals("false", saved.getProperty("packageMultiFiles"));
        assertEquals("true", saved.getProperty("testMode"));
    }

    @Test
    public void reloadsSameValuesFromFile() throws Exception {
        SettingsStorage.setVolumeLimitPreset(VolumeLimitPreset.MB_100);
        SettingsStorage.setPackageMultiFilesDefault(false);
        SettingsStorage.setPasswordCacheTimeout(15);
        SettingsStorage.setTestMode(true);

        SettingsStorage.resetForTests();

        assertEquals(VolumeLimitPreset.MB_100, SettingsStorage.getVolumeLimitPreset());
        assertFalse(SettingsStorage.isPackageMultiFilesDefault());
        assertEquals(15, SettingsStorage.getPasswordCacheTimeout());
        assertTrue(SettingsStorage.isTestMode());
    }

    @Test
    public void corruptVolumePresetFallsBackToDefault() throws Exception {
        Path config = temporaryFolder.getRoot().toPath().resolve("config");
        Files.createDirectories(config);
        Files.write(config.resolve("settings.properties"),
                "volumeLimitPreset=NOT_A_PRESET\n".getBytes(StandardCharsets.ISO_8859_1));

        SettingsStorage.resetForTests();

        assertEquals(VolumeLimitPreset.DEFAULT, SettingsStorage.getVolumeLimitPreset());
        assertEquals(500_000_000L, SettingsStorage.getVolumeLimitBytes());
    }

    @Test
    public void settingsFileStoresOnlyConfigurationKeysAndNoProcessedFileNames() throws Exception {
        SettingsStorage.setDataDir("custom-data");
        SettingsStorage.setVolumeLimitPreset(VolumeLimitPreset.GB_1);
        SettingsStorage.setPackageMultiFilesDefault(false);
        SettingsStorage.setPasswordCacheTimeout(15);
        SettingsStorage.setThreadPoolSize(2);
        SettingsStorage.setTestMode(true);

        Path file = temporaryFolder.getRoot().toPath().resolve("config/settings.properties");
        Properties saved = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            saved.load(input);
        }

        Set<String> allowed = new HashSet<String>(Arrays.asList(
                "testMode", "data.dir", "passwordCacheTimeout", "securityLevel",
                "threadPoolSize", "volumeLimitPreset", "packageMultiFiles"));
        for (String key : saved.stringPropertyNames()) {
            assertTrue("设置文件不应出现未知键: " + key, allowed.contains(key));
        }
        assertFalse("设置文件不能保存已处理文件名",
                saved.toString().contains("工资表 2026.xlsx"));
        assertFalse("设置文件不能保存输入文件路径键",
                saved.containsKey("fileInputPath"));
        assertFalse("设置文件不能保存输出文件路径键",
                saved.containsKey("fileOutputPath"));
        // data.dir 是用户显式选择的数据目录，属于配置而不是文件处理残留。
        assertTrue(saved.containsKey("data.dir"));
    }

    @Test
    public void dataDirIsStoredAsAbsolutePath() throws Exception {
        SettingsStorage.setDataDir("custom-data");

        String expected = temporaryFolder.getRoot().toPath().resolve("custom-data").toAbsolutePath().normalize().toString();
        assertEquals(expected, SettingsStorage.getDataDir());

        SettingsStorage.resetForTests();
        assertEquals(expected, SettingsStorage.getDataDir());
    }
}
