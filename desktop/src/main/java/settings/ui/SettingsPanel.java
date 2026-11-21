package settings.ui;

import app.AppVersion;
import app.MainFrame;
import app.UpdateCheck;
import app.theme.AppFonts;
import app.theme.ThemeManager;
import app.theme.UIConstants;
import core.storage.StorageManager;
import core.util.ExportPasswordDialog;
import core.util.LogManager;
import core.util.RestartHelper;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import securecomm.ui.SecureTextPanel;
import settings.model.VolumeLimitPreset;
import settings.storage.SettingsStorage;

public class SettingsPanel extends JPanel {

    private final settings.repository.SettingsRepository settingsRepository =
            new settings.repository.SettingsRepository();
    private final history.repository.HistoryRepository historyRepository =
            new history.repository.HistoryRepository();

    /** 检查更新失败（含取不到结果）时的唯一提示，与 Android 的 `about_update_failed` 同义。 */
    private static final String UPDATE_CHECK_FAILED =
            "检查失败：连不上 GitHub，可用「查看最新版本」";

    /** 「关于」页的检查更新是否在跑：跑的时候开关不能把按钮重新点亮。 */
    private boolean updateCheckRunning;

    private JSplitPane splitPane;
    private JList<String> menuList;
    private CardLayout cardLayout;
    private JPanel contentPanel;
    
    // 用于存储原始菜单列表
    private static final String[] FULL_MENU_ITEMS = {"主题设置", "安全设置", "日志管理", "存储设置", "文件处理", "开发者选项", "关于"};
    private static final String[] NO_SECURITY_MENU_ITEMS = {"主题设置", "日志管理", "存储设置", "开发者选项", "关于"};

    /** 官网站点与最新版本入口；只交给系统浏览器打开，程序自身不发起任何网络请求。 */
    private static final String SITE_URL = "https://pasokontukau.github.io/asymmetric-crypto-tool-release/";
    private static final String LATEST_RELEASE_URL =
            "https://github.com/Pasokontukau/asymmetric-crypto-tool-release/releases/latest";
    
    // 主题设置组件
    private ButtonGroup themeButtonGroup;
    private JRadioButton lightThemeRadio;
    private JRadioButton darkThemeRadio;
    private JComboBox<String> fontComboBox;
    
    // 安全设置组件
    private JComboBox<String> securityLevelComboBox;
    private JPanel timeoutPanel;
    
    // 日志管理组件
    private JTable logTable;
    private DefaultTableModel logTableModel;
    
    // 主框架引用，用于访问UsagePanel
    private app.MainFrame mainFrame;
    
    public SettingsPanel(app.MainFrame mainFrame) {
        this.mainFrame = mainFrame;
        setLayout(new BorderLayout());
        setBackground(ThemeManager.getBackgroundColor());
        
        // 初始化左右分栏布局
        initSplitPane();
        
        // 先初始化内容面板和页面
        initContentPanel();
        initThemeSettingsPanel();
        initSecuritySettingsPanel();
        initLogManagementPanel();
        initStorageSettingsPanel();
        initFileProcessingPanel();
        initAboutPanel();
        initDeveloperOptionsPanel();

        // 最后初始化菜单列表，避免选择监听器在组件初始化完成前被触发
        initMenuList();
        
        // 添加组件到面板
        add(splitPane, BorderLayout.CENTER);
    }
    
    /**
     * 初始化左右分栏
     */
    private void initSplitPane() {
        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        // 设置分栏不可调整，固定左侧菜单栏宽度
        splitPane.setEnabled(false);
        splitPane.setDividerSize(0); // 设置分割线宽度为0，隐藏拖动条
        splitPane.setResizeWeight(0.0);
    }
    
    /**
     * 初始化菜单列表
     */
    private void initMenuList() {
        // 根据安全级别确定显示哪些菜单项
        String[] menuItems = getCurrentMenuItems();
        menuList = new JList<>(menuItems);
        menuList.setFont(app.theme.AppFonts.getNormalFont());
        menuList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        menuList.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        menuList.setFixedCellHeight(40);
        
        // 计算并设置合适的固定宽度，根据菜单项文本和字体大小
        FontMetrics fontMetrics = menuList.getFontMetrics(app.theme.AppFonts.getNormalFont());
        int maxWidth = 0;
        for (String item : FULL_MENU_ITEMS) { // 使用完整菜单计算宽度，避免切换时宽度变化
            int width = fontMetrics.stringWidth(item);
            if (width > maxWidth) {
                maxWidth = width;
            }
        }
        // 加上左右内边距和一些额外空间
        int fixedWidth = maxWidth + 40;
        menuList.setFixedCellWidth(fixedWidth);
        
        // 设置左侧面板宽度
        splitPane.setDividerLocation(fixedWidth + 20); // 加上边框和内边距
        
        // 添加列表选择监听器
        menuList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String selectedItem = menuList.getSelectedValue();
                if (selectedItem != null) {
                    cardLayout.show(contentPanel, selectedItem);
                    
                    // 如果选择的是安全设置，更新安全级别选择和超时面板可见性
                    if ("安全设置".equals(selectedItem) && securityLevelComboBox != null && timeoutPanel != null) {
                        String currentLevel = settingsRepository.getSecurityLevel();
                        if (settings.storage.SettingsStorage.SECURITY_LEVEL_STRICT.equals(currentLevel)) {
                            securityLevelComboBox.setSelectedIndex(0);
                            timeoutPanel.setVisible(false);
                        } else if (settings.storage.SettingsStorage.SECURITY_LEVEL_CONVENIENT.equals(currentLevel)) {
                            securityLevelComboBox.setSelectedIndex(2);
                            timeoutPanel.setVisible(true);
                        } else {
                            securityLevelComboBox.setSelectedIndex(1);
                            timeoutPanel.setVisible(true);
                        }
                    }
                }
            }
        });
        
        // 默认选择第一个菜单项
        menuList.setSelectedIndex(0);
        
        // 添加到分栏左边，不使用滚动面板，避免出现滚动条
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setPreferredSize(new Dimension(fixedWidth + 20, 0));
        leftPanel.add(menuList, BorderLayout.CENTER);
        splitPane.setLeftComponent(leftPanel);
    }
    
    /**
     * 根据安全级别获取当前应该显示的菜单项
     */
    private String[] getCurrentMenuItems() {
        // 始终显示"安全设置"菜单项，便于用户在各模式之间切换
        return FULL_MENU_ITEMS;
    }
    
    /**
     * 更新菜单列表（根据当前安全级别显示/隐藏安全设置）
     */
    public void updateMenuItems() {
        // 保存当前选择的菜单项
        String selectedItem = menuList.getSelectedValue();
        
        // 更新菜单列表
        String[] newMenuItems = getCurrentMenuItems();
        menuList.setListData(newMenuItems);
        
        // 尝试选择原来的菜单项，如果不存在则选择第一个
        int index = -1;
        for (int i = 0; i < newMenuItems.length; i++) {
            if (newMenuItems[i].equals(selectedItem)) {
                index = i;
                break;
            }
        }
        menuList.setSelectedIndex(index >= 0 ? index : 0);
    }
    
    /**
     * 初始化内容面板
     */
    private void initContentPanel() {
        cardLayout = new CardLayout();
        contentPanel = new JPanel(cardLayout);
        contentPanel.setBackground(ThemeManager.getPanelBackgroundColor());
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // 添加到分栏右边
        splitPane.setRightComponent(contentPanel);
    }
    
    /**
     * 初始化主题设置页面（字体大小 + 主题）
     */
    private void initThemeSettingsPanel() {
        JPanel themeSettingsPanel = new JPanel(new BorderLayout(10, 10));
        themeSettingsPanel.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        TitledBorder border = BorderFactory.createTitledBorder("主题设置");
        border.setTitleFont(app.theme.AppFonts.getBoldFont());
        themeSettingsPanel.setBorder(border);
        
        // 主设置区域
        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        // === 字体大小设置 ===
        JLabel fontSizeSectionLabel = new JLabel("字体大小设置");
        fontSizeSectionLabel.setFont(app.theme.AppFonts.getBoldFont());
        fontSizeSectionLabel.setForeground(app.theme.ThemeManager.getTextColor());
        gbc.gridwidth = 3;
        mainPanel.add(fontSizeSectionLabel, gbc);
        
        gbc.gridy++;
        gbc.gridwidth = 1;
        JLabel fontSizeLabel = new JLabel("全局字体大小:");
        fontSizeLabel.setFont(app.theme.AppFonts.getNormalFont());
        fontSizeLabel.setForeground(app.theme.ThemeManager.getTextColor());
        mainPanel.add(fontSizeLabel, gbc);
        
        gbc.gridx = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        // 创建输入框和单位的组合面板
        JPanel fontSizeInputPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        fontSizeInputPanel.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        
        JTextField fontSizeField = new JTextField(String.valueOf(settingsRepository.getFontSize()), 3);
        fontSizeField.setFont(app.theme.AppFonts.getNormalFont());
        fontSizeField.setBorder(ThemeManager.createRoundedBorder(1));
        fontSizeField.putClientProperty("TextField.arc", 12);
        fontSizeField.setPreferredSize(new Dimension(60, 30));
        fontSizeField.setToolTipText("范围 10-24");
        
        JLabel fontSizeUnitLabel = new JLabel(" px");
        fontSizeUnitLabel.setFont(app.theme.AppFonts.getNormalFont());
        fontSizeUnitLabel.setForeground(app.theme.ThemeManager.getTextColor());
        
        fontSizeInputPanel.add(fontSizeField);
        fontSizeInputPanel.add(fontSizeUnitLabel);
        mainPanel.add(fontSizeInputPanel, gbc);
        
        gbc.gridx = 2;
        gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.EAST;
        JButton applyFontSizeButton = ThemeManager.createPrimaryButton("应用");
        applyFontSizeButton.addActionListener(e -> {
            try {
                int newFontSize = Integer.parseInt(fontSizeField.getText().trim());
                if (newFontSize < 10 || newFontSize > 24) {
                    JOptionPane.showMessageDialog(SettingsPanel.this, "请输入10-24之间的数字！", "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                settingsRepository.setFontSize(newFontSize);
                core.util.RestartHelper.showRestartConfirmDialog(SettingsPanel.this, "字体大小已更新");
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(SettingsPanel.this, "请输入有效的数字！", "错误", JOptionPane.ERROR_MESSAGE);
            }
        });
        mainPanel.add(applyFontSizeButton, gbc);
        
        // 分隔线
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JSeparator separator = new JSeparator();
        separator.setBackground(app.theme.ThemeManager.getBorderColor());
        mainPanel.add(separator, gbc);
        
        // === 主题选择设置 ===
        gbc.gridy++;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.NONE;
        JLabel themeSectionLabel = new JLabel("主题设置");
        themeSectionLabel.setFont(app.theme.AppFonts.getBoldFont());
        themeSectionLabel.setForeground(app.theme.ThemeManager.getTextColor());
        mainPanel.add(themeSectionLabel, gbc);
        
        gbc.gridy++;
        gbc.gridwidth = 3;
        themeButtonGroup = new ButtonGroup();
        
        // 创建横向的主题选择面板
        JPanel themePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 0));
        themePanel.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        
        // 跟随系统主题单选按钮
        JRadioButton systemThemeRadio = new JRadioButton("跟随系统");
        systemThemeRadio.setFont(app.theme.AppFonts.getNormalFont());
        systemThemeRadio.setForeground(app.theme.ThemeManager.getTextColor());
        systemThemeRadio.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        systemThemeRadio.addActionListener(e -> {
            settingsRepository.setTheme(app.theme.ThemeManager.THEME_SYSTEM);
            core.util.RestartHelper.showRestartConfirmDialog(SettingsPanel.this, "主题已更新");
        });
        themeButtonGroup.add(systemThemeRadio);
        themePanel.add(systemThemeRadio);
        
        lightThemeRadio = new JRadioButton("浅色主题");
        lightThemeRadio.setFont(app.theme.AppFonts.getNormalFont());
        lightThemeRadio.setForeground(app.theme.ThemeManager.getTextColor());
        lightThemeRadio.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        lightThemeRadio.addActionListener(e -> {
            settingsRepository.setTheme("light");
            core.util.RestartHelper.showRestartConfirmDialog(SettingsPanel.this, "主题已更新");
        });
        themeButtonGroup.add(lightThemeRadio);
        themePanel.add(lightThemeRadio);
        
        darkThemeRadio = new JRadioButton("深色主题");
        darkThemeRadio.setFont(app.theme.AppFonts.getNormalFont());
        darkThemeRadio.setForeground(app.theme.ThemeManager.getTextColor());
        darkThemeRadio.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        darkThemeRadio.addActionListener(e -> {
            settingsRepository.setTheme("dark");
            core.util.RestartHelper.showRestartConfirmDialog(SettingsPanel.this, "主题已更新");
        });
        themeButtonGroup.add(darkThemeRadio);
        themePanel.add(darkThemeRadio);
        
        mainPanel.add(themePanel, gbc);
        
        // 根据当前设置选择主题
        String currentTheme = settingsRepository.getTheme();
        if (app.theme.ThemeManager.THEME_SYSTEM.equals(currentTheme)) {
            systemThemeRadio.setSelected(true);
        } else if (currentTheme.equals("dark") || currentTheme.equals("darcula") || currentTheme.equals("intellij")) {
            darkThemeRadio.setSelected(true);
        } else { // light
            lightThemeRadio.setSelected(true);
        }
        
        // 分隔线 - 主题设置和字体样式之间
        gbc.gridy++;
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JSeparator fontSeparator = new JSeparator();
        fontSeparator.setBackground(app.theme.ThemeManager.getBorderColor());
        mainPanel.add(fontSeparator, gbc);
        
        // 字体选择
        gbc.gridy++;
        gbc.insets = new Insets(15, 10, 5, 10);
        JLabel fontLabel = new JLabel("字体样式：");
        fontLabel.setFont(app.theme.AppFonts.getBoldFont());
        fontLabel.setForeground(app.theme.ThemeManager.getTextColor());
        mainPanel.add(fontLabel, gbc);
        
        gbc.gridy++;
        gbc.insets = new Insets(5, 10, 5, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        // 获取当前字体配置
        String currentFont = settingsRepository.getFontFamily();
        // 获取系统所有可用字体
        String[] availableFonts = app.theme.AppFonts.getAvailableFontFamilies();
        // 创建下拉框模型，包含"自动检测"选项
        DefaultComboBoxModel<String> fontComboBoxModel = new DefaultComboBoxModel<>();
        fontComboBoxModel.addElement("自动检测（推荐）");
        for (String font : availableFonts) {
            fontComboBoxModel.addElement(font);
        }
        fontComboBox = new JComboBox<>(fontComboBoxModel);
        fontComboBox.setFont(app.theme.AppFonts.getNormalFont());
        // 设置当前选中项
        if (currentFont == null || currentFont.trim().isEmpty()) {
            fontComboBox.setSelectedItem("自动检测（推荐）");
        } else {
            fontComboBox.setSelectedItem(currentFont);
        }
        fontComboBox.addActionListener(e -> {
            Object selected = fontComboBox.getSelectedItem();
            String selectedFont = selected != null ? selected.toString() : "";
            if ("自动检测（推荐）".equals(selectedFont)) {
                settingsRepository.setFontFamily("");
            } else {
                settingsRepository.setFontFamily(selectedFont);
            }
            // 清除字体缓存
            app.theme.AppFonts.clearFontCache();
            // 先收起下拉框，再延迟显示弹框，避免被挡住
            fontComboBox.hidePopup();
            SwingUtilities.invokeLater(() -> {
                core.util.RestartHelper.showRestartConfirmDialog(SettingsPanel.this, "字体已更新");
            });
        });
        mainPanel.add(fontComboBox, gbc);
        gbc.fill = GridBagConstraints.NONE;
        
        // 说明文本
        gbc.gridy++;
        gbc.insets = new Insets(10, 10, 10, 10);
        JLabel infoLabel = new JLabel("<html>调整后请重启应用程序使设置生效</html>");
        infoLabel.setFont(app.theme.AppFonts.getSmallFont());
        infoLabel.setForeground(app.theme.ThemeManager.getSecondaryTextColor());
        mainPanel.add(infoLabel, gbc);
        
        themeSettingsPanel.add(mainPanel, BorderLayout.NORTH);
        
        // 添加到内容面板
        contentPanel.add(themeSettingsPanel, "主题设置");
    }

    /**
     * 初始化安全设置页面
     */
    private void initSecuritySettingsPanel() {
        JPanel securitySettingsPanel = new JPanel(new BorderLayout(10, 10));
        securitySettingsPanel.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        TitledBorder border = BorderFactory.createTitledBorder("安全设置");
        border.setTitleFont(app.theme.AppFonts.getBoldFont());
        securitySettingsPanel.setBorder(border);

        // 主设置区域
        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // === 安全级别设置 ===
        JLabel securityLevelSectionLabel = new JLabel("安全级别");
        securityLevelSectionLabel.setFont(app.theme.AppFonts.getBoldFont());
        securityLevelSectionLabel.setForeground(app.theme.ThemeManager.getTextColor());
        gbc.gridwidth = 3;
        mainPanel.add(securityLevelSectionLabel, gbc);

        gbc.gridy++;
        gbc.gridwidth = 3;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        String[] securityLevelOptions = {
            "严格模式（每次操作输入密码）",
            "平衡模式（会话内缓存密码）",
            "便捷模式（密码保存到系统密钥链）"
        };
        securityLevelComboBox = new JComboBox<>(securityLevelOptions);
        securityLevelComboBox.setFont(app.theme.AppFonts.getNormalFont());

        // 设置当前选择
        String currentLevel = settingsRepository.getSecurityLevel();
        if (settings.storage.SettingsStorage.SECURITY_LEVEL_STRICT.equals(currentLevel)) {
            securityLevelComboBox.setSelectedIndex(0);
        } else if (settings.storage.SettingsStorage.SECURITY_LEVEL_CONVENIENT.equals(currentLevel)) {
            securityLevelComboBox.setSelectedIndex(2);
        } else {
            securityLevelComboBox.setSelectedIndex(1); // 默认平衡模式
        }

        // 超时设置组件（仅在平衡模式下显示）
        String[] timeoutOptions = {
            "5 分钟",
            "15 分钟",
            "30 分钟",
            "1 小时",
            "永不超时"
        };
        JComboBox<String> timeoutComboBox = new JComboBox<>(timeoutOptions);
        timeoutComboBox.setFont(app.theme.AppFonts.getNormalFont());

        // 设置当前超时选择
        int currentTimeout = settingsRepository.getPasswordCacheTimeout();
        if (currentTimeout == settings.storage.SettingsStorage.TIMEOUT_NEVER) {
            timeoutComboBox.setSelectedIndex(4);
        } else if (currentTimeout == settings.storage.SettingsStorage.TIMEOUT_5_MIN) {
            timeoutComboBox.setSelectedIndex(0);
        } else if (currentTimeout == settings.storage.SettingsStorage.TIMEOUT_15_MIN) {
            timeoutComboBox.setSelectedIndex(1);
        } else if (currentTimeout == settings.storage.SettingsStorage.TIMEOUT_1_HOUR) {
            timeoutComboBox.setSelectedIndex(3);
        } else {
            timeoutComboBox.setSelectedIndex(2); // 默认30分钟
        }

        // 创建超时设置的包装面板，让整个内容左对齐
        timeoutPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        timeoutPanel.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        
        // 创建超时设置的内容面板，让标题和下拉框水平并排
        JPanel timeoutContentPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        timeoutContentPanel.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        
        JLabel timeoutSectionLabel = new JLabel("密码缓存超时");
        timeoutSectionLabel.setFont(app.theme.AppFonts.getBoldFont());
        timeoutSectionLabel.setForeground(app.theme.ThemeManager.getTextColor());
        timeoutContentPanel.add(timeoutSectionLabel);
        
        timeoutContentPanel.add(timeoutComboBox);
        
        timeoutPanel.add(timeoutContentPanel);
        
        // 根据当前安全级别设置超时面板的可见性（strict 不显示，balanced/convenient 显示）
        if (settings.storage.SettingsStorage.SECURITY_LEVEL_STRICT.equals(currentLevel)) {
            timeoutPanel.setVisible(false);
        } else {
            timeoutPanel.setVisible(true);
        }

        // 安全级别变化监听器
        securityLevelComboBox.addActionListener(e -> {
            int selected = securityLevelComboBox.getSelectedIndex();
            String level;
            if (selected == 0) {
                level = settings.storage.SettingsStorage.SECURITY_LEVEL_STRICT;
                // 严格模式下隐藏超时设置
                timeoutPanel.setVisible(false);
                // 切换到严格模式时，立即清除所有内存密码缓存
                securecomm.ui.SecureTextPanel.clearAllCachedPasswords();
            } else if (selected == 2) {
                level = settings.storage.SettingsStorage.SECURITY_LEVEL_CONVENIENT;
                // 便捷模式下显示超时设置
                timeoutPanel.setVisible(true);
                // 检查系统密钥链是否可用
                if (!keymanagement.storage.KeychainStorage.isAvailable()) {
                    JOptionPane.showMessageDialog(SettingsPanel.this,
                        "<html>当前系统不支持系统密钥链（可能是非 Windows 系统或策略限制）。<br>便捷模式无法正常工作，已自动切换为平衡模式。</html>",
                        "不支持便捷模式", JOptionPane.WARNING_MESSAGE);
                    level = settings.storage.SettingsStorage.SECURITY_LEVEL_BALANCED;
                    securityLevelComboBox.setSelectedIndex(1);
                }
            } else {
                level = settings.storage.SettingsStorage.SECURITY_LEVEL_BALANCED;
                // 平衡模式下显示超时设置
                timeoutPanel.setVisible(true);
            }
            settingsRepository.setSecurityLevel(level);
        });

        // 超时变化监听器
        timeoutComboBox.addActionListener(e -> {
            int selected = timeoutComboBox.getSelectedIndex();
            int timeout;
            switch (selected) {
                case 0:
                    timeout = settings.storage.SettingsStorage.TIMEOUT_5_MIN;
                    break;
                case 1:
                    timeout = settings.storage.SettingsStorage.TIMEOUT_15_MIN;
                    break;
                case 3:
                    timeout = settings.storage.SettingsStorage.TIMEOUT_1_HOUR;
                    break;
                case 4:
                    timeout = settings.storage.SettingsStorage.TIMEOUT_NEVER;
                    break;
                default:
                    timeout = settings.storage.SettingsStorage.TIMEOUT_30_MIN;
            }
            settingsRepository.setPasswordCacheTimeout(timeout);
        });

        mainPanel.add(securityLevelComboBox, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JLabel securityLevelInfo = new JLabel("<html>• <b>严格模式</b>：每次加密/解密操作都需要重新输入密码<br>• <b>平衡模式</b>：同一密钥在会话内缓存，超时后清除<br>• <b>便捷模式</b>：密码保存到系统密钥链，重启后无需重新输入</html>");
        securityLevelInfo.setFont(app.theme.AppFonts.getSmallFont());
        securityLevelInfo.setForeground(app.theme.ThemeManager.getSecondaryTextColor());
        mainPanel.add(securityLevelInfo, gbc);

        // 分隔线
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 3;
        JSeparator separator = new JSeparator();
        separator.setBackground(app.theme.ThemeManager.getBorderColor());
        mainPanel.add(separator, gbc);

        // 添加超时设置面板
        gbc.gridy++;
        gbc.gridwidth = 3;
        mainPanel.add(timeoutPanel, gbc);

        // 初始化超时设置可见性
        timeoutPanel.setVisible(settings.storage.SettingsStorage.SECURITY_LEVEL_BALANCED.equals(currentLevel));

        securitySettingsPanel.add(mainPanel, BorderLayout.NORTH);

        // 添加到内容面板
        contentPanel.add(securitySettingsPanel, "安全设置");
    }

    /**
     * 初始化日志管理页面
     */
    private void initLogManagementPanel() {
        JPanel logPanel = new JPanel(new BorderLayout(10, 10));
        logPanel.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        TitledBorder border = BorderFactory.createTitledBorder("日志管理");
        border.setTitleFont(app.theme.AppFonts.getBoldFont());
        logPanel.setBorder(border);
        
        // 日志表格
        String[] columnNames = {"日志文件名", "大小", "修改时间"};
        logTableModel = new DefaultTableModel(columnNames, 0);
        logTable = new JTable(logTableModel);
        
        // 设置表格属性
        logTable.setFont(app.theme.AppFonts.getNormalFont());
        logTable.getTableHeader().setFont(app.theme.AppFonts.getBoldFont());
        logTable.getTableHeader().setBackground(app.theme.ThemeManager.getButtonBackgroundColor());
        logTable.getTableHeader().setForeground(app.theme.ThemeManager.getTextColor());
        logTable.getTableHeader().setBorder(ThemeManager.createRoundedBorder(1));
        logTable.setRowHeight(30);
        logTable.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        logTable.setForeground(app.theme.ThemeManager.getTextColor());
        logTable.setGridColor(app.theme.ThemeManager.getBorderColor());
        logTable.setSelectionBackground(app.theme.UIConstants.PRIMARY_BUTTON_COLOR);
        logTable.setSelectionForeground(Color.WHITE);
        logTable.setAutoCreateRowSorter(true);
        
        // 设置表格不可编辑，避免双击时进入编辑模式
        logTable.setDefaultEditor(Object.class, null);
        
        // 添加双击事件监听器
        logTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    // 获取点击位置的行索引
                    int row = logTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        // 转换为模型索引
                        int modelRow = logTable.convertRowIndexToModel(row);
                        // 获取日志文件名
                        String logFileName = (String) logTableModel.getValueAt(modelRow, 0);
                        // 显示日志详情
                        showLogDetails(logFileName);
                    }
                }
            }
        });
        
        // 日志表格滚动面板
        JScrollPane logTableScrollPane = new JScrollPane(logTable);
        logTableScrollPane.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        logTableScrollPane.getViewport().setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        logTableScrollPane.setBorder(ThemeManager.createRoundedBorder(1));
        logPanel.add(logTableScrollPane, BorderLayout.CENTER);
        
        // 操作按钮面板
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        buttonPanel.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        
        // 刷新按钮
            JButton refreshButton = ThemeManager.createPrimaryButton("刷新列表");
            refreshButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    refreshLogList();
                }
            });
            buttonPanel.add(refreshButton);
            
            // 清空日志按钮
            JButton clearButton = ThemeManager.createDangerButton("清空日志");
            clearButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    clearLogs();
                }
            });
            buttonPanel.add(clearButton);
        
        logPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        // 添加到内容面板
        contentPanel.add(logPanel, "日志管理");
        
        // 初始加载日志列表
        refreshLogList();
    }
    
    /**
     * 刷新日志文件列表
     */
    private void refreshLogList() {
        // 清空现有数据
        logTableModel.setRowCount(0);
        
        String logDirPath = LogManager.getLogDirectory();
        if (logDirPath == null) {
            logDirPath = "data/logs";
        }
        
        File logDir = new File(logDirPath);
        if (logDir.exists() && logDir.isDirectory()) {
            File[] logFiles = logDir.listFiles((dir, name) -> name.endsWith(".log"));
            if (logFiles != null) {
                List<File> logFileList = new ArrayList<>();
                for (File file : logFiles) {
                    logFileList.add(file);
                }
                
                // 按修改时间倒序排列
                logFileList.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                
                // 添加到表格
                for (File file : logFileList) {
                    String fileName = file.getName();
                    String size = formatFileSize(file.length());
                    String modifiedTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(file.lastModified());
                    logTableModel.addRow(new Object[]{fileName, size, modifiedTime});
                }
            }
        }
    }
    
    /**
     * 格式化文件大小
     */
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else {
            return String.format("%.2f MB", size / (1024.0 * 1024.0));
        }
    }
    
    /**
     * 显示日志详情
     */
    private void showLogDetails(String logFileName) {
        String logDirPath = LogManager.getLogDirectory();
        if (logDirPath == null) {
            logDirPath = "data/logs";
        }
        
        String logFilePath = logDirPath + File.separator + logFileName;
        try {
            // 读取日志文件内容
            String content = new String(Files.readAllBytes(Paths.get(logFilePath)),
                    java.nio.charset.StandardCharsets.UTF_8);
            
            // 创建日志详情对话框
            JDialog logDialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "日志详情 - " + logFileName, true);
            logDialog.setSize(800, 600);
            logDialog.setLocationRelativeTo(this);
            logDialog.getContentPane().setBackground(ThemeManager.getPanelBackgroundColor());
            
            // 日志内容显示区域
            JTextArea logContentArea = new JTextArea(content);
            logContentArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
            logContentArea.setEditable(false);
            logContentArea.setLineWrap(true);
            logContentArea.setWrapStyleWord(true);
            logContentArea.setBackground(ThemeManager.getPanelBackgroundColor());
            logContentArea.setForeground(ThemeManager.getTextColor());
            logContentArea.setBorder(ThemeManager.createTextAreaBorder());
            JScrollPane scrollPane = new JScrollPane(logContentArea);
            scrollPane.setBorder(ThemeManager.createRoundedBorder(1));
            scrollPane.setBackground(ThemeManager.getPanelBackgroundColor());
            
            // 添加到对话框
            logDialog.add(scrollPane, BorderLayout.CENTER);
            logDialog.setVisible(true);
            
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "读取日志文件失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            LogManager.error("读取日志文件失败", e);
        }
    }
    
    /**
     * 清空日志文件
     */
    private void clearLogs() {
        int result = JOptionPane.showConfirmDialog(this, "确定要清空所有日志文件吗？此操作不可恢复。", "确认", JOptionPane.YES_NO_OPTION);
        if (result == JOptionPane.YES_OPTION) {
            String logDirPath = LogManager.getLogDirectory();
            if (logDirPath == null) {
                logDirPath = "data/logs";
            }
            
            File logDir = new File(logDirPath);
            if (logDir.exists() && logDir.isDirectory()) {
                File[] logFiles = logDir.listFiles((dir, name) -> name.endsWith(".log"));
                if (logFiles != null) {
                    int deletedCount = 0;
                    for (File file : logFiles) {
                        if (file.delete()) {
                            deletedCount++;
                        }
                    }
                    refreshLogList();
                    JOptionPane.showMessageDialog(this, "已清空 " + deletedCount + " 个日志文件。", "提示", JOptionPane.INFORMATION_MESSAGE);
                }
            }
        }
    }
    
    /**
     * 初始化存储设置页面（文件设置 + 数据目录）
     */
    private void initStorageSettingsPanel() {
        JPanel storageSettingsPanel = new JPanel(new BorderLayout(10, 10));
        storageSettingsPanel.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        TitledBorder border = BorderFactory.createTitledBorder("存储设置");
        border.setTitleFont(app.theme.AppFonts.getBoldFont());
        storageSettingsPanel.setBorder(border);
        
        // 主设置区域
        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        // === 默认输出目录设置 ===
        JLabel outputDirSectionLabel = new JLabel("默认输出目录");
        outputDirSectionLabel.setFont(app.theme.AppFonts.getBoldFont());
        outputDirSectionLabel.setForeground(app.theme.ThemeManager.getTextColor());
        gbc.gridwidth = 3;
        mainPanel.add(outputDirSectionLabel, gbc);
        
        gbc.gridy++;
        gbc.gridwidth = 2;
        gbc.gridx = 0;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        JTextField defaultOutputDirField = new JTextField();
        defaultOutputDirField.setFont(app.theme.AppFonts.getNormalFont());
        defaultOutputDirField.setText(settingsRepository.getDefaultOutputDirectory());
        defaultOutputDirField.setBorder(ThemeManager.createRoundedBorder(1));
        defaultOutputDirField.putClientProperty("TextField.arc", 12);
        defaultOutputDirField.setEditable(false);
        mainPanel.add(defaultOutputDirField, gbc);
        
        gbc.gridx = 2;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        JButton browseOutputDirButton = ThemeManager.createPrimaryButton("更改");
        browseOutputDirButton.addActionListener(e -> {
            JFileChooser directoryChooser = new JFileChooser();
            directoryChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            directoryChooser.setDialogTitle("选择默认输出目录");
            
            setFontForAllComponents(directoryChooser, app.theme.AppFonts.getNormalFont());
            directoryChooser.setPreferredSize(new Dimension(1000, 700));
            directoryChooser.setMinimumSize(new Dimension(800, 600));
            
            String currentDir = defaultOutputDirField.getText();
            if (!currentDir.isEmpty()) {
                File dir = new File(currentDir);
                if (dir.exists() && dir.isDirectory()) {
                    directoryChooser.setCurrentDirectory(dir.getParentFile());
                }
            }
            
            int result = directoryChooser.showOpenDialog(this);
            if (result == JFileChooser.APPROVE_OPTION) {
                File selectedDir = directoryChooser.getSelectedFile();
                File newOutputDir = new File(selectedDir, "output");
                
                // 询问是否迁移旧数据
                String oldDir = settingsRepository.getDefaultOutputDirectory();
                int migrateConfirm = JOptionPane.YES_OPTION;
                if (oldDir != null && !oldDir.isEmpty() && !oldDir.equals(newOutputDir.getAbsolutePath())) {
                    File oldDirFile = new File(oldDir);
                    if (oldDirFile.exists() && oldDirFile.isDirectory()) {
                        migrateConfirm = JOptionPane.showConfirmDialog(
                            this,
                            "<html>是否要将旧输出目录 " + oldDir + " 中的数据迁移到新目录？<br><br>" +
                            "<b>建议：</b>建议迁移，这样可以保证旧数据不丢失，方便后续通过软件更好地追溯和管理。</html>",
                            "迁移数据确认",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE
                        );
                    }
                }
                
                // 如果目录不存在，自动创建
                if (!newOutputDir.exists()) {
                    newOutputDir.mkdirs();
                }
                
                // 迁移数据
                if (migrateConfirm == JOptionPane.YES_OPTION && oldDir != null && !oldDir.isEmpty()) {
                    File oldDirFile = new File(oldDir);
                    if (oldDirFile.exists() && oldDirFile.isDirectory() && !oldDir.equals(newOutputDir.getAbsolutePath())) {
                        try {
                            copyDirectory(oldDirFile, newOutputDir);
                            
                            // 更新历史记录中的文件路径
                            int updatedRecords = historyRepository.updateFilePaths(oldDir, newOutputDir.getAbsolutePath());
                            
                            // 询问是否删除旧数据
                            String message;
                            if (updatedRecords > 0) {
                                message = "数据已成功迁移到新目录！已更新 " + updatedRecords + " 条历史记录的文件路径。\n是否删除旧目录 " + oldDir + " 中的数据以释放空间？";
                            } else {
                                message = "数据已成功迁移到新目录！\n是否删除旧目录 " + oldDir + " 中的数据以释放空间？";
                            }
                            
                            int deleteConfirm = JOptionPane.showConfirmDialog(
                                this,
                                message,
                                "迁移成功",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.QUESTION_MESSAGE
                            );
                            
                            if (deleteConfirm == JOptionPane.YES_OPTION) {
                                deleteDirectory(oldDirFile);
                                JOptionPane.showMessageDialog(this, "旧数据已删除！", "成功", JOptionPane.INFORMATION_MESSAGE);
                            }
                        } catch (IOException ex) {
                            JOptionPane.showMessageDialog(this, "数据迁移失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                            LogManager.error("数据迁移失败", ex);
                        }
                    }
                }
                
                // 更新设置
                defaultOutputDirField.setText(newOutputDir.getAbsolutePath());
                settingsRepository.setDefaultOutputDirectory(newOutputDir.getAbsolutePath());
                JOptionPane.showMessageDialog(this, "默认输出目录已更新！", "提示", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        mainPanel.add(browseOutputDirButton, gbc);
        
        // 默认输出目录说明文本
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JTextArea outputDirInfoArea = new JTextArea("设置默认输出目录后，所有加密、解密、签名等操作的默认保存位置都将在此目录中。");
        outputDirInfoArea.setFont(app.theme.AppFonts.getSmallFont());
        outputDirInfoArea.setForeground(app.theme.ThemeManager.getSecondaryTextColor());
        outputDirInfoArea.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        outputDirInfoArea.setEditable(false);
        outputDirInfoArea.setBorder(null);
        outputDirInfoArea.setLineWrap(true);
        outputDirInfoArea.setWrapStyleWord(true);
        mainPanel.add(outputDirInfoArea, gbc);
        
        // 分隔线
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 3;
        JSeparator separator = new JSeparator();
        separator.setBackground(app.theme.ThemeManager.getBorderColor());
        mainPanel.add(separator, gbc);
        
        // === 数据目录设置 ===
        gbc.gridy++;
        JLabel dataDirSectionLabel = new JLabel("数据目录");
        dataDirSectionLabel.setFont(app.theme.AppFonts.getBoldFont());
        dataDirSectionLabel.setForeground(app.theme.ThemeManager.getTextColor());
        mainPanel.add(dataDirSectionLabel, gbc);
        
        gbc.gridy++;
        gbc.gridwidth = 2;
        gbc.gridx = 0;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        JTextField dataDirField = new JTextField();
        dataDirField.setFont(app.theme.AppFonts.getNormalFont());
        dataDirField.setText(core.storage.StorageManager.getDataDir());
        dataDirField.setBorder(ThemeManager.createRoundedBorder(1));
        dataDirField.putClientProperty("TextField.arc", 12);
        dataDirField.setEditable(false);
        mainPanel.add(dataDirField, gbc);
        
        gbc.gridx = 2;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        JButton browseDataDirButton = ThemeManager.createPrimaryButton("更改");
        browseDataDirButton.addActionListener(e -> {
            JFileChooser directoryChooser = new JFileChooser();
            directoryChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            directoryChooser.setDialogTitle("选择数据目录");
            
            setFontForAllComponents(directoryChooser, app.theme.AppFonts.getNormalFont());
            directoryChooser.setPreferredSize(new Dimension(1000, 700));
            directoryChooser.setMinimumSize(new Dimension(800, 600));
            
            String currentDir = dataDirField.getText();
            if (!currentDir.isEmpty()) {
                File dir = new File(currentDir);
                if (dir.exists() && dir.isDirectory()) {
                    directoryChooser.setCurrentDirectory(dir.getParentFile());
                }
            }
            
            int result = directoryChooser.showOpenDialog(this);
            if (result == JFileChooser.APPROVE_OPTION) {
                File selectedDir = directoryChooser.getSelectedFile();
                File newDataDir = new File(selectedDir, "data");
                
                // 询问是否迁移旧数据
                String oldDir = core.storage.StorageManager.getDataDir();
                int migrateConfirm = JOptionPane.YES_OPTION;
                if (oldDir != null && !oldDir.isEmpty() && !oldDir.equals(newDataDir.getAbsolutePath())) {
                    File oldDirFile = new File(oldDir);
                    if (oldDirFile.exists() && oldDirFile.isDirectory()) {
                        // 不使用 HTML，确保字体正确
                        String message = "是否要将旧数据目录 " + oldDir + " 中的数据迁移到新目录？\n\n" +
                            "建议：建议迁移，这样可以保证旧数据不丢失，方便后续通过软件更好地追溯和管理。";
                        
                        JTextArea messageArea = new JTextArea(message);
                        messageArea.setFont(app.theme.AppFonts.getNormalFont());
                        messageArea.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
                        messageArea.setForeground(app.theme.ThemeManager.getTextColor());
                        messageArea.setEditable(false);
                        messageArea.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
                        
                        migrateConfirm = JOptionPane.showConfirmDialog(
                            this,
                            messageArea,
                            "迁移数据确认",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE
                        );
                    }
                }
                
                // 如果目录不存在，自动创建
                if (!newDataDir.exists()) {
                    newDataDir.mkdirs();
                }
                
                // 迁移数据
                boolean migrate = migrateConfirm == JOptionPane.YES_OPTION;
                if (migrate && oldDir != null && !oldDir.isEmpty()) {
                    File oldDirFile = new File(oldDir);
                    if (oldDirFile.exists() && oldDirFile.isDirectory() && !oldDir.equals(newDataDir.getAbsolutePath())) {
                        try {
                            copyDirectory(oldDirFile, newDataDir);
                            
                            // 询问是否删除旧数据
                            int deleteConfirm = JOptionPane.showConfirmDialog(
                                this,
                                "数据已成功迁移到新目录！\n是否删除旧目录 " + oldDir + " 中的数据以释放空间？",
                                "迁移成功",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.QUESTION_MESSAGE
                            );
                            
                            if (deleteConfirm == JOptionPane.YES_OPTION) {
                                deleteDirectory(oldDirFile);
                                JOptionPane.showMessageDialog(this, "旧数据已删除！", "成功", JOptionPane.INFORMATION_MESSAGE);
                            }
                        } catch (IOException ex) {
                            JOptionPane.showMessageDialog(this, "迁移数据失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                            LogManager.error("迁移数据失败", ex);
                        }
                    }
                }
                
                // 更新设置
                dataDirField.setText(newDataDir.getAbsolutePath());
                settingsRepository.setDataDir(newDataDir.getAbsolutePath());
                core.util.RestartHelper.showRestartConfirmDialog(this, "数据目录已更新");
            }
        });
        mainPanel.add(browseDataDirButton, gbc);
        
        // 说明文本
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JTextArea infoArea = new JTextArea("设置数据目录后，所有密钥对、联系人、历史记录和日志文件都将存储在该目录中。\n更改需要重启应用程序才能生效。");
        infoArea.setFont(app.theme.AppFonts.getSmallFont());
        infoArea.setForeground(app.theme.ThemeManager.getSecondaryTextColor());
        infoArea.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        infoArea.setEditable(false);
        infoArea.setBorder(null);
        infoArea.setLineWrap(true);
        infoArea.setWrapStyleWord(true);
        mainPanel.add(infoArea, gbc);
        
        storageSettingsPanel.add(mainPanel, BorderLayout.NORTH);
        
        // 添加到内容面板
        contentPanel.add(storageSettingsPanel, "存储设置");
    }
    
    /**
     * 初始化文件处理页面：多文件默认打包 + 分卷大小。
     */
    private void initFileProcessingPanel() {
        JPanel fileProcessingPanel = new JPanel(new BorderLayout(10, 10));
        fileProcessingPanel.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        TitledBorder border = BorderFactory.createTitledBorder("文件处理");
        border.setTitleFont(app.theme.AppFonts.getBoldFont());
        fileProcessingPanel.setBorder(border);

        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridwidth = 3;

        JLabel sectionLabel = new JLabel("分卷大小");
        sectionLabel.setFont(app.theme.AppFonts.getBoldFont());
        sectionLabel.setForeground(app.theme.ThemeManager.getTextColor());
        mainPanel.add(sectionLabel, gbc);

        VolumeLimitPreset[] presets = VolumeLimitPreset.values();
        JRadioButton[] presetButtons = new JRadioButton[presets.length];
        ButtonGroup presetGroup = new ButtonGroup();
        JPanel presetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 0));
        presetRow.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());

        VolumeLimitPreset currentPreset = settingsRepository.getVolumeLimitPreset();
        for (int i = 0; i < presets.length; i++) {
            final VolumeLimitPreset preset = presets[i];
            JRadioButton radio = new JRadioButton(preset.getLabel());
            radio.setFont(app.theme.AppFonts.getNormalFont());
            radio.setForeground(app.theme.ThemeManager.getTextColor());
            radio.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
            radio.setSelected(preset == currentPreset);
            radio.addActionListener(e -> settingsRepository.setVolumeLimitPreset(preset));
            presetGroup.add(radio);
            presetRow.add(radio);
            presetButtons[i] = radio;
        }

        gbc.gridy++;
        mainPanel.add(presetRow, gbc);

        JTextArea hintArea = new JTextArea(
                "加密后的单个分卷不超过此大小；超过时自动拆成多卷。"
                        + "\n单文件与文件夹打包共用此设置，修改后立即生效。"
                        + "\n每卷最大 2 GB，最多 99 卷；默认 500 MB。");
        hintArea.setFont(app.theme.AppFonts.getSmallFont());
        hintArea.setForeground(app.theme.ThemeManager.getSecondaryTextColor());
        hintArea.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        hintArea.setEditable(false);
        hintArea.setBorder(null);
        hintArea.setLineWrap(true);
        hintArea.setWrapStyleWord(true);

        gbc.gridy++;
        mainPanel.add(hintArea, gbc);

        JCheckBox packageDefault = new JCheckBox("多个文件默认打包为一个加密包");
        packageDefault.setFont(app.theme.AppFonts.getNormalFont());
        packageDefault.setForeground(app.theme.ThemeManager.getTextColor());
        packageDefault.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        packageDefault.setSelected(settingsRepository.isPackageMultiFilesDefault());
        packageDefault.addActionListener(
                e -> settingsRepository.setPackageMultiFilesDefault(packageDefault.isSelected()));
        gbc.gridy++;
        mainPanel.add(packageDefault, gbc);

        JTextArea packageHint = new JTextArea(
                "只对 2 个及以上普通文件生效；文件夹仍各自打包。"
                        + "\n文件页会按此值预选，开始前仍可逐次取消。");
        packageHint.setFont(app.theme.AppFonts.getSmallFont());
        packageHint.setForeground(app.theme.ThemeManager.getSecondaryTextColor());
        packageHint.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        packageHint.setEditable(false);
        packageHint.setBorder(null);
        packageHint.setLineWrap(true);
        packageHint.setWrapStyleWord(true);
        gbc.gridy++;
        mainPanel.add(packageHint, gbc);

        JButton resetButton = ThemeManager.createPrimaryButton("恢复默认（500 MB）");
        resetButton.addActionListener(e -> {
            settingsRepository.setVolumeLimitPreset(VolumeLimitPreset.DEFAULT);
            for (int i = 0; i < presets.length; i++) {
                if (presets[i] == VolumeLimitPreset.DEFAULT) {
                    presetButtons[i].setSelected(true);
                }
            }
        });
        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actionRow.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        actionRow.add(resetButton);

        gbc.gridy++;
        mainPanel.add(actionRow, gbc);

        // ---------- 文件关联（仅当前用户，不需管理员） ----------
        JLabel assocLabel = new JLabel("文件关联");
        assocLabel.setFont(app.theme.AppFonts.getBoldFont());
        assocLabel.setForeground(app.theme.ThemeManager.getTextColor());
        gbc.gridy++;
        mainPanel.add(assocLabel, gbc);

        final JLabel assocStatus = new JLabel();
        assocStatus.setFont(app.theme.AppFonts.getSmallFont());
        assocStatus.setForeground(app.theme.ThemeManager.getSecondaryTextColor());
        gbc.gridy++;
        mainPanel.add(assocStatus, gbc);

        JButton assocOn = app.theme.ThemeManager.createPrimaryButton("关联 .aegix");
        JButton assocOff = app.theme.ThemeManager.createNeutralButton("取消关联");
        Runnable refreshAssoc = () -> {
            boolean supported = core.storage.FileAssociation.isSupported();
            boolean linked = supported && core.storage.FileAssociation.isAssociated();
            if (!supported) {
                assocStatus.setText("仅 Windows 支持文件关联");
            } else if (linked) {
                assocStatus.setText("已关联：双击 .aegix 会打开本程序并载入该文件");
            } else {
                assocStatus.setText("未关联：关联后双击 .aegix 即可直接打开（只写当前用户注册表）");
            }
            assocOn.setEnabled(supported && !linked);
            assocOff.setEnabled(linked);
        };
        assocOn.addActionListener(e -> {
            if (core.storage.FileAssociation.install()) {
                refreshAssoc.run();
            } else {
                JOptionPane.showMessageDialog(this, "关联失败，可查看日志后重试。",
                        "文件关联", JOptionPane.WARNING_MESSAGE);
            }
        });
        assocOff.addActionListener(e -> {
            if (core.storage.FileAssociation.uninstall()) {
                refreshAssoc.run();
            } else {
                JOptionPane.showMessageDialog(this, "取消关联失败，可查看日志后重试。",
                        "文件关联", JOptionPane.WARNING_MESSAGE);
            }
        });
        JPanel assocRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        assocRow.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        assocRow.add(assocOn);
        assocRow.add(assocOff);
        gbc.gridy++;
        mainPanel.add(assocRow, gbc);
        refreshAssoc.run();

        fileProcessingPanel.add(mainPanel, BorderLayout.NORTH);
        contentPanel.add(fileProcessingPanel, "文件处理");
    }

    /**
     * 递归设置容器中所有组件的字体
     * @param container 容器组件
     * @param font 要设置的字体
     */
    private void setFontForAllComponents(Container container, Font font) {
        for (Component component : container.getComponents()) {
            component.setFont(font);
            if (component instanceof Container) {
                setFontForAllComponents((Container) component, font);
            }
        }
    }
    
    /**
     * 初始化关于页面
     */
    private void initAboutPanel() {
        JPanel aboutPanel = new JPanel(new BorderLayout(10, 10));
        aboutPanel.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        TitledBorder border = BorderFactory.createTitledBorder("关于");
        border.setTitleFont(app.theme.AppFonts.getBoldFont());
        aboutPanel.setBorder(border);
        
        // 主滚动面板
        JScrollPane scrollPane = new JScrollPane();
        scrollPane.setBorder(null);
        scrollPane.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        scrollPane.getViewport().setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(16);
        
        JPanel contentContainer = new JPanel(new BorderLayout(10, 10));
        contentContainer.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        
        // 关于信息区域
        JPanel infoPanel = new JPanel(new GridBagLayout());
        infoPanel.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        infoPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        // 继续处理...
        
        // 基本信息
        JLabel titleLabel = new JLabel("非对称加密工具");
        titleLabel.setFont(app.theme.AppFonts.getBoldFont().deriveFont(Font.BOLD, 24f));
        titleLabel.setForeground(app.theme.ThemeManager.getTextColor());
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(10, 10, 20, 10);
        infoPanel.add(titleLabel, gbc);
        
        gbc.gridy++;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridwidth = 1;
        gbc.insets = new Insets(5, 10, 5, 10);
        
        JLabel versionLabel = new JLabel("版本: " + AppVersion.get());
        versionLabel.setFont(app.theme.AppFonts.getNormalFont());
        versionLabel.setForeground(app.theme.ThemeManager.getTextColor());
        infoPanel.add(versionLabel, gbc);
        
        gbc.gridy++;
        JLabel javaVersionLabel = new JLabel("Java 版本: " + System.getProperty("java.version"));
        javaVersionLabel.setFont(app.theme.AppFonts.getNormalFont());
        javaVersionLabel.setForeground(app.theme.ThemeManager.getTextColor());
        infoPanel.add(javaVersionLabel, gbc);
        
        gbc.gridy++;
        JLabel osLabel = new JLabel("操作系统: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        osLabel.setFont(app.theme.AppFonts.getNormalFont());
        osLabel.setForeground(app.theme.ThemeManager.getTextColor());
        infoPanel.add(osLabel, gbc);
        
        gbc.gridy++;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JSeparator aboutSeparator = new JSeparator();
        aboutSeparator.setBackground(app.theme.ThemeManager.getBorderColor());
        infoPanel.add(aboutSeparator, gbc);
        
        gbc.gridy++;
        gbc.gridwidth = 2;
        JTextArea descriptionArea = new JTextArea(
            "本工具提供基于 RSA 非对称加密算法的加密、解密、签名和验证功能。\n" +
            "支持大文件分卷加密、密钥对管理、通讯录管理等实用功能。\n\n" +
            "主要特性：\n" +
            "• 非对称加密（RSA）\n" +
            "• 数字签名与验证\n" +
            "• 大文件分卷加密\n" +
            "• 密钥对管理\n" +
            "• 通讯录管理\n" +
            "• 操作历史记录\n" +
            "• 隐写功能\n" +
            "• 多主题支持"
        );
        descriptionArea.setFont(app.theme.AppFonts.getNormalFont());
        descriptionArea.setForeground(app.theme.ThemeManager.getTextColor());
        descriptionArea.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        descriptionArea.setEditable(false);
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        descriptionArea.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));
        infoPanel.add(descriptionArea, gbc);

        gbc.gridy++;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JSeparator linksSeparator = new JSeparator();
        linksSeparator.setBackground(app.theme.ThemeManager.getBorderColor());
        infoPanel.add(linksSeparator, gbc);

        gbc.gridy++;
        JTextArea updateHintArea = new JTextArea(
            "新版本发布在官网 pasokontukau.github.io 与 GitHub Releases。\n" +
            "「打开官网」与「查看最新版本」用系统浏览器打开；程序默认不联网，\n" +
            "开启下面的开关才能在这里直接检查。"
        );
        updateHintArea.setFont(app.theme.AppFonts.getSmallFont());
        updateHintArea.setForeground(app.theme.ThemeManager.getSecondaryTextColor());
        updateHintArea.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        updateHintArea.setEditable(false);
        updateHintArea.setBorder(null);
        updateHintArea.setLineWrap(true);
        updateHintArea.setWrapStyleWord(true);
        infoPanel.add(updateHintArea, gbc);

        gbc.gridy++;
        JPanel linkRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        linkRow.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        JButton siteButton = ThemeManager.createPrimaryButton("打开官网");
        siteButton.addActionListener(e -> openExternalLink(SITE_URL));
        JButton updateButton = ThemeManager.createNeutralButton("查看最新版本");
        updateButton.addActionListener(e -> openExternalLink(LATEST_RELEASE_URL));
        linkRow.add(siteButton);
        linkRow.add(updateButton);
        infoPanel.add(linkRow, gbc);

        gbc.gridy++;
        JCheckBox updateCheckBox = new JCheckBox("允许检查更新（联网一次 GitHub）");
        updateCheckBox.setFont(app.theme.AppFonts.getNormalFont());
        updateCheckBox.setForeground(app.theme.ThemeManager.getTextColor());
        updateCheckBox.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        updateCheckBox.setSelected(settingsRepository.isUpdateCheckEnabled());
        infoPanel.add(updateCheckBox, gbc);

        gbc.gridy++;
        JPanel checkRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        checkRow.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        JButton checkButton = ThemeManager.createNeutralButton("检查更新");
        checkButton.setEnabled(updateCheckBox.isSelected());
        JLabel checkStatus = new JLabel(" ");
        checkStatus.setFont(app.theme.AppFonts.getSmallFont());
        checkStatus.setForeground(app.theme.ThemeManager.getSecondaryTextColor());
        checkButton.addActionListener(e -> startUpdateCheck(checkButton, checkStatus, updateCheckBox));
        updateCheckBox.addActionListener(e -> {
            settingsRepository.setUpdateCheckEnabled(updateCheckBox.isSelected());
            checkButton.setEnabled(updateCheckBox.isSelected() && !updateCheckRunning);
            // 与 Android 一致：只要动了开关，上一次的结论就不再适应当前设置。
            checkStatus.setText(" ");
        });
        checkRow.add(checkButton);
        checkRow.add(checkStatus);
        infoPanel.add(checkRow, gbc);
        
        contentContainer.add(infoPanel, BorderLayout.NORTH);
        scrollPane.setViewportView(contentContainer);
        aboutPanel.add(scrollPane, BorderLayout.CENTER);
        
        // 添加到内容面板
        contentPanel.add(aboutPanel, "关于");
    }

    /** 后台查 GitHub 最新版本，只提示不下载；失败也只提示，不影响其他功能。 */
    private void startUpdateCheck(JButton button, JLabel statusLabel, JCheckBox toggle) {
        updateCheckRunning = true;
        button.setEnabled(false);
        statusLabel.setText("检查中…");
        new javax.swing.SwingWorker<UpdateCheck.Result, Void>() {
            @Override
            protected UpdateCheck.Result doInBackground() {
                return UpdateCheck.check(AppVersion.get());
            }

            @Override
            protected void done() {
                updateCheckRunning = false;
                // 检查期间用户可能已经关了开关，此时不该把按钮重新点亮。
                button.setEnabled(toggle.isSelected());
                UpdateCheck.Result result;
                try {
                    result = get();
                } catch (InterruptedException ex) {
                    // 程序退出或线程被中断：与网络失败同一个结论，不猜具体原因。
                    Thread.currentThread().interrupt();
                    result = null;
                } catch (ExecutionException ex) {
                    result = null;
                }
                statusLabel.setText(updateStatusText(result));
            }
        }.execute();
    }

    /** 把检查结果译成一行状态；null（取不到结果）与 FAILED 用同一句话。 */
    private static String updateStatusText(UpdateCheck.Result result) {
        if (result == null) {
            return UPDATE_CHECK_FAILED;
        }
        switch (result.status) {
            case NEWER:
                return "有新版本 " + result.version + "——不会自动下载，用「查看最新版本」";
            case UP_TO_DATE:
                return "已是最新版本（" + result.version + "）";
            default:
                return UPDATE_CHECK_FAILED;
        }
    }

    /**
     * 用系统浏览器打开链接；没有桌面环境或默认浏览器时把地址显示出来，让用户自己打开。
     *
     * 这里不联网：程序唯一的网络请求是「检查更新」（`app.UpdateCheck`），本方法只交给浏览器。
     */
    private void openExternalLink(String url) {
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception ex) {
            // 无桌面 API（HeadlessException）、无默认浏览器（IOException）或地址非法都落在这里。
            JOptionPane.showMessageDialog(this, "无法打开浏览器，请手动访问：\n" + url,
                    "打开链接失败", JOptionPane.INFORMATION_MESSAGE);
        }
    }
    
    /**
     * 初始化开发者选项页面
     */
    private void initDeveloperOptionsPanel() {
        JPanel devPanel = new JPanel(new BorderLayout(10, 10));
        devPanel.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        TitledBorder border = BorderFactory.createTitledBorder("开发者选项");
        border.setTitleFont(app.theme.AppFonts.getBoldFont());
        devPanel.setBorder(border);
        
        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        // 导出设置按钮
        JLabel exportSectionLabel = new JLabel("配置导出/导入");
        exportSectionLabel.setFont(app.theme.AppFonts.getBoldFont());
        exportSectionLabel.setForeground(app.theme.ThemeManager.getTextColor());
        gbc.gridwidth = 2;
        mainPanel.add(exportSectionLabel, gbc);
        
        gbc.gridy++;
        gbc.gridwidth = 1;
        JButton exportButton = ThemeManager.createPrimaryButton("导出设置（加密）");
        exportButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("导出设置文件（加密）");
            fileChooser.setSelectedFile(new File("settings.aexp"));
            setFontForAllComponents(fileChooser, app.theme.AppFonts.getNormalFont());
            
            int result = fileChooser.showSaveDialog(this);
            if (result == JFileChooser.APPROVE_OPTION) {
                File exportFile = fileChooser.getSelectedFile();
                char[] password = ExportPasswordDialog.ask(this, "导出设置", true);
                if (password == null) {
                    return;
                }
                try {
                    settingsRepository.exportSettings(exportFile, password);
                    JOptionPane.showMessageDialog(this, "设置已加密导出到: " + exportFile.getAbsolutePath(), "成功", JOptionPane.INFORMATION_MESSAGE);
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this, "导出设置失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    LogManager.error("导出设置失败", ex);
                } finally {
                    java.util.Arrays.fill(password, '\0');
                }
            }
        });
        mainPanel.add(exportButton, gbc);
        
        gbc.gridx = 1;
        JButton importButton = ThemeManager.createPrimaryButton("导入设置");
        importButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("选择设置文件");
            setFontForAllComponents(fileChooser, app.theme.AppFonts.getNormalFont());
            
            int result = fileChooser.showOpenDialog(this);
            if (result == JFileChooser.APPROVE_OPTION) {
                File importFile = fileChooser.getSelectedFile();
                char[] password = null;
                try {
                    if (settingsRepository.isEncryptedExport(importFile)) {
                        password = ExportPasswordDialog.ask(this, "导入设置", false);
                        if (password == null) {
                            return;
                        }
                    }
                    settingsRepository.importSettings(importFile, password);
                    core.util.RestartHelper.showRestartConfirmDialog(this, "设置导入成功");
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this, ex.getMessage(), "导入失败", JOptionPane.ERROR_MESSAGE);
                } finally {
                    if (password != null) {
                        java.util.Arrays.fill(password, '\0');
                    }
                }
            }
        });
        mainPanel.add(importButton, gbc);
        
        // 清除所有数据按钮
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JSeparator devSeparator = new JSeparator();
        devSeparator.setBackground(app.theme.ThemeManager.getBorderColor());
        mainPanel.add(devSeparator, gbc);
        
        gbc.gridy++;
        gbc.gridwidth = 2;
        JLabel dangerSectionLabel = new JLabel("危险操作");
        dangerSectionLabel.setFont(app.theme.AppFonts.getBoldFont());
        dangerSectionLabel.setForeground(Color.RED);
        mainPanel.add(dangerSectionLabel, gbc);
        
        gbc.gridy++;
        gbc.gridwidth = 1;
        JButton clearAllButton = ThemeManager.createDangerButton("清除所有数据");
        clearAllButton.addActionListener(e -> {
            JTextArea messageArea = new JTextArea(
                "警告：此操作将删除所有密钥对、联系人、历史记录、设置和日志文件！\n\n" +
                "此操作不可恢复。在继续之前，程序会自动备份当前数据目录。\n\n" +
                "确定要继续吗？"
            );
            messageArea.setFont(app.theme.AppFonts.getNormalFont());
            messageArea.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
            messageArea.setForeground(Color.RED);
            messageArea.setEditable(false);
            messageArea.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            
            int confirm = JOptionPane.showConfirmDialog(
                this,
                messageArea,
                "危险操作确认",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            );
            
            if (confirm == JOptionPane.YES_OPTION) {
                try {
                    settingsRepository.clearAllData(true);
                    core.util.RestartHelper.showRestartConfirmDialog(this, "所有数据已清除");
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "清除数据失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    LogManager.error("清除数据失败", ex);
                }
            }
        });
        mainPanel.add(clearAllButton, gbc);
        
        devPanel.add(mainPanel, BorderLayout.NORTH);
        
        // 添加到内容面板
        contentPanel.add(devPanel, "开发者选项");
    }
    
    // 辅助方法：复制目录
    private static void copyDirectory(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists()) {
                target.mkdirs();
            }
            File[] files = source.listFiles();
            if (files != null) {
                for (File file : files) {
                    copyDirectory(file, new File(target, file.getName()));
                }
            }
        } else {
            Files.copy(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
    
    // 辅助方法：删除目录
    private static void deleteDirectory(File dir) throws IOException {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        Files.deleteIfExists(dir.toPath());
    }
}
