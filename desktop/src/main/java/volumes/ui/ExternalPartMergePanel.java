package volumes.ui;

import app.theme.AppFonts;
import app.theme.ThemeManager;
import app.theme.UIConstants;
import contacts.model.Contact;
import contacts.storage.ContactStorage;
import core.util.LogManager;
import crypto.key.KeyUtils;
import crypto.key.PrivateKeyAccessService;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import keymanagement.model.KeyPairInfo;
import keymanagement.storage.KeyPairStorage;
import securecomm.ui.OperationPanel;
import settings.manager.SettingsManager;
import volumes.service.ExternalPartMerger;

/**
 * 桌面端“分卷合并”页：选择 Android 生成的一组外部 .aegix 分卷，检查齐套后合并还原。
 * 单文件分卷直接得到原文件；文件夹/归档分卷得到 ZIP。
 *
 * <p>这是 P4 接收侧在桌面的入口。检查只读每卷 VOLU 元数据；合并由
 * {@link ExternalPartMerger} 完成，缺卷/混包/重卷/取消都不会留下半成品。</p>
 */
public class ExternalPartMergePanel extends OperationPanel {

    private final DefaultListModel<File> partModel = new DefaultListModel<File>();
    private final JList<File> partList = new JList<File>(partModel);
    private final JComboBox<String> keyPairCombo = new JComboBox<String>();
    private final JTextField outputDirField = new JTextField();
    private final JTextArea statusArea = new JTextArea(4, 20);
    private final JProgressBar progressBar;
    private final JButton inspectButton;
    private final JButton mergeButton;
    private final JButton cancelButton;
    private final JButton addButton;
    private final JButton removeButton;
    private final JButton clearButton;
    private final JButton openFolderButton;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private boolean busy;
    private boolean merging;
    private boolean loadingKeys;
    private boolean canMerge;
    private File lastOutput;

    public ExternalPartMergePanel() {
        super();
        progressBar = createProgressBar();
        inspectButton = createButton("检查分卷", e -> startInspection());
        mergeButton = createButton("合并还原", e -> startMerge());
        cancelButton = createRedButton("取消", e -> cancelMerge());
        addButton = createSmallButton("添加分卷…");
        removeButton = createSmallButton("移除选中");
        clearButton = createSmallButton("清空");
        openFolderButton = createSmallButton("打开输出目录");

        initComponents();
        refreshData();
        keyPairCombo.addActionListener(e -> {
            if (!loadingKeys) {
                resetInspection("已切换密钥对，请重新检查分卷。");
            }
        });
        setStatus("请选择同一套 .aegix 分卷，然后点击“检查分卷”。",
                ThemeManager.getTextColor());
        updateButtons();
    }

    private void initComponents() {
        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setOpaque(false);
        content.add(createHeaderPanel(), BorderLayout.NORTH);
        content.add(createCenterPanel(), BorderLayout.CENTER);
        content.add(createBottomPanel(), BorderLayout.SOUTH);
        add(content, BorderLayout.CENTER);
    }

    private JPanel createHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout(8, 8));
        header.setOpaque(false);

        JLabel hint = new JLabel("<html>选择 Android 加密生成的多个 .aegix 分卷"
                + "（如 _vol001、_vol002…）。检查只读取每卷元数据；"
                + "合并失败或取消不会留下半成品。</html>");
        hint.setFont(AppFonts.getNormalFont());
        hint.setForeground(ThemeManager.getTextColor());
        header.add(hint, BorderLayout.NORTH);

        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new javax.swing.BoxLayout(form, javax.swing.BoxLayout.Y_AXIS));

        JPanel keyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        keyRow.setOpaque(false);
        JLabel keyLabel = new JLabel("我的密钥对:");
        keyLabel.setFont(AppFonts.getNormalFont());
        keyRow.add(keyLabel);
        keyPairCombo.setFont(AppFonts.getNormalFont());
        keyPairCombo.setPreferredSize(new Dimension(280, 30));
        keyRow.add(keyPairCombo);
        JButton refreshButton = createSmallButton("刷新");
        refreshButton.addActionListener(e -> refreshData());
        keyRow.add(refreshButton);
        form.add(keyRow);

        JPanel outputRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        outputRow.setOpaque(false);
        JLabel outputLabel = new JLabel("输出目录:");
        outputLabel.setFont(AppFonts.getNormalFont());
        outputRow.add(outputLabel);
        outputDirField.setFont(AppFonts.getNormalFont());
        outputDirField.setPreferredSize(new Dimension(460, 30));
        outputDirField.setText(SettingsManager.getDefaultOutputDirectory());
        outputRow.add(outputDirField);
        JButton chooseOutputButton = createSmallButton("选择…");
        chooseOutputButton.addActionListener(e -> chooseOutputDirectory());
        outputRow.add(chooseOutputButton);
        form.add(outputRow);

        header.add(form, BorderLayout.CENTER);
        return header;
    }

    private JPanel createCenterPanel() {
        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.setOpaque(false);

        JPanel filesPanel = new JPanel(new BorderLayout(8, 8));
        filesPanel.setOpaque(false);
        filesPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        JLabel filesLabel = new JLabel("分卷文件");
        filesLabel.setFont(AppFonts.getBoldFont());
        filesLabel.setForeground(ThemeManager.getTextColor());
        filesPanel.add(filesLabel, BorderLayout.NORTH);

        partList.setFont(AppFonts.getNormalFont());
        partList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        partList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean isSelected,
                    boolean cellHasFocus
            ) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                if (value instanceof File) {
                    File file = (File) value;
                    label.setText(file.getName() + "    "
                            + LogManager.formatBytes(file.length()));
                }
                label.setFont(AppFonts.getNormalFont());
                return label;
            }
        });
        JScrollPane listScroll = new JScrollPane(partList);
        listScroll.setPreferredSize(new Dimension(100, 220));
        filesPanel.add(listScroll, BorderLayout.CENTER);

        JPanel fileButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        fileButtons.setOpaque(false);
        addButton.addActionListener(e -> addParts());
        fileButtons.add(addButton);
        removeButton.addActionListener(e -> removeSelectedParts());
        clearButton.addActionListener(e -> clearParts());
        fileButtons.add(removeButton);
        fileButtons.add(clearButton);
        filesPanel.add(fileButtons, BorderLayout.SOUTH);

        JPanel statusPanel = new JPanel(new BorderLayout(5, 5));
        statusPanel.setOpaque(false);
        JLabel statusLabel = new JLabel("检查结果");
        statusLabel.setFont(AppFonts.getBoldFont());
        statusLabel.setForeground(ThemeManager.getTextColor());
        statusPanel.add(statusLabel, BorderLayout.NORTH);

        statusArea.setEditable(false);
        statusArea.setLineWrap(true);
        statusArea.setWrapStyleWord(true);
        statusArea.setFont(AppFonts.getNormalFont());
        statusArea.setForeground(ThemeManager.getTextColor());
        statusArea.setBackground(ThemeManager.getPanelBackgroundColor());
        statusArea.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        JScrollPane statusScroll = new JScrollPane(statusArea);
        statusScroll.setPreferredSize(new Dimension(100, 86));
        statusPanel.add(statusScroll, BorderLayout.CENTER);

        center.add(filesPanel, BorderLayout.CENTER);
        center.add(statusPanel, BorderLayout.SOUTH);
        return center;
    }

    private JPanel createBottomPanel() {
        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        bottom.setOpaque(false);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 6));
        actions.setOpaque(false);
        cancelButton.setEnabled(false);
        openFolderButton.setEnabled(false);
        openFolderButton.addActionListener(e -> openOutputDirectory());
        actions.add(inspectButton);
        actions.add(mergeButton);
        actions.add(cancelButton);
        actions.add(openFolderButton);
        bottom.add(actions, BorderLayout.NORTH);
        bottom.add(progressBar, BorderLayout.CENTER);
        return bottom;
    }

    private void addParts() {
        fileChooser.setFileFilter(new FileNameExtensionFilter("加密分卷 (*.aegix)", "aegix"));
        fileChooser.setMultiSelectionEnabled(true);
        int result = fileChooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }

        int added = 0;
        int skipped = 0;
        for (File file : fileChooser.getSelectedFiles()) {
            if (!file.isFile()
                    || !file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".aegix")) {
                skipped++;
                continue;
            }
            if (!partModel.contains(file)) {
                partModel.addElement(file);
                added++;
            }
        }
        if (added > 0) {
            resetInspection("已添加 " + added + " 个分卷，请点击“检查分卷”。");
        }
        if (skipped > 0) {
            JOptionPane.showMessageDialog(
                    this,
                    "已跳过 " + skipped + " 个非 .aegix 文件。",
                    "提示",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void removeSelectedParts() {
        int[] selected = partList.getSelectedIndices();
        if (selected == null || selected.length == 0) {
            return;
        }
        for (int index = selected.length - 1; index >= 0; index--) {
            partModel.remove(selected[index]);
        }
        resetInspection("已移除 " + selected.length + " 个分卷，请重新检查。");
    }

    private void clearParts() {
        if (partModel.isEmpty()) {
            return;
        }
        partModel.clear();
        resetInspection("已清空分卷列表。");
    }

    private void chooseOutputDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        File current = new File(outputDirField.getText());
        if (current.isDirectory()) {
            chooser.setCurrentDirectory(current);
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            outputDirField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void startInspection() {
        final List<File> files = snapshotParts();
        final String keyName = selectedKeyPairName();
        if (!validateSelection(files, keyName)) {
            return;
        }

        canMerge = false;
        lastOutput = null;
        openFolderButton.setEnabled(false);
        setStatus("正在读取 " + files.size() + " 个分卷的元数据…",
                ThemeManager.getTextColor());
        progressBar.setIndeterminate(true);
        progressBar.setString("检查分卷…");
        beginWork(false);

        new SwingWorker<ExternalPartMerger.Inspection, Void>() {
            @Override
            protected ExternalPartMerger.Inspection doInBackground() throws Exception {
                PrivateKey privateKey = PrivateKeyAccessService.getPrivateKey(keyName);
                if (privateKey == null) {
                    return null;
                }
                return ExternalPartMerger.inspect(files, privateKey);
            }

            @Override
            protected void done() {
                progressBar.setIndeterminate(false);
                progressBar.setValue(0);
                progressBar.setString("就绪");
                try {
                    ExternalPartMerger.Inspection inspection = get();
                    if (inspection == null) {
                        setStatus("未加载私钥，检查未开始。", UIConstants.ERROR_TEXT_COLOR);
                        return;
                    }
                    setStatus(ExternalPartMergeText.inspection(inspection),
                            inspectionColor(inspection));
                    canMerge = inspection.isComplete();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    setStatus("检查被中断。", UIConstants.ERROR_TEXT_COLOR);
                } catch (ExecutionException error) {
                    setStatus(ExternalPartMergeText.checkFailure(error.getCause()),
                            UIConstants.ERROR_TEXT_COLOR);
                } finally {
                    endWork();
                }
            }
        }.execute();
    }

    private void startMerge() {
        if (!canMerge) {
            setStatus("请先检查分卷，确认齐套后再合并。", UIConstants.ERROR_TEXT_COLOR);
            return;
        }
        final List<File> files = snapshotParts();
        final String keyName = selectedKeyPairName();
        if (!validateSelection(files, keyName)) {
            return;
        }
        String outputPath = outputDirField.getText();
        if (outputPath == null || outputPath.trim().isEmpty()) {
            setStatus("请填写输出目录。", UIConstants.ERROR_TEXT_COLOR);
            return;
        }
        final File outputDirectory = new File(outputPath.trim());
        if (outputDirectory.exists() && !outputDirectory.isDirectory()) {
            setStatus("输出路径不是目录：" + outputDirectory.getAbsolutePath(),
                    UIConstants.ERROR_TEXT_COLOR);
            return;
        }

        cancelled.set(false);
        lastOutput = null;
        openFolderButton.setEnabled(false);
        progressBar.setValue(0);
        progressBar.setString("准备合并…");
        setStatus("正在合并 " + files.size() + " 个分卷…",
                ThemeManager.getTextColor());
        beginWork(true);

        new SwingWorker<ExternalPartMerger.MergeResult, ProgressUpdate>() {
            @Override
            protected ExternalPartMerger.MergeResult doInBackground() throws Exception {
                PrivateKey privateKey = PrivateKeyAccessService.getPrivateKey(keyName);
                if (privateKey == null) {
                    return null;
                }
                List<PublicKey> senders = loadSenderCandidates();
                return ExternalPartMerger.merge(
                        files,
                        privateKey,
                        senders,
                        outputDirectory,
                        null,
                        (partNumber, partCount, doneBytes, totalBytes) ->
                                publish(new ProgressUpdate(partNumber, partCount, doneBytes, totalBytes)),
                        cancelled);
            }

            @Override
            protected void process(List<ProgressUpdate> chunks) {
                if (chunks == null || chunks.isEmpty()) {
                    return;
                }
                ProgressUpdate update = chunks.get(chunks.size() - 1);
                int percent = update.totalBytes <= 0
                        ? 0
                        : (int) Math.min(100L,
                                Math.max(0L, update.doneBytes * 100L / update.totalBytes));
                progressBar.setValue(percent);
                progressBar.setString("第 " + update.partNumber + "/" + update.partCount
                        + " 卷 · " + LogManager.formatBytes(update.doneBytes)
                        + " / " + LogManager.formatBytes(update.totalBytes));
            }

            @Override
            protected void done() {
                try {
                    ExternalPartMerger.MergeResult result = get();
                    if (result == null) {
                        progressBar.setValue(0);
                        progressBar.setString("未开始合并");
                        setStatus("未加载私钥，合并未开始。", UIConstants.ERROR_TEXT_COLOR);
                        return;
                    }
                    lastOutput = result.getOutput();
                    openFolderButton.setEnabled(true);
                    progressBar.setValue(100);
                    progressBar.setString("合并完成");
                    String text = "合并完成：" + result.getPartCount() + " 卷 → "
                            + result.getOutput().getAbsolutePath()
                            + (result.isSignatureValid()
                            ? "；签名验证通过"
                            : "；未找到发送者公钥，签名未验证");
                    setStatus(text, UIConstants.SUCCESS_TEXT_COLOR);
                    JOptionPane.showMessageDialog(
                            ExternalPartMergePanel.this,
                            text,
                            "合并完成",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    setStatus("合并被中断。", UIConstants.ERROR_TEXT_COLOR);
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    String message = ExternalPartMergeText.failure(cause);
                    setStatus(message, UIConstants.ERROR_TEXT_COLOR);
                    progressBar.setValue(0);
                    progressBar.setString(ExternalPartMergeText.isCancelled(cause)
                            ? "已取消"
                            : "合并失败");
                    if (!ExternalPartMergeText.isCancelled(cause)) {
                        JOptionPane.showMessageDialog(
                                ExternalPartMergePanel.this,
                                message,
                                "合并失败",
                                JOptionPane.ERROR_MESSAGE);
                    }
                } finally {
                    endWork();
                }
            }
        }.execute();
    }

    private void cancelMerge() {
        if (!busy || !merging) {
            return;
        }
        cancelled.set(true);
        cancelButton.setEnabled(false);
        progressBar.setString("正在取消…");
        setStatus("正在取消，请稍候；已写入的临时文件会被删除。",
                ThemeManager.getTextColor());
    }

    private void openOutputDirectory() {
        File target = null;
        if (lastOutput != null) {
            target = lastOutput.getParentFile();
        }
        if (target == null) {
            target = new File(outputDirField.getText());
        }
        if (target == null || !target.isDirectory()) {
            JOptionPane.showMessageDialog(
                    this,
                    "输出目录不存在。",
                    "提示",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            if (!Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                JOptionPane.showMessageDialog(
                        this,
                        target.getAbsolutePath(),
                        "输出目录",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            Desktop.getDesktop().open(target);
        } catch (Exception error) {
            LogManager.warning("打开输出目录失败", error);
            JOptionPane.showMessageDialog(
                    this,
                    "无法打开目录：\n" + target.getAbsolutePath(),
                    "提示",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private List<PublicKey> loadSenderCandidates() {
        List<PublicKey> candidates = new ArrayList<PublicKey>();
        try {
            List<Contact> contacts = ContactStorage.loadAllContacts();
            for (Contact contact : contacts) {
                try {
                    byte[] keyBytes = KeyUtils.decodeBase64(contact.getPublicKey());
                    candidates.add(KeyUtils.getPublicKeyFromBytes(keyBytes));
                } catch (Exception error) {
                    LogManager.warning("跳过无法解析的联系人公钥: " + contact.getName(), error);
                }
            }
        } catch (Exception error) {
            LogManager.warning("加载联系人公钥失败，合并时签名将显示为未验证", error);
        }
        return candidates;
    }

    private List<File> snapshotParts() {
        List<File> files = new ArrayList<File>(partModel.size());
        for (int index = 0; index < partModel.size(); index++) {
            files.add(partModel.get(index));
        }
        return files;
    }

    private String selectedKeyPairName() {
        Object selected = keyPairCombo.getSelectedItem();
        return selected == null ? null : selected.toString();
    }

    private boolean validateSelection(List<File> files, String keyName) {
        if (files == null || files.isEmpty()) {
            setStatus("请先添加分卷文件。", UIConstants.ERROR_TEXT_COLOR);
            return false;
        }
        if (keyName == null || keyName.trim().isEmpty()) {
            setStatus("没有可用的密钥对。请先在“密钥对管理”中创建或刷新。",
                    UIConstants.ERROR_TEXT_COLOR);
            return false;
        }
        return true;
    }

    private Color inspectionColor(ExternalPartMerger.Inspection inspection) {
        switch (inspection.getStatus()) {
            case COMPLETE:
                return UIConstants.SUCCESS_TEXT_COLOR;
            case INCOMPLETE:
            case MIXED:
            case DUPLICATE:
            case INVALID_INDEX:
                return UIConstants.ERROR_TEXT_COLOR;
            default:
                return ThemeManager.getTextColor();
        }
    }

    private void beginWork(boolean isMerging) {
        busy = true;
        merging = isMerging;
        updateButtons();
    }

    private void endWork() {
        busy = false;
        merging = false;
        updateButtons();
    }

    private void resetInspection(String message) {
        canMerge = false;
        lastOutput = null;
        openFolderButton.setEnabled(false);
        progressBar.setIndeterminate(false);
        progressBar.setValue(0);
        progressBar.setString("就绪");
        setStatus(message, ThemeManager.getTextColor());
        updateButtons();
    }

    private void updateButtons() {
        boolean hasFiles = !partModel.isEmpty();
        boolean hasKey = keyPairCombo.getItemCount() > 0;
        inspectButton.setEnabled(!busy && hasFiles && hasKey);
        mergeButton.setEnabled(!busy && canMerge);
        cancelButton.setEnabled(busy && merging);
        addButton.setEnabled(!busy);
        removeButton.setEnabled(!busy && hasFiles);
        clearButton.setEnabled(!busy && hasFiles);
        keyPairCombo.setEnabled(!busy);
        outputDirField.setEnabled(!busy);
    }

    private void setStatus(String message, Color color) {
        statusArea.setText(message == null ? "" : message);
        statusArea.setForeground(color == null ? ThemeManager.getTextColor() : color);
        statusArea.setCaretPosition(0);
    }

    @Override
    public void refreshData() {
        String previous = selectedKeyPairName();
        loadingKeys = true;
        keyPairCombo.removeAllItems();
        try {
            List<KeyPairInfo> keyPairs = KeyPairStorage.loadAllKeyPairs();
            for (KeyPairInfo keyPair : keyPairs) {
                keyPairCombo.addItem(keyPair.getName());
            }
            if (previous != null) {
                keyPairCombo.setSelectedItem(previous);
            }
            if (keyPairCombo.getSelectedItem() == null) {
                try {
                    String defaultName = KeyPairStorage.getDefaultKeyPair();
                    if (defaultName != null) {
                        keyPairCombo.setSelectedItem(defaultName);
                    }
                } catch (Exception error) {
                    LogManager.warning("读取默认密钥对失败", error);
                }
            }
            if (keyPairCombo.getSelectedItem() == null && keyPairCombo.getItemCount() > 0) {
                keyPairCombo.setSelectedIndex(0);
            }
        } catch (Exception error) {
            LogManager.error("加载密钥对失败", error);
            setStatus("加载密钥对失败：" + error.getMessage(), UIConstants.ERROR_TEXT_COLOR);
        } finally {
            loadingKeys = false;
        }
        if (keyPairCombo.getItemCount() == 0) {
            setStatus("没有可用的密钥对。请先在“密钥对管理”中创建密钥对。",
                    UIConstants.ERROR_TEXT_COLOR);
        }
        updateButtons();
    }

    private static final class ProgressUpdate {
        final int partNumber;
        final int partCount;
        final long doneBytes;
        final long totalBytes;

        ProgressUpdate(int partNumber, int partCount, long doneBytes, long totalBytes) {
            this.partNumber = partNumber;
            this.partCount = partCount;
            this.doneBytes = doneBytes;
            this.totalBytes = totalBytes;
        }
    }
}
