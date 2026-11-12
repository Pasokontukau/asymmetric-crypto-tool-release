package settings.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import core.storage.AppPaths;
import core.storage.StorageManager;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 桌面 UI 设置（字体/主题/默认输出目录）的隐私边界。
 *
 * <p>默认输出目录在程序目录内时写成相对路径，设置文件不应出现安装位置或用户名；
 * 用户选择程序目录外的目录时才保留绝对路径。</p>
 */
public class SettingsManagerPrivacyTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private String originalAppHome;
    private String originalDataDir;

    @Before
    public void pointAtTemporaryDataDir() throws Exception {
        originalAppHome = System.getProperty(AppPaths.APP_HOME_PROPERTY);
        originalDataDir = StorageManager.getDataDir();
        System.setProperty(AppPaths.APP_HOME_PROPERTY,
                temporaryFolder.getRoot().getAbsolutePath());
        StorageManager.useDataDirForTests(
                temporaryFolder.getRoot().toPath().resolve("data").toString());
        SettingsManager.init();
    }

    @After
    public void restoreLocation() {
        StorageManager.useDataDirForTests(originalDataDir);
        if (originalAppHome == null) {
            System.clearProperty(AppPaths.APP_HOME_PROPERTY);
        } else {
            System.setProperty(AppPaths.APP_HOME_PROPERTY, originalAppHome);
        }
    }

    @Test
    public void defaultOutputDirectoryIsStoredRelatively() throws Exception {
        Path settingsFile = temporaryFolder.getRoot().toPath()
                .resolve("data/settings.properties");
        Properties saved = load(settingsFile);

        assertEquals("output", saved.getProperty("default_output_dir"));
        String text = new String(Files.readAllBytes(settingsFile), "UTF-8");
        assertFalse("设置文件不能写入程序安装位置", text.contains(
                temporaryFolder.getRoot().getAbsolutePath()));
        String userName = System.getProperty("user.name");
        if (userName != null && userName.length() >= 4) {
            assertFalse("设置文件不能写入用户名", text.contains(userName));
        }
        assertFalse("设置文件不能写入已处理文件名", text.contains("工资表 2026.xlsx"));

        assertEquals(
                temporaryFolder.getRoot().toPath().resolve("output").toAbsolutePath().normalize().toString(),
                SettingsManager.getDefaultOutputDirectory());
    }

    @Test
    public void customDirectoryUnderAppHomeIsAlsoRelative() throws Exception {
        Path custom = temporaryFolder.getRoot().toPath().resolve("custom-output");
        SettingsManager.setDefaultOutputDirectory(custom.toString());

        Properties saved = load(temporaryFolder.getRoot().toPath()
                .resolve("data/settings.properties"));
        assertEquals("custom-output", saved.getProperty("default_output_dir"));
        assertEquals(custom.toAbsolutePath().normalize().toString(),
                SettingsManager.getDefaultOutputDirectory());
    }

    @Test
    public void directoryOutsideAppHomeStaysAbsolute() throws Exception {
        Path outside = Files.createTempDirectory("aegix-outside-");
        try {
            SettingsManager.setDefaultOutputDirectory(outside.toString());

            Properties saved = load(temporaryFolder.getRoot().toPath()
                    .resolve("data/settings.properties"));
            assertEquals(outside.toString(), saved.getProperty("default_output_dir"));
            assertEquals(outside.toString(), SettingsManager.getDefaultOutputDirectory());
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    private static Properties load(Path file) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return properties;
    }
}
