package contacts.ui;

import app.theme.AppFonts;
import app.theme.ThemeManager;
import contacts.model.Contact;
import contacts.service.ContactValidator;
import contacts.storage.ContactStorage;
import core.storage.StorageManager;
import core.util.LogManager;
import crypto.key.KeyPairManager;
import crypto.key.KeyUtils;
import keymanagement.model.KeyPairInfo;
import java.awt.BorderLayout;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import keymanagement.storage.KeyPairStorage;
import core.ui.ListManagerPanel;

public class ContactPanel extends ListManagerPanel {
    
    public ContactPanel() {
        // 更新列名，添加"绑定私钥"列
        super("联系人列表", new String[]{"名称", "添加时间", "密钥长度", "指纹", "绑定私钥"});
        
        // 允许表格内容被复制
        dataTable.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }
            
            @Override
            protected Transferable createTransferable(JComponent c) {
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
                return new StringSelection(selection.toString());
            }
        });
        
        // 添加底部按钮 - 使用自定义圆角按钮
        JButton addButton = ThemeManager.createPrimaryButton("添加联系人");
        addButton.addActionListener(e -> addContact());
        
        addButtonPanel(addButton);
        
        // 按 Delete 键触发删除（和右键菜单的删除按钮行为一致）
        dataTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
            javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteContact");
        dataTable.getActionMap().put("deleteContact", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                deleteContact();
            }
        });
        
        loadContacts();
    }
    
    private void addContact() {
        showContactDialog(null);
    }
    
    private void editContact(int row) {
        String name = (String) tableModel.getValueAt(row, 0);
        try {
            Contact contact = ContactStorage.loadContact(name);
            if (contact != null) {
                showContactDialog(contact);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "加载联系人失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void showContactDialog(Contact existingContact) {
        try {
            // 创建一个新的JDialog实例，使用FlatLaf默认主题
            JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), 
                    existingContact == null ? "添加联系人" : "编辑联系人", true);
            dialog.setResizable(false);
            
            // 创建一个新的内容面板，使用GridBagLayout实现更好的布局
            JPanel contentPanel = new JPanel();
            contentPanel.setLayout(new GridBagLayout());
            contentPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
            
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = new Insets(5, 5, 5, 5);
            
            // 联系人名称
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.anchor = GridBagConstraints.WEST;
            gbc.weightx = 0.0;
            JLabel nameLabel = new JLabel("联系人名称:");
            nameLabel.setFont(AppFonts.getNormalFont());
            contentPanel.add(nameLabel, gbc);
            
            gbc.gridx = 1;
            gbc.weightx = 1.0;
            JTextField nameField = new JTextField(40);
            nameField.setFont(AppFonts.getNormalFont());
            nameField.setBorder(ThemeManager.createRoundedBorder(1));
            if (existingContact != null) {
                nameField.setText(existingContact.getName());
                nameField.setEditable(true); // 编辑时可以改名
            }
            contentPanel.add(nameField, gbc);
            
            // 公钥（仅添加时显示）
            final JTextArea[] keyAreaHolder = new JTextArea[1];
            if (existingContact == null) {
                gbc.gridx = 0;
                gbc.gridy = 1;
                gbc.weightx = 0.0;
                gbc.anchor = GridBagConstraints.NORTHWEST;
                JLabel keyLabel = new JLabel("公钥(Base64):");
                keyLabel.setFont(AppFonts.getNormalFont());
                contentPanel.add(keyLabel, gbc);
                
                gbc.gridx = 1;
                gbc.weightx = 1.0;
                gbc.weighty = 1.0;
                gbc.fill = GridBagConstraints.BOTH;
                JTextArea keyArea = new JTextArea(10, 40);
                keyArea.setFont(AppFonts.getNormalFont());
                keyArea.setLineWrap(true);
                keyArea.setWrapStyleWord(true);
                keyArea.setBorder(ThemeManager.createTextAreaBorder());
                keyAreaHolder[0] = keyArea;
                
                // 为文本区域添加右键菜单，包含粘贴功能
                JPopupMenu popupMenu = new JPopupMenu();
                JMenuItem pasteMenuItem = new JMenuItem("粘贴");
                pasteMenuItem.setFont(AppFonts.getNormalFont());
                pasteMenuItem.addActionListener(e -> {
                    keyArea.paste();
                });
                popupMenu.add(pasteMenuItem);
                keyArea.setComponentPopupMenu(popupMenu);
                
                JScrollPane keyScrollPane = new JScrollPane(keyArea);
                keyScrollPane.setPreferredSize(new Dimension(600, 200));
                keyScrollPane.setBorder(ThemeManager.createRoundedBorder(1));
                contentPanel.add(keyScrollPane, gbc);
                
                // 绑定私钥
                gbc.gridx = 0;
                gbc.gridy = 2;
                gbc.weightx = 0.0;
                gbc.weighty = 0.0;
                gbc.fill = GridBagConstraints.HORIZONTAL;
            } else {
                // 编辑时不显示公钥，绑定私钥直接移到下一行
                gbc.gridx = 0;
                gbc.gridy = 1;
                gbc.weightx = 0.0;
                gbc.weighty = 0.0;
                gbc.fill = GridBagConstraints.HORIZONTAL;
            }
            JLabel boundKeyLabel = new JLabel("绑定私钥:");
            boundKeyLabel.setFont(AppFonts.getNormalFont());
            contentPanel.add(boundKeyLabel, gbc);
            
            gbc.gridx = 1;
            gbc.weightx = 1.0;
            JComboBox<String> boundKeyCombo = new JComboBox<>();
            boundKeyCombo.setFont(AppFonts.getNormalFont());
            
            // 加载所有密钥对
            List<KeyPairInfo> keyPairs = new java.util.ArrayList<>();
            try {
                keyPairs = KeyPairStorage.loadAllKeyPairs();
                for (KeyPairInfo keyPair : keyPairs) {
                    boundKeyCombo.addItem(keyPair.getName());
                }
            } catch (Exception e) {
                // 忽略
            }
            
            // 如果是编辑模式，设置当前绑定的密钥
            if (existingContact != null) {
                String currentBoundKey = existingContact.getBoundKeyPairName();
                if (currentBoundKey != null) {
                    boundKeyCombo.setSelectedItem(currentBoundKey);
                } else if (!keyPairs.isEmpty()) {
                    // 旧数据没有绑定，默认选择第一个密钥对
                    boundKeyCombo.setSelectedIndex(0);
                }
            } else if (!keyPairs.isEmpty()) {
                // 新添加联系人，默认选择第一个密钥对
                boundKeyCombo.setSelectedIndex(0);
            }
            
            contentPanel.add(boundKeyCombo, gbc);
            
            // 按钮
            gbc.gridx = 0;
            gbc.gridy = 3;
            gbc.gridwidth = 2;
            gbc.weightx = 0.0;
            gbc.weighty = 0.0;
            gbc.fill = GridBagConstraints.NONE;
            gbc.anchor = GridBagConstraints.CENTER;
            
            JPanel buttonPanel = new JPanel();
            buttonPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 20, 10));
            
            JButton okButton = ThemeManager.createPrimaryButton("确定");
            JButton cancelButton = ThemeManager.createNeutralButton("取消");
            
            buttonPanel.add(okButton);
            buttonPanel.add(cancelButton);
            contentPanel.add(buttonPanel, gbc);
            
            // 设置对话框的内容面板
            dialog.setContentPane(contentPanel);
            
            // 添加事件监听器
            okButton.addActionListener(e -> {
                try {
                    // 处理添加/编辑联系人逻辑
                    String name = nameField.getText().trim();
                    String publicKey;
                    
                    if (existingContact == null) {
                        // 添加模式，从输入框获取
                        publicKey = keyAreaHolder[0].getText().trim();
                        if (name.isEmpty() || publicKey.isEmpty()) {
                            JOptionPane.showMessageDialog(dialog, "请输入完整的联系人信息", "错误", JOptionPane.ERROR_MESSAGE);
                            return;
                        }
                    } else {
                        // 编辑模式，从现有联系人获取
                        publicKey = existingContact.getPublicKey();
                        if (name.isEmpty()) {
                            JOptionPane.showMessageDialog(dialog, "请输入联系人名称", "错误", JOptionPane.ERROR_MESSAGE);
                            return;
                        }
                    }
                    
                    // 获取绑定的密钥
                    String boundKeyName = (String) boundKeyCombo.getSelectedItem();
                    
                    // 检查是否选择了密钥对
                    if (boundKeyName == null || boundKeyName.isEmpty()) {
                        JOptionPane.showMessageDialog(dialog, "请先创建密钥对，再添加联系人", "错误", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    
                    // 添加时按 Android 语义校验弱密钥、自己的公钥和重复联系人。
                    String fingerprint;
                    if (existingContact == null) {
                        try {
                            fingerprint = ContactValidator.validateForAdd(publicKey);
                        } catch (IllegalArgumentException ex) {
                            JOptionPane.showMessageDialog(
                                    dialog, ex.getMessage(), "无法添加联系人", JOptionPane.ERROR_MESSAGE);
                            return;
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(
                                    dialog, "公钥校验失败：" + ex.getMessage(),
                                    "错误", JOptionPane.ERROR_MESSAGE);
                            return;
                        }
                    } else {
                        fingerprint = existingContact.getFingerprint();
                    }
                    
                    Contact contact = new Contact(name, publicKey, "RSA", fingerprint, boundKeyName);
                    
                    ContactStorage.saveContact(contact);
                    JOptionPane.showMessageDialog(dialog, 
                            existingContact == null ? "联系人添加成功！" : "联系人更新成功！", 
                            "成功", JOptionPane.INFORMATION_MESSAGE);
                    
                    loadContacts();
                    dialog.dispose();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog, "操作失败：" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    LogManager.error("联系人操作失败", ex);
                }
            });
            
            cancelButton.addActionListener(e -> dialog.dispose());
            
            // 打包并显示对话框
            dialog.pack();
            // 设置最小宽度，确保标签完全显示
            if (dialog.getWidth() < 700) {
                dialog.setSize(700, dialog.getHeight());
            }
            dialog.setLocationRelativeTo(this);
            dialog.setVisible(true);
            
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "操作失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            LogManager.error("联系人操作失败", e);
        }
    }
    
    private void deleteContact() {
        int[] selectedRows = dataTable.getSelectedRows();
        
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(this, "请选择要删除的联系人", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        String message;
        if (selectedRows.length == 1) {
            String name = (String) tableModel.getValueAt(selectedRows[0], 0);
            message = "确定要删除联系人 " + name + " 吗？";
        } else {
            message = "确定要删除选中的 " + selectedRows.length + " 个联系人吗？";
        }
        
        int confirm = JOptionPane.showConfirmDialog(this, message, "确认删除", JOptionPane.YES_NO_OPTION);
        
        if (confirm == JOptionPane.YES_OPTION) {
            try {
                int successCount = 0;
                int failCount = 0;
                for (int row : selectedRows) {
                    String name = (String) tableModel.getValueAt(row, 0);
                    boolean deleted = ContactStorage.deleteContact(name);
                    if (deleted) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                }
                if (successCount > 0) {
                    String resultMsg = selectedRows.length == 1
                        ? "联系人删除成功！"
                        : ("成功删除 " + successCount + " 个联系人" + (failCount > 0 ? "，" + failCount + " 个失败" : ""));
                    JOptionPane.showMessageDialog(this, resultMsg, "成功", JOptionPane.INFORMATION_MESSAGE);
                    loadContacts();
                } else {
                    JOptionPane.showMessageDialog(this, "删除联系人失败", "错误", JOptionPane.ERROR_MESSAGE);
                }
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "删除联系人失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                LogManager.error("删除联系人失败", e);
            }
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
            
            JMenuItem editItem = new JMenuItem("编辑联系人");
            editItem.setFont(AppFonts.getNormalFont());
            editItem.addActionListener(e -> editContact(row));
            popup.add(editItem);

            // Phase 2: salt 协议功能
            popup.addSeparator();

            String contactName = (String) tableModel.getValueAt(row, 0);
            Contact contactForSalt = null;
            try {
                contactForSalt = ContactStorage.loadContact(contactName);
            } catch (Exception ignored) {}

            // 复制我的 salt（对方公钥加密）—— 仅当联系人有绑定自己的密钥对时可用
            JMenuItem copyMySaltItem = new JMenuItem("复制我的 salt（对方公钥加密）");
            copyMySaltItem.setFont(AppFonts.getNormalFont());
            boolean hasBoundKey = contactForSalt != null && contactForSalt.getBoundKeyPairName() != null
                    && !contactForSalt.getBoundKeyPairName().isEmpty();
            copyMySaltItem.setEnabled(hasBoundKey);
            copyMySaltItem.addActionListener(e -> copyMySaltForContact(row));
            popup.add(copyMySaltItem);

            // 导入对方 salt（用我的私钥解密）—— 任何时候都可用
            JMenuItem importSaltItem = new JMenuItem("导入对方 salt（我的私钥解密）");
            importSaltItem.setFont(AppFonts.getNormalFont());
            importSaltItem.addActionListener(e -> importContactSalt(row));
            popup.add(importSaltItem);
            
            popup.addSeparator();
        }
        
        // 多选或单选都显示删除按钮
        String deleteLabel = selectedRows.length == 1 ? "删除联系人" : "删除选中的 " + selectedRows.length + " 个联系人";
        JMenuItem deleteItem = new JMenuItem(deleteLabel);
        deleteItem.setFont(AppFonts.getNormalFont());
        deleteItem.addActionListener(e -> deleteContact());
        popup.add(deleteItem);
        
        return popup;
    }
    
    /**
     * 复制公钥到剪贴板
     */
    private void copyPublicKey(int row) {
        try {
            String name = (String) tableModel.getValueAt(row, 0);
            Contact contact = ContactStorage.loadContact(name);
            if (contact != null) {
                StringSelection selection = new StringSelection(contact.getPublicKey());
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
                JOptionPane.showMessageDialog(this, "公钥已复制到剪贴板", "成功", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "复制公钥失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            LogManager.error("复制公钥失败", e);
        }
    }

    /**
     * Phase 2: 把我的 salt 用对方联系人的公钥 RSA-OAEP 加密后复制到剪贴板
     */
    private void copyMySaltForContact(int row) {
        try {
            String contactName = (String) tableModel.getValueAt(row, 0);
            Contact contact = ContactStorage.loadContact(contactName);
            if (contact == null) {
                JOptionPane.showMessageDialog(this, "联系人不存在", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // 1) 必须有绑定自己的密钥对（用来解密自己的 salt 明文）
            String boundKeyPairName = contact.getBoundKeyPairName();
            if (boundKeyPairName == null || boundKeyPairName.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "该联系人未绑定任何本地密钥对。\n请先在编辑联系人中选择一个您自己的密钥对。",
                        "无法复制", JOptionPane.WARNING_MESSAGE);
                return;
            }

            // 2) 解密我的 salt 明文（调用 PrivateKeyAccessService，会弹出密码对话框）
            byte[] mySalt = crypto.key.PrivateKeyAccessService.getMySalt(boundKeyPairName);
            if (mySalt == null) return; // 用户取消或解密失败，已有提示

            // 3) 解析对方联系人的公钥
            java.security.PublicKey contactPublicKey;
            try {
                byte[] pkBytes = java.util.Base64.getDecoder().decode(contact.getPublicKey().trim());
                contactPublicKey = crypto.key.KeyUtils.getPublicKeyFromBytes(pkBytes);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "解析对方公钥失败：" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (contactPublicKey == null) {
                JOptionPane.showMessageDialog(this, "无法解析对方公钥。", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // 4) 用对方公钥加密我的 salt
            byte[] encryptedSalt = crypto.key.PrivateKeyAccessService.encryptSaltForContact(mySalt, contactPublicKey);
            if (encryptedSalt == null) return; // 已弹出错误

            // 5) Base64 编码后复制到剪贴板
            String b64 = java.util.Base64.getEncoder().encodeToString(encryptedSalt);
            StringSelection selection = new StringSelection(b64);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);

            JOptionPane.showMessageDialog(this,
                    "已为联系人 \"" + contactName + "\" 生成加密的 salt。\n\n"
                  + "内容（Base64 编码）已复制到剪贴板，请将其发送给对方。\n\n"
                  + "对方用自己的私钥解密后即可得到您的 salt，用于与您进行隐写通信。",
                    "成功", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "复制 salt 失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            LogManager.error("复制 salt 失败", e);
        }
    }

    /**
     * Phase 2: 让用户粘贴对方传来的 salt 密文（Base64），用自己的私钥解密并存入 Contact.salt
     */
    private void importContactSalt(int row) {
        try {
            String contactName = (String) tableModel.getValueAt(row, 0);
            Contact contact = ContactStorage.loadContact(contactName);
            if (contact == null) {
                JOptionPane.showMessageDialog(this, "联系人不存在", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // 弹出对话框让用户粘贴 Base64 文本
            javax.swing.JTextArea textArea = new javax.swing.JTextArea(10, 50);
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);
            textArea.setFont(AppFonts.getNormalFont());

            JPanel panel = new JPanel(new BorderLayout(10, 10));
            panel.add(new JLabel("请粘贴对方发送给您的 salt 密文（Base64 文本）："), BorderLayout.NORTH);
            panel.add(new JScrollPane(textArea), BorderLayout.CENTER);
            panel.add(new JLabel("<html><i>说明：该密文是对方用您的公钥加密后的内容，您需要用您的私钥解密得到明文 salt。<br>"
                    + "对方应已将其绑定的密钥对与您的联系人关联。</i></html>"), BorderLayout.SOUTH);

            int result = JOptionPane.showConfirmDialog(this, panel,
                    "导入对方 salt — " + contactName, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

            if (result != JOptionPane.OK_OPTION) return;

            String encryptedSaltB64 = textArea.getText().trim();
            if (encryptedSaltB64.isEmpty()) {
                JOptionPane.showMessageDialog(this, "内容为空", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // 获取我自己的密钥对名称（通过 contact.boundKeyPairName 指向）
            String boundKeyPairName = contact.getBoundKeyPairName();
            if (boundKeyPairName == null || boundKeyPairName.isEmpty()) {
                // 也可以允许用户选择本地任一密钥对来解密
                java.util.List<String> keyPairNames = new java.util.ArrayList<>();
                try {
                    for (keymanagement.model.KeyPairInfo kpi : keymanagement.storage.KeyPairStorage.loadAllKeyPairs()) {
                        keyPairNames.add(kpi.getName());
                    }
                } catch (Exception ignored) {}
                if (keyPairNames.isEmpty()) {
                    JOptionPane.showMessageDialog(this,
                            "您还没有任何本地密钥对。请先在密钥对管理中创建一个。",
                            "无法解密", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                String chosen = (String) JOptionPane.showInputDialog(this,
                        "选择用于解密的密钥对：", "选择密钥对",
                        JOptionPane.QUESTION_MESSAGE, null,
                        keyPairNames.toArray(new String[0]), keyPairNames.get(0));
                if (chosen == null || chosen.isEmpty()) return;
                boundKeyPairName = chosen;
            }

            // 调用统一服务解密并存入 Contact.salt
            boolean ok = crypto.key.PrivateKeyAccessService.decryptAndImportContactSalt(
                    encryptedSaltB64, contactName, null, boundKeyPairName);
            if (ok) {
                // 刷新 UI
                loadContacts();
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "导入 salt 失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            LogManager.error("导入 salt 失败", e);
        }
    }
    
    private void loadContacts() {
        try {
            List<Contact> contacts = ContactStorage.loadAllContacts();
            tableModel.setRowCount(0);
            
            for (Contact contact : contacts) {
                // 获取联系人创建时间（从文件修改时间获取）
                Date createTime = null;
                try {
                    String contactPath = core.storage.StorageManager.getContactsDir() + File.separator + contact.getName() + ".pub";
                    File file = new File(contactPath);
                    if (file.exists()) {
                        createTime = new Date(file.lastModified());
                    }
                } catch (Exception e) {
                    // 忽略文件获取失败的情况
                }
                
                // 获取密钥长度（从公钥解析）
                int keySize = 0;
                try {
                    byte[] publicKeyBytes = KeyUtils.decodeBase64(contact.getPublicKey());
                    keySize = KeyUtils.getRSAPublicKeySize(publicKeyBytes);
                } catch (Exception e) {
                    keySize = 0; // 解析失败时显示0
                }
                
                // 获取绑定的密钥名称，如果没有则显示空字符串
                String boundKey = contact.getBoundKeyPairName();
                if (boundKey == null) {
                    boundKey = "";
                }
                
                Object[] row = {
                    contact.getName(),
                    formatDateTime(createTime),
                    keySize > 0 ? keySize : "未知",
                    ContactValidator.formatFingerprint(contact.getFingerprint()),
                    boundKey
                };
                tableModel.addRow(row);
            }
            
            // 自动调整所有列的宽度，使其适应内容
            packAllColumns();
            
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "加载联系人失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            LogManager.error("加载联系人列表失败", e);
        }
    }
}
