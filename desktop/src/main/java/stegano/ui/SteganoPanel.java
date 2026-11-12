package stegano.ui;

import app.theme.AppFonts;
import app.theme.ThemeManager;
import app.theme.UIConstants;
import core.event.PasswordCacheClearListener;
import core.util.LogManager;
import crypto.key.KeyPairManager;
import keymanagement.model.KeyPairInfo;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import keymanagement.storage.KeychainStorage;
import keymanagement.storage.KeyPairStorage;
import securecomm.ui.SecureTextPanel;
import settings.storage.SettingsStorage;
import stegano.service.SteganoCore;

public class SteganoPanel extends JPanel {

    // --- 状态 ---
    private enum Mode { EMBED, EXTRACT }
    private Mode currentMode = Mode.EMBED;

    // 载体相关
    private byte[] carrierData;
    private String carrierFileName;
    private BufferedImage carrierPreview;

    // --- UI 组件 ---
    private CardLayout modeCardLayout;
    private JPanel modePanel;
    private JToggleButton btnEmbed;
    private JToggleButton btnExtract;
    private ButtonGroup modeGroup;

    // 载体区域
    private JLabel carrierPreviewLabel;
    private JLabel carrierInfoLabel;
    private JButton btnSelectCarrier;
    private JLabel capacityLabel;

    // 密钥对选择（安全等级和密码缓存由 PrivateKeyAccessService 统一管理）
    private JComboBox<String> keyPairCombo;
    private JLabel keyPairHintLabel;
    private String currentKeyPairName; // 仅用于 UI：记住当前选中的密钥对名称

    // 嵌入模式组件
    private JTabbedPane payloadTypeTabs;
    private JTextArea textPayloadArea;
    private JLabel filePayloadPath;
    private JButton btnSelectPayloadFile;
    private byte[] filePayloadData;
    private JLabel payloadSizeLabel;
    private JProgressBar capacityBar;

    // 提取模式组件
    private JTextArea extractResultArea;
    private JButton btnSaveExtract;

    // 外部载荷（来自 SecureTextPanel 右键发送到隐写工具）
    private byte[] externalPayload;
    private String externalPayloadName;

    // 操作按钮
    private JButton btnExecute;
    private JProgressBar progressBar;

    // 嵌入率
    private JLabel embedRateLabel;
    private JComboBox<String> embedRateCombo;

    public SteganoPanel() {
        initComponents();
        setupDragDrop();
        refreshKeyPairList();
        // 注意：安全等级策略和密码缓存由 PrivateKeyAccessService 统一管理，
        // 无需在此处注册监听器。
    }

    private void initComponents() {
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(15, 20, 15, 20));
        setBackground(ThemeManager.getBackgroundColor());

        // === 顶部: 模式切换 + 载体区域 ===
        JPanel topPanel = new JPanel(new BorderLayout(10, 10));
        topPanel.setOpaque(false);

        // 模式切换按钮组
        JPanel modeSwitchPanel = createModeSwitch();
        topPanel.add(modeSwitchPanel, BorderLayout.NORTH);

        // 主内容面板（模式卡片 + 操作区）
        JPanel contentPanel = new JPanel(new BorderLayout(10, 10));
        contentPanel.setOpaque(false);

        // 载体 + 密码区
        JPanel carrierAndPassword = new JPanel();
        carrierAndPassword.setLayout(new BoxLayout(carrierAndPassword, BoxLayout.Y_AXIS));
        carrierAndPassword.setOpaque(false);

        carrierAndPassword.add(createCarrierPanel());
        carrierAndPassword.add(Box.createVerticalStrut(12));
        carrierAndPassword.add(createKeyPanel());
        carrierAndPassword.add(Box.createVerticalStrut(12));
        carrierAndPassword.add(createEmbedRatePanel());

        contentPanel.add(carrierAndPassword, BorderLayout.NORTH);

        // 模式卡片区域
        modeCardLayout = new CardLayout();
        modePanel = new JPanel(modeCardLayout);
        modePanel.setOpaque(false);
        modePanel.add(createEmbedPanel(), "EMBED");
        modePanel.add(createExtractPanel(), "EXTRACT");
        contentPanel.add(modePanel, BorderLayout.CENTER);

        // 底部: 执行按钮 + 进度条
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 10));
        bottomPanel.setOpaque(false);

        btnExecute = new JButton("执行嵌入");
        btnExecute.setFont(AppFonts.getButtonFont());
        btnExecute.setBackground(UIConstants.PRIMARY_BUTTON_COLOR);
        btnExecute.setForeground(Color.WHITE);
        btnExecute.setFocusPainted(false);
        btnExecute.addActionListener(e -> execute());

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);

        JPanel execPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        execPanel.setOpaque(false);
        execPanel.add(btnExecute);

        bottomPanel.add(execPanel, BorderLayout.NORTH);
        bottomPanel.add(progressBar, BorderLayout.CENTER);

        contentPanel.add(bottomPanel, BorderLayout.SOUTH);
        topPanel.add(contentPanel, BorderLayout.CENTER);

        add(topPanel, BorderLayout.CENTER);
    }

    // ============================================================
    // 模式切换
    // ============================================================
    private JPanel createModeSwitch() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        panel.setOpaque(false);

        btnEmbed = new JToggleButton("嵌入");
        btnExtract = new JToggleButton("提取");

        Dimension btnSize = new Dimension(140, 36);
        for (JToggleButton btn : new JToggleButton[]{btnEmbed, btnExtract}) {
            btn.setPreferredSize(btnSize);
            btn.setFont(AppFonts.getBoldFont());
            btn.setFocusPainted(false);
        }

        modeGroup = new ButtonGroup();
        modeGroup.add(btnEmbed);
        modeGroup.add(btnExtract);

        btnEmbed.setSelected(true);
        updateModeButtonStyle();

        btnEmbed.addActionListener(e -> {
            currentMode = Mode.EMBED;
            updateModeButtonStyle();
            modeCardLayout.show(modePanel, "EMBED");
            btnExecute.setText("执行嵌入");
            updateCapacityDisplay();
        });

        btnExtract.addActionListener(e -> {
            currentMode = Mode.EXTRACT;
            updateModeButtonStyle();
            modeCardLayout.show(modePanel, "EXTRACT");
            btnExecute.setText("执行提取");
        });

        panel.add(btnEmbed);
        panel.add(btnExtract);
        return panel;
    }

    private void updateModeButtonStyle() {
        for (JToggleButton btn : new JToggleButton[]{btnEmbed, btnExtract}) {
            if (btn.isSelected()) {
                btn.setBackground(UIConstants.PRIMARY_BUTTON_COLOR);
                btn.setForeground(Color.WHITE);
                btn.setBorder(BorderFactory.createEmptyBorder(4, 16, 4, 16));
            } else {
                btn.setBackground(ThemeManager.getBackgroundColor());
                btn.setForeground(ThemeManager.getTextColor());
                btn.setBorder(BorderFactory.createLineBorder(ThemeManager.getBorderColor(), 1));
            }
        }
    }

    // ============================================================
    // 载体区域
    // ============================================================
    private JPanel createCarrierPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(ThemeManager.getBorderColor()),
                "载体图片", TitledBorder.LEFT, TitledBorder.TOP,
                AppFonts.getBoldFont()));

        // 预览区域
        carrierPreviewLabel = new JLabel("拖入 PNG/BMP 图片或点击选择", SwingConstants.CENTER);
        carrierPreviewLabel.setPreferredSize(new Dimension(300, 180));
        carrierPreviewLabel.setMinimumSize(new Dimension(200, 120));
        carrierPreviewLabel.setBorder(BorderFactory.createDashedBorder(
                ThemeManager.getBorderColor(), 3, 2));
        carrierPreviewLabel.setOpaque(true);
        carrierPreviewLabel.setBackground(ThemeManager.getBackgroundColor());
        carrierPreviewLabel.setForeground(ThemeManager.getSecondaryTextColor());
        carrierPreviewLabel.setFont(AppFonts.getNormalFont());

        // 点击选择
        carrierPreviewLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                chooseCarrierFile();
            }
        });
        carrierPreviewLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        infoPanel.setOpaque(false);
        carrierInfoLabel = new JLabel("未选择载体");
        carrierInfoLabel.setFont(AppFonts.getNormalFont());
        capacityLabel = new JLabel("");
        capacityLabel.setFont(AppFonts.getNormalFont());
        infoPanel.add(carrierInfoLabel);
        infoPanel.add(capacityLabel);

        btnSelectCarrier = new JButton("选择图片");
        btnSelectCarrier.setFont(AppFonts.getButtonFont());
        btnSelectCarrier.addActionListener(e -> chooseCarrierFile());

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnPanel.setOpaque(false);
        btnPanel.add(btnSelectCarrier);

        panel.add(carrierPreviewLabel, BorderLayout.CENTER);
        panel.add(infoPanel, BorderLayout.SOUTH);
        panel.add(btnPanel, BorderLayout.EAST);
        return panel;
    }

    private void chooseCarrierFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter(
                "图片文件 (*.png, *.bmp, *.jpg, *.jpeg)",
                "png", "bmp", "jpg", "jpeg"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            try {
                loadCarrier(f);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "无法加载图片: " + ex.getMessage(),
                        "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void loadCarrier(File file) throws IOException {
        carrierData = Files.readAllBytes(file.toPath());
        carrierFileName = file.getName();

        // 生成预览缩略图
        BufferedImage full = ImageIO.read(new ByteArrayInputStream(carrierData));
        if (full == null) throw new IOException("无法解析图片");

        int previewW = 300, previewH = 180;
        double scale = Math.min((double) previewW / full.getWidth(),
                (double) previewH / full.getHeight());
        int w = (int) (full.getWidth() * scale);
        int h = (int) (full.getHeight() * scale);
        Image scaled = full.getScaledInstance(w, h, Image.SCALE_SMOOTH);
        carrierPreview = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        carrierPreview.getGraphics().drawImage(scaled, 0, 0, null);

        carrierPreviewLabel.setIcon(new ImageIcon(carrierPreview));
        carrierPreviewLabel.setText("");
        carrierPreviewLabel.setBorder(null);

        carrierInfoLabel.setText(String.format("%s (%d×%d, %.1f KB)",
                carrierFileName, full.getWidth(), full.getHeight(),
                carrierData.length / 1024.0));
        updateCapacityDisplay();
    }

    private void updateCapacityDisplay() {
        if (carrierData != null) {
            double rate = getSelectedEmbedRate();
            long cap = SteganoCore.calculateCapacity(carrierData, carrierFileName, rate);
            capacityLabel.setText(String.format("| 容量: %s", formatSize(cap)));
        }
    }

    // ============================================================
    // 密钥对选择区域（替代独立密码框）
    // 使用全局安全等级机制：便捷模式 → 系统密钥链；平衡模式 → 内存缓存；严格模式 → 每次输入
    // ============================================================
    private JPanel createKeyPanel() {
        JPanel outer = new JPanel(new BorderLayout(8, 5));
        outer.setOpaque(false);
        outer.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(ThemeManager.getBorderColor()),
                "密钥对", TitledBorder.LEFT, TitledBorder.TOP,
                AppFonts.getBoldFont()));

        // 标签 + 下拉框
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);

        keyPairCombo = new JComboBox<>();
        keyPairCombo.setFont(AppFonts.getNormalFont());
        keyPairCombo.addActionListener(e -> updateKeyPairHintLabel());

        // 提示文字
        keyPairHintLabel = new JLabel("选择一个密钥对用于嵌入/提取保护");
        keyPairHintLabel.setFont(AppFonts.getNormalFont());
        keyPairHintLabel.setForeground(ThemeManager.getSecondaryTextColor());

        // 刷新按钮
        JButton btnRefresh = new JButton("刷新");
        btnRefresh.setFont(AppFonts.getNormalFont());
        btnRefresh.addActionListener(e -> refreshKeyPairList());

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        top.add(keyPairCombo, BorderLayout.CENTER);
        top.add(btnRefresh, BorderLayout.EAST);

        row.add(top, BorderLayout.CENTER);
        row.add(keyPairHintLabel, BorderLayout.SOUTH);
        outer.add(row, BorderLayout.CENTER);

        return outer;
    }

    /**
     * 刷新密钥对列表：从 KeyPairStorage 读取密钥对名称并填充下拉框。
     * 仅用于显示名称，具体密码输入在执行时通过安全等级机制获取。
     */
    private void refreshKeyPairList() {
        try {
            java.util.List<KeyPairInfo> pairs =
                    keymanagement.storage.KeyPairStorage.loadAllKeyPairs();
            keyPairCombo.removeAllItems();
            if (pairs.isEmpty()) {
                keyPairHintLabel.setText("(尚未创建密钥对，请先在「密钥对管理」中创建)");
                return;
            }
            for (KeyPairInfo pair : pairs) {
                keyPairCombo.addItem(pair.getName() + " (" + pair.getKeySize() + "-bit RSA)");
            }
            // 尝试恢复上次选中的密钥对
            if (currentKeyPairName != null) {
                for (int i = 0; i < keyPairCombo.getItemCount(); i++) {
                    String item = keyPairCombo.getItemAt(i);
                    if (item != null && item.startsWith(currentKeyPairName + " ")) {
                        keyPairCombo.setSelectedIndex(i);
                        break;
                    }
                }
            }
            updateKeyPairHintLabel();
        } catch (Exception ex) {
            keyPairHintLabel.setText("读取密钥对失败: " + ex.getMessage());
        }
    }

    /**
     * 根据当前选中的密钥对名称更新提示文字。
     */
    private void updateKeyPairHintLabel() {
        String sel = (String) keyPairCombo.getSelectedItem();
        if (sel == null) {
            keyPairHintLabel.setText("(尚未选择密钥对)");
            return;
        }
        // 从显示文本中提取真实密钥对名称（去掉 " (XXXX-bit RSA)" 后缀）
        String name = sel.replaceAll(" \\(\\d+-bit RSA\\)$", "");
        if (!name.equals(currentKeyPairName)) {
            // 切换密钥对 → PrivateKeyAccessService 内部用 keyPairName 区分缓存，无需手动清理
            currentKeyPairName = name;
        }
        String level = settings.storage.SettingsStorage.getSecurityLevel();
        String levelText;
        if (settings.storage.SettingsStorage.SECURITY_LEVEL_CONVENIENT.equals(level)) {
            levelText = "便捷模式（自动读取系统密钥链）";
        } else if (settings.storage.SettingsStorage.SECURITY_LEVEL_BALANCED.equals(level)) {
            levelText = "平衡模式（临时内存缓存）";
        } else {
            levelText = "严格模式（每次执行都需要输入密码）";
        }
        keyPairHintLabel.setText("当前密钥对: " + name + "  |  安全等级: " + levelText);
    }

    // ============================================================
    // 嵌入率选择
    // ============================================================
    private JPanel createEmbedRatePanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        panel.setOpaque(false);

        embedRateLabel = new JLabel("嵌入率:");
        embedRateLabel.setFont(AppFonts.getNormalFont());

        embedRateCombo = new JComboBox<>(new String[]{"10%", "20% (推荐)", "25%", "30%"});
        embedRateCombo.setFont(AppFonts.getNormalFont());
        embedRateCombo.setSelectedIndex(1);
        embedRateCombo.addActionListener(e -> updateCapacityDisplay());

        panel.add(embedRateLabel);
        panel.add(embedRateCombo);
        return panel;
    }

    private double getSelectedEmbedRate() {
        String sel = (String) embedRateCombo.getSelectedItem();
        if (sel == null) return 0.20;
        return Integer.parseInt(sel.replaceAll("[^0-9]", "")) / 100.0;
    }

    // ============================================================
    // 嵌入模式面板
    // ============================================================
    private JPanel createEmbedPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setOpaque(false);

        // 载荷类型选项卡
        payloadTypeTabs = new JTabbedPane();
        payloadTypeTabs.setFont(AppFonts.getBoldFont());

        // 文本选项卡
        JPanel textPanel = new JPanel(new BorderLayout());
        textPanel.setOpaque(false);
        textPayloadArea = new JTextArea(6, 30);
        textPayloadArea.setFont(AppFonts.getNormalFont());
        textPayloadArea.setLineWrap(true);
        textPayloadArea.setWrapStyleWord(true);
        textPayloadArea.setBorder(BorderFactory.createLineBorder(ThemeManager.getBorderColor()));
        textPayloadArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { clearExternalPayloadIfSet(); updatePayloadSize(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { clearExternalPayloadIfSet(); updatePayloadSize(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { clearExternalPayloadIfSet(); updatePayloadSize(); }
        });
        textPanel.add(new JScrollPane(textPayloadArea), BorderLayout.CENTER);

        // 文件选项卡
        JPanel filePanel = new JPanel(new BorderLayout(8, 8));
        filePanel.setOpaque(false);
        filePayloadPath = new JLabel("未选择文件");
        filePayloadPath.setFont(AppFonts.getNormalFont());
        btnSelectPayloadFile = new JButton("选择文件");
        btnSelectPayloadFile.setFont(AppFonts.getButtonFont());
        btnSelectPayloadFile.addActionListener(e -> choosePayloadFile());
        JPanel fileTop = new JPanel(new BorderLayout(8, 0));
        fileTop.setOpaque(false);
        fileTop.add(filePayloadPath, BorderLayout.CENTER);
        fileTop.add(btnSelectPayloadFile, BorderLayout.EAST);
        filePanel.add(fileTop, BorderLayout.NORTH);

        payloadTypeTabs.addTab("文本输入", textPanel);
        payloadTypeTabs.addTab("文件选择", filePanel);

        // 载荷大小与容量条
        JPanel sizePanel = new JPanel(new BorderLayout(8, 4));
        sizePanel.setOpaque(false);
        payloadSizeLabel = new JLabel("载荷: 0 B");
        payloadSizeLabel.setFont(AppFonts.getNormalFont());
        capacityBar = new JProgressBar(0, 100);
        capacityBar.setStringPainted(true);
        capacityBar.setFont(AppFonts.getNormalFont());
        sizePanel.add(payloadSizeLabel, BorderLayout.NORTH);
        sizePanel.add(capacityBar, BorderLayout.CENTER);

        panel.add(payloadTypeTabs, BorderLayout.CENTER);
        panel.add(sizePanel, BorderLayout.SOUTH);
        return panel;
    }

    private void choosePayloadFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            try {
                filePayloadData = Files.readAllBytes(f.toPath());
                filePayloadPath.setText(f.getName() + " (" + formatSize(filePayloadData.length) + ")");
                // 用户手动选择文件 → 放弃之前的外部载荷，避免混合使用
                clearExternalPayloadIfSet();
                updatePayloadSize();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "无法读取文件: " + ex.getMessage(),
                        "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void updatePayloadSize() {
        long size = getPayloadSize();
        if (externalPayload != null) {
            payloadSizeLabel.setText("外部载荷: " + (externalPayloadName == null ? "(加密数据)" : externalPayloadName)
                    + " (" + formatSize(size) + ")");
            payloadSizeLabel.setForeground(new Color(255, 140, 0));
        } else {
            payloadSizeLabel.setText("载荷: " + formatSize(size));
            payloadSizeLabel.setForeground(ThemeManager.getTextColor());
        }

        if (carrierData != null && size > 0) {
            double rate = getSelectedEmbedRate();
            long cap = SteganoCore.calculateCapacity(carrierData, carrierFileName, rate);
            int pct = cap > 0 ? (int) (100L * size / cap) : 0;
            pct = Math.min(pct, 100);
            capacityBar.setValue(pct);
            capacityBar.setString(pct + "%");
            capacityBar.setForeground(pct > 90 ? UIConstants.DANGER_BUTTON_COLOR :
                    pct > 70 ? new Color(255, 165, 0) : UIConstants.SUCCESS_TEXT_COLOR);
            capacityBar.setVisible(true);
        } else {
            capacityBar.setValue(0);
            capacityBar.setString("");
            capacityBar.setVisible(false);
        }
    }

    private long getPayloadSize() {
        // 优先使用外部载荷（来自 SecureTextPanel "发送到隐写工具"）
        if (externalPayload != null) {
            return externalPayload.length;
        }
        if (payloadTypeTabs.getSelectedIndex() == 0) {
            String text = textPayloadArea.getText();
            return text.isEmpty() ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
        } else {
            return filePayloadData != null ? filePayloadData.length : 0;
        }
    }

    private byte[] getPayload() {
        // 优先使用外部载荷
        if (externalPayload != null) {
            return externalPayload;
        }
        if (payloadTypeTabs.getSelectedIndex() == 0) {
            String text = textPayloadArea.getText();
            if (text.isEmpty()) return null;
            return text.getBytes(StandardCharsets.UTF_8);
        } else {
            return filePayloadData;
        }
    }

    /**
     * 接受来自 SecureTextPanel 的外部载荷并自动填充。
     * 由外部调用方（如 SecureTextPanel 右键菜单）触发。
     *
     * @param payload       载荷字节（文本/加密数据等）
     * @param payloadName   载荷描述（用于在 UI 上提示用户）
     * @param keyPairName   建议使用的密钥对名称（可为 null）
     */
    public void loadExternalPayload(byte[] payload, String payloadName, String keyPairName) {
        // 若已有外部载荷，向用户确认是否替换
        if (externalPayload != null) {
            int confirm = javax.swing.JOptionPane.showConfirmDialog(this,
                    "当前已存在外部载荷（" + (externalPayloadName == null ? "(加密数据)" : externalPayloadName)
                            + "），是否替换为新的？",
                    "替换外部载荷", javax.swing.JOptionPane.YES_NO_OPTION,
                    javax.swing.JOptionPane.QUESTION_MESSAGE);
            if (confirm != javax.swing.JOptionPane.YES_OPTION) {
                return;
            }
        }
        this.externalPayload = payload;
        this.externalPayloadName = payloadName;
        // 尝试设置为指定密钥对
        if (keyPairName != null) {
            for (int i = 0; i < keyPairCombo.getItemCount(); i++) {
                String item = keyPairCombo.getItemAt(i);
                if (item != null && item.startsWith(keyPairName + " ")) {
                    keyPairCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
        // 视觉提示 + 自动切换到嵌入模式（不模拟按钮点击，避免按钮闪烁）
        updatePayloadSize();
        switchToEmbedMode();
    }

    /**
     * 清理外部载荷标记（用户开始手动输入文本 / 手动选择文件时调用）。
     */
    private void clearExternalPayloadIfSet() {
        if (externalPayload != null) {
            externalPayload = null;
            externalPayloadName = null;
        }
    }

    /**
     * 直接切换到嵌入模式，避免调用 btnEmbed.doClick() 导致的按钮闪烁。
     */
    private void switchToEmbedMode() {
        currentMode = Mode.EMBED;
        if (btnEmbed != null && !btnEmbed.isSelected()) {
            btnEmbed.setSelected(true);
        }
        updateModeButtonStyle();
        if (modeCardLayout != null && modePanel != null) {
            modeCardLayout.show(modePanel, "EMBED");
        }
        if (btnExecute != null) {
            btnExecute.setText("执行嵌入");
        }
        updateCapacityDisplay();
    }

    // ============================================================
    // 提取模式面板
    // ============================================================
    private JPanel createExtractPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setOpaque(false);

        JLabel hint = new JLabel("提取结果将显示在下方。文本自动显示，文件可以保存。");
        hint.setFont(AppFonts.getNormalFont());

        extractResultArea = new JTextArea(10, 30);
        extractResultArea.setFont(AppFonts.getNormalFont());
        extractResultArea.setEditable(false);
        extractResultArea.setLineWrap(true);
        extractResultArea.setWrapStyleWord(true);
        extractResultArea.setBorder(BorderFactory.createLineBorder(ThemeManager.getBorderColor()));

        btnSaveExtract = new JButton("保存为文件");
        btnSaveExtract.setFont(AppFonts.getButtonFont());
        btnSaveExtract.setVisible(false);
        btnSaveExtract.addActionListener(e -> saveExtractedData());

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER));
        bottom.setOpaque(false);
        bottom.add(btnSaveExtract);

        panel.add(hint, BorderLayout.NORTH);
        panel.add(new JScrollPane(extractResultArea), BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private byte[] lastExtractedData;
    private String lastExtractedFileName;

    // ============================================================
    // 拖放支持
    // ============================================================
    private void setupDragDrop() {
        DropTarget dt = new DropTarget(this, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent e) {
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) e.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) {
                        File f = files.get(0);
                        String name = f.getName().toLowerCase();
                        if (name.endsWith(".png") || name.endsWith(".bmp")) {
                            loadCarrier(f);
                        } else {
                            JOptionPane.showMessageDialog(SteganoPanel.this,
                                    "请拖入 PNG 或 BMP 图片作为载体", "格式不支持",
                                    JOptionPane.WARNING_MESSAGE);
                        }
                    }
                    e.dropComplete(true);
                } catch (Exception ex) {
                    e.dropComplete(false);
                }
            }
        });
        setDropTarget(dt);
    }

    // ============================================================
    // 核心操作
    // ============================================================
    private void execute() {
        if (carrierData == null) {
            JOptionPane.showMessageDialog(this, "请先选择或拖入载体图片",
                    "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String sel = (String) keyPairCombo.getSelectedItem();
        if (sel == null) {
            JOptionPane.showMessageDialog(this, "请先选择一个密钥对",
                    "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // 获取实际密钥对名称（用于通过 PrivateKeyAccessService 获取自己的私钥）
        final String keyPairName = sel.replaceAll(" \\(\\d+-bit RSA\\)$", "");

        // 统一走非对称协议：无论 JPEG / PNG / BMP，都需要联系人（公钥 + salt）
        java.util.List<contacts.model.Contact> contactsList;
        try {
            contactsList = contacts.storage.ContactStorage.loadAllContacts();
        } catch (Exception e) {
            contactsList = new java.util.ArrayList<>();
        }
        if (contactsList.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "非对称隐写协议需要一位联系人（公钥 + salt）。请先在联系人管理中添加至少一位联系人。",
                    "缺少联系人", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String[] names = new String[contactsList.size()];
        for (int i = 0; i < contactsList.size(); i++) names[i] = contactsList.get(i).getName();
        final String contactName = (String) JOptionPane.showInputDialog(this,
                "选择要隐藏内容的联系人（使用该联系人的公钥加密，对方用私钥解密）：",
                "选择联系人", JOptionPane.QUESTION_MESSAGE, null, names, names[0]);
        if (contactName == null || contactName.isEmpty()) return;

        btnExecute.setEnabled(false);
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);

        new SwingWorker<Void, Void>() {
            private String errorMsg;
            private byte[] result;
            private java.security.PrivateKey privateKey; // 由 PrivateKeyAccessService 统一获取
            private boolean embedMode = (currentMode == Mode.EMBED);
            private boolean isJpegInternal = carrierFileName != null
                    && (carrierFileName.toLowerCase().endsWith(".jpg")
                    || carrierFileName.toLowerCase().endsWith(".jpeg"));

            @Override
            protected Void doInBackground() {
                try {
                    // 1. 通过 PrivateKeyAccessService 获取私钥（自动处理安全等级、密码缓存、系统密钥链）
                    //    PrivateKeyAccessService 内部会在需要时弹出密码对话框（通过 SwingUtilities.invokeAndWait）
                    privateKey = crypto.key.PrivateKeyAccessService.getPrivateKey(keyPairName);
                    if (privateKey == null) {
                        // 用户取消弹框或解密失败
                        return null;
                    }

                    double rate = getSelectedEmbedRate();

                    // 3. 加载联系人：公钥 + salt
                    contacts.model.Contact contact = null;
                    try {
                        for (contacts.model.Contact c : contacts.storage.ContactStorage.loadAllContacts()) {
                            if (contactName.equals(c.getName())) { contact = c; break; }
                        }
                    } catch (Exception ignored) {}
                    if (contact == null) { errorMsg = "联系人不存在: " + contactName; return null; }
                    byte[] contactSalt = contact.getSalt();
                    if (contactSalt == null || contactSalt.length < stegano.model.SteganoConfig.CONTACT_SALT_MIN_LENGTH) {
                        errorMsg = "联系人 " + contactName + " 缺少合法的 salt。请在联系人管理中为该联系人生成 salt。";
                        return null;
                    }
                    java.security.PublicKey contactPublicKey;
                    try {
                        byte[] pkBytes = java.util.Base64.getDecoder().decode(
                                contact.getPublicKey().trim().replace("-----", "").replaceAll("\\s+", ""));
                        contactPublicKey = crypto.key.KeyUtils.getPublicKeyFromBytes(pkBytes);
                        if (contactPublicKey == null) {
                            errorMsg = "联系人公钥解析失败";
                            return null;
                        }
                    } catch (Exception ex) {
                        errorMsg = "联系人公钥解析失败: " + ex.getMessage();
                        return null;
                    }

                    if (embedMode) {
                        // 嵌入：生成 32 字节 r → 用联系人公钥 RSA-OAEP 加密得到 c → 与 payload 一起嵌入
                        byte[] payload = getPayload();
                        if (payload == null || payload.length == 0) {
                            errorMsg = "请输入要隐藏的内容";
                            return null;
                        }
                        byte[] r = new byte[stegano.model.SteganoConfig.RSA_R_LENGTH];
                        java.security.SecureRandom.getInstanceStrong().nextBytes(r);
                        byte[] c = SteganoCore.encryptRngForContact(r, contactPublicKey);
                        result = SteganoCore.embedRsa(c, payload, carrierData, carrierFileName, contactSalt, rate);
                    } else {
                        // 提取：Phase1 得到 c → 用自己的私钥解密 c 得到 r → Phase2 得到 payload
                        byte[] c = SteganoCore.extractRsaPhase1(carrierData, carrierFileName, contactSalt, rate);
                        byte[] r = SteganoCore.decryptRngWithPrivateKey(c, privateKey);
                        result = SteganoCore.extractRsaPhase2(carrierData, carrierFileName, contactSalt, r, rate);
                    }

                    // 注意：密码缓存由 PrivateKeyAccessService 内部管理，无需在此处理

                } catch (Exception e) {
                    errorMsg = e.getMessage();
                }
                // 注意：敏感数据清理由 PrivateKeyAccessService 内部管理，JCA PrivateKey 对象由其自身管理
                return null;
            }

            @Override
            protected void done() {
                progressBar.setIndeterminate(false);
                progressBar.setVisible(false);
                btnExecute.setEnabled(true);

                if (errorMsg != null) {
                    JOptionPane.showMessageDialog(SteganoPanel.this,
                            "操作失败: " + errorMsg, "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                if (currentMode == Mode.EMBED) {
                    // 嵌入成功，提示保存（JPEG 扩展名保持 jpg，其他保持 png）
                    carrierData = result;
                    if (isJpegInternal) {
                        carrierFileName = carrierFileName.replaceFirst("\\.[^.]+$", "_stego.jpg");
                    } else {
                        carrierFileName = carrierFileName.replaceFirst("\\.[^.]+$", "_stego.png");
                    }
                    // 清掉已消费的外部载荷
                    externalPayload = null;
                    saveStegoImage();
                } else {
                    // 提取成功，显示结果
                    lastExtractedData = result;
                    showExtractResult(result);
                }
            }
        }.execute();
    }

    // ============================================================
    // 安全等级机制：获取密码（与 SecureTextPanel 复用同一套逻辑）
    // ============================================================

    /**
     * 根据全局安全等级获取密码：
     * - 便捷模式 (convenient)：从系统密钥链读取；若不存在则弹窗输入并存入密钥链
     * - 平衡模式 (balanced)：从内存缓存读取；若不存在或过期则弹窗输入并缓存
     * - 严格模式 (strict)：每次都弹窗输入
     *
     * 注：安全等级策略、密码弹框、密码缓存/系统密钥链、私钥解密统一由
     * {@link crypto.key.PrivateKeyAccessService} 管理，详见该类。
     */

    // ============================================================
    // 旧的密码获取 + 缓存代码已移至 PrivateKeyAccessService
    // ============================================================

    private void saveStegoImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(carrierFileName));
        // 实际写入格式：JPEG 载体写出 jpg；PNG/BMP 等都写出 png（LSB 载体实现统一写出 PNG）
        boolean isJpeg = carrierFileName.matches("(?i).*\\.(jpg|jpeg)$");
        if (isJpeg) {
            chooser.setFileFilter(new FileNameExtensionFilter(
                    "JPEG 图片 (*.jpg, *.jpeg)", "jpg", "jpeg"));
        } else {
            chooser.setFileFilter(new FileNameExtensionFilter(
                    "PNG 图片 (*.png)", "png"));
        }
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                File target = chooser.getSelectedFile();
                // 保证扩展名与实际数据格式匹配
                String name = target.getName();
                if (isJpeg && !name.matches("(?i).*\\.(jpg|jpeg)$")) {
                    target = new File(target.getParent(), name + ".jpg");
                } else if (!isJpeg && !name.toLowerCase().endsWith(".png")) {
                    target = new File(target.getParent(), name + ".png");
                }
                Files.write(target.toPath(), carrierData);
                JOptionPane.showMessageDialog(this, "含密图片已保存!", "成功",
                        JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "保存失败: " + ex.getMessage(),
                        "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void showExtractResult(byte[] data) {
        // 尝试作为文本显示
        try {
            String text = new String(data, StandardCharsets.UTF_8);
            // 检查是否为可打印文本
            boolean printable = true;
            int checkLen = Math.min(data.length, 200);
            for (int i = 0; i < checkLen; i++) {
                int b = data[i] & 0xFF;
                if (b < 0x20 && b != 0x0A && b != 0x0D && b != 0x09) {
                    printable = false;
                    break;
                }
            }

            if (printable) {
                extractResultArea.setText(text);
                btnSaveExtract.setVisible(true);
                JOptionPane.showMessageDialog(this, "提取成功!", "成功",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        } catch (Exception ignored) {}

        // 非文本内容
        extractResultArea.setText("(提取到二进制数据, " + formatSize(data.length) + ")\n请点击\"保存为文件\"");
        btnSaveExtract.setVisible(true);
        JOptionPane.showMessageDialog(this, "提取成功! 结果不是可读文本，请保存为文件。",
                "成功", JOptionPane.INFORMATION_MESSAGE);
    }

    private void saveExtractedData() {
        if (lastExtractedData == null) return;
        JFileChooser chooser = new JFileChooser();
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Files.write(chooser.getSelectedFile().toPath(), lastExtractedData);
                JOptionPane.showMessageDialog(this, "已保存!", "成功",
                        JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "保存失败: " + ex.getMessage(),
                        "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // ============================================================
    // 工具方法
    // ============================================================
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }
}