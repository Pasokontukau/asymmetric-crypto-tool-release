package core.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import core.storage.AppPaths;
import java.io.File;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 重启命令构建：便携包拉起 launcher，开发环境回退到 java 并指定真实主类。 */
public class RestartHelperTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private String originalLauncher;
    private String originalAppHome;

    @Before
    public void rememberProperties() {
        originalLauncher = System.getProperty(AppPaths.JPACKAGE_APP_PATH_PROPERTY);
        originalAppHome = System.getProperty(AppPaths.APP_HOME_PROPERTY);
        System.clearProperty(AppPaths.JPACKAGE_APP_PATH_PROPERTY);
        System.clearProperty(AppPaths.APP_HOME_PROPERTY);
    }

    @After
    public void restoreProperties() {
        restore(AppPaths.JPACKAGE_APP_PATH_PROPERTY, originalLauncher);
        restore(AppPaths.APP_HOME_PROPERTY, originalAppHome);
    }

    @Test
    public void portableLauncherIsPreferred() throws Exception {
        File launcher = temporaryFolder.newFile("CryptoAssistant.exe");
        System.setProperty(AppPaths.JPACKAGE_APP_PATH_PROPERTY, launcher.getAbsolutePath());

        List<String> command = RestartHelper.buildRestartCommand();

        assertEquals(1, command.size());
        assertEquals(launcher.getAbsolutePath(), command.get(0));
    }

    @Test
    public void developmentFallsBackToJavaClasspath() throws Exception {
        List<String> command = RestartHelper.buildRestartCommand();

        assertTrue(command.size() >= 4);
        assertTrue(command.get(0).toLowerCase().contains("java"));
        assertTrue(command.contains("-cp"));
        assertTrue(command.contains("app.AppMain"));
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
