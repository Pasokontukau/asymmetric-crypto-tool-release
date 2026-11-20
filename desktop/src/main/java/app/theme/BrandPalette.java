package app.theme;

import java.awt.Color;
import javax.swing.UIManager;

/**
 * 与 Android 端一致的品牌色板。
 *
 * <p>颜色取值与 {@code android/.../ui/theme/Color.kt} 的 ColorScheme 对齐：
 * 深色用 Navy950/900/800/700 + Indigo400 + Teal300，浅色用 Cloud100/200/300/500 + Indigo700 + Teal700。
 * 这里只覆盖 FlatLaf 的 UI 默认值，不改变任何业务组件的布局与行为。</p>
 */
public final class BrandPalette {

    private BrandPalette() {
    }

    // 深色（对齐 DarkColorScheme）
    private static final Color D_BACKGROUND = new Color(0x080C16);      // Navy950
    private static final Color D_SURFACE = new Color(0x0B1120);         // Navy900
    private static final Color D_SURFACE_CONTAINER = new Color(0x131C31); // Navy800
    private static final Color D_SURFACE_HIGH = new Color(0x18233A);    // surfaceContainerHigh
    private static final Color D_SURFACE_VARIANT = new Color(0x1B2742); // Navy700
    private static final Color D_OUTLINE = new Color(0x3A4867);
    private static final Color D_OUTLINE_VARIANT = new Color(0x273350);
    private static final Color D_TEXT = new Color(0xE8ECF7);
    private static final Color D_TEXT_MUTED = new Color(0xB4BFDA);
    private static final Color D_PRIMARY = new Color(0x8B9CFF);         // Indigo400
    private static final Color D_PRIMARY_HOVER = new Color(0x9AA8FF);   // Indigo300
    private static final Color D_ON_PRIMARY = new Color(0x0B1020);
    private static final Color D_TEAL = new Color(0x6FE3C4);            // Teal300

    // 浅色（对齐 LightColorScheme）
    private static final Color L_BACKGROUND = new Color(0xF6F7FC);      // Cloud100
    private static final Color L_SURFACE = new Color(0xFFFFFF);
    private static final Color L_SURFACE_CONTAINER = new Color(0xEEF1FA); // Cloud200
    private static final Color L_SURFACE_HIGH = new Color(0xE8ECF7);
    private static final Color L_SURFACE_VARIANT = new Color(0xE3E8F5); // Cloud300
    private static final Color L_OUTLINE = new Color(0xC3CADD);         // Cloud500
    private static final Color L_OUTLINE_VARIANT = new Color(0xDCE2EF);
    private static final Color L_TEXT = new Color(0x141B2B);
    private static final Color L_TEXT_MUTED = new Color(0x4A5470);
    private static final Color L_PRIMARY = new Color(0x4457C9);         // Indigo700
    private static final Color L_PRIMARY_HOVER = new Color(0x5B67E8);
    private static final Color L_ON_PRIMARY = Color.WHITE;
    private static final Color L_TEAL = new Color(0x12866C);            // Teal700

    /**
     * 把当前主题对应的品牌色写入 UIManager。
     *
     * <p>必须在 {@code UIManager.setLookAndFeel(...)} 之后、创建任何窗口之前调用，
     * 否则已创建的组件会保留旧颜色。</p>
     *
     * @param dark 是否深色主题
     */
    public static void apply(boolean dark) {
        Color background = dark ? D_BACKGROUND : L_BACKGROUND;
        Color surface = dark ? D_SURFACE : L_SURFACE;
        Color container = dark ? D_SURFACE_CONTAINER : L_SURFACE_CONTAINER;
        Color containerHigh = dark ? D_SURFACE_HIGH : L_SURFACE_HIGH;
        Color variant = dark ? D_SURFACE_VARIANT : L_SURFACE_VARIANT;
        Color outline = dark ? D_OUTLINE : L_OUTLINE;
        Color outlineVariant = dark ? D_OUTLINE_VARIANT : L_OUTLINE_VARIANT;
        Color text = dark ? D_TEXT : L_TEXT;
        Color muted = dark ? D_TEXT_MUTED : L_TEXT_MUTED;
        Color primary = dark ? D_PRIMARY : L_PRIMARY;
        Color primaryHover = dark ? D_PRIMARY_HOVER : L_PRIMARY_HOVER;
        Color onPrimary = dark ? D_ON_PRIMARY : L_ON_PRIMARY;
        Color teal = dark ? D_TEAL : L_TEAL;
        // 选中标签用 primaryContainer（与 Android 选中态一致），浅靛蓝底配深色字
        Color selectedTab = dark ? new Color(0x232F63) : new Color(0xDDE2FF);
        Color selectedTabText = dark ? new Color(0xDDE2FF) : new Color(0x0B1233);

        // 面板与容器
        put("Panel.background", surface);
        put("Viewport.background", surface);
        put("ScrollPane.background", surface);
        put("SplitPane.background", background);
        put("SplitPane.dividerColor", outlineVariant);
        put("OptionPane.background", surface);
        put("OptionPane.messageForeground", text);
        put("RootPane.background", background);

        // 文本
        put("Label.foreground", text);
        put("Label.disabledForeground", muted);
        put("TitledBorder.titleColor", text);
        put("TitledBorder.borderColor", outlineVariant);
        put("Separator.foreground", outlineVariant);
        put("Separator.background", surface);

        // 边框与焦点
        put("Component.borderColor", outline);
        put("Component.disabledBorderColor", outlineVariant);
        put("Component.focusColor", primary);
        put("Component.innerFocusColor", primary);
        put("Component.focusedBorderColor", primary);

        // 输入框
        put("TextField.background", container);
        put("TextField.foreground", text);
        put("TextField.placeholderForeground", muted);
        put("TextField.focusedBackground", container);
        put("PasswordField.background", container);
        put("PasswordField.foreground", text);
        put("FormattedTextField.background", container);
        put("FormattedTextField.foreground", text);
        put("TextArea.background", container);
        put("TextArea.foreground", text);
        put("EditorPane.background", container);
        put("EditorPane.foreground", text);

        // 按钮
        put("Button.background", containerHigh);
        put("Button.foreground", text);
        put("Button.hoverBackground", dark ? new Color(0x1F2C46) : new Color(0xDFE5F2));
        put("Button.pressedBackground", variant);
        put("Button.disabledBackground", variant);
        put("Button.default.background", primary);
        put("Button.default.foreground", onPrimary);
        put("Button.default.hoverBackground", primaryHover);
        put("Button.default.pressedBackground", primary);

        // 进度
        put("ProgressBar.foreground", primary);
        put("ProgressBar.background", variant);
        put("ProgressBar.selectionForeground", onPrimary);
        put("ProgressBar.selectionBackground", text);

        // 列表与表格
        put("List.background", surface);
        put("List.foreground", text);
        put("List.selectionBackground", dark ? new Color(0x2B3A66) : new Color(0xDDE2FF));
        put("List.selectionForeground", dark ? text : new Color(0x0B1233));
        put("Table.background", surface);
        put("Table.foreground", text);
        put("Table.gridColor", outlineVariant);
        put("Table.selectionBackground", dark ? new Color(0x2B3A66) : new Color(0xDDE2FF));
        put("Table.selectionForeground", dark ? text : new Color(0x0B1233));
        put("TableHeader.background", container);
        put("TableHeader.foreground", text);
        put("TableHeader.separatorColor", outlineVariant);
        put("TableHeader.bottomSeparatorColor", outlineVariant);

        // 下拉与滚动
        put("ComboBox.background", container);
        put("ComboBox.foreground", text);
        put("ComboBox.buttonBackground", container);
        put("ComboBox.selectionBackground", dark ? new Color(0x2B3A66) : new Color(0xDDE2FF));
        put("ComboBox.selectionForeground", dark ? text : new Color(0x0B1233));
        put("ScrollBar.thumb", dark ? new Color(0x3A4867) : new Color(0xC3CADD));
        put("ScrollBar.hoverThumbColor", primary);
        put("ScrollBar.track", dark ? new Color(0x0B1120) : new Color(0xEEF1FA));

        // 标签页
        put("TabbedPane.background", background);
        put("TabbedPane.foreground", muted);
        put("TabbedPane.selectedBackground", selectedTab);
        put("TabbedPane.selectedForeground", selectedTabText);
        put("TabbedPane.hoverColor", container);
        // 实测：FlatLaf 用 focusColor 填充选中标签，selectedBackground 在本版本不生效
        put("TabbedPane.focusColor", selectedTab);
        put("TabbedPane.underlineColor", primary);
        put("TabbedPane.inactiveUnderlineColor", outline);

        // 菜单与弹出层
        put("MenuBar.background", surface);
        put("MenuBar.foreground", text);
        put("Menu.background", surface);
        put("Menu.foreground", text);
        put("MenuItem.background", surface);
        put("MenuItem.foreground", text);
        put("MenuItem.selectionBackground", dark ? new Color(0x2B3A66) : new Color(0xDDE2FF));
        put("MenuItem.selectionForeground", dark ? text : new Color(0x0B1233));
        put("PopupMenu.background", surface);
        put("PopupMenu.borderColor", outline);
        put("ToolTip.background", containerHigh);
        put("ToolTip.foreground", text);
        put("ToolTip.borderColor", outline);

        // 窗口标题栏（FlatLaf 窗口装饰）
        put("TitlePane.background", dark ? D_SURFACE_CONTAINER : L_SURFACE_CONTAINER);
        put("TitlePane.inactiveBackground", background);
        put("TitlePane.foreground", text);
        put("TitlePane.inactiveForeground", muted);
        put("TitlePane.buttonHoverBackground", containerHigh);

        // 语义色（成功用品牌青绿）
        put("Actions.Green", teal);
        put("Actions.Red", new Color(0xD93A31));
        put("Actions.Yellow", dark ? new Color(0xF4C67A) : new Color(0x8A5A00));
    }

    private static void put(String key, Color value) {
        UIManager.put(key, value);
    }

    /** 深色主色，供自定义绘制的组件取用。 */
    public static Color primary(boolean dark) {
        return dark ? D_PRIMARY : L_PRIMARY;
    }

    /** 深色主色悬停态。 */
    public static Color primaryHover(boolean dark) {
        return dark ? D_PRIMARY_HOVER : L_PRIMARY_HOVER;
    }
}
