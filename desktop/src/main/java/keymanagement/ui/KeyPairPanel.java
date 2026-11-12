package keymanagement.ui;

import app.theme.AppFonts;
import app.theme.ThemeManager;
import contacts.storage.ContactStorage;
import core.security.MemorySecurityUtils;
import core.security.PasswordStrengthChecker;
import core.util.LogManager;
import crypto.config.CryptoConfig;
import crypto.key.KeyPairManager;
import crypto.key.KeyUtils;
import keymanagement.model.KeyPairInfo;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import keymanagement.service.KeyBackupService;
import keymanagement.storage.KeyPairStorage;
import core.ui.ListManagerPanel;
import settings.storage.SettingsStorage;

public class KeyPairPanel extends ListManagerPanel {
    
    public KeyPairPanel() {
        // 移除算法列，使用列名：名称、创建时间、密钥长度、指纹
        super("已生成的密钥对", new String[]{"名称", "创建时间", "密钥长度", "指纹"});
        
        // 允许表格内容被单个复制
        dataTable.setTransferHandler(new javax.swing.TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }
            
            @Override
            protected java.awt.datatransfer.Transferable createTransferable(JComponent c) {
                JTable table = (JTable) c;
                StringBuilder selection = new StringBuilder();
                int[] selectedRows = table.getSelectedRows();
                int[] selectedColumns = table.getSelectedColumns();
                
                for (int i = 0; i < selectedRows.length; i++) {
                    for (int j = 0; j < selectedColumns.length; j++) {
                        selection.append(table.getValueAt(selectedRows[i], selectedColumns[j]));
                        if (j < selectedColumns.length - 1) {
                            selection.append("\t");
                        }
                    }
                    if (i < selectedRows.length - 1) {
                        selection.append("\n");
                    }
                }
                return new java.awt.datatransfer.StringSelection(selection.toString());
            }
        });
        
        // 添加底部按钮
        JButton generateButton = ThemeManager.createPrimaryButton("生成密钥对");
        generateButton.addActionListener(e -> generateKeyPair());

        JButton importButton = ThemeManager.createNeutralButton("导入密钥备份");
        importButton.addActionListener(e -> importKeyBackup());
        
        addButtonPanel(generateButton, importButton);
        
        // 按 Delete 键触发删除（和右键菜单的删除按钮行为一致）
        dataTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
            javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteKeyPair");
        dataTable.getActionMap().put("deleteKeyPair", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                deleteKeyPair();
            }
        });
        
        loadKeyPairs();
    }
    
    private void showKeyPairDetails(int row) {
        try {
            String name = (String) tableModel.getValueAt(row, 0);
            
            // 获取绑定的联系人
            List<String> boundContacts = ContactStorage.getContactsByKeyPair(name);
            
            // 创建详情对话框
            JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "密钥绑定的联系人 - " + name, true);
            dialog.setResizable(true);
            dialog.setMinimumSize(new Dimension(400, 300));
            
            JPanel contentPanel = new JPanel(new BorderLayout(10, 10));
            contentPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
            contentPanel.setBackground(ThemeManager.getPanelBackgroundColor());
            
            // 绑定的联系人
            JPanel contactPanel = new JPanel(new BorderLayout());
            contactPanel.setBackground(ThemeManager.getPanelBackgroundColor());
            contactPanel.setBorder(BorderFactory.createTitledBorder("绑定的联系人"));
            
            if (boundContacts.isEmpty()) {
                JLabel noContactLabel = new JLabel("（无绑定联系人）");
                noContactLabel.setFont(AppFonts.getNormalFont());
                noContactLabel.setForeground(Color.GRAY);
                noContactLabel.setHorizontalAlignment(SwingConstants.CENTER);
                contactPanel.add(noContactLabel, BorderLayout.CENTER);
            } else {
                DefaultListModel<String> listModel = new DefaultListModel<>();
                for (String contact : boundContacts) {
                    listModel.addElement(contact);
                }
                JList<String> contactList = new JList<>(listModel);
                contactList.setFont(AppFonts.getNormalFont());
                contactList.setBackground(ThemeManager.getPanelBackgroundColor());
                JScrollPane contactScrollPane = new JScrollPane(contactList);
                contactScrollPane.setPreferredSize(new Dimension(300, 150));
                contactPanel.add(contactScrollPane, BorderLayout.CENTER);
            }
            
            // 按钮面板
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10));
            buttonPanel.setBackground(ThemeManager.getPanelBackgroundColor());
            
            JButton closeButton = ThemeManager.createNeutralButton("关闭");
            closeButton.addActionListener(e -> dialog.dispose());
            buttonPanel.add(closeButton);
            
            // 组装
            contentPanel.add(contactPanel, BorderLayout.CENTER);
            contentPanel.add(buttonPanel, BorderLayout.SOUTH);
            
            dialog.setContentPane(contentPanel);
            dialog.pack();
            dialog.setLocationRelativeTo(this);
            dialog.setVisible(true);
            
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "显示绑定联系人失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            LogManager.error("查看密钥对详情失败", e);
        }
    }
    
    private void generateKeyPair() {
        try {
            // 创建生成密钥对对话框
            JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "生成密钥对", true);
            dialog.setLayout(new BorderLayout(10, 10));
            dialog.setResizable(false);
            
            JPanel contentPanel = new JPanel(new GridBagLayout());
            contentPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
            
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = new Insets(10, 10, 10, 10);
            
            // 密钥对名称
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.anchor = GridBagConstraints.WEST;
            JLabel nameLabel = new JLabel("密钥对名称:");
            nameLabel.setFont(AppFonts.getNormalFont());
            contentPanel.add(nameLabel, gbc);
            
            gbc.gridx = 1;
            gbc.gridy = 0;
            gbc.weightx = 1.0;
            JTextField nameField = new JTextField(20);
            nameField.setFont(AppFonts.getNormalFont());
            contentPanel.add(nameField, gbc);
            
            // 密钥长度
            gbc.gridx = 0;
            gbc.gridy = 1;
            gbc.anchor = GridBagConstraints.WEST;
            JLabel sizeLabel = new JLabel("密钥长度:");
            sizeLabel.setFont(AppFonts.getNormalFont());
            contentPanel.add(sizeLabel, gbc);
            
            gbc.gridx = 1;
            gbc.gridy = 1;
            gbc.weightx = 1.0;
            JComboBox<Integer> sizeComboBox = new JComboBox<>(new Integer[]{2048, 4096});
            sizeComboBox.setFont(AppFonts.getNormalFont());
            sizeComboBox.setSelectedIndex(0);
            contentPanel.add(sizeComboBox, gbc);
            
            // 私钥密码
            gbc.gridx = 0;
            gbc.gridy = 2;
            gbc.anchor = GridBagConstraints.WEST;
            JLabel passwordLabel = new JLabel("私钥密码：");
            passwordLabel.setFont(AppFonts.getNormalFont());
            contentPanel.add(passwordLabel, gbc);
            
            gbc.gridx = 1;
            gbc.gridy = 2;
            gbc.weightx = 1.0;
            JPanel passwordPanel = new JPanel(new BorderLayout(5, 0));
            passwordPanel.setBackground(ThemeManager.getPanelBackgroundColor());
            
            JPasswordField passwordField = new JPasswordField(20);
            passwordField.setFont(AppFonts.getNormalFont());
            
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
            
            passwordPanel.add(passwordField, BorderLayout.CENTER);
            passwordPanel.add(toggleButton, BorderLayout.EAST);
            contentPanel.add(passwordPanel, gbc);
            
            // 密码强度指示器（只保留文字提示）
            gbc.gridx = 0;
            gbc.gridy = 3;
            gbc.gridwidth = 2;
            gbc.anchor = GridBagConstraints.WEST;
            JPanel strengthPanel = new JPanel(new BorderLayout(5, 0));
            strengthPanel.setBackground(ThemeManager.getPanelBackgroundColor());

            JLabel strengthLabel = new JLabel("密码强度：");
            strengthLabel.setFont(AppFonts.getNormalFont());
            strengthLabel.setForeground(ThemeManager.getTextColor());

            JLabel strengthTextLabel = new JLabel("未输入");
            strengthTextLabel.setFont(AppFonts.getNormalFont());

            strengthPanel.add(strengthLabel, BorderLayout.WEST);
            strengthPanel.add(strengthTextLabel, BorderLayout.CENTER);
            contentPanel.add(strengthPanel, gbc);

            // 添加密码强度监听
            passwordField.getDocument().addDocumentListener(new DocumentListener() {
                private void updateStrength() {
                    char[] password = passwordField.getPassword();
                    try {
                        PasswordStrengthChecker.StrengthResult result = 
                            PasswordStrengthChecker.checkPasswordStrength(password);
                        
                        strengthTextLabel.setText(result.level.getDisplayName());
                        strengthTextLabel.setForeground(new Color(result.level.getColor()));
                    } finally {
                        MemorySecurityUtils.clearCharArray(password);
                    }
                }
                
                @Override
                public void insertUpdate(DocumentEvent e) { updateStrength(); }
                @Override
                public void removeUpdate(DocumentEvent e) { updateStrength(); }
                @Override
                public void changedUpdate(DocumentEvent e) { updateStrength(); }
            });
            
            // 密码强度提示
            gbc.gridx = 0;
            gbc.gridy = 4;
            gbc.gridwidth = 2;
            gbc.anchor = GridBagConstraints.WEST;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1;
            gbc.weighty = 0;
            JTextArea passwordRequirementsArea = new JTextArea("密码要求：");
            passwordRequirementsArea.setFont(AppFonts.getBoldFont().deriveFont(16.0f));
            passwordRequirementsArea.setForeground(ThemeManager.getProgressColor());
            passwordRequirementsArea.setBorder(BorderFactory.createEmptyBorder(10, 5, 0, 5));
            passwordRequirementsArea.setEditable(false);
            passwordRequirementsArea.setLineWrap(true);
            passwordRequirementsArea.setWrapStyleWord(true);
            passwordRequirementsArea.setBackground(ThemeManager.getPanelBackgroundColor());
            passwordRequirementsArea.setOpaque(true);
            passwordRequirementsArea.setRows(1);
            contentPanel.add(passwordRequirementsArea, gbc);
            
            // 密码强度要求详情
            gbc.gridx = 0;
            gbc.gridy = 5;
            gbc.gridwidth = 2;
            gbc.anchor = GridBagConstraints.WEST;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1;
            gbc.weighty = 0;
            JTextArea passwordRequirementsDetailArea = new JTextArea("1. 长度至少12位\n2. 包含大写字母\n3. 包含小写字母\n4. 包含数字\n5. 包含特殊字符");
            passwordRequirementsDetailArea.setFont(AppFonts.getNormalFont().deriveFont(15.0f));
            passwordRequirementsDetailArea.setForeground(ThemeManager.getTextColor());
            passwordRequirementsDetailArea.setBorder(BorderFactory.createEmptyBorder(0, 5, 10, 5));
            passwordRequirementsDetailArea.setEditable(false);
            passwordRequirementsDetailArea.setLineWrap(true);
            passwordRequirementsDetailArea.setWrapStyleWord(true);
            passwordRequirementsDetailArea.setBackground(ThemeManager.getPanelBackgroundColor());
            passwordRequirementsDetailArea.setOpaque(true);
            passwordRequirementsDetailArea.setRows(5);
            contentPanel.add(passwordRequirementsDetailArea, gbc);
            
            // 添加密码提示
            gbc.gridx = 0;
            gbc.gridy = 6;
            gbc.gridwidth = 2;
            gbc.anchor = GridBagConstraints.WEST;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1;
            gbc.weighty = 0;
            // 使用JTextArea替代JLabel，支持自动换行
            JTextArea passwordWarningTextArea = new JTextArea("重要提示：请务必牢记私钥密码！私钥密码一旦丢失，私钥将无法使用，所有加密数据也将无法解密！");
            passwordWarningTextArea.setFont(AppFonts.getNormalFont().deriveFont(16.0f));
            passwordWarningTextArea.setForeground(new Color(234, 67, 53));
            passwordWarningTextArea.setBorder(BorderFactory.createEmptyBorder(10, 5, 10, 5));
            passwordWarningTextArea.setEditable(false);
            passwordWarningTextArea.setLineWrap(true); // 启用自动换行
            passwordWarningTextArea.setWrapStyleWord(true); // 按单词换行
            passwordWarningTextArea.setBackground(ThemeManager.getPanelBackgroundColor()); // 设置背景色与面板一致
            passwordWarningTextArea.setOpaque(true); // 确保背景色生效
            passwordWarningTextArea.setRows(2); // 设置初始行数
            passwordWarningTextArea.setMaximumSize(new Dimension(540, 100)); // 设置最大宽度，避免太宽
            
            // 不使用滚动面板，直接添加文本区域
            contentPanel.add(passwordWarningTextArea, gbc);
            
            // 按钮面板
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 8));
            buttonPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
            buttonPanel.setBackground(ThemeManager.getPanelBackgroundColor());
            
            JButton okButton = ThemeManager.createPrimaryButton("生成");
            JButton cancelButton = ThemeManager.createNeutralButton("取消");
            
            okButton.addActionListener(e -> {
                char[] passwordChars = null;
                try {
                    String name = nameField.getText();
                    if (name.isEmpty()) {
                        JOptionPane.showMessageDialog(dialog, "请输入密钥对名称", "错误", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    // 安全文件名验证
                    if (!name.matches("^[a-zA-Z0-9_\\-\\u4e00-\\u9fa5]+$")) {
                        JOptionPane.showMessageDialog(dialog, "密钥对名称只能包含字母、数字、下划线、连字符和中文", "错误", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    
                    passwordChars = passwordField.getPassword();
                    if (passwordChars.length == 0) {
                        JOptionPane.showMessageDialog(dialog, "请输入私钥密码", "错误", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    
                    // 验证密码强度
                    String passwordError = PasswordStrengthChecker.validatePasswordRequirements(passwordChars);
                    if (passwordError != null) {
                        // 检查是否开启了测试模式
                        if (settings.storage.SettingsStorage.isTestMode()) {
                            // 测试模式：显示警告但不阻止
                            int confirm = JOptionPane.showConfirmDialog(dialog, 
                                "警告！这是测试模式，密码强度没有达到安全要求：\n" + passwordError + "\n\n是否继续生成？", 
                                "测试模式警告", 
                                JOptionPane.YES_NO_OPTION, 
                                JOptionPane.WARNING_MESSAGE);
                            if (confirm != JOptionPane.YES_OPTION) {
                                return;
                            }
                        } else {
                            // 正常模式：阻止
                            JOptionPane.showMessageDialog(dialog, passwordError, "密码强度不足", JOptionPane.ERROR_MESSAGE);
                            return;
                        }
                    }
                    
                    int keySize = (int) sizeComboBox.getSelectedItem();
                    
                    KeyPair keyPair = KeyPairManager.generateKeyPair(keySize);
                    String publicKey = KeyUtils.encodeBase64(keyPair.getPublic().getEncoded());
                    byte[] privateKeyBytes = keyPair.getPrivate().getEncoded();
                    byte[] encryptedPrivateKey = KeyPairManager.encryptPrivateKey(privateKeyBytes, passwordChars);

                    // Phase 2: 同时生成 16 字节 salt 并用相同密码加密存储
                    byte[] mySalt = new byte[CryptoConfig.SALT_LENGTH];
                    crypto.provider.SecurityProvider.STRONG_RANDOM.nextBytes(mySalt);
                    byte[] encryptedSalt = KeyPairManager.encryptPrivateKey(mySalt, passwordChars);
                    MemorySecurityUtils.clearByteArray(mySalt);

                    MemorySecurityUtils.clearByteArray(privateKeyBytes);

                    KeyPairInfo keyPairInfo = new KeyPairInfo(
                            name,
                            Instant.now(),
                            "RSA",
                            keySize,
                            publicKey,
                            name + ".pem"
                    );

                    // Phase 2: 把加密后的 salt 设置到 KeyPairInfo
                    keyPairInfo.setEncryptedSalt(encryptedSalt);

                    KeyPairStorage.saveKeyPair(name, keyPairInfo, encryptedPrivateKey);
                    JOptionPane.showMessageDialog(dialog, "密钥对生成成功！", "成功", JOptionPane.INFORMATION_MESSAGE);
                    
                    loadKeyPairs();
                    dialog.dispose();
                    
                    // 只有成功时才清空密码框
                    passwordField.setText("");
                    
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog, "生成密钥对失败：" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    LogManager.error("生成密钥对失败", ex);
                } finally {
                    if (passwordChars != null) {
                        MemorySecurityUtils.clearCharArray(passwordChars);
                    }
                }
            });
            
            cancelButton.addActionListener(e -> dialog.dispose());
            
            buttonPanel.add(okButton);
            buttonPanel.add(cancelButton);
            
            dialog.add(contentPanel, BorderLayout.CENTER);
            dialog.add(buttonPanel, BorderLayout.SOUTH);
            dialog.pack(); // 自动适应内容大小
            dialog.setLocationRelativeTo(this);
            dialog.setVisible(true);
            
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "生成密钥对失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            LogManager.error("生成密钥对失败", e);
        }
    }
    
    private void deleteKeyPair() {
        int[] selectedRows = dataTable.getSelectedRows();
        
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(this, "请选择要删除的密钥对", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        String message;
        if (selectedRows.length == 1) {
            String name = (String) tableModel.getValueAt(selectedRows[0], 0);
            message = "确定要删除密钥对 " + name + " 吗？";
        } else {
            message = "确定要删除选中的 " + selectedRows.length + " 个密钥对吗？";
        }
        
        int confirm = JOptionPane.showConfirmDialog(this, message, "确认删除", JOptionPane.YES_NO_OPTION);
        
        if (confirm == JOptionPane.YES_OPTION) {
            try {
                int successCount = 0;
                int failCount = 0;
                for (int row : selectedRows) {
                    String name = (String) tableModel.getValueAt(row, 0);
                    boolean deleted = KeyPairStorage.deleteKeyPair(name);
                    if (deleted) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                }
                if (successCount > 0) {
                    String resultMsg = selectedRows.length == 1
                        ? "密钥对删除成功！"
                        : ("成功删除 " + successCount + " 个密钥对" + (failCount > 0 ? "，" + failCount + " 个失败" : ""));
                    JOptionPane.showMessageDialog(this, resultMsg, "成功", JOptionPane.INFORMATION_MESSAGE);
                    loadKeyPairs();
                } else {
                    JOptionPane.showMessageDialog(this, "删除密钥对失败", "错误", JOptionPane.ERROR_MESSAGE);
                }
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "删除密钥对失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                LogManager.error("删除密钥对失败", e);
            }
        }
    }
    
    private void loadKeyPairs() {
        try {
            List<KeyPairInfo> keyPairs = KeyPairStorage.loadAllKeyPairs();
            tableModel.setRowCount(0);
            
            for (KeyPairInfo keyPair : keyPairs) {
                // 生成公钥指纹
                String fingerprint = "";
                try {
                    fingerprint = KeyUtils.generateKeyFingerprint(keyPair.getPublicKey());
                } catch (Exception e) {
                    fingerprint = "获取失败";
                }
                
                Object[] row = {
                    keyPair.getName(),
                    formatDateTime(keyPair.getCreateTime()),
                    keyPair.getKeySize(),
                    fingerprint
                };
                tableModel.addRow(row);
            }
            
            // 数据加载完成后，自动调整所有列的宽度
            packAllColumns();
            
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "加载密钥对失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            LogManager.error("加载密钥对列表失败", e);
        }
    }
    
    /**
     * 创建右键菜单
     */
    @Override
    protected JPopupMenu createPopupMenu(int[] selectedRows) {
        if (selectedRows.length == 0) {
            return null;
        }
        
        JPopupMenu popup = new JPopupMenu();
        
        if (selectedRows.length == 1) {
            // 单选：显示完整菜单
            int row = selectedRows[0];
            
            JMenuItem copyPublicKeyItem = new JMenuItem("复制公钥");
            copyPublicKeyItem.setFont(AppFonts.getNormalFont());
            copyPublicKeyItem.addActionListener(e -> copyPublicKey(row));
            popup.add(copyPublicKeyItem);

            JMenuItem exportBackupItem = new JMenuItem("导出密钥备份...");
            exportBackupItem.setFont(AppFonts.getNormalFont());
            exportBackupItem.addActionListener(e -> exportKeyBackup(row));
            popup.add(exportBackupItem);
            
            JMenuItem detailsItem = new JMenuItem("查看详情");
            detailsItem.setFont(AppFonts.getNormalFont());
            detailsItem.addActionListener(e -> showKeyPairDetails(row));
            popup.add(detailsItem);
            
            popup.addSeparator();
        }
        
        // 多选或单选都显示删除按钮
        String deleteLabel = selectedRows.length == 1 ? "删除密钥对" : "删除选中的 " + selectedRows.length + " 个密钥对";
        JMenuItem deleteItem = new JMenuItem(deleteLabel);
        deleteItem.setFont(AppFonts.getNormalFont());
        deleteItem.addActionListener(e -> deleteKeyPair());
        popup.add(deleteItem);
        
        return popup;
    }
    
    /** 从 Android/桌面共用的 `aegix-key-bundle` 文本导入一个密钥对。 */
    private void importKeyBackup() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择密钥备份文件");
        chooser.setFileFilter(new FileNameExtensionFilter("密钥备份 (*.txt)", "txt"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        try {
            String text = KeyBackupService.readText(chooser.getSelectedFile());
            KeyBackupService.Preview preview = KeyBackupService.inspect(text);

            JTextField nameField = new JTextField(preview.getName(), 28);
            JPasswordField passwordField = new JPasswordField(28);
            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            addFormRow(form, 0, "备份名称：", nameField);
            addFormRow(form, 1, "密钥长度：", new JLabel(preview.getKeySize() + " 位"));
            addFormRow(form, 2, "备份口令：", passwordField);

            int result = JOptionPane.showConfirmDialog(
                    this,
                    form,
                    "导入密钥备份",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return;
            }

            char[] password = passwordField.getPassword();
            try {
                KeyPairInfo imported = KeyBackupService.importBundle(
                        text, nameField.getText(), password);
                loadKeyPairs();
                JOptionPane.showMessageDialog(
                        this,
                        "已导入密钥对 \"" + imported.getName() + "\"。",
                        "导入成功",
                        JOptionPane.INFORMATION_MESSAGE);
            } finally {
                MemorySecurityUtils.clearCharArray(password);
            }
        } catch (Exception error) {
            JOptionPane.showMessageDialog(
                    this,
                    "导入密钥备份失败：" + error.getMessage(),
                    "错误",
                    JOptionPane.ERROR_MESSAGE);
            LogManager.error("导入密钥备份失败", error);
        }
    }

    /** 导出当前密钥对；先读取原私钥，再用新的备份口令生成 GCM1 块。 */
    private void exportKeyBackup(int row) {
        try {
            String name = (String) tableModel.getValueAt(row, 0);
            KeyPairInfo info = null;
            for (KeyPairInfo keyPair : KeyPairStorage.loadAllKeyPairs()) {
                if (keyPair.getName().equals(name)) {
                    info = keyPair;
                    break;
                }
            }
            if (info == null) {
                JOptionPane.showMessageDialog(this, "密钥对不存在", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            PrivateKey privateKey = crypto.key.PrivateKeyAccessService.getPrivateKey(name);
            if (privateKey == null) {
                return;
            }
            char[] password = askBackupPassword();
            if (password == null) {
                return;
            }

            try {
                String text = KeyBackupService.exportBundle(info, privateKey, password);
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("保存密钥备份");
                chooser.setFileFilter(new FileNameExtensionFilter("密钥备份 (*.txt)", "txt"));
                chooser.setSelectedFile(new File(backupFileName(name)));
                if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                    return;
                }

                File target = chooser.getSelectedFile();
                if (!target.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) {
                    target = new File(target.getParentFile(), target.getName() + ".txt");
                }
                Files.write(target.toPath(), text.getBytes(StandardCharsets.UTF_8));
                JOptionPane.showMessageDialog(
                        this,
                        "密钥备份已保存：\n" + target.getAbsolutePath(),
                        "导出成功",
                        JOptionPane.INFORMATION_MESSAGE);
            } finally {
                MemorySecurityUtils.clearCharArray(password);
            }
        } catch (Exception error) {
            JOptionPane.showMessageDialog(
                    this,
                    "导出密钥备份失败：" + error.getMessage(),
                    "错误",
                    JOptionPane.ERROR_MESSAGE);
            LogManager.error("导出密钥备份失败", error);
        }
    }

    /** 备份口令至少 8 位，并要求再次输入；取消返回 null。 */
    private char[] askBackupPassword() {
        JPasswordField first = new JPasswordField(28);
        JPasswordField second = new JPasswordField(28);
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        addFormRow(form, 0, "备份口令（至少 " + KeyBackupService.MIN_PASSWORD_LENGTH + " 位）：", first);
        addFormRow(form, 1, "再次输入口令：", second);

        while (true) {
            int result = JOptionPane.showConfirmDialog(
                    this,
                    form,
                    "设置备份口令",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return null;
            }

            char[] firstPassword = first.getPassword();
            char[] secondPassword = second.getPassword();
            if (firstPassword.length < KeyBackupService.MIN_PASSWORD_LENGTH) {
                MemorySecurityUtils.clearCharArray(firstPassword);
                MemorySecurityUtils.clearCharArray(secondPassword);
                JOptionPane.showMessageDialog(
                        this,
                        "备份口令至少 " + KeyBackupService.MIN_PASSWORD_LENGTH + " 位。",
                        "口令太短",
                        JOptionPane.WARNING_MESSAGE);
                continue;
            }
            if (!Arrays.equals(firstPassword, secondPassword)) {
                MemorySecurityUtils.clearCharArray(firstPassword);
                MemorySecurityUtils.clearCharArray(secondPassword);
                JOptionPane.showMessageDialog(
                        this,
                        "两次输入的备份口令不一致。",
                        "口令不一致",
                        JOptionPane.WARNING_MESSAGE);
                continue;
            }

            MemorySecurityUtils.clearCharArray(secondPassword);
            first.setText("");
            second.setText("");
            return firstPassword;
        }
    }

    private static void addFormRow(JPanel panel, int row, String labelText, JComponent field) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(4, 4, 4, 4);
        JLabel label = new JLabel(labelText);
        label.setFont(AppFonts.getNormalFont());
        panel.add(label, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        field.setFont(AppFonts.getNormalFont());
        panel.add(field, gbc);
    }

    private static String backupFileName(String keyName) {
        String stamp = java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return "aegix-key-backup_" + KeyBackupService.sanitizeName(keyName)
                + "_" + stamp + ".txt";
    }

    /**
     * 复制公钥到剪贴板
     */
    private void copyPublicKey(int row) {
        try {
            String name = (String) tableModel.getValueAt(row, 0);
            List<KeyPairInfo> keyPairs = KeyPairStorage.loadAllKeyPairs();
            
            for (KeyPairInfo keyPair : keyPairs) {
                if (keyPair.getName().equals(name)) {
                    StringSelection selection = new StringSelection(keyPair.getPublicKey());
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
                    JOptionPane.showMessageDialog(this, "公钥已复制到剪贴板", "成功", JOptionPane.INFORMATION_MESSAGE);
                    break;
                }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "复制公钥失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            LogManager.error("复制公钥失败", e);
        }
    }
}
