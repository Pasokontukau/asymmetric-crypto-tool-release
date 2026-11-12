package core.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 程序根目录解析与数据目录安全边界。 */
public class AppPathsTest {

    private static final String[] KEYS = {
            AppPaths.APP_HOME_PROPERTY,
            AppPaths.JPACKAGE_APP_PATH_PROPERTY,
            "user.dir"
    };

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final Map<String, String> originalValues = new HashMap<String, String>();

    @Before
    public void rememberSystemProperties() {
        for (String key : KEYS) {
            originalValues.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
    }

    @After
    public void restoreSystemProperties() {
        for (String key : KEYS) {
            String value = originalValues.get(key);
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        }
    }

    @Test
    public void appHomePropertyWins() throws Exception {
        File appHome = temporaryFolder.newFolder("portable");
        System.setProperty(AppPaths.APP_HOME_PROPERTY, appHome.getAbsolutePath());

        assertEquals(appHome.getCanonicalFile().toPath(), AppPaths.home().toFile().getCanonicalFile().toPath());
    }

    @Test
    public void launcherParentIsUsedWhenAppHomeIsMissing() throws Exception {
        File launcher = temporaryFolder.newFile("CryptoAssistant.exe");
        System.setProperty(AppPaths.JPACKAGE_APP_PATH_PROPERTY, launcher.getAbsolutePath());

        assertEquals(temporaryFolder.getRoot().getCanonicalFile().toPath(),
                AppPaths.home().toFile().getCanonicalFile().toPath());
    }

    @Test
    public void userDirIsDevelopmentFallback() throws Exception {
        File workingDir = temporaryFolder.newFolder("repo");
        System.setProperty("user.dir", workingDir.getAbsolutePath());

        assertEquals(workingDir.getCanonicalFile().toPath(), AppPaths.home().toFile().getCanonicalFile().toPath());
    }

    @Test
    public void relativeConfiguredPathResolvesAgainstHome() throws Exception {
        File appHome = temporaryFolder.newFolder("app-home");
        System.setProperty(AppPaths.APP_HOME_PROPERTY, appHome.getAbsolutePath());

        assertEquals(appHome.getCanonicalFile().toPath().resolve("custom-data"),
                AppPaths.resolvePath("custom-data").toFile().getCanonicalFile().toPath());
    }

    @Test
    public void defaultDirectoriesLiveUnderHome() throws Exception {
        File appHome = temporaryFolder.newFolder("app-home-2");
        System.setProperty(AppPaths.APP_HOME_PROPERTY, appHome.getAbsolutePath());

        assertEquals(appHome.getCanonicalFile().toPath().resolve("data"),
                AppPaths.defaultDataDir().toFile().getCanonicalFile().toPath());
        assertEquals(appHome.getCanonicalFile().toPath().resolve("output"),
                AppPaths.defaultOutputDir().toFile().getCanonicalFile().toPath());
    }

    @Test
    public void managedDataDirAcceptsProgramAndUserLocations() throws Exception {
        File appHome = temporaryFolder.newFolder("managed-app");
        System.setProperty(AppPaths.APP_HOME_PROPERTY, appHome.getAbsolutePath());
        Path appDataDir = appHome.toPath().resolve("data");

        assertTrue(AppPaths.isManagedDataDir(appDataDir));
        assertTrue(AppPaths.isManagedDataDir(AppPaths.userDataDir().resolve("data")));
    }

    @Test
    public void managedDataDirRejectsOutsidePaths() throws Exception {
        File appHome = temporaryFolder.newFolder("managed-app-2");
        System.setProperty(AppPaths.APP_HOME_PROPERTY, appHome.getAbsolutePath());

        Path outside = temporaryFolder.newFolder("outside").toPath().resolve("data");
        assertFalse(AppPaths.isManagedDataDir(outside));
    }

    @Test
    public void userDataDirIsNamedCryptoAssistant() {
        assertEquals("CryptoAssistant", AppPaths.userDataDir().getFileName().toString());
    }

    @Test
    public void localDataDirIsNamedCryptoAssistant() {
        assertEquals("CryptoAssistant", AppPaths.localDataDir().getFileName().toString());
    }

    @Test
    public void writableTempDirIsKept() throws Exception {
        Path current = temporaryFolder.newFolder("tmp-ok").toPath();
        Path fallback = temporaryFolder.newFolder("tmp-fallback").toPath();

        assertEquals(current, AppPaths.chooseWritableTempDir(current, Collections.singletonList(fallback)));
    }

    @Test
    public void nonWritableTempDirFallsBack() throws Exception {
        Path notDirectory = temporaryFolder.newFile("tmp-file").toPath();
        Path fallback = temporaryFolder.newFolder("tmp-fallback-2").toPath();

        assertEquals(fallback, AppPaths.chooseWritableTempDir(notDirectory, Collections.singletonList(fallback)));
    }

    @Test
    public void missingFallbackTempDirIsCreated() throws Exception {
        Path notDirectory = temporaryFolder.newFile("tmp-file-2").toPath();
        Path fallback = temporaryFolder.getRoot().toPath().resolve("nested").resolve("temp");

        assertEquals(fallback, AppPaths.chooseWritableTempDir(notDirectory, Collections.singletonList(fallback)));
        assertTrue(Files.isDirectory(fallback));
    }

    @Test
    public void fallbackTempDirIsWrittenToSystemProperties() throws Exception {
        String originalTmp = System.getProperty("java.io.tmpdir");
        String originalJna = System.getProperty("jna.tmpdir");
        try {
            Path notDirectory = temporaryFolder.newFile("tmp-file-3").toPath();
            Path fallback = temporaryFolder.newFolder("tmp-fallback-3").toPath();

            assertEquals(fallback, AppPaths.ensureWritableTempDir(notDirectory, Collections.singletonList(fallback)));
            assertEquals(fallback.toString(), System.getProperty("java.io.tmpdir"));
            assertEquals(fallback.toString(), System.getProperty("jna.tmpdir"));
        } finally {
            restoreProperty("java.io.tmpdir", originalTmp);
            restoreProperty("jna.tmpdir", originalJna);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
