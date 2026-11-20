import app.AppMain;
import app.MainFrame;
import core.storage.StorageManager;
import core.util.LogManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import settings.manager.SettingsManager;
import settings.storage.SettingsStorage;

/**
 * 真实 MainFrame 的壳层自测（DR-3 3.5 之后）：标签页集合、默认页、逐页切换不抛异常。
 *
 * <p>用法：{@code ShellSmokeDriver <home>}。会真实打开窗口几秒，退出码 0 表示通过。</p>
 */
public final class ShellSmokeDriver {

    private static final List<String> EXPECTED_TABS = Arrays.asList(
            "密钥对管理", "通讯录", "安全通信", "历史记录", "使用说明", "设置", "隐写工具");

    private static volatile Throwable edtFailure;

    private ShellSmokeDriver() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("usage: ShellSmokeDriver <home>");
        }
        File home = new File(args[0]).getAbsoluteFile();
        Files.createDirectories(home.toPath());
        System.setProperty("app.home", home.getAbsolutePath());
        System.setProperty("user.dir", home.getAbsolutePath());

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> edtFailure = error);
        LogManager.init();
        SettingsManager.init();
        SettingsStorage.setTestMode(true);

        AppMain.main(new String[0]);
        MainFrame frame = waitForFrame();
        if (frame == null) {
            fail("MainFrame 未在超时内显示");
        }

        JTabbedPane tabs = find(frame.getContentPane(), JTabbedPane.class);
        if (tabs == null) {
            fail("未找到标签栏");
        }
        final JTabbedPane tabbedPane = tabs;
        List<String> actual = new ArrayList<String>();
        SwingUtilities.invokeAndWait(() -> {
            for (int index = 0; index < tabbedPane.getTabCount(); index++) {
                actual.add(tabbedPane.getTitleAt(index));
            }
        });

        if (actual.contains("分卷合并")) {
            fail("“分卷合并”标签仍在：" + actual);
        }
        if (!EXPECTED_TABS.equals(actual)) {
            fail("标签集合不符，期望 " + EXPECTED_TABS + "，实际 " + actual);
        }
        pass("标签页集合正确，没有“分卷合并”");

        final int[] selected = new int[1];
        SwingUtilities.invokeAndWait(() -> selected[0] = tabbedPane.getSelectedIndex());
        if (selected[0] != 2) {
            fail("默认页应为“安全通信”（索引 2），实际 " + selected[0]);
        }
        pass("默认停在“安全通信”");

        for (int index = 0; index < EXPECTED_TABS.size(); index++) {
            final int target = index;
            SwingUtilities.invokeAndWait(() -> tabbedPane.setSelectedIndex(target));
            Thread.sleep(150L);
            if (edtFailure != null) {
                fail("切到“" + EXPECTED_TABS.get(index) + "”时 EDT 抛异常: " + edtFailure);
            }
        }
        pass("逐页切换无异常");

        SwingUtilities.invokeAndWait(frame::dispose);
        pass("窗口已关闭");
        System.out.println("SHELL_SMOKE_PASS");
        System.exit(0);
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

    private static <T extends Component> T find(Component root, Class<T> type) {
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

    private static void pass(String message) {
        System.out.println("PASS " + message);
    }

    private static void fail(String message) {
        System.out.println("FAIL " + message);
        System.exit(2);
    }
}
