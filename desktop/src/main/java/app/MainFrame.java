package app;

import app.theme.AppFonts;
import app.theme.ThemeManager;
import app.theme.UIConstants;
import contacts.ui.ContactPanel;
import history.ui.HistoryPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.UIManager;
import javax.swing.border.TitledBorder;
import keymanagement.ui.KeyPairPanel;
import securecomm.ui.SecureTextPanel;
import settings.ui.SettingsPanel;
import stegano.ui.SteganoPanel;
import usage.ui.UsagePanel;

public class MainFrame extends JFrame {
    
    public MainFrame() {
        // 移除窗口标题
        // setTitle("Java本地非对称加密工具");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(app.theme.UIConstants.MAIN_WINDOW_WIDTH, app.theme.UIConstants.MAIN_WINDOW_HEIGHT); // 设置更合理的窗口大小
        setResizable(true); // 允许调整窗口大小
        setLocationRelativeTo(null);
        setMinimumSize(new Dimension(app.theme.UIConstants.MAIN_WINDOW_MIN_WIDTH, app.theme.UIConstants.MAIN_WINDOW_MIN_HEIGHT)); // 设置最小窗口大小
        
        // 移除窗口和任务栏图标
        // setIconImage(null);
        
        initComponents();
        
        // 添加窗口关闭监听器，清除暂存的密码
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                crypto.key.PrivateKeyAccessService.clearAllPasswordCaches();
            }
        });
    }
    
    // 保存UsagePanel实例，以便在字体大小变化时更新样式
    private usage.ui.UsagePanel usagePanel;
    private securecomm.ui.SecureTextPanel secureTextPanel;
    private JTabbedPane tabbedPane;

    /**
     * 外部打开（双击 .aegix、命令行传入文件）：切到安全通信页并加入待解密列表。
     * 与拖入文件走同一条判型/分流路径。
     */
    public void openExternalFiles(java.util.List<java.io.File> files) {
        if (files == null || files.isEmpty() || secureTextPanel == null) {
            return;
        }
        if (tabbedPane != null) {
            tabbedPane.setSelectedIndex(2);
        }
        secureTextPanel.openExternalFiles(files);
    }
    
    private void initComponents() {
        // 设置主题颜色
        setThemeColors();
        
        // 设置标签页样式
        UIManager.put("TabbedPane.font", app.theme.AppFonts.getBoldFont());
        UIManager.put("TabbedPane.selectedTabFont", app.theme.AppFonts.getBoldFont());
        
        this.tabbedPane = new JTabbedPane();
        JTabbedPane tabbedPane = this.tabbedPane;
        
        // 优化标签页样式
        tabbedPane.setTabPlacement(JTabbedPane.TOP);
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        
        // 设置标签页UI属性，使其更现代化
        tabbedPane.putClientProperty("JTabbedPane.tabAreaBackground", ThemeManager.getBackgroundColor());
        tabbedPane.putClientProperty("JTabbedPane.contentBackground", ThemeManager.getBackgroundColor());
        tabbedPane.putClientProperty("JTabbedPane.selectedTabBackground", ThemeManager.getSelectionBackgroundColor());
        tabbedPane.putClientProperty("JTabbedPane.selectedTabForeground", ThemeManager.getSelectionForegroundColor());
        tabbedPane.putClientProperty("JTabbedPane.underlineHeight", 2);
        tabbedPane.putClientProperty("JTabbedPane.underlineColor", ThemeManager.getProgressColor());
        
        // 创建各功能面板
        keymanagement.ui.KeyPairPanel keyPairPanel = new keymanagement.ui.KeyPairPanel();
        contacts.ui.ContactPanel contactPanel = new contacts.ui.ContactPanel();
        secureTextPanel = new securecomm.ui.SecureTextPanel();
        history.ui.HistoryPanel historyPanel = new history.ui.HistoryPanel();
        usagePanel = new usage.ui.UsagePanel();
        settings.ui.SettingsPanel settingsPanel = new settings.ui.SettingsPanel(this);
        SteganoPanel steganoPanel = new SteganoPanel();
        
        // 添加标签页
        tabbedPane.addTab("密钥对管理", keyPairPanel);
        tabbedPane.addTab("通讯录", contactPanel);
        tabbedPane.addTab("安全通信", secureTextPanel);
        tabbedPane.addTab("历史记录", historyPanel);
        tabbedPane.addTab("使用说明", usagePanel);
        tabbedPane.addTab("设置", settingsPanel);
        tabbedPane.addTab("隐写工具", steganoPanel);
        
        // 设置默认选中安全通信标签页
        tabbedPane.setSelectedIndex(2);
        
        // 添加标签页切换监听，当切换到使用说明标签页时更新样式，以及刷新其他页面数据
        tabbedPane.addChangeListener(e -> {
            java.awt.Component selected = tabbedPane.getSelectedComponent();
            if (selected == usagePanel) {
                usagePanel.updateStyleSheet();
            } else if (selected == secureTextPanel) {
                secureTextPanel.refreshData();
            } else if (selected == historyPanel) {
                historyPanel.refreshData();
            }
        });
        
        // 创建主内容面板，移除顶部边距，将标签页往上移
        JPanel mainContentPanel = new JPanel(new BorderLayout());
        mainContentPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        mainContentPanel.setBackground(ThemeManager.getBackgroundColor());
        mainContentPanel.add(tabbedPane, BorderLayout.CENTER);
        
        add(mainContentPanel, BorderLayout.CENTER);
        
        // 设置主窗口背景色
        getContentPane().setBackground(ThemeManager.getBackgroundColor());
    }
    
    /**
     * 设置主题颜色和UI属性
     * 优化FlatLaf主题的视觉效果
     */
    private void setThemeColors() {
        // 设置标签页样式
        UIManager.put("TabbedPane.tabInsets", new Insets(10, 15, 10, 15));
        UIManager.put("TabbedPane.selectedTabPadInsets", new Insets(10, 15, 10, 15));
        
        // 设置窗口样式
        UIManager.put("Window.alpha", 1.0f);
        UIManager.put("Window.buttonAutoRequestFocus", false);
        
        // 设置面板样式
        UIManager.put("Panel.background", ThemeManager.getBackgroundColor());
        
        // 设置分隔线样式
        UIManager.put("Separator.background", ThemeManager.getBorderColor());
        UIManager.put("Separator.foreground", ThemeManager.getBorderColor());
        
        // 设置滚动条样式
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("ScrollBar.trackArc", 6);
        UIManager.put("ScrollBar.thumbArc", 6);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        
        // 设置 HTML 渲染器的默认字体，确保所有 HTML 标签使用正确字体
        UIManager.put("Label.font", app.theme.AppFonts.getNormalFont());
        UIManager.put("TitledBorder.font", app.theme.AppFonts.getBoldFont());
        UIManager.put("Button.font", app.theme.AppFonts.getButtonFont());
        UIManager.put("TextField.font", app.theme.AppFonts.getNormalFont());
        UIManager.put("TextArea.font", app.theme.AppFonts.getNormalFont());
        UIManager.put("ComboBox.font", app.theme.AppFonts.getNormalFont());
        UIManager.put("List.font", app.theme.AppFonts.getNormalFont());
        UIManager.put("Table.font", app.theme.AppFonts.getTableFont());
        UIManager.put("TableHeader.font", app.theme.AppFonts.getTableHeaderFont());
    }
    
    public void showFrame() {
        setVisible(true);
    }
}
