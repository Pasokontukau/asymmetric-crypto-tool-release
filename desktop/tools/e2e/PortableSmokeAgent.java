import app.MainFrame;
import core.storage.StorageManager;
import java.awt.Frame;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import platform.windows.WindowsDPAPI;
import settings.manager.SettingsManager;

/**
 * 便携包冒烟 agent：由 verify-portable-smoke.bat 通过 -javaagent 注入到
 * CryptoAssistant.exe 启动的 JVM 中。
 *
 * <p>等待真实 MainFrame 显示，然后通过 SettingsManager 保存一次字号，
 * 记录 app.home、数据目录、设置文件路径与保存结果，最后退出进程。</p>
 */
public final class PortableSmokeAgent {

    private PortableSmokeAgent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        final Path result = Paths.get(agentArgs);
        Thread thread = new Thread(() -> run(result), "portable-smoke");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run(Path result) {
        try {
            MainFrame frame = waitForMainFrame(30_000L);
            if (frame == null) {
                write(result, "ERROR=no-main-window\n");
                System.exit(2);
            }

            SettingsManager.setFontSize(23);
            String home = System.getProperty("app.home", "");
            String dataDir = StorageManager.getDataDir();
            Path settingsFile = Paths.get(dataDir, "settings.properties");
            String settings = Files.isRegularFile(settingsFile)
                    ? Files.readString(settingsFile, StandardCharsets.ISO_8859_1)
                    : "";

            String report = "WINDOW=true\n"
                    + "TITLE=" + frame.getTitle() + "\n"
                    + "HOME=" + home + "\n"
                    + "DATA=" + dataDir + "\n"
                    + "SETTINGS=" + settingsFile.toAbsolutePath() + "\n"
                    + "FONT_SAVED=" + settings.contains("font_size=23") + "\n"
                    + "DPAPI=" + WindowsDPAPI.isAvailable() + "\n";
            write(result, report);
            // 留出时间让外部脚本确认窗口标题，随后主动退出。
            Thread.sleep(2_000L);
            System.exit(0);
        } catch (Throwable error) {
            try {
                write(result, "ERROR=" + error.getClass().getName()
                        + ": " + error.getMessage() + "\n");
            } catch (IOException ignored) {
                // 结果文件写不出来时只能靠脚本超时兜底。
            }
            System.exit(3);
        }
    }

    private static MainFrame waitForMainFrame(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (Frame frame : Frame.getFrames()) {
                if (frame instanceof MainFrame && frame.isShowing()) {
                    return (MainFrame) frame;
                }
            }
            Thread.sleep(100L);
        }
        return null;
    }

    private static void write(Path result, String text) throws IOException {
        Path parent = result.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(result, text, StandardCharsets.UTF_8);
    }
}
