import app.MainFrame;
import core.storage.StorageManager;
import crypto.key.KeyPairManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import keymanagement.model.KeyPairInfo;
import keymanagement.storage.KeyPairStorage;
import securecomm.ui.SecureTextPanel;

/** 真实界面驱动共用的小工具：窗口/字段/按钮查找、密码框泵送、夹具密钥与文本。 */
final class UiDriverSupport {

    private UiDriverSupport() {
    }

    static void pass(String message) {
        System.out.println("PASS " + message);
    }

    static void fail(String message) {
        System.out.println("FAIL " + message);
        System.exit(2);
    }

    static boolean waitFor(Condition condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.matches()) {
                return true;
            }
            Thread.sleep(100L);
        }
        return condition.matches();
    }

    static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    static Object fieldQuietly(Object target, String name) {
        try {
            return field(target, name);
        } catch (Exception error) {
            return null;
        }
    }

    static JButton findButton(Component root, String text) {
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

    static <T extends Component> T find(Component root, Class<T> type) {
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

    static MainFrame waitForFrame() throws Exception {
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

    /** 在真实界面的公钥下拉框里选中指定密钥，等面板确认。 */
    static void selectKey(SecureTextPanel panel, String keyName) throws Exception {
        JComboBox<?> combo = (JComboBox<?>) field(panel, "otherPublicKeyCombo");
        int keyIndex = -1;
        for (int index = 0; index < combo.getItemCount(); index++) {
            if (String.valueOf(combo.getItemAt(index)).endsWith(keyName)) {
                keyIndex = index;
            }
        }
        if (keyIndex < 0) {
            fail("公钥下拉框没有本机密钥项");
        }
        final int selected = keyIndex;
        SwingUtilities.invokeAndWait(() -> combo.setSelectedIndex(selected));
        if (!waitFor(() -> keyName.equals(fieldQuietly(panel, "currentKeyPairName")), 10_000L)) {
            fail("公钥下拉框未切换到 " + keyName);
        }
    }

    /** 通过文件页的真实入口加入文件，并等列表数量到位。 */
    static void addFiles(SecureTextPanel panel, File[] files) throws Exception {
        java.lang.reflect.Method addFiles = SecureTextPanel.class.getDeclaredMethod(
                "addFilesToFileList", File[].class);
        addFiles.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try {
                addFiles.invoke(panel, (Object) files);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        if (!waitFor(() -> {
            Object model = fieldQuietly(panel, "fileListModel");
            return model instanceof DefaultListModel
                    && ((DefaultListModel<?>) model).getSize() == files.length;
        }, 30_000L)) {
            fail("文件未全部加入列表");
        }
    }

    static void pumpPasswordDialogs(AtomicBoolean finished, String password) {
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
                    passwordField.setText(password);
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

    static void presetKeyPair(String keyName, String password, KeyPair keyPair) throws Exception {
        Files.createDirectories(Paths.get(StorageManager.getKeypairsDir()));
        byte[] encryptedPrivateKey = KeyPairManager.encryptPrivateKey(
                keyPair.getPrivate().getEncoded(), password.toCharArray());
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        File privateKey = new File(StorageManager.getKeypairsDir(), keyName + ".pem");
        KeyPairInfo info = new KeyPairInfo(
                keyName, Instant.now(), "RSA", 4096, publicKey, privateKey.getAbsolutePath());
        KeyPairStorage.saveKeyPair(keyName, info, encryptedPrivateKey);
        KeyPairStorage.setDefaultKeyPair(keyName);
    }

    static String randomText(long seed, int count) {
        Random random = new Random(seed);
        StringBuilder builder = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            builder.append((char) ('a' + random.nextInt(26)));
        }
        return builder.toString();
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        int read;
        try (InputStream input = Files.newInputStream(file.toPath())) {
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest()) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }

    interface Condition {
        boolean matches() throws Exception;
    }
}
