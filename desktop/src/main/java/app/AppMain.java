package app;

import app.MainFrame;
import app.theme.AppFonts;
import app.theme.BrandPalette;
import app.theme.ThemeManager;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import core.storage.AppPaths;
import core.storage.StorageManager;
import core.util.LogManager;
import java.awt.Component;
import java.awt.Font;
import java.util.List;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.TitledBorder;
import settings.manager.SettingsManager;

public class AppMain {
    
    public static void main(String[] args) {
        // 必须在加载 JNA/FlatLaf 原生库之前：原生库默认解压到 java.io.tmpdir，
        // 该目录不可写时会让 JNA 抛 UnsatisfiedLinkError、DPAPI 不可用。
        String requestedTempDir = System.getProperty("java.io.tmpdir");
        AppPaths.ensureWritableTempDir();

        optimizeDpiDisplay();
        
        LogManager.init();
        LogManager.info("应用程序启动，初始化日志系统");
        if (requestedTempDir != null && !requestedTempDir.equals(System.getProperty("java.io.tmpdir"))) {
            LogManager.info("系统临时目录不可写，已改用应用临时目录");
        }
        
        SettingsManager.init();
        
        try {
            String theme = SettingsManager.getProperty("theme", "light");
            
            if (ThemeManager.THEME_SYSTEM.equals(theme)) {
                if (ThemeManager.isSystemDarkTheme()) {
                    UIManager.setLookAndFeel(new FlatDarkLaf());
                } else {
                    UIManager.setLookAndFeel(new FlatLightLaf());
                }
            } else if ("dark".equals(theme)) {
                UIManager.setLookAndFeel(new FlatDarkLaf());
            } else {
                UIManager.setLookAndFeel(new FlatLightLaf());
            }
            
            configureFlatLafTheme();

            // 品牌色必须在设置 LAF 之后、创建窗口之前写入，否则已创建的组件会保留 FlatLaf 默认色
            BrandPalette.apply(ThemeManager.isDarkTheme());

            Font normalFont = AppFonts.getNormalFont();
            Font boldFont = AppFonts.getBoldFont();
            setGlobalFonts(normalFont, boldFont);
            
            ThemeManager.setGlobalTheme();
            
        } catch (Exception e) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                
                Font normalFont = AppFonts.getNormalFont();
                Font boldFont = AppFonts.getBoldFont();
                setGlobalFonts(normalFont, boldFont);
                
                ThemeManager.setGlobalTheme();
            } catch (Exception ex) {
                LogManager.error("应用启动失败", ex);
            }
        }
        
        java.util.List<java.io.File> openFiles = parseOpenFiles(args);

        SwingUtilities.invokeLater(() -> {
            MainFrame mainFrame = new MainFrame();
            mainFrame.showFrame();
            if (!openFiles.isEmpty()) {
                mainFrame.openExternalFiles(openFiles);
            }
            showDataDirectoryFallbackWarning(mainFrame);
        });
    }

    /** 命令行/文件关联传入的文件；忽略以「-」开头的选项与不存在的路径。 */
    static java.util.List<java.io.File> parseOpenFiles(String[] args) {
        java.util.List<java.io.File> files = new java.util.ArrayList<>();
        if (args == null) {
            return files;
        }
        for (String arg : args) {
            if (arg == null || arg.isEmpty() || arg.startsWith("-")) {
                continue;
            }
            java.io.File file = new java.io.File(arg);
            if (file.isFile()) {
                files.add(file);
            }
        }
        return files;
    }

    private static void showDataDirectoryFallbackWarning(Component parent) {
        if (!StorageManager.isUsingFallbackDataDir()) {
            return;
        }

        String unavailable = StorageManager.getUnavailableDataDir();
        String message = "原数据目录无法写入：\n" +
                (unavailable == null ? "（未记录）" : unavailable) +
                "\n\n程序已临时改用：\n" + StorageManager.getDataDir() +
                "\n\n请检查磁盘或权限，并在“设置 → 存储设置”中重新选择数据目录。";
        JOptionPane.showMessageDialog(parent, message, "数据目录已回退", JOptionPane.WARNING_MESSAGE);
    }
    
    private static void optimizeDpiDisplay() {
        System.setProperty("sun.java2d.uiScale", "auto");
        System.setProperty("sun.java2d.dpiaware", "true");
        
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        System.setProperty("swing.useSystemFontSettings", "true");
        
        System.setProperty("sun.java2d.renderer", "sun.java2d.marlin.MarlinRenderingEngine");
        
        // 不要覆盖 JDK 的字体管理器：sun.font.SunFontManager 是抽象类，
        // 在 Windows 上设置它会让 FontManagerFactory 抛 InstantiationException
        // （应用在 FlatLaf 初始化阶段直接退出）。让 JDK 选择平台默认实现。
        System.setProperty("sun.java2d.pmoffscreen", "true");
        System.setProperty("sun.java2d.ddoffscreen", "true");
        System.setProperty("sun.java2d.ddscale", "true");
        
        System.setProperty("sun.java2d.xrender", "true");
        System.setProperty("sun.java2d.metal", "true");
        
        System.setProperty("sun.java2d.antialiasing", "true");
        System.setProperty("sun.java2d.text.antialiasing", "true");
        System.setProperty("sun.java2d.font.disableAlgorithmicStyles", "true");
        
        System.setProperty("sun.java2d.translaccel", "false");
    }
    
    private static void setGlobalFonts(Font normalFont, Font boldFont) {
        UIManager.put("TableHeader.font", boldFont);
        UIManager.put("TitledBorder.font", boldFont);
    }
    
    private static void configureFlatLafTheme() {
        System.setProperty("flatlaf.uiScale", "auto");
        System.setProperty("flatlaf.useWindowDecorations", "true");
        
        UIManager.put("FlatLaf.menuBarEmbedded", true);
        UIManager.put("FlatLaf.useWindowDecorations", true);
        
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("ScrollBar.trackArc", 6);
        UIManager.put("ScrollBar.thumbArc", 6);
        
        UIManager.put("Component.borderWidth", 1);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.arc", 8);
        
        UIManager.put("Button.arc", 12);
        UIManager.put("Button.borderWidth", 0);
        
        UIManager.put("Table.rowHeight", 24);
        UIManager.put("Table.arc", 8);
        
        UIManager.put("Tree.rowHeight", 24);
        
        UIManager.put("List.rowHeight", 24);
        
        UIManager.put("ProgressBar.arc", 12);
    }
}
