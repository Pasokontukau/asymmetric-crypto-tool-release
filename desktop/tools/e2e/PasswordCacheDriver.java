import app.AppMain;
import app.MainFrame;
import core.util.LogManager;
import crypto.key.KeyPairManager;
import crypto.key.PrivateKeyAccessService;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.io.File;
import java.nio.file.Files;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import keymanagement.storage.KeyPairStorage;
import keymanagement.storage.KeychainStorage;
import securecomm.ui.SecureTextPanel;
import settings.manager.SettingsManager;
import settings.storage.SettingsStorage;

/**
 * P-38 密码缓存三模式的真实界面自测：真 MainFrame、真 JOptionPane 密码框。
 *
 * <p>每个模式一个进程；跨进程复用靠同一个 home 目录下的
 * {@code config/settings.properties} 与 Windows 密钥链（DPAPI）。
 * 用法：{@code PasswordCacheDriver <mode> <home>}。</p>
 */
public final class PasswordCacheDriver {

    private static final String KEY_A = "e2e-cache-a";
    private static final String KEY_B = "e2e-cache-b";
    private static final String PASSWORD = "e2e-cache-pass-123";

    private static DialogPump pump;
    private static MainFrame frame;

    private PasswordCacheDriver() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: PasswordCacheDriver <mode> <home>");
        }
        String mode = args[0];
        File home = new File(args[1]).getAbsoluteFile();
        Files.createDirectories(home.toPath());
        System.setProperty("app.home", home.getAbsolutePath());
        System.setProperty("user.dir", home.getAbsolutePath());

        LogManager.init();
        SettingsManager.init();
        SettingsStorage.setTestMode(true);
        SettingsStorage.setUpdateCheckEnabled(false);

        switch (mode) {
            case "convenient":
                convenient();
                break;
            case "convenient-restart":
                convenientRestart();
                break;
            case "balanced-after-convenient":
                balancedAfterConvenient();
                break;
            case "switch-key":
                switchKey();
                break;
            case "convenient-delete":
                convenientDelete();
                break;
            case "strict":
                strict();
                break;
            case "wrong-password":
                wrongPassword();
                break;
            case "balanced-ttl":
                balancedTtl();
                break;
            default:
                throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    // ------------------------------------------------------------------
    // 场景
    // ------------------------------------------------------------------

    /** 便捷模式：第一次询问并写入系统密钥链，然后正常退出（不主动清密钥链）。 */
    private static void convenient() throws Exception {
        setLevel(SettingsStorage.SECURITY_LEVEL_CONVENIENT);
        createKey(KEY_A);
        startPump();
        startApp();

        fetch(KEY_A, "便捷模式首次解密");
        expectPrompts(1, "便捷模式首次操作");
        if (!KeychainStorage.hasPassword(KEY_A)) {
            fail("便捷模式没有把密码写入系统密钥链");
        }
        UiDriverSupport.pass("便捷模式已把密码写入系统密钥链（DPAPI）");

        System.out.println("PASSWORD_CACHE_CONVENIENT_PASS");
        System.exit(0);
    }

    /** 便捷模式重启：跨进程复用；切严格清空且每次都问；切平衡后同进程复用。 */
    private static void convenientRestart() throws Exception {
        startPump();
        startApp();

        fetch(KEY_A, "重启后便捷模式解密");
        expectPrompts(0, "便捷模式跨进程复用（密码框 0 次）");

        SettingsStorage.setSecurityLevel(SettingsStorage.SECURITY_LEVEL_STRICT);
        if (KeychainStorage.hasPassword(KEY_A)) {
            fail("切到严格模式后密钥链仍保留密码");
        }
        UiDriverSupport.pass("切到严格模式立即清空系统密钥链");

        fetch(KEY_A, "严格模式第一次");
        expectPrompts(1, "严格模式第一次操作");
        fetch(KEY_A, "严格模式第二次");
        expectPrompts(2, "严格模式第二次操作（每次询问）");

        SettingsStorage.setSecurityLevel(SettingsStorage.SECURITY_LEVEL_BALANCED);
        if (KeychainStorage.hasPassword(KEY_A)) {
            fail("严格切到平衡后密钥链仍有密码");
        }
        fetch(KEY_A, "平衡模式第一次");
        expectPrompts(3, "平衡模式第一次操作");
        fetch(KEY_A, "平衡模式第二次");
        expectPrompts(3, "平衡模式同进程内复用");

        System.out.println("PASSWORD_CACHE_CONVENIENT_RESTART_PASS");
        System.exit(0);
    }

    /** 离开便捷模式后重启：密钥链已清空，平衡模式必须重新询问。 */
    private static void balancedAfterConvenient() throws Exception {
        startPump();
        startApp();

        fetch(KEY_A, "离开便捷模式后重启解密");
        expectPrompts(1, "平衡模式重启后重新询问");
        if (KeychainStorage.hasPassword(KEY_A)) {
            fail("离开便捷模式后密钥链仍有密码");
        }
        UiDriverSupport.pass("离开便捷模式后密钥链为空，重启重新询问");

        System.out.println("PASSWORD_CACHE_BALANCED_AFTER_CONVENIENT_PASS");
        System.exit(0);
    }

    /** 界面切换到另一个密钥对：旧密钥链密码立即失效。 */
    private static void switchKey() throws Exception {
        setLevel(SettingsStorage.SECURITY_LEVEL_CONVENIENT);
        createKey(KEY_A);
        createKey(KEY_B);
        startPump();
        startApp();

        fetch(KEY_A, "切密钥前解密 A");
        expectPrompts(1, "切密钥前操作 A");
        if (!KeychainStorage.hasPassword(KEY_A)) {
            fail("A 的密码没有进入密钥链");
        }

        SecureTextPanel panel = UiDriverSupport.find(frame.getContentPane(), SecureTextPanel.class);
        if (panel == null) {
            fail("未找到 SecureTextPanel");
        }
        UiDriverSupport.selectKey(panel, KEY_B);
        Thread.sleep(200L);
        if (KeychainStorage.hasPassword(KEY_A)) {
            fail("界面切到密钥 B 后，A 的密钥链密码仍在");
        }
        UiDriverSupport.pass("界面切到密钥 B 后，A 的密钥链密码已清除");

        System.out.println("PASSWORD_CACHE_SWITCH_KEY_PASS");
        System.exit(0);
    }

    /** 删除密钥对：旧缓存失效，且不会再用旧密码解密。 */
    private static void convenientDelete() throws Exception {
        setLevel(SettingsStorage.SECURITY_LEVEL_CONVENIENT);
        createKey(KEY_A);
        startPump();
        startApp();

        fetch(KEY_A, "删除前解密");
        expectPrompts(1, "删除前操作");
        if (!KeychainStorage.hasPassword(KEY_A)) {
            fail("删除前密码没有进入密钥链");
        }

        if (!KeyPairStorage.deleteKeyPair(KEY_A)) {
            fail("删除密钥对失败");
        }
        if (KeychainStorage.hasPassword(KEY_A)) {
            fail("删除密钥对后密钥链密码仍在");
        }
        PrivateKey after = PrivateKeyAccessService.getPrivateKey(KEY_A);
        Thread.sleep(200L);
        if (after != null) {
            fail("删除密钥对后仍能拿到私钥");
        }
        expectPrompts(1, "删除后不再使用旧缓存（未再弹密码框）");
        UiDriverSupport.pass("删除密钥对后旧缓存失效，私钥不可再取");

        System.out.println("PASSWORD_CACHE_DELETE_PASS");
        System.exit(0);
    }

    /** 严格模式：连续三次操作都询问，且不写密钥链。 */
    private static void strict() throws Exception {
        setLevel(SettingsStorage.SECURITY_LEVEL_STRICT);
        createKey(KEY_A);
        startPump();
        startApp();

        for (int index = 1; index <= 3; index++) {
            fetch(KEY_A, "严格模式第 " + index + " 次");
            expectPrompts(index, "严格模式第 " + index + " 次操作");
        }
        if (KeychainStorage.hasPassword(KEY_A)) {
            fail("严格模式把密码写进了密钥链");
        }
        UiDriverSupport.pass("严格模式不写密钥链");

        System.out.println("PASSWORD_CACHE_STRICT_PASS");
        System.exit(0);
    }

    /** 口令输错：给出可读提示，不缓存失败结果；重试正确后正常。 */
    private static void wrongPassword() throws Exception {
        setLevel(SettingsStorage.SECURITY_LEVEL_CONVENIENT);
        createKey(KEY_A);
        startPump();
        pump.answer("wrong-pass-000");
        startApp();

        PrivateKey failed = PrivateKeyAccessService.getPrivateKey(KEY_A);
        Thread.sleep(300L);
        if (failed != null) {
            fail("错误口令仍拿到了私钥");
        }
        expectPrompts(1, "错误口令只询问一次");
        if (pump.messages() < 1) {
            fail("错误口令没有给出可读提示");
        }
        UiDriverSupport.pass("错误提示：" + pump.lastMessage().replace('\n', ' ').trim());
        if (KeychainStorage.hasPassword(KEY_A)) {
            fail("错误口令被缓存成成功");
        }
        UiDriverSupport.pass("错误口令没有写入密钥链");

        fetch(KEY_A, "错误后重试正确口令");
        expectPrompts(2, "错误后重试重新询问");
        if (!KeychainStorage.hasPassword(KEY_A)) {
            fail("正确口令后没有写入密钥链");
        }
        UiDriverSupport.pass("重试成功后正常写入密钥链");
        KeychainStorage.deletePassword(KEY_A);
        UiDriverSupport.pass("测试用密钥链条目已清理");

        System.out.println("PASSWORD_CACHE_WRONG_PASSWORD_PASS");
        System.exit(0);
    }

    /** 平衡模式 5 分钟：到点后必须重新询问。 */
    private static void balancedTtl() throws Exception {
        setLevel(SettingsStorage.SECURITY_LEVEL_BALANCED);
        SettingsStorage.setPasswordCacheTimeout(SettingsStorage.TIMEOUT_5_MIN);
        createKey(KEY_A);
        startPump();

        long startedAt = System.currentTimeMillis();
        fetch(KEY_A, "平衡模式第一次");
        expectPrompts(1, "平衡模式第一次操作");
        fetch(KEY_A, "平衡模式第二次");
        expectPrompts(1, "平衡模式 5 分钟内复用");

        long remaining = SettingsStorage.TIMEOUT_5_MIN * 60_000L
                - (System.currentTimeMillis() - startedAt) + 5_000L;
        UiDriverSupport.pass("等待 " + (remaining / 1000L) + " 秒让 5 分钟缓存过期");
        Thread.sleep(Math.max(remaining, 0L));

        fetch(KEY_A, "平衡模式超时后");
        expectPrompts(2, "超过 5 分钟必须重新询问");

        System.out.println("PASSWORD_CACHE_BALANCED_TTL_PASS");
        System.exit(0);
    }

    // ------------------------------------------------------------------
    // 公共辅助
    // ------------------------------------------------------------------

    private static void setLevel(String level) {
        SettingsStorage.setSecurityLevel(level);
        String actual = SettingsStorage.getSecurityLevel();
        if (!level.equals(actual)) {
            fail("安全等级没设为 " + level + "，实际 " + actual + "（DPAPI 是否可用？）");
        }
        UiDriverSupport.pass("安全等级：" + actual
                + (SettingsStorage.SECURITY_LEVEL_BALANCED.equals(actual)
                        ? "，缓存超时 " + SettingsStorage.getPasswordCacheTimeout() + " 分钟" : ""));
    }

    private static void createKey(String name) throws Exception {
        UiDriverSupport.presetKeyPair(name, PASSWORD, KeyPairManager.generateKeyPair(4096));
        UiDriverSupport.pass("已预置密钥对 " + name);
    }

    private static void startApp() throws Exception {
        AppMain.main(new String[0]);
        frame = UiDriverSupport.waitForFrame();
        if (frame == null) {
            fail("MainFrame 未在超时内显示");
        }
        UiDriverSupport.pass("MainFrame 已显示");
    }

    private static void startPump() {
        pump = new DialogPump();
        pump.start();
    }

    private static PrivateKey fetch(String keyName, String what) throws Exception {
        PrivateKey key = PrivateKeyAccessService.getPrivateKey(keyName);
        Thread.sleep(150L);
        if (key == null) {
            fail(what + "：没有拿到私钥");
        }
        UiDriverSupport.pass(what + "：拿到私钥");
        return key;
    }

    private static void expectPrompts(int expected, String what) {
        int actual = pump.prompts();
        if (actual != expected) {
            fail(what + "：密码框应为 " + expected + " 次，实际 " + actual + " 次");
        }
        UiDriverSupport.pass(what + "：累计密码框 " + actual + " 次");
    }

    private static void fail(String message) {
        System.out.println("FAIL " + message);
        System.exit(2);
    }

    /** 真实密码框/提示框泵：记录次数、按队列回答、自动点“确定”。 */
    private static final class DialogPump implements Runnable {

        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final AtomicInteger promptCount = new AtomicInteger();
        private final AtomicInteger messageCount = new AtomicInteger();
        private final List<String> answers = Collections.synchronizedList(new ArrayList<String>());
        private final List<String> messages = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public void run() {
            Set<Window> handled = Collections.newSetFromMap(new IdentityHashMap<Window, Boolean>());
            while (!finished.get()) {
                for (Window window : Window.getWindows()) {
                    if (!(window instanceof JDialog) || !window.isShowing()) {
                        continue;
                    }
                    final JDialog dialog = (JDialog) window;
                    if (handled.contains(dialog)) {
                        continue;
                    }
                    final JPasswordField passwordField = UiDriverSupport.find(dialog, JPasswordField.class);
                    if (passwordField != null) {
                        handled.add(dialog);
                        String value;
                        synchronized (answers) {
                            value = answers.isEmpty() ? PASSWORD : answers.remove(0);
                        }
                        final String typed = value;
                        promptCount.incrementAndGet();
                        SwingUtilities.invokeLater(() -> {
                            passwordField.setText(typed);
                            JButton ok = button(dialog);
                            if (ok != null) {
                                ok.doClick();
                            }
                        });
                        continue;
                    }
                    JButton ok = button(dialog);
                    if (ok != null) {
                        handled.add(dialog);
                        messageCount.incrementAndGet();
                        messages.add(text(dialog));
                        SwingUtilities.invokeLater(ok::doClick);
                    }
                }
                try {
                    Thread.sleep(80L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private Thread thread;

        void start() {
            thread = new Thread(this, "password-dialog-pump");
            thread.setDaemon(true);
            thread.start();
        }

        void answer(String value) {
            answers.add(value);
        }

        int prompts() {
            return promptCount.get();
        }

        int messages() {
            return messageCount.get();
        }

        String lastMessage() {
            synchronized (messages) {
                return messages.isEmpty() ? "" : messages.get(messages.size() - 1);
            }
        }

        private static JButton button(JDialog dialog) {
            JButton ok = UiDriverSupport.findButton(dialog, "确定");
            if (ok == null) {
                ok = UiDriverSupport.findButton(dialog, "OK");
            }
            return ok;
        }

        private static String text(Component root) {
            StringBuilder builder = new StringBuilder();
            collectText(root, builder);
            return builder.toString();
        }

        private static void collectText(Component root, StringBuilder builder) {
            if (root instanceof JLabel) {
                String value = ((JLabel) root).getText();
                if (value != null && !value.trim().isEmpty()) {
                    builder.append(value).append(' ');
                }
            }
            if (root instanceof Container) {
                for (Component child : ((Container) root).getComponents()) {
                    collectText(child, builder);
                }
            }
        }
    }
}
