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

    // 按钮颜色
    public static final Color PRIMARY_BUTTON_COLOR = new Color(66, 133, 244);
    public static final Color DANGER_BUTTON_COLOR = new Color(234, 67, 53);
    public static final Color NEUTRAL_BUTTON_COLOR = new Color(200, 200, 200);

    // 语义颜色
    public static final Color ERROR_TEXT_COLOR = new Color(200, 0, 0);
    public static final Color SUCCESS_TEXT_COLOR = new Color(0, 150, 0);
}
