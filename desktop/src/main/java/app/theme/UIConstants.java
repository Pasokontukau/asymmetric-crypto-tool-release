package app.theme;

import app.theme.UIConstants;
import java.awt.Color;

public final class UIConstants {

    private UIConstants() {}

    // 窗口尺寸
    public static final int MAIN_WINDOW_WIDTH = 1200;
    public static final int MAIN_WINDOW_HEIGHT = 840;
    public static final int MAIN_WINDOW_MIN_WIDTH = 1000;
    public static final int MAIN_WINDOW_MIN_HEIGHT = 720;
    public static final int FILE_CHOOSER_WIDTH = 1000;
    public static final int FILE_CHOOSER_HEIGHT = 700;

    // 圆角值
    public static final int CORNER_RADIUS = 12;

    // 按钮颜色：与 Android 主题同一套品牌色（Indigo / 错误红 / Cloud 中性灰）
    public static final Color PRIMARY_BUTTON_COLOR = new Color(0x5B67E8);
    public static final Color DANGER_BUTTON_COLOR = new Color(0xB3261E);
    public static final Color NEUTRAL_BUTTON_COLOR = new Color(0xC3CADD);

    // 语义颜色：青绿表示成功，深红表示错误；深浅主题下都保持可读
    public static final Color ERROR_TEXT_COLOR = new Color(0xD93A31);
    public static final Color SUCCESS_TEXT_COLOR = new Color(0x0E8F74);
}
