package core.util;

import core.storage.AppPaths;
import core.util.LogManager;
import java.awt.Component;
import java.awt.Window;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JOptionPane;

public class RestartHelper {
    
    /**
     * 显示重启确认对话框
     * @param parentComponent 父组件，用于定位对话框
     * @param message 提示消息
     */
    public static void showRestartConfirmDialog(Component parentComponent, String message) {
        int result = JOptionPane.showConfirmDialog(
            parentComponent,
            message + "\n\n是否立即重启应用程序以使设置生效？",
            "设置已更新",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        );
        
        if (result == JOptionPane.YES_OPTION) {
            restartApplication(parentComponent);
        }
    }
    
    /**
     * 重启应用程序（纯 Java 方式，不依赖任何外部文件）
     * @param parentComponent 父组件，用于显示错误提示
     */
    public static void restartApplication(Component parentComponent) {
        try {
            // 先关闭所有窗口
            closeAllWindows();
            
            // jpackage 便携包优先重新拉起 launcher；开发环境再用 java 命令
            List<String> command = buildRestartCommand();
            
            // 启动新进程
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.start();
            
            LogManager.info("应用程序正在重启");
            
            // 退出当前进程
            System.exit(0);
            
        } catch (Exception e) {
            LogManager.error("重启应用程序失败", e);
            JOptionPane.showMessageDialog(
                parentComponent,
                "自动重启失败，请手动重启应用程序。\n错误信息：" + e.getMessage(),
                "重启失败",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }
    
    /**
     * 构建重启命令。包私有便于测试，不真正启动进程。
     */
    static List<String> buildRestartCommand() throws Exception {
        Path launcher = AppPaths.launcherPath();
        if (launcher != null) {
            return Collections.singletonList(launcher.toString());
        }

        String javaBin = getJavaBinaryPath();
        String appPath = getApplicationPath();
        List<String> command = new ArrayList<>();
        command.add(javaBin);

        if (appPath.endsWith(".jar")) {
            command.add("-jar");
            command.add(appPath);
        } else {
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add("app.AppMain");
        }
        return command;
    }

    /**
     * 获取 Java 可执行文件路径
     */
    private static String getJavaBinaryPath() {
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        // Windows 下需要 .exe 后缀
        if (System.getProperty("os.name").toLowerCase().contains("win") && !javaBin.endsWith(".exe")) {
            javaBin += ".exe";
        }
        return javaBin;
    }
    
    /**
     * 获取应用程序路径（jar 文件路径或开发环境的类路径根目录）
     */
    private static String getApplicationPath() throws URISyntaxException {
        // 尝试从类的位置获取 jar 文件路径
        CodeSource codeSource = RestartHelper.class.getProtectionDomain().getCodeSource();
        if (codeSource != null) {
            URI uri = codeSource.getLocation().toURI();
            String path = uri.getPath();
            
            // 如果是 jar 文件，直接返回
            if (path.endsWith(".jar")) {
                return new File(path).getAbsolutePath();
            }
        }
        
        // 如果不是 jar 文件（开发环境），返回项目根目录
        return System.getProperty("user.dir");
    }
    
    /**
     * 关闭所有窗口
     */
    private static void closeAllWindows() {
        Window[] windows = Window.getWindows();
        for (Window window : windows) {
            if (window.isDisplayable()) {
                window.dispose();
            }
        }
    }
}
