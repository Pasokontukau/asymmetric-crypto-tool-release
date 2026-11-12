package settings.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import core.storage.AppPaths;
import core.util.ExportCrypto;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import settings.model.VolumeLimitPreset;

/** 设置导出默认加密；旧的明文设置文件仍能导入。 */
public class SettingsExportTest {

    private static final char[] PASSWORD = "settings-export-pass".toCharArray();

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private String originalAppHome;

    @Before
    public void pointAtTemporaryAppHome() {
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
    public void exportIsEncryptedAndRoundTrips() throws Exception {
        SettingsStorage.setVolumeLimitPreset(VolumeLimitPreset.GB_2);
        SettingsStorage.setPackageMultiFilesDefault(false);
        SettingsStorage.setPasswordCacheTimeout(30);

        File target = temporaryFolder.newFile("settings.aexp");
        SettingsStorage.exportSettings(target, PASSWORD);

        byte[] stored = Files.readAllBytes(target.toPath());
        assertTrue("设置导出应是加密容器", ExportCrypto.isEncrypted(stored));
        String raw = new String(stored, StandardCharsets.ISO_8859_1);
        assertFalse("导出文件不能出现设置键名", raw.contains("volumeLimitPreset"));
        assertFalse("导出文件不能出现数据目录", raw.contains("data.dir"));

        // 模拟“换一台机器导入”：删掉当前设置文件，再让存储重新解析。
        Files.deleteIfExists(temporaryFolder.getRoot().toPath()
                .resolve("config/settings.properties"));
        SettingsStorage.resetForTests();
        assertEquals(VolumeLimitPreset.DEFAULT, SettingsStorage.getVolumeLimitPreset());

        SettingsStorage.importSettings(target, PASSWORD);
        assertEquals(VolumeLimitPreset.GB_2, SettingsStorage.getVolumeLimitPreset());
        assertFalse(SettingsStorage.isPackageMultiFilesDefault());
        assertEquals(30, SettingsStorage.getPasswordCacheTimeout());
    }

    @Test
    public void wrongPasswordDoesNotChangeSettings() throws Exception {
        SettingsStorage.setVolumeLimitPreset(VolumeLimitPreset.GB_1);
        File target = temporaryFolder.newFile("settings.aexp");
        SettingsStorage.exportSettings(target, PASSWORD);

        try {
            SettingsStorage.importSettings(target, "wrong-password".toCharArray());
            fail("错误密码不应导入成功");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("密码错误"));
        }
        assertEquals(VolumeLimitPreset.GB_1, SettingsStorage.getVolumeLimitPreset());
    }

    @Test
    public void legacyPlaintextSettingsAreRejected() throws Exception {
        SettingsStorage.setVolumeLimitPreset(VolumeLimitPreset.GB_1);

        File legacy = temporaryFolder.newFile("settings.properties");
        Properties props = new Properties();
        props.setProperty("volumeLimitPreset", "MB_100");
        props.setProperty("packageMultiFiles", "false");
        try (OutputStream output = Files.newOutputStream(legacy.toPath())) {
            props.store(output, "legacy");
        }

        try {
            SettingsStorage.importSettings(legacy, null);
            fail("旧明文设置不应被导入");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("旧明文"));
        }

        assertEquals("拒绝导入后设置不能变",
                VolumeLimitPreset.GB_1, SettingsStorage.getVolumeLimitPreset());
        assertTrue(SettingsStorage.isPackageMultiFilesDefault());
    }
}
