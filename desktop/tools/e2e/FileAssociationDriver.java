import core.storage.FileAssociation;
import java.awt.Frame;
import java.awt.Window;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import javax.swing.DefaultListModel;
import javax.swing.SwingUtilities;

/**
 * 双击 .aegix 的两半验证：
 * ① 关联写入 / 查询 / 删除的注册表往返（只动 HKCU，结束时一定清干净）；
 * ② MainFrame 收到外部文件后，安全通信页的文件列表里确实多了这一条。
 *
 * <p>用法：{@code FileAssociationDriver <home>}；需要桌面会话。</p>
 */
public final class FileAssociationDriver {

    public static void main(String[] args) {
        int failures = 0;
        String mode = args.length > 1 ? args[1] : "full";
        try {
            System.setProperty("user.dir", args[0]);
            if ("install".equals(mode)) {
                System.out.println("install=" + FileAssociation.install());
                System.exit(0);
            }
            if ("remove".equals(mode)) {
                System.out.println("uninstall=" + FileAssociation.uninstall()
                        + " after_remove=" + FileAssociation.isAssociated());
                System.exit(0);
            }
            Files.createDirectories(Path.of(args[0], "data"));

            if (!FileAssociation.isSupported()) {
                System.out.println("SKIP non-windows");
                System.exit(0);
            }

            System.out.println("before=" + FileAssociation.isAssociated());
            boolean installed = FileAssociation.install();
            System.out.println("install=" + installed);
            boolean afterInstall = FileAssociation.isAssociated();
            System.out.println("after_install=" + afterInstall);
            if (!installed || !afterInstall) {
                failures++;
            }

            System.out.println("command=" + FileAssociation.currentOpenCommand());

            boolean removed = FileAssociation.uninstall();
            boolean afterRemove = FileAssociation.isAssociated();
            System.out.println("uninstall=" + removed + " after_remove=" + afterRemove);
            if (!removed || afterRemove) {
                failures++;
            }

            failures += uiSmoke(args[0]);
        } catch (Throwable t) {
            t.printStackTrace();
            failures++;
        }
        System.out.println(failures == 0 ? "FILE_ASSOCIATION_ALL_PASS" : "FILE_ASSOCIATION_FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    /** 外部文件能否进到安全通信页的待处理列表（走真实入口 AppMain.main）。 */
    private static int uiSmoke(String home) throws Exception {
        Path file = Path.of(home, "demo.aegix");
        Files.writeString(file, "not-a-real-container");

        app.AppMain.main(new String[] {file.toAbsolutePath().toString()});

        app.MainFrame frame = null;
        for (int i = 0; i < 60 && frame == null; i++) {
            Thread.sleep(200);
            for (Window window : Window.getWindows()) {
                if (window instanceof app.MainFrame && window.isShowing()) {
                    frame = (app.MainFrame) window;
                    break;
                }
            }
        }
        if (frame == null) {
            System.out.println("UI: frame not created");
            return 1;
        }

        Thread.sleep(1500);
        Object panel = readField(frame, "secureTextPanel");
        Object model = panel == null ? null : readField(panel, "fileListModel");
        int size = model instanceof DefaultListModel ? ((DefaultListModel<?>) model).size() : -1;
        Object tabs = readField(frame, "tabbedPane");
        int selected = tabs instanceof javax.swing.JTabbedPane ? ((javax.swing.JTabbedPane) tabs).getSelectedIndex() : -1;
        System.out.println("UI: queued_files=" + size + " selected_tab=" + selected);

        final app.MainFrame target = frame;
        SwingUtilities.invokeLater(target::dispose);
        return size >= 1 && selected == 2 ? 0 : 1;
    }

    private static Object readField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
