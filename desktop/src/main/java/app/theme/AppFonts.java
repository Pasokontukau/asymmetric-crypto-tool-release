package app.theme;

import core.util.LogManager;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.List;
import settings.manager.SettingsManager;

public class AppFonts {
    
    // 字体候选列表，按优先级排序
    private static final List<String> FONT_CANDIDATES = Arrays.asList(
        "DengXian",           // Windows: 等线体
        "Microsoft YaHei",    // Windows: 微软雅黑
        "PingFang SC",        // macOS: 苹方
        "Noto Sans SC",       // Linux/通用: 思源黑体
        "Source Han Sans SC", // 通用: 思源黑体
        "SansSerif"           // 兜底: 系统无衬线字体
    );
    
    // 等宽字体，用于代码和数据显示
    public static final String MONOSPACED_FONT = "Monospaced";
    
    // 缓存检测到的可用字体
    private static String cachedFont = null;
    
    /**
     * 获取适合当前系统的字体
     * @return 字体名称
     */
    private static String getCompatibleFont() {
        if (cachedFont != null) {
            return cachedFont;
        }
        
        // 先检查用户配置
        String userFont = settings.manager.SettingsManager.getFontFamily();
        if (userFont != null && !userFont.trim().isEmpty()) {
            // 验证用户配置的字体是否可用
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            String[] availableFonts = ge.getAvailableFontFamilyNames();
            for (String availableFont : availableFonts) {
                if (availableFont.equals(userFont)) {
                    cachedFont = userFont;
                    return userFont;
                }
            }
            // 用户配置的字体不可用，回退到自动检测
            core.util.LogManager.warning("配置的字体不可用：" + userFont + "，将使用自动检测字体");
        }
        
        // 自动检测字体
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        String[] availableFonts = ge.getAvailableFontFamilyNames();
        
        // 按优先级查找第一个可用字体
        for (String fontName : FONT_CANDIDATES) {
            for (String availableFont : availableFonts) {
                if (availableFont.equals(fontName)) {
                    cachedFont = fontName;
                    return fontName;
                }
            }
        }
        
        // 如果都没找到，使用兜底字体
        cachedFont = "SansSerif";
        return cachedFont;
    }
    
    /**
     * 清除字体缓存（用于切换字体后刷新）
     */
    public static void clearFontCache() {
        cachedFont = null;
    }
    
    /**
     * 获取系统所有可用字体族列表
     * @return 字体族名称数组
     */
    public static String[] getAvailableFontFamilies() {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        return ge.getAvailableFontFamilyNames();
    }
    
    /**
     * 获取基础字体大小
     * @return 基础字体大小（像素）
     */
    private static int getBaseFontSize() {
        return SettingsManager.getFontSize();
    }
    
    /**
     * 获取标题字体
     * @return 标题字体
     */
    public static Font getTitleFont() {
        return new Font(getCompatibleFont(), Font.BOLD, getBaseFontSize() + 12);
    }
    
    /**
     * 获取小标题字体
     * @return 小标题字体
     */
    public static Font getSubTitleFont() {
        return new Font(getCompatibleFont(), Font.BOLD, getBaseFontSize() + 6);
    }
    
    /**
     * 获取大号标题字体
     * @return 大号标题字体
     */
    public static Font getLargeTitleFont() {
        return new Font(getCompatibleFont(), Font.BOLD, getBaseFontSize() + 16);
    }
    
    /**
     * 获取普通文本字体
     * @return 普通文本字体
     */
    public static Font getNormalFont() {
        return new Font(getCompatibleFont(), Font.PLAIN, getBaseFontSize());
    }
    
    /**
     * 获取加粗文本字体
     * @return 加粗文本字体
     */
    public static Font getBoldFont() {
        return new Font(getCompatibleFont(), Font.BOLD, getBaseFontSize());
    }
    
    /**
     * 获取小号字体
     * @return 小字体
     */
    public static Font getSmallFont() {
        return new Font(getCompatibleFont(), Font.PLAIN, getBaseFontSize() - 2);
    }
    
    /**
     * 获取超小字体
     * @return 超小字体
     */
    public static Font getExtraSmallFont() {
        return new Font(getCompatibleFont(), Font.PLAIN, getBaseFontSize() - 4);
    }
    
    /**
     * 获取等宽字体
     * @return 等宽字体
     */
    public static Font getMonospacedFont() {
        return new Font(MONOSPACED_FONT, Font.PLAIN, getBaseFontSize());
    }
    
    /**
     * 获取加粗等宽字体
     * @return 加粗等宽字体
     */
    public static Font getBoldMonospacedFont() {
        return new Font(MONOSPACED_FONT, Font.BOLD, getBaseFontSize());
    }
    
    /**
     * 获取小号等宽字体
     * @return 小号等宽字体
     */
    public static Font getSmallMonospacedFont() {
        return new Font(MONOSPACED_FONT, Font.PLAIN, getBaseFontSize() - 2);
    }
    
    /**
     * 获取按钮文本字体
     * @return 按钮文本字体
     */
    public static Font getButtonFont() {
        return new Font(getCompatibleFont(), Font.BOLD, getBaseFontSize());
    }
    
    /**
     * 获取标签文本字体
     * @return 标签文本字体
     */
    public static Font getLabelFont() {
        return new Font(getCompatibleFont(), Font.PLAIN, getBaseFontSize());
    }
    
    /**
     * 获取表格文本字体
     * @return 表格文本字体
     */
    public static Font getTableFont() {
        return new Font(getCompatibleFont(), Font.PLAIN, getBaseFontSize());
    }
    
    /**
     * 获取表格标题字体
     * @return 表格标题字体
     */
    public static Font getTableHeaderFont() {
        return new Font(getCompatibleFont(), Font.BOLD, getBaseFontSize());
    }
    
    /**
     * 获取小面板标题字体（比普通加粗字体等比例缩小70%）
     * @return 小面板标题字体
     */
    public static Font getLittleTitleFont() {
        return new Font(getCompatibleFont(), Font.BOLD, (int) (getBaseFontSize() * 0.7f));
    }
}
