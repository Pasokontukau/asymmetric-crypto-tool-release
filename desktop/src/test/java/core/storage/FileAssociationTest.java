package core.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** `.aegix` 关联：命令行拼装与平台判定（不写注册表）。 */
public class FileAssociationTest {

    @Test
    public void exeCommandQuotesExecutableAndPassesPlaceholder() {
        String command = FileAssociation.buildExeCommand("C:\\Program Files\\CryptoAssistant\\CryptoAssistant.exe");
        assertEquals("\"C:\\Program Files\\CryptoAssistant\\CryptoAssistant.exe\" \"%1\"", command);
    }

    @Test
    public void jarCommandKeepsArgumentOrder() {
        String command = FileAssociation.buildJarCommand("C:\\javaw.exe", "C:\\app\\cryptoassistant.jar");
        assertEquals("\"C:\\javaw.exe\" -jar \"C:\\app\\cryptoassistant.jar\" \"%1\"", command);
    }

    @Test
    public void installedExeIsNullWithoutAppHome() {
        String original = System.getProperty("app.home");
        System.clearProperty("app.home");
        try {
            assertNull(FileAssociation.installedExe());
        } finally {
            if (original != null) {
                System.setProperty("app.home", original);
            }
        }
    }

    @Test
    public void supportMatchesOperatingSystem() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        assertEquals(windows, FileAssociation.isSupported());
        assertFalse(FileAssociation.PROG_ID.isEmpty());
        assertTrue(FileAssociation.EXTENSION.startsWith("."));
    }
}
