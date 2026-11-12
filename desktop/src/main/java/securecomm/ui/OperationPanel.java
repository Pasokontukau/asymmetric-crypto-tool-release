package securecomm.ui;

import app.theme.AppFonts;
import app.theme.ThemeManager;
import app.theme.UIConstants;
import contacts.model.Contact;
import contacts.storage.ContactStorage;
import core.model.FileInfo;
import core.util.LogManager;
import crypto.key.KeyPairManager;
import history.model.HistoryRecord;
import history.model.HistoryType;
import history.model.RecordCategory;
import history.storage.HistoryStorage;
import keymanagement.model.KeyPairInfo;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Graphics;
import java.awt.RenderingHints;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.border.TitledBorder;
import keymanagement.storage.KeyPairStorage;
import securecomm.ui.OperationPanel;
import settings.manager.SettingsManager;

public abstract class OperationPanel extends JPanel {

    /** 历史记录加密不可用时只提示一次，避免每次操作都弹窗。 */
    private static final java.util.concurrent.atomic.AtomicBoolean historyWarningShown =
            new java.util.concurrent.atomic.AtomicBoolean();
    
    protected JFileChooser fileChooser;
    
    public OperationPanel() {
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        fileChooser = new JFileChooser();
        // 设置文件选择对话框的默认大小，使其更大，方便用户浏览文件
        fileChooser.setPreferredSize(new Dimension(UIConstants.FILE_CHOOSER_WIDTH, UIConstants.FILE_CHOOSER_HEIGHT));
        fileChooser.setMinimumSize(new Dimension(800, 600));
        
        // 直接设置文件选择对话框中所有组件的字体
        setFontForAllComponents(fileChooser, AppFonts.getNormalFont());
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
     * 创建带标题的面板
     * @param title 面板标题
     * @return 带标题的面板
     */
    protected JPanel createTitledPanel(String title) {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        
        // 使用现代化的圆角边框
        javax.swing.border.TitledBorder border = BorderFactory.createTitledBorder(title);
        border.setTitleFont(AppFonts.getBoldFont());
        
        panel.setBorder(BorderFactory.createCompoundBorder(
                border,
                BorderFactory.createEmptyBorder(15, 15, 15, 15)
        ));
        
        return panel;
    }
    
    /**
     * 创建控制面板
     * @return 控制面板
     */
    protected JPanel createControlPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        return panel;
    }
    
    /**
     * 创建文本区域
     * @return 文本区域
     */
    protected JTextArea createTextArea() {
        JTextArea textArea = new JTextArea();
        textArea.setFont(AppFonts.getNormalFont());
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        return textArea;
    }
    
    /**
     * 创建不可编辑的文本区域
     * @return 不可编辑的文本区域
     */
    protected JTextArea createReadOnlyTextArea() {
        JTextArea textArea = createTextArea();
        textArea.setEditable(false);
        return textArea;
    }
    
    /**
     * 创建带标签的文本区域面板
     * @param labelText 标签文本
     * @param textArea 文本区域
     * @return 带标签的文本区域面板
     */
    protected JPanel createTextAreaPanel(String labelText, JTextArea textArea) {
        return createTextAreaPanel(labelText, textArea, new JButton[0]);
    }
    
    /**
     * 创建带标签的文本区域面板（支持添加额外按钮）
     * @param labelText 标签文本
     * @param textArea 文本区域
     * @param extraButtons 额外要添加的按钮
     * @return 带标签的文本区域面板
     */
    protected JPanel createTextAreaPanel(String labelText, JTextArea textArea, JButton... extraButtons) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        
        // 标签和按钮面板
        JPanel labelPanel = new JPanel(new BorderLayout(5, 0));
        
        JLabel label = new JLabel(labelText);
        label.setFont(AppFonts.getNormalFont());
        labelPanel.add(label, BorderLayout.WEST);
        
        // 按钮容器，用于存放复制和清空按钮
        JPanel buttonContainer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        
        // 先添加额外按钮
        for (JButton btn : extraButtons) {
            buttonContainer.add(btn);
        }
        
        // 如果文本区域是只读的，添加复制按钮
        if (!textArea.isEditable()) {
            // 使用我们自定义的小按钮
            JButton copyButton = createSmallButton("复制");
            // 添加复制功能
            copyButton.addActionListener(e -> {
                textArea.selectAll();
                textArea.copy();
            });
            buttonContainer.add(copyButton);
        } else {
            // 如果文本区域是可编辑的，添加清空按钮
            JButton clearButton = createSmallButton("清空");
            // 添加清空功能
            clearButton.addActionListener(e -> {
                textArea.setText("");
            });
            buttonContainer.add(clearButton);
        }
        
        labelPanel.add(buttonContainer, BorderLayout.EAST);
        panel.add(labelPanel, BorderLayout.NORTH);
        
        // 添加右键菜单支持
        addRightClickMenu(textArea);
        
        JScrollPane scrollPane = new JScrollPane(textArea);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        return panel;
    }
    
    /**
     * 为文本区域添加右键菜单
     * @param textArea 文本区域
     */
    private void addRightClickMenu(JTextArea textArea) {
        JPopupMenu popupMenu = new JPopupMenu();
        
        // 粘贴菜单项
        JMenuItem pasteMenuItem = new JMenuItem("粘贴");
        pasteMenuItem.setFont(AppFonts.getNormalFont());
        pasteMenuItem.addActionListener(e -> {
            textArea.paste();
        });
        
        popupMenu.add(pasteMenuItem);
        
        // 为文本区域添加右键菜单
        textArea.setComponentPopupMenu(popupMenu);
    }
    
    /**
     * 创建进度条
     * @return 进度条
     */
    protected JProgressBar createProgressBar() {
        JProgressBar progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setString("就绪");
        progressBar.setFont(AppFonts.getSmallFont());
        return progressBar;
    }
    
    /**
     * 创建下拉选择框
     * @return 下拉选择框
     */
    protected JComboBox<String> createComboBox() {
        JComboBox<String> comboBox = new JComboBox<>();
        comboBox.setFont(AppFonts.getNormalFont());
        comboBox.setPreferredSize(new Dimension(180, 30));
        return comboBox;
    }
    
    /**
     * 创建密码输入框
     * @return 密码输入框
     */
    protected JPasswordField createPasswordField() {
        JPasswordField passwordField = new JPasswordField(15);
        passwordField.setFont(AppFonts.getNormalFont());
        passwordField.setPreferredSize(new Dimension(150, 30));
        return passwordField;
    }
    
    /**
     * 创建带显示密码按钮的密码输入框（用于单个密码框的情况）
     * @param columns 列数
     * @return 包含密码输入框和显示按钮的面板
     */
    protected JPanel createPasswordFieldWithToggle(int columns) {
        JPanel panel = new JPanel(new BorderLayout(5, 0));
        panel.setBackground(ThemeManager.getPanelBackgroundColor());
        
        JPasswordField passwordField = new JPasswordField(columns);
        passwordField.setFont(AppFonts.getNormalFont());
        passwordField.setPreferredSize(new Dimension(150, 30));
        
        JButton toggleButton = new JButton("👁");
        toggleButton.setToolTipText("显示/隐藏密码");
        toggleButton.addActionListener(e -> {
            if (passwordField.getEchoChar() == '•') {
                passwordField.setEchoChar((char) 0);
                toggleButton.setText("🙈");
            } else {
                passwordField.setEchoChar('•');
                toggleButton.setText("👁");
            }
        });
        
        panel.add(passwordField, BorderLayout.CENTER);
        panel.add(toggleButton, BorderLayout.EAST);
        
        // 设置一个client property，方便后续获取密码框
        panel.putClientProperty("passwordField", passwordField);
        
        return panel;
    }
    
    /**
     * 创建小按钮（用于复制、清空等操作）
     * @param text 按钮文本
     * @return 小按钮
     */
    protected JButton createSmallButton(String text) {
        // 使用自定义按钮，确保圆角生效
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                
                // 绘制圆角背景
                java.awt.Color bg = ThemeManager.getButtonBackgroundColor();
                if (getModel().isPressed()) {
                    g2.setColor(bg.darker());
                } else if (getModel().isRollover()) {
                    g2.setColor(ThemeManager.getButtonHoverColor());
                } else {
                    g2.setColor(bg);
                }
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                
                // 绘制文字
                g2.setColor(ThemeManager.getTextColor());
                g2.setFont(getFont());
                java.awt.FontMetrics fm = g2.getFontMetrics();
                String buttonText = getText();
                int textWidth = fm.stringWidth(buttonText);
                int textHeight = fm.getAscent();
                g2.drawString(buttonText, (getWidth() - textWidth) / 2, (getHeight() + textHeight) / 2 - 1);
                
                g2.dispose();
            }
            
            @Override
            public boolean isOpaque() {
                return false;
            }
        };
        
        button.setFont(AppFonts.getSmallFont());
        button.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 12, 4, 12));
        button.setFocusPainted(false);
        button.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        button.setContentAreaFilled(false);
        // 不强制设置固定大小，让按钮根据文字自动调整
        // button.setPreferredSize(new Dimension(60, 25));
        
        return button;
    }
    
    /**
     * 创建按钮
     * @param text 按钮文本
     * @param actionListener 点击事件监听器
     * @return 按钮
     */
    protected JButton createButton(String text, ActionListener actionListener) {
        JButton button = ThemeManager.createPrimaryButton(text);
        button.addActionListener(actionListener);
        return button;
    }
    
    /**
     * 创建红色按钮（用于解密等操作）
     * @param text 按钮文本
     * @param actionListener 点击事件监听器
     * @return 按钮
     */
    protected JButton createRedButton(String text, ActionListener actionListener) {
        JButton button = ThemeManager.createDangerButton(text);
        button.addActionListener(actionListener);
        return button;
    }
    
    /**
     * 刷新数据方法，用于在数据变化时重新加载
     */
    public abstract void refreshData();
    
    /**
     * 加载密钥对列表到指定的下拉选择框
     * @param comboBox 下拉选择框
     */
    protected void loadKeyPairs(JComboBox<String> comboBox) {
        try {
            List<KeyPairInfo> keyPairs = KeyPairStorage.loadAllKeyPairs();
            comboBox.removeAllItems();
            for (KeyPairInfo keyPair : keyPairs) {
                comboBox.addItem(keyPair.getName());
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "加载密钥对失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            LogManager.error("加载密钥对失败", e);
        }
    }
    
    /**
     * 加载联系人列表到指定的下拉选择框
     * @param comboBox 下拉选择框
     */
    protected void loadContacts(JComboBox<String> comboBox) {
        try {
            List<Contact> contacts = ContactStorage.loadAllContacts();
            comboBox.removeAllItems();
            for (Contact contact : contacts) {
                comboBox.addItem(contact.getName());
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "加载联系人失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            LogManager.error("加载联系人失败", e);
        }
    }
    
    /**
     * 添加历史记录
     * @param type 历史记录类型
     * @param description 描述
     * @param success 是否成功
     * @param status 状态
     * @param details 详细信息
     */
    protected void addHistoryRecord(HistoryType type, String description, boolean success, String status, String details) {
        if (!HistoryStorage.addHistory(new HistoryRecord(type, description, success, status, details))) {
            warnHistoryNotSaved();
        }
    }
    
    protected void addHistoryRecordEnhanced(
            HistoryType type,
            String contactName,
            RecordCategory category,
            String description,
            boolean success,
            String result,
            String details,
            String inputText,
            java.util.List<FileInfo> inputFiles,
            String outputText,
            java.util.List<FileInfo> outputFiles,
            String keyPairName,
            long durationMs
    ) {
        HistoryRecord record = new HistoryRecord(type, category, description, success, result, details);
        record.setContactName(contactName);
        record.setInputText(inputText);
        record.setInputFiles(inputFiles);
        record.setOutputText(outputText);
        record.setOutputFiles(outputFiles);
        record.setKeyPairName(keyPairName);
        record.setDurationMs(durationMs);
        if (!HistoryStorage.addHistory(record)) {
            warnHistoryNotSaved();
        }
    }

    private void warnHistoryNotSaved() {
        if (!historyWarningShown.compareAndSet(false, true)) {
            return;
        }
        javax.swing.SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                this,
                "本机加密不可用，历史记录没有保存。请先修复本机密钥后重试。",
                "历史记录未保存",
                JOptionPane.WARNING_MESSAGE
        ));
    }
    
    /**
     * 显示错误消息
     * @param message 错误消息
     */
    protected void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "错误", JOptionPane.ERROR_MESSAGE);
    }
    
    /**
     * 显示成功消息
     * @param message 成功消息
     */
    protected void showSuccess(String message) {
        JOptionPane.showMessageDialog(this, message, "成功", JOptionPane.INFORMATION_MESSAGE);
    }
    
    /**
     * 格式化文件大小
     * @param bytes 文件大小（字节）
     * @return 格式化后的字符串（如 "1.23 MB"）
     */
    protected static String formatFileSize(long bytes) {
        return LogManager.formatBytes(bytes);
    }
    
    /**
     * 获取带扩展名的输出文件
     * @param tempFile 临时文件
     * @param extension 默认扩展名
     * @return 带扩展名的输出文件
     */
    protected File getOutputFileWithExtension(File tempFile, String extension) {
        String outputFileName;
        if (tempFile.getName().endsWith(extension)) {
            outputFileName = tempFile.getName();
        } else {
            outputFileName = tempFile.getName() + extension;
        }
        
        // 检查是否设置了默认输出目录
        String outputDir = settings.manager.SettingsManager.getDefaultOutputDirectory();
        if (!outputDir.isEmpty()) {
            // 使用设置的默认输出目录
            return new File(outputDir, outputFileName);
        } else {
            // 使用输入文件的目录
            return new File(tempFile.getParent(), outputFileName);
        }
    }
}