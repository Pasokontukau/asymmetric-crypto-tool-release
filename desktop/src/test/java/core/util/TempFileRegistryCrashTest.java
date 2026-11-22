package core.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 崩溃后的启动清理回归：真实子 JVM 创建含明文的 ZIP、解密中间文件和暂存目录，
 * 用 {@code halt} 跳过 shutdown hook；父进程只调用启动清理，不再执行任何任务收尾。
 */
public class TempFileRegistryCrashTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void cleanAllRemovesTrackedFileAndDirectory() throws Exception {
        File tempRoot = temporaryFolder.newFolder("clean-all-temp");
        File file = TempFileRegistry.createTempFile("aegix-decrypt-", ".tmp", tempRoot);
        File directory = TempFileRegistry.createTempDirectory("aegix-unzip-", tempRoot);
        java.nio.file.Files.write(file.toPath(), "secret".getBytes(StandardCharsets.UTF_8));
        java.nio.file.Files.write(new File(directory, "note.txt").toPath(),
                "secret".getBytes(StandardCharsets.UTF_8));

        TempFileRegistry.cleanAll();

        assertFalse(file.exists());
        assertFalse(directory.exists());
        assertFalse("正常清理后不应留下会话目录",
                new File(tempRoot, "aegix-sessions").exists());
    }

    @Test
    public void startupCleanupRemovesLegacyPrefixesWithoutSession() throws Exception {
        File tempRoot = temporaryFolder.newFolder("legacy-temp");
        File zip = new File(tempRoot, "aegix-folder-legacy.zip");
        File decrypted = new File(tempRoot, "aegix-decrypt-legacy.tmp");
        File staging = new File(tempRoot, "aegix-unzip-legacy");
        assertTrue(zip.createNewFile());
        assertTrue(decrypted.createNewFile());
        assertTrue(staging.mkdirs());

        int cleaned = TempFileRegistry.cleanupStale(tempRoot.toPath());

        assertTrue("旧版本前缀也应被启动清理，实际=" + cleaned, cleaned >= 3);
        assertFalse(zip.exists());
        assertFalse(decrypted.exists());
        assertFalse(staging.exists());
    }

    @Test
    public void startupCleanupRemovesPlaintextLeftByHaltedProcess() throws Exception {
        File tempRoot = temporaryFolder.newFolder("crash-temp");
        File outputDirectory = new File(tempRoot, "output");
        assertTrue(outputDirectory.mkdirs());

        Process child = startFixture(tempRoot, outputDirectory);
        String childOutput = readAll(child.getInputStream());
        assertTrue("崩溃夹具超时；输出: " + childOutput,
                child.waitFor(30, TimeUnit.SECONDS));
        assertEquals("崩溃夹具必须跳过 shutdown hook 退出；输出: " + childOutput,
                7, child.exitValue());

        File zip = firstWithPrefix(tempRoot, "aegix-folder-");
        File decrypted = firstWithPrefix(outputDirectory, "aegix-decrypt-");
        File staging = firstWithPrefix(outputDirectory, "aegix-unzip-");
        assertNotNull("夹具没有留下文件夹临时 ZIP", zip);
        assertNotNull("夹具没有留下解密临时文件", decrypted);
        assertNotNull("夹具没有留下解包暂存目录", staging);
        assertTrue(zip.isFile());
        assertTrue(decrypted.isFile());
        assertTrue(staging.isDirectory());

        int cleaned = TempFileRegistry.cleanupStale(tempRoot.toPath());

        assertTrue("启动清理应至少删除三个已知临时路径，实际=" + cleaned, cleaned >= 3);
        assertFalse("文件夹临时 ZIP 仍存在", zip.exists());
        assertFalse("解密临时文件仍存在", decrypted.exists());
        assertFalse("解包暂存目录仍存在", staging.exists());
        assertTrue("输出目录不能留下 aegix 临时路径", managedEntries(outputDirectory).isEmpty());
        assertFalse("旧会话目录仍存在", new File(tempRoot, "aegix-sessions").exists());
    }

    private Process startFixture(File tempRoot, File outputDirectory) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Djava.io.tmpdir=" + tempRoot.getAbsolutePath());
        command.add("-cp");
        command.add(classpath());
        command.add(TempCrashFixture.class.getName());
        command.add(tempRoot.getAbsolutePath());
        command.add(outputDirectory.getAbsolutePath());
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
    }

    private static String javaExecutable() {
        String executable = isWindows() ? "java.exe" : "java";
        return new File(new File(System.getProperty("java.home"), "bin"), executable)
                .getAbsolutePath();
    }

    private static String classpath() {
        String current = System.getProperty("java.class.path", "");
        File testClasses = new File("target/test-classes");
        File mainClasses = new File("target/classes");
        if (testClasses.isDirectory() && mainClasses.isDirectory()) {
            return testClasses.getAbsolutePath()
                    + File.pathSeparator + mainClasses.getAbsolutePath()
                    + File.pathSeparator + current;
        }
        return current;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static File firstWithPrefix(File directory, String prefix) {
        File[] files = directory.listFiles();
        if (files == null) {
            return null;
        }
        Arrays.sort(files);
        for (File file : files) {
            if (file.getName().startsWith(prefix)) {
                return file;
            }
        }
        return null;
    }

    private static List<File> managedEntries(File directory) {
        List<File> matches = new ArrayList<>();
        File[] files = directory.listFiles();
        if (files == null) {
            return matches;
        }
        for (File file : files) {
            if (TempFileRegistry.isManagedName(file.getName())) {
                matches.add(file);
            }
        }
        return matches;
    }

    private static String readAll(InputStream input) throws Exception {
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
