package app.theme;

import app.theme.AppFonts;
import app.theme.UIConstants;
import core.util.LogManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.UIManager;
import javax.swing.border.Border;
import settings.manager.SettingsManager;

public class ThemeManager {
    
    // 主题常量定义
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String THEME_INTELLIJ = "intellij";
    public static final String THEME_DARCULA = "darcula";
    public static final String THEME_SYSTEM = "system";
    
    /**
     * 是否为深色主题
     * @return 是否为深色主题
     */
    public static boolean isDarkTheme() {
        String theme = settings.manager.SettingsManager.getProperty("theme", THEME_LIGHT);
        if (THEME_SYSTEM.equals(theme)) {
            return isSystemDarkTheme();
        }
        return THEME_DARK.equals(theme) || THEME_DARCULA.equals(theme);
    }
    
    /**
     * 检测当前系统是否使用深色主题
     * @return 是否为深色主题
     */
    public static boolean isSystemDarkTheme() {
        // 跨平台检测系统深色主题
        try {
            // Windows 检测
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                return isWindowsDarkTheme();
            }
            // macOS 检测
            else if (os.contains("mac")) {
                return isMacDarkTheme();
            }
            // Linux 检测
            else if (os.contains("linux")) {
                return isLinuxDarkTheme();
            }
        } catch (Exception e) {
            // 检测失败，默认返回 false（浅色主题）
            core.util.LogManager.warning("检测系统主题失败", e);
        }
        return false;
    }
    
    /**
     * 检测 Windows 是否使用深色主题
     */
    private static boolean isWindowsDarkTheme() {
        try {
            // 检查注册表项
            java.io.File registry = new java.io.File("C:\\Windows\\System32\\reg.exe");
            if (!registry.exists()) {
                return false;
            }
            
            // 执行注册表查询命令
            Process process = Runtime.getRuntime().exec("reg query \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\" /v AppsUseLightTheme");
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("AppsUseLightTheme")) {
                    // 0 = 深色主题, 1 = 浅色主题
                    return line.contains("0x0");
                }
            }
        } catch (Exception e) {
            // 检测失败，默认返回 false
        }
        return false;
    }
    
    /**
     * 检测 macOS 是否使用深色主题
     */
    private static boolean isMacDarkTheme() {
        try {
            // 使用 AppleScript 检测
            Process process = Runtime.getRuntime().exec("defaults read -g AppleInterfaceStyle 2>/dev/null");
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            
            String result = reader.readLine();
            return "Dark".equalsIgnoreCase(result);
        } catch (Exception e) {
            // 检测失败，默认返回 false
        }
        return false;
    }
    
    /**
     * 检测 Linux 是否使用深色主题
     */
    private static boolean isLinuxDarkTheme() {
        try {
            // 检查常见的 GTK/DE 配置
            // 这是一个简化的检测，实际可能需要更复杂的处理
            String[] checkCommands = {
                "gsettings get org.gnome.desktop.interface color-scheme",
                "gsettings get org.gnome.desktop.interface gtk-theme"
            };
            
            for (String cmd : checkCommands) {
                try {
                    Process process = Runtime.getRuntime().exec(cmd);
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()));
                    
                    String result = reader.readLine();
                    if (result != null && (result.toLowerCase().contains("dark") || result.toLowerCase().contains("prefer-dark"))) {
                        return true;
                    }
                } catch (Exception e) {
                    // 此命令失败，继续下一个
                }
            }
        } catch (Exception e) {
            // 检测失败，默认返回 false
        }
        return false;
    }
    
    /**
     * 获取当前主题名称
     * @return 当前主题名称
     */
    public static String getCurrentTheme() {
        return settings.manager.SettingsManager.getProperty("theme", THEME_LIGHT);
    }
    
    /**
     * 设置当前主题
     * @param theme 主题名称
     */
    public static void setCurrentTheme(String theme) {
        if (THEME_LIGHT.equals(theme) || THEME_DARK.equals(theme) || 
            THEME_INTELLIJ.equals(theme) || THEME_DARCULA.equals(theme)) {
            settings.manager.SettingsManager.setProperty("theme", theme);
        }
    }
    
    /**
     * 获取背景颜色
     * @return 背景颜色
     */
    public static Color getBackgroundColor() {
        return UIManager.getColor("Panel.background");
    }
    
    /**
     * 获取面板背景颜色
     * @return 面板背景颜色
     */
    public static Color getPanelBackgroundColor() {
        return UIManager.getColor("Panel.background");
    }
    
    /**
     * 获取主文本颜色
     * @return 主文本颜色
     */
    public static Color getTextColor() {
        return UIManager.getColor("Label.foreground");
    }
    
    /**
     * 获取次要文本颜色
     * @return 次要文本颜色
     */
    public static Color getSecondaryTextColor() {
        return UIManager.getColor("Label.disabledForeground");
    }
    
    /**
     * 获取边框颜色
     * @return 边框颜色
     */
    public static Color getBorderColor() {
        return UIManager.getColor("Component.borderColor");
    }
    
    /**
     * 获取按钮背景颜色
     * @return 按钮背景颜色
     */
    public static Color getButtonBackgroundColor() {
        return UIManager.getColor("Button.background");
    }
    
    /**
     * 获取按钮悬停颜色
     * @return 按钮悬停颜色
     */
    public static Color getButtonHoverColor() {
        return UIManager.getColor("Button.hoverBackground");
    }
    
    /**
     * 获取进度条颜色
     * @return 进度条颜色
     */
    public static Color getProgressColor() {
        return UIManager.getColor("ProgressBar.foreground");
    }
    
    /**
     * 获取标题栏颜色
     * @return 标题栏颜色
     */
    public static Color getTitleBarColor() {
        return UIManager.getColor("TitlePane.background");
    }
    
    /**
     * 获取选中项背景颜色
     * @return 选中项背景颜色
     */
    public static Color getSelectionBackgroundColor() {
        return UIManager.getColor("List.selectionBackground");
    }
    
    /**
     * 获取选中项文本颜色
     * @return 选中项文本颜色
     */
    public static Color getSelectionForegroundColor() {
        return UIManager.getColor("List.selectionForeground");
    }
    
    /**
     * 创建圆角边框
     * @param thickness 边框厚度
     * @return 圆角边框
     */
    public static Border createRoundedBorder(int thickness) {
        return BorderFactory.createLineBorder(getBorderColor(), thickness, true);
    }
    
    /**
     * 创建圆角面板边框
     * @return 圆角面板边框
     */
    public static Border createPanelBorder() {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(getBorderColor(), 1, true),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        );
    }
    
    /**
     * 创建文本区域边框
     * @return 文本区域边框
     */
    public static Border createTextAreaBorder() {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(getBorderColor(), 1, true),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)
        );
    }
    
    /**
     * 获取阴影效果颜色
     * @return 阴影效果颜色
     */
    public static Color getShadowColor() {
        return isDarkTheme() ? new Color(0, 0, 0, 40) : new Color(0, 0, 0, 20);
    }
    
    /**
     * 设置全局UI组件的主题优化
     * 添加一些FlatLaf支持的现代化UI属性
     */
    public static void setGlobalTheme() {
        // 设置TabbedPane样式
        UIManager.put("TabbedPane.tabInsets", new java.awt.Insets(8, 15, 8, 15));
        UIManager.put("TabbedPane.selectedTabPadInsets", new java.awt.Insets(8, 15, 8, 15));
        
        // 设置按钮样式 - 确保圆角生效
        UIManager.put("Button.arc", UIConstants.CORNER_RADIUS);
        UIManager.put("Button.borderWidth", 0);
        UIManager.put("Button.focusWidth", 0);
        UIManager.put("Button.innerFocusWidth", 0);
        UIManager.put("Button.default.arc", UIConstants.CORNER_RADIUS);
        
        // 设置文本框和密码框样式
        UIManager.put("TextField.arc", UIConstants.CORNER_RADIUS);
        UIManager.put("PasswordField.arc", UIConstants.CORNER_RADIUS);
        
        // 设置组合框样式
        UIManager.put("ComboBox.arc", UIConstants.CORNER_RADIUS);
        
        // 设置滚动条样式
        UIManager.put("ScrollBar.thumbArc", UIConstants.CORNER_RADIUS);
        
        // 设置进度条样式
        UIManager.put("ProgressBar.arc", UIConstants.CORNER_RADIUS);
        
        // 设置列表样式
        UIManager.put("List.selectionArc", UIConstants.CORNER_RADIUS);
        
        // 设置表格样式
        UIManager.put("Table.selectionArc", UIConstants.CORNER_RADIUS);
        
        // 设置JOptionPane相关组件的字体
        try {
            // 获取应用程序的普通字体
            java.awt.Font normalFont = AppFonts.getNormalFont();
            
            // 设置各种对话框组件的字体
            setComponentFonts("OptionPane", normalFont);
            setComponentFonts("Dialog", normalFont);
            setComponentFonts("MessagePane", normalFont);
            setComponentFonts("ConfirmDialog", normalFont);
            setComponentFonts("InputDialog", normalFont);
            
            // InputDialog 额外设置 fieldFont
            UIManager.put("InputDialog.fieldFont", normalFont);
        } catch (Exception e) {
            // 如果设置字体失败，不影响应用程序运行
            core.util.LogManager.error("设置JOptionPane字体失败", e);
        }
    }
    
    /**
     * 为指定组件设置统一的字体
     * @param prefix 组件前缀（如 "OptionPane"）
     * @param font 要设置的字体
     */
    private static void setComponentFonts(String prefix, java.awt.Font font) {
        UIManager.put(prefix + ".font", font);
        UIManager.put(prefix + ".messageFont", font);
        UIManager.put(prefix + ".buttonFont", font);
        UIManager.put(prefix + ".titleFont", font.deriveFont(java.awt.Font.BOLD));
    }
    
    /**
     * 创建一个带有圆角的标准按钮
     * @param text 按钮文本
     * @param backgroundColor 背景颜色
     * @param foregroundColor 前景颜色
     * @return 配置好的按钮
     */
    public static javax.swing.JButton createRoundedButton(String text, java.awt.Color backgroundColor, java.awt.Color foregroundColor) {
        // 使用自定义按钮，确保圆角生效
        javax.swing.JButton button = new javax.swing.JButton(text) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                
                // 绘制圆角背景
                boolean enabled = isEnabled();
                if (!enabled) {
                    // 禁用态必须明显区别于可点击态，否则“缺卷禁止合并”看起来仍可点。
                    Color base = backgroundColor != null ? backgroundColor : getBackground();
                    g2.setColor(mix(base, ThemeManager.getBackgroundColor(), 0.72f));
                } else if (getModel().isPressed()) {
                    // 按下时稍微暗一点
                    g2.setColor(backgroundColor != null ? backgroundColor.darker() : getBackground().darker());
                } else if (getModel().isRollover()) {
                    // 悬停时稍微亮一点
                    g2.setColor(backgroundColor != null ? backgroundColor.brighter() : getBackground().brighter());
                } else {
                    g2.setColor(backgroundColor != null ? backgroundColor : getBackground());
                }
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                
                // 绘制文字
                if (!enabled) {
                    Color disabledText = javax.swing.UIManager.getColor("Label.disabledForeground");
                    g2.setColor(disabledText != null ? disabledText : new Color(140, 140, 140));
                } else {
                    g2.setColor(foregroundColor != null ? foregroundColor : getForeground());
                }
                g2.setFont(getFont());
                java.awt.FontMetrics fm = g2.getFontMetrics();
                String buttonText = getText();
                int textWidth = fm.stringWidth(buttonText);
                int textHeight = fm.getAscent();
                g2.drawString(buttonText, (getWidth() - textWidth) / 2, (getHeight() + textHeight) / 2 - 2);
                
                g2.dispose();
            }
            
            @Override
            public boolean isOpaque() {
                return false;
            }

            @Override
            public void setEnabled(boolean enabled) {
                super.setEnabled(enabled);
                setCursor(enabled
                        ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                        : java.awt.Cursor.getDefaultCursor());
            }
        };
        
        // 设置字体
        button.setFont(AppFonts.getBoldFont());
        
        // 设置颜色
        if (backgroundColor != null) {
            button.setBackground(backgroundColor);
        }
        if (foregroundColor != null) {
            button.setForeground(foregroundColor);
        }
        
        // 移除默认边框
        button.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 20, 10, 20));
        
        // 移除焦点绘制
        button.setFocusPainted(false);
        
        // 设置手型光标
        button.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        
        // 设置内容区域不填充
        button.setContentAreaFilled(false);
        
        return button;
    }
    
    /** 按比例混合两种颜色，用于禁用态背景。 */
    private static Color mix(Color first, Color second, float secondRatio) {
        float ratio = Math.max(0f, Math.min(1f, secondRatio));
        float inverse = 1f - ratio;
        return new Color(
                Math.round(first.getRed() * inverse + second.getRed() * ratio),
                Math.round(first.getGreen() * inverse + second.getGreen() * ratio),
                Math.round(first.getBlue() * inverse + second.getBlue() * ratio));
    }

    /**
     * 创建一个带有圆角的蓝色按钮（用于主要操作）
     * @param text 按钮文本
     * @return 配置好的按钮
     */
    public static javax.swing.JButton createPrimaryButton(String text) {
        return createRoundedButton(text, UIConstants.PRIMARY_BUTTON_COLOR, java.awt.Color.WHITE);
    }
    
    /**
     * 创建一个带有圆角的红色按钮（用于危险操作）
     * @param text 按钮文本
     * @return 配置好的按钮
     */
    public static javax.swing.JButton createDangerButton(String text) {
        return createRoundedButton(text, UIConstants.DANGER_BUTTON_COLOR, java.awt.Color.WHITE);
    }
    
    /**
     * 创建一个带有圆角的中性按钮（用于取消、关闭等操作）
     * @param text 按钮文本
     * @return 配置好的按钮
     */
    public static javax.swing.JButton createNeutralButton(String text) {
        return createRoundedButton(text, UIConstants.NEUTRAL_BUTTON_COLOR, java.awt.Color.BLACK);
    }
}
