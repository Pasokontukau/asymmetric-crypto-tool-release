import app.AppMain;
import app.MainFrame;
import app.theme.UIConstants;
import core.storage.StorageManager;
import core.util.LogManager;
import crypto.key.KeyPairManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import keymanagement.model.KeyPairInfo;
import keymanagement.storage.KeyPairStorage;
import securecomm.ui.SecureTextPanel;
import settings.manager.SettingsManager;
import settings.storage.SettingsStorage;

/**
 * 真实 MainFrame + 真实文件列表 + 真实密码框 + 真实“加密并签名”按钮。
 *
 * <p>参数：mode（long|disk-full|readonly|file-in-use|locked-file） home</p>
 *
 * <ul>
 *   <li>long：输出目录含 300 字符目录名；预检应给出“路径过长”，且不创建目录。</li>
 *   <li>disk-full：-Daegix.test.failWrite=disk-full 注入写入失败；应清理临时文件。</li>
 *   <li>readonly：输出目录由脚本用 ACL 拒绝写入；应提示“没有写入权限”。</li>
 *   <li>file-in-use：-Daegix.test.failWrite=file-in-use 注入共享冲突；应提示文件被占用。</li>
 *   <li>locked-file：外部 PowerShell 进程以 FileShare.None 独占源文件，产生真实的
 *       Windows 共享冲突（非注入）；应提示文件被占用，且输出目录无残留。</li>
 * </ul>
 */
public class ErrorPathDriver {

    private static final String KEY_NAME = "e2e-errors";
    private static final String PASSWORD = "e2e-pass-123";
    private static final long TIMEOUT_MS = 45_000L;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "usage: ErrorPathDriver <long|disk-full|readonly|file-in-use> <home>");
        }
        String mode = args[0];
        File home = new File(args[1]).getAbsoluteFile();
        Files.createDirectories(home.toPath());
        System.setProperty("app.home", home.getAbsolutePath());
        System.setProperty("user.dir", home.getAbsolutePath());

        File inputDir = new File(home, "input");
        Files.createDirectories(inputDir.toPath());
        File source = new File(inputDir, "note.txt");
        Files.write(source.toPath(), "hello error path".getBytes(StandardCharsets.UTF_8));

        File outputDir = outputDirectoryFor(mode, home);
        // long 场景故意让 300 字符的目录段保持不存在，交给应用预检拒绝；
        // 其它场景先准备好目录，readonly 场景由脚本先建目录再加 ACL。
        if (!"long".equals(mode) && !outputDir.exists()) {
            Files.createDirectories(outputDir.toPath());
        }

        LogManager.init();
        SettingsManager.init();
        SettingsStorage.setTestMode(true);
        SettingsManager.setDefaultOutputDirectory(outputDir.getAbsolutePath());
        presetKeyPair();

        AppMain.main(new String[0]);
        MainFrame frame = waitForFrame();
        if (frame == null) {
            fail("MainFrame 未在超时内显示");
        }
        SecureTextPanel panel = find(frame.getContentPane(), SecureTextPanel.class);
        if (panel == null) {
            fail("未找到 SecureTextPanel");
        }

        JComboBox<?> combo = (JComboBox<?>) field(panel, "otherPublicKeyCombo");
        int keyIndex = -1;
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < combo.getItemCount(); i++) {
            String item = String.valueOf(combo.getItemAt(i));
            items.append(" [").append(item).append(']');
            if (item.endsWith(KEY_NAME)) {
                keyIndex = i;
            }
        }
        if (keyIndex < 0) {
            fail("公钥下拉框没有本机密钥项:" + items);
        }
        final int selectedKeyIndex = keyIndex;
        SwingUtilities.invokeAndWait(() -> combo.setSelectedIndex(selectedKeyIndex));
        waitFor(() -> KEY_NAME.equals(fieldQuietly(panel, "currentKeyPairName")), 10_000L);

        Method addFiles = SecureTextPanel.class.getDeclaredMethod(
                "addFilesToFileList", File[].class);
        addFiles.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try {
                addFiles.invoke(panel, (Object) new File[]{source});
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        if (!waitFor(() -> {
            Object model = fieldQuietly(panel, "fileListModel");
            return model instanceof javax.swing.DefaultListModel
                    && ((javax.swing.DefaultListModel<?>) model).getSize() > 0;
        }, 15_000L)) {
            fail("文件未加入文件列表");
        }

        JLabel label = (JLabel) field(panel, "verifyResultLabel");
        AtomicBoolean finished = new AtomicBoolean(false);
        Thread passwordPump = new Thread(() -> pumpPasswordDialogs(finished), "password-pump");
        passwordPump.setDaemon(true);
        passwordPump.start();

        JButton encryptButton = findButton(frame, "加密并签名");
        if (encryptButton == null) {
            fail("未找到“加密并签名”按钮");
        }

        Process lockHolder = null;
        if ("locked-file".equals(mode)) {
            lockHolder = lockWithExternalProcess(home, source);
        }
        final Process holder = lockHolder;
        if (holder != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                holder.destroyForcibly();
            }, "lock-holder-cleanup"));
        }

        SwingUtilities.invokeAndWait(encryptButton::doClick);

        if (!waitFor(() -> {
            String text = label.getText();
            return text != null && (text.contains("批量加密完成")
                    || text.contains("批量加密失败"));
        }, TIMEOUT_MS)) {
            fail("操作未在超时内结束，当前提示: " + label.getText());
        }
        finished.set(true);
        if (holder != null) {
            holder.destroyForcibly();
        }

        String labelText = label.getText() == null ? "" : label.getText();
        boolean red = UIConstants.ERROR_TEXT_COLOR.equals(label.getForeground());
        String expected = expectedMessage(mode);
        boolean messageOk = labelText.contains(expected);
        boolean noOutput = "long".equals(mode)
                ? !outputDir.exists()
                : noOutputArtifacts(outputDir);

        System.out.println("MODE=" + mode);
        System.out.println("LABEL=" + labelText);
        System.out.println("RED=" + red);
        System.out.println("MESSAGE_OK=" + messageOk);
        System.out.println("NO_OUTPUT=" + noOutput);
        System.out.println("OUTPUT_DIR=" + outputDir.getAbsolutePath());
        if (!red || !messageOk || !noOutput) {
            fail("断言失败: red=" + red + " messageOk=" + messageOk + " noOutput=" + noOutput);
        }
        System.out.println("PASS");
        frame.dispose();
        System.exit(0);
    }

    private static File outputDirectoryFor(String mode, File home) {
        if ("long".equals(mode)) {
            StringBuilder longName = new StringBuilder();
            for (int i = 0; i < 300; i++) {
                longName.append('x');
            }
            File parent = new File(home, "out-long");
            try {
                Files.createDirectories(parent.toPath());
            } catch (Exception error) {
                fail("无法创建长路径父目录: " + error.getMessage());
            }
            return new File(parent, longName.toString());
        }
        return new File(home, "output");
    }

    private static String expectedMessage(String mode) {
        if ("long".equals(mode)) {
            return "路径过长";
        }
        if ("disk-full".equals(mode)) {
            return "磁盘空间不足";
        }
        if ("readonly".equals(mode)) {
            return "没有写入权限";
        }
        if ("file-in-use".equals(mode) || "locked-file".equals(mode)) {
            return "正在被其他程序使用";
        }
        throw new IllegalArgumentException("unknown mode: " + mode);
    }

    private static boolean noOutputArtifacts(File directory) {
        File[] files = directory.listFiles();
        if (files == null || files.length == 0) {
            return true;
        }
        for (File file : files) {
            String name = file.getName().toLowerCase();
            if (name.endsWith(".aegix") || name.endsWith(".tmp")
                    || name.endsWith(".part") || name.contains("aegix-write-")) {
                return false;
            }
        }
        return true;
    }

    /**
     * 真实外部锁：另起一个 PowerShell 进程，以 FileShare.None 打开源文件并保持到测试结束。
     * 这不依赖任何 -Daegix.test.* 注入属性。
     */
    private static Process lockWithExternalProcess(File home, File source) throws Exception {
        File script = new File(home, "lock-file.ps1");
        String powershell = "param([string]$Path,[string]$Ready)\n"
                + "$stream=[IO.File]::Open($Path,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)\n"
                + "try {\n"
                + "  [IO.File]::WriteAllText($Ready,'ready')\n"
                + "  Start-Sleep -Seconds 300\n"
                + "} finally { $stream.Dispose() }\n";
        Files.writeString(script.toPath(), powershell, StandardCharsets.UTF_8);

        File ready = new File(home, "lock-file.ready");
        Files.deleteIfExists(ready.toPath());
        ProcessBuilder builder = new ProcessBuilder(
                "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", script.getAbsolutePath(), source.getAbsolutePath(), ready.getAbsolutePath());
        builder.redirectErrorStream(true);
        builder.redirectOutput(new File(home, "lock-file.log"));
        Process process = builder.start();

        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (ready.isFile()) {
                return process;
            }
            if (!process.isAlive()) {
                fail("外部锁进程提前退出，无法制造共享冲突");
            }
            Thread.sleep(50L);
        }
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        fail("外部锁进程未在超时内就绪");
        return null;
    }

    private static void presetKeyPair() throws Exception {
        Files.createDirectories(Paths.get(StorageManager.getKeypairsDir()));
        KeyPair keyPair = KeyPairManager.generateKeyPair(4096);
        byte[] encryptedPrivateKey = KeyPairManager.encryptPrivateKey(
                keyPair.getPrivate().getEncoded(), PASSWORD.toCharArray());
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        File privateKey = new File(StorageManager.getKeypairsDir(), KEY_NAME + ".pem");
        KeyPairInfo info = new KeyPairInfo(
                KEY_NAME, Instant.now(), "RSA", 4096, publicKey, privateKey.getAbsolutePath());
        KeyPairStorage.saveKeyPair(KEY_NAME, info, encryptedPrivateKey);
        KeyPairStorage.setDefaultKeyPair(KEY_NAME);
    }

    private static void pumpPasswordDialogs(AtomicBoolean finished) {
        Set<Window> handled = Collections.newSetFromMap(new IdentityHashMap<Window, Boolean>());
        while (!finished.get()) {
            for (Window window : Window.getWindows()) {
                if (!(window instanceof JDialog) || !window.isShowing()) {
                    continue;
                }
                final JDialog dialog = (JDialog) window;
                final JPasswordField passwordField = find(dialog, JPasswordField.class);
                if (passwordField == null || !handled.add(dialog)) {
                    continue;
                }
                SwingUtilities.invokeLater(() -> {
                    passwordField.setText(PASSWORD);
                    JButton ok = findButton(dialog, "确定");
                    if (ok == null) {
                        ok = findButton(dialog, "OK");
                    }
                    if (ok != null) {
                        ok.doClick();
                    }
                });
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static MainFrame waitForFrame() throws Exception {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            for (Window window : Window.getWindows()) {
                if (window instanceof MainFrame && window.isShowing()) {
                    return (MainFrame) window;
                }
            }
            Thread.sleep(100L);
        }
        return null;
    }

    private static boolean waitFor(Condition condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.test()) {
                return true;
            }
            Thread.sleep(100L);
        }
        return condition.test();
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object fieldQuietly(Object target, String name) {
        try {
            return field(target, name);
        } catch (Exception error) {
            return null;
        }
    }

    private static <T> T find(Component root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof Container) {
            for (Component child : ((Container) root).getComponents()) {
                T found = find(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JButton findButton(Component root, String text) {
        if (root instanceof JButton && text.equals(((JButton) root).getText())) {
            return (JButton) root;
        }
        if (root instanceof Container) {
            for (Component child : ((Container) root).getComponents()) {
                JButton found = findButton(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void fail(String message) {
        System.out.println("FAIL: " + message);
        System.exit(1);
    }

    private interface Condition {
        boolean test() throws Exception;
    }
}
