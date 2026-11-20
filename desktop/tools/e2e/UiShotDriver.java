import app.AppMain;
import app.MainFrame;
import crypto.config.CryptoConfig;
import crypto.key.KeyPairManager;
import crypto.key.KeyUtils;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import keymanagement.model.KeyPairInfo;
import keymanagement.storage.KeyPairStorage;

/**
 * 官网截图驱动：启动真实 MainFrame（真实启动路径：LAF、品牌色、字体、数据目录），
 * 在隔离的临时 home 里造演示密钥与联系人，再把指定标签页离屏渲染成 PNG。
 *
 * <p>参数：{@code <home> <out> [dark|light] [tabs]}，例如
 * {@code UiShotDriver /tmp/home /tmp/out light 0,1,2,6}。
 * 只用临时数据，不读真实用户目录；由 {@code shoot-site-shots.sh} 调用。</p>
 *
 * <p>标签页编号：0 密钥对管理、1 通讯录、2 安全通信、3 历史记录、4 使用说明、5 设置、6 隐写工具。</p>
 */
public final class UiShotDriver {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static void run(String[] args) throws Exception {
        startWatchdog(240);
        String home = args[0];
        String out = args[1];
        String theme = args.length > 2 && !args[2].isEmpty() ? args[2] : "dark";
        // 输出倍率：2 表示按 2x 渲染（矢量文字放大后再缩显，高分屏才不糊）
        int scale = args.length > 4 && !args[4].isEmpty() ? Integer.parseInt(args[4].trim()) : 1;
        Files.createDirectories(Paths.get(home, "data"));
        Files.write(Paths.get(home, "data", "settings.properties"),
                ("theme=" + theme + "\n").getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(Paths.get(out));
        System.setProperty("user.dir", home);

        AppMain.main(new String[0]);
        Thread.sleep(4500);
        closeStrayDialogs();
        seedDemoData();
        Thread.sleep(500);

        // 关掉启动窗口，重建一个以加载刚写入的密钥与联系人
        for (Window window : Window.getWindows()) {
            if (window instanceof Frame) {
                Frame frame = (Frame) window;
                onEdt(frame::dispose);
            }
        }
        Thread.sleep(600);

        MainFrame[] holder = new MainFrame[1];
        onEdt(() -> {
            holder[0] = new MainFrame();
            holder[0].showFrame();
        });
        // 建窗口是异步的，等它真的显示出来再继续；不硬等 EDT 以免被模态框卡死
        Frame frame = null;
        for (int attempt = 0; attempt < 80 && frame == null; attempt++) {
            Thread.sleep(150);
            for (Window window : Window.getWindows()) {
                if (window instanceof MainFrame && window.isShowing()) {
                    frame = (Frame) window;
                    break;
                }
            }
        }
        if (frame == null) {
            System.err.println("NO_FRAME");
            System.exit(4);
        }
        Thread.sleep(1200);
        final Frame shotFrame = frame;
        onEdt(() -> {
            shotFrame.setLocation(20, 20);
            shotFrame.setSize(1200, 840);
            shotFrame.validate();
        });

        JTabbedPane tabs = find(frame, JTabbedPane.class);
        int count = tabs == null ? 1 : tabs.getTabCount();
        Set<Integer> wanted = new HashSet<>();
        if (args.length > 3 && !args[3].isEmpty()) {
            for (String part : args[3].split(",")) {
                wanted.add(Integer.parseInt(part.trim()));
            }
        }

        for (int i = 0; i < count; i++) {
            if (!wanted.isEmpty() && !wanted.contains(i)) {
                continue;
            }
            final int index = i;
            onEdt(() -> {
                if (tabs != null) {
                    tabs.setSelectedIndex(index);
                }
            });
            Thread.sleep(1000);
            if (i == 2) {
                fillSampleText(tabs == null ? frame : (Container) tabs.getComponentAt(2));
                Thread.sleep(500);
            }
            onEdt(frame::repaint);
            Thread.sleep(400);
            capture(frame, new File(out, String.format("tab%d.png", i)), scale);
            System.out.println("saved tab" + i + ".png");
        }
        System.out.println("UI_SHOTS_DONE " + theme);
    }

    /** 卡住时硬退出，避免脚本永久挂起。 */
    private static void startWatchdog(int seconds) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException interrupted) {
                return;
            }
            System.err.println("UI_SHOTS_TIMEOUT after " + seconds + "s");
            Runtime.getRuntime().halt(3);
        }, "ui-shot-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** 不阻塞主线程地丢到 EDT 执行；模态框占着 EDT 时也不会把驱动锁死。 */
    private static void onEdt(Runnable task) {
        SwingUtilities.invokeLater(task);
    }

    /** 关掉启动阶段可能弹出的模态对话框（如数据目录回退提示），否则会卡住后续切页。 */
    private static void closeStrayDialogs() {
        for (Window window : Window.getWindows()) {
            if (window instanceof java.awt.Dialog && window.isShowing()) {
                final Window dialog = window;
                onEdt(dialog::dispose);
            }
        }
    }

    /** 造一把演示密钥与两个演示联系人，只写进临时 home。 */
    private static void seedDemoData() throws Exception {
        KeyPair pair = KeyPairManager.generateKeyPair(4096);
        String publicKey = KeyUtils.encodeBase64(pair.getPublic().getEncoded());
        char[] password = "DemoShot2026!".toCharArray();
        byte[] encryptedPrivate = KeyPairManager.encryptPrivateKey(pair.getPrivate().getEncoded(), password);
        byte[] salt = new byte[CryptoConfig.SALT_LENGTH];
        crypto.provider.SecurityProvider.STRONG_RANDOM.nextBytes(salt);
        byte[] encryptedSalt = KeyPairManager.encryptPrivateKey(salt, password);

        KeyPairInfo info = new KeyPairInfo("演示密钥", Instant.now(), "RSA", 4096, publicKey, "演示密钥.pem");
        info.setEncryptedSalt(encryptedSalt);
        KeyPairStorage.saveKeyPair("演示密钥", info, encryptedPrivate);

        for (String name : new String[] {"林野", "周岚"}) {
            KeyPair other = KeyPairManager.generateKeyPair(4096);
            String otherKey = KeyUtils.encodeBase64(other.getPublic().getEncoded());
            contacts.storage.ContactStorage.saveContact(
                    new contacts.model.Contact(name, otherKey, "RSA", fingerprint(otherKey)));
        }
        System.out.println("seeded demo key pair and contacts");
    }

    private static String fingerprint(String publicKey) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(KeyUtils.decodeBase64(publicKey));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 安全通信页的输入区填一段演示文本，避免截图里是整片空白。 */
    private static void fillSampleText(Container root) {
        List<JTextArea> areas = new ArrayList<>();
        collect(root, areas);
        JTextArea target = null;
        int best = -1;
        for (JTextArea area : areas) {
            Rectangle bounds = area.getBounds();
            if (area.isEditable() && bounds.width * bounds.height > best) {
                best = bounds.width * bounds.height;
                target = area;
            }
        }
        if (target != null) {
            final JTextArea area = target;
            SwingUtilities.invokeLater(() -> area.setText(
                    "这是一段演示文本：Aegix 先用接收方的公钥加密，再用自己的私钥签名。\n"
                            + "接收方用对应私钥解密，并用发送方公钥验签，全程不联网。"));
        }
    }

    private static void capture(Frame frame, File file, int scale) throws Exception {
        BufferedImage image = new BufferedImage(frame.getWidth() * scale, frame.getHeight() * scale,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(scale, scale);
        frame.printAll(g);
        g.dispose();
        ImageIO.write(image, "png", file);
    }

    private static <T> T find(Container container, Class<T> type) {
        for (Component component : container.getComponents()) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container) {
                T found = find((Container) component, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void collect(Container container, List<JTextArea> out) {
        for (Component component : container.getComponents()) {
            if (component instanceof JTextArea) {
                out.add((JTextArea) component);
            }
            if (component instanceof Container) {
                collect((Container) component, out);
            }
        }
    }
}
