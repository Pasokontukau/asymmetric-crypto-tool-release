package securecomm.ui;

import app.MainFrame;
import app.theme.AppFonts;
import app.theme.ThemeManager;
import app.theme.UIConstants;
import contacts.model.Contact;
import contacts.storage.ContactStorage;
import core.event.PasswordCacheClearListener;
import core.model.FileInfo;
import core.model.SignedAndEncryptedData;
import core.util.FolderZipUtils;
import core.util.LogManager;
import crypto.key.KeyUtils;
import securecomm.ui.util.WorkerCallback;
import history.model.HistoryType;
import history.model.RecordCategory;
import keymanagement.model.KeyPairInfo;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.PublicKey;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import keymanagement.storage.KeyPairStorage;
import securecomm.ui.OperationPanel;
import securecomm.ui.util.DecryptResult;
import securecomm.ui.util.FileTransferable;
import securecomm.ui.util.SecureTextStringUtils;
import securecomm.ui.util.TempFileManager;
import settings.manager.SettingsManager;
import settings.storage.SettingsStorage;
import stegano.ui.SteganoPanel;

public class SecureTextPanel extends OperationPanel {

    // 閻劋绨〒鍛倞閻ㄥ嫰娼ら幀浣哥穿閻劌鍨悰?
    private static java.util.List<SecureTextPanel> instances = new java.util.ArrayList<>();

    // 大文件阈值 - 用于判断文件是否需要特殊处理

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // 退出只清内存；便捷模式的系统密钥链密码需要跨进程保留。
            crypto.key.PrivateKeyAccessService.clearMemoryPasswordCaches();
        }));
    }

    /**
     * 清除所有缓存的密码（通过 PrivateKeyAccessService 统一处理）
     */
    public static void clearAllCachedPasswords() {
        crypto.key.PrivateKeyAccessService.clearAllPasswordCaches();
    }

    /**
     * 清除指定密钥对的密码缓存（根据名称查找并通过 PrivateKeyAccessService 统一处理）
     */
    public static void clearCachedPasswordFor(String keyName) {
        if (keyName != null) {
            crypto.key.PrivateKeyAccessService.clearPasswordCache(keyName);
        }
    }

    // private JComboBox<String> myKeyPairCombo; // 预留：当前密钥对下拉框（可能用于密钥对切换）
    private JComboBox<String> otherPublicKeyCombo;

    // 当前密钥对的名称（由用户选择或联系人绑定）。注意：密钥对本身的密码缓存通过 PrivateKeyAccessService 统一处理
    private String currentKeyPairName = null;
    private boolean isInitialized = false; // 标志：UI 是否完成初始化，避免在构造过程中触发事件导致空指针

    private JTextArea inputArea;
    private JTextArea outputArea;
    private JLabel verifyResultLabel;
    private JProgressBar progressBar;
    /** 批量任务的取消标记；没有批量任务在跑时为 null。 */
    private java.util.concurrent.atomic.AtomicBoolean batchCancelled;
    /** 批量任务的取消按钮，只在任务进行中可见。 */
    private JButton cancelButton;

    // 输入区滚动面板
    private JScrollPane inputScrollPane;

    // ============== 文件列表组件声明 ==============
    /** 文件列表 */
    /** DR-3 3.1：选择模型与规则交给 controller；面板只留界面模型。 */
    private final securecomm.controller.FileSelectionController fileSelection =
            new securecomm.controller.FileSelectionController(
                    this::discardTemporaryArchive,
                    TempFileManager::isZipFile);
    /** 文件列表组件 */
    private JList<java.io.File> fileListComponent;
    /** 文件列表模型 */
    private DefaultListModel<java.io.File> fileListModel;
    /** 文件列表滚动面板 */
    private JScrollPane fileListScrollPane;
    /** 文件列表 + 详情面板 */
    private JPanel combinedPanel;
    /** 文件详情标签 */
    private JLabel fileDetailsLabel;
    /** 文件详情容器 */
    private Box fileDetailsBox;
    /** 本次是否把多个普通文件打包成一个加密包；默认值来自设置页。 */
    private JCheckBox packageMultiFilesCheckBox;
    private boolean packageChoiceWasEligible = false;
    /** 中央面板 */
    private JPanel centerPanel;
    /** 卡片布局 */
    private java.awt.CardLayout cardLayout;

    // ========== 输出文件列表及相关组件 ==========
    /** 输出文件列表 */
    private java.util.List<FileInfo> outputFilesList = new java.util.ArrayList<>();
    /** 输出文件列表组件 */
    private JList<FileInfo> outputFileListComponent;
    /** 输出文件列表模型 */
    private DefaultListModel<FileInfo> outputFileListModel;
    /** 输出文件列表滚动面板 */
    private JScrollPane outputFileListScrollPane;
    /** 输出文件列表 + 详情面板 */
    private JPanel outputCombinedPanel;
    /** 输出文件详情标签 */
    private JLabel outputFileDetailsLabel;
    /** 输出文件详情容器 */
    private Box outputFileDetailsBox;
    /** 输出区中央面板 */
    private JPanel outputCenterPanel;
    /** 输出区卡片布局 */
    private java.awt.CardLayout outputCardLayout;

    // 保存和复制按钮
    private JButton saveButton;
    private JButton copyButton;
    private JButton openLocationButton;

    // 分卷压缩处理支持
    private static final core.event.PasswordCacheClearListener cacheClearListener = new core.event.PasswordCacheClearListener() {
        @Override
        public void onClearPasswordCache(String keyPairName) {
            clearCachedPasswordFor(keyPairName);
        }

        @Override
        public void onClearAllPasswordCaches() {
            clearAllCachedPasswords();
        }
    };

    static {
        // 濞夈劌鍞介惄鎴濇儔閸ｃ劌鍩孠eyPairStorage
        keymanagement.storage.KeyPairStorage.addPasswordCacheClearListener(cacheClearListener);
        // 濞夈劌鍞界€瑰鍙忕痪褍鍩嗛崣妯绘纯閻╂垵鎯夐崳?
        settings.storage.SettingsStorage.addSecurityLevelChangeListener((newLevel, oldLevel) -> {
            // 婵″倹鐏夐崚鍥ㄥ床閸掗寮楅弽鍏寄佸蹇ョ礉濞撳懘娅庨幍鈧張澶岀处鐎涙ê鐦戦惍?
            if (settings.storage.SettingsStorage.SECURITY_LEVEL_STRICT.equals(newLevel)) {
                clearAllCachedPasswords();
            }
        });
    }

    public SecureTextPanel() {
        super();
        synchronized (instances) {
            instances.add(this);
        }
        initComponents();
        // 閸掓繂顫愰崠鏍▉濞堝吀绗夌憴锕€褰傚瑙勵攱閿涘本澧滈崝銊ュ鏉炶姤鏆熼幑?        // 閳跨媴绗?娑撳秹娓剁憰?loadKeyPairsWithPrompt()閿涗椒绗夐棁鈧憰浣侯潌闁姐儵鈧瀚ㄥ鍡磼
        loadAllPublicKeys(otherPublicKeyCombo);
        // 閺堚偓閸氬骸鍟€鐠佸墽鐤嗛崚婵嗩潗閸栨牕鐣幋?
        isInitialized = true;
        // 閳跨媴绗?閸掓繂顫愰崠鏍х暚閹存劕鎮楅敍灞惧閸斻劏袝閸欐垳绔村▎陇浠堢化璁虫眽闁瀚ㄦ径鍕倞閿涘瞼鈥樻穱?currentKeyPairName 鐞氼偉顔曠純顕嗙礄闂堟瑩绮Ο鈥崇础閿涘奔绗夊褰掓晩鐠囶垱顢嬮敍?
        handleContactSelectionChangeSilent();
    }

    private void initComponents() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 妞ゅ爼鍎撮幒褍鍩楅棃銏℃緲
        JPanel controlPanel = createControlPanel();

        // 閳跨媴绗?閸掔娀娅?myKeyPairCombo閿涗椒绗夐棁鈧憰浣侯潌闁姐儵鈧瀚ㄥ鍡磼

        otherPublicKeyCombo = createComboBox();
        otherPublicKeyCombo.addActionListener(e -> handleContactSelectionChange());
        // 鐠佸墽鐤嗘稉瀣濡楀棙娲挎径褏娈戠€硅棄瀹?
        otherPublicKeyCombo.setPreferredSize(new Dimension(500, 30));
        controlPanel.add(otherPublicKeyCombo);

        mainPanel.add(controlPanel, BorderLayout.NORTH);

        // 分割面板（左右布局，输入+输出）
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(450);
        splitPane.setResizeWeight(0.5);

        // 创建输入面板
        JPanel inputPanel = createInputPanel();

        // 创建输出面板
        JPanel outputPanel = createOutputPanel();

        splitPane.setLeftComponent(inputPanel);
        splitPane.setRightComponent(outputPanel);

        mainPanel.add(splitPane, BorderLayout.CENTER);

        // 按钮面板（加密/解密按钮）
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10));
        buttonPanel.setBackground(ThemeManager.getPanelBackgroundColor());

        JButton encryptSignBtn = createButton("加密并签名", e -> encryptAndSign());
        buttonPanel.add(encryptSignBtn);

        JButton decryptVerifyBtn = createRedButton("解密并验证", e -> decryptAndVerify());
        buttonPanel.add(decryptVerifyBtn);

        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        // 进度条相关组件（底部显示操作进度）
        progressBar = createProgressBar();
        JPanel progressPanel = new JPanel(new BorderLayout());
        progressPanel.setBackground(ThemeManager.getPanelBackgroundColor());
        progressPanel.add(progressBar, BorderLayout.CENTER);

        cancelButton = createButton("取消", e -> requestBatchCancel());
        cancelButton.setVisible(false);
        progressPanel.add(cancelButton, BorderLayout.EAST);

        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.setBackground(ThemeManager.getPanelBackgroundColor());
        southPanel.add(buttonPanel, BorderLayout.NORTH);
        southPanel.add(progressPanel, BorderLayout.SOUTH);

        mainPanel.add(southPanel, BorderLayout.SOUTH);

        add(mainPanel, BorderLayout.CENTER);
    }

    private JPanel createInputPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBackground(ThemeManager.getPanelBackgroundColor());

        JPanel labelPanel = new JPanel(new BorderLayout(5, 0));
        labelPanel.setBackground(ThemeManager.getPanelBackgroundColor());

        JLabel label = new JLabel("输入区");
        label.setFont(AppFonts.getNormalFont());
        labelPanel.add(label, BorderLayout.WEST);

        JPanel buttonContainer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        buttonContainer.setBackground(ThemeManager.getPanelBackgroundColor());

        JButton loadButton = createSmallButton("加载文件");
        loadButton.addActionListener(e -> loadFromFile());
        buttonContainer.add(loadButton);

        JButton clearButton = createSmallButton("清空");
        clearButton.addActionListener(e -> setTextMode());
        buttonContainer.add(clearButton);

        labelPanel.add(buttonContainer, BorderLayout.EAST);

        panel.add(labelPanel, BorderLayout.NORTH);

        // ============== 娑擃參妫块柈銊ュ瀻閿涙碍鏋冮張顒€灏崺?/ 閺傚洣娆㈤崚妤勩€?鐠囷附鍎?==============
        JPanel centerPanel = new JPanel(new java.awt.CardLayout(5, 5));
        centerPanel.setBackground(ThemeManager.getPanelBackgroundColor());

        inputArea = createTextArea();
        inputScrollPane = new JScrollPane(inputArea);
        centerPanel.add(inputScrollPane, "TEXT_MODE");

        // 閺傚洣娆㈤崚妤勩€?+ 鐠囷附鍎忛棃銏℃緲
        combinedPanel = new JPanel(new BorderLayout(0, 5));
        combinedPanel.setBackground(ThemeManager.getPanelBackgroundColor());

        JPanel packageChoiceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        packageChoiceRow.setBackground(ThemeManager.getPanelBackgroundColor());
        packageMultiFilesCheckBox = new JCheckBox("打包为一个加密包（多个文件）");
        packageMultiFilesCheckBox.setFont(AppFonts.getSmallFont());
        packageMultiFilesCheckBox.setForeground(ThemeManager.getTextColor());
        packageMultiFilesCheckBox.setBackground(ThemeManager.getPanelBackgroundColor());
        packageMultiFilesCheckBox.setSelected(SettingsStorage.isPackageMultiFilesDefault());
        packageMultiFilesCheckBox.setEnabled(false);
        packageChoiceRow.add(packageMultiFilesCheckBox);
        combinedPanel.add(packageChoiceRow, BorderLayout.NORTH);

        // 閺傚洣娆㈤崚妤勩€?
        fileListModel = new DefaultListModel<>();
        fileListComponent = new JList<>(fileListModel);
        fileListComponent.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        fileListComponent.setFont(AppFonts.getNormalFont());

        // 閼奉亜鐣炬稊澶嬭閺屾挸娅掗敍姘▔缁€鐑樻瀮娴犺泛鎮曢崪灞姐亣鐏?
        fileListComponent.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setFont(AppFonts.getNormalFont());
                if (value instanceof java.io.File) {
                    java.io.File file = (java.io.File) value;
                    String displayText;

                    if (file.isDirectory()) {
                        // 文件夹
                        long size = core.util.FolderZipUtils.calculateFolderSize(file);
                        String sizeStr;
                        if (size < 1024) {
                            sizeStr = size + " B";
                        } else if (size < 1024 * 1024) {
                            sizeStr = String.format("%.1f KB", size / 1024.0);
                        } else {
                            sizeStr = String.format("%.1f MB", size / (1024.0 * 1024.0));
                        }
                        displayText = "[文件夹] " + file.getName() + " (" + sizeStr + ")";
                    } else {
                        // 普通文件
                        long size = file.length();
                        String sizeStr;
                        if (size < 1024) {
                            sizeStr = size + " B";
                        } else if (size < 1024 * 1024) {
                            sizeStr = String.format("%.1f KB", size / 1024.0);
                        } else {
                            sizeStr = String.format("%.1f MB", size / (1024.0 * 1024.0));
                        }
                        displayText = file.getName() + " (" + sizeStr + ")";
                    }

                    setText(displayText);
                }
                return this;
            }
        });

        fileListScrollPane = new JScrollPane(fileListComponent);
        combinedPanel.add(fileListScrollPane, BorderLayout.CENTER);

        // 文件详情面板
        JPanel fileDetailsPanel = new JPanel(new BorderLayout(5, 0));
        fileDetailsPanel.setBackground(ThemeManager.getPanelBackgroundColor());

        // 用 Box 实现垂直布局，支持 HTML 标签
        this.fileDetailsBox = Box.createVerticalBox();
        this.fileDetailsBox.setBackground(ThemeManager.getPanelBackgroundColor());
        fileDetailsLabel = new JLabel("未选择文件");
        fileDetailsLabel.setFont(AppFonts.getNormalFont());
        fileDetailsLabel.setForeground(ThemeManager.getTextColor());
        this.fileDetailsBox.add(fileDetailsLabel);
        fileDetailsPanel.add(this.fileDetailsBox, BorderLayout.WEST);

        // 缂佹瑨顕涢幆鍛存桨閺夊灝濮炲姘З閺?
        JScrollPane fileDetailsScrollPane = new JScrollPane(fileDetailsPanel);
        fileDetailsScrollPane.setPreferredSize(new Dimension(0, 130));
        fileDetailsScrollPane.setBorder(ThemeManager.createTextAreaBorder());
        fileDetailsScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        fileDetailsScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        // 设置平滑滚动
        fileDetailsScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        fileDetailsScrollPane.getHorizontalScrollBar().setUnitIncrement(16);

        combinedPanel.add(fileDetailsScrollPane, BorderLayout.SOUTH);

        centerPanel.add(combinedPanel, "FILE_MODE");

        // 保存对 centerPanel 和其 cardLayout 的引用（用于切换文本/文件模式）
        this.centerPanel = centerPanel;
        this.cardLayout = (java.awt.CardLayout) centerPanel.getLayout();

        panel.add(centerPanel, BorderLayout.CENTER);

        // ============== 文件列表选择变化处理 ==============
        fileListComponent.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateFileDetails();
            }
        });

        // 设置文件拖放目标（支持拖拽文件到面板
        makeDropTarget(panel);
        makeDropTarget(inputArea);
        makeDropTarget(fileListComponent);

        // 为输入区和文件列表组件设置右键菜单
        addCustomRightClickMenu(inputArea);
        addCustomRightClickMenu(fileListComponent);

        return panel;
    }

    // 添加右键菜单支持
    private void makeDropTarget(java.awt.Component component) {
        new java.awt.dnd.DropTarget(component, new java.awt.dnd.DropTargetAdapter() {
            @Override
            public void drop(java.awt.dnd.DropTargetDropEvent dtde) {
                try {
                    if (dtde.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                        dtde.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY);

                        @SuppressWarnings("unchecked")
                        java.util.List<java.io.File> files = (java.util.List<java.io.File>)
                            dtde.getTransferable().getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);

                        if (!files.isEmpty()) {
                            // ========== 根据文件类型智能处理 ==========
                            handleFilesIntelligently(files.toArray(new java.io.File[0]));
                        }

                        dtde.dropComplete(true);
                    } else {
                        dtde.rejectDrop();
                    }
                } catch (Exception ex) {
                    LogManager.error("处理文件拖拽失败", ex);
                }
            }
        });
    }

    // ========== 智能文件处理（分卷压缩+加密）==========
    private void handleFilesIntelligently(java.io.File[] files) {
        // 所有文件（含 .txt）一律进文件列表，统一产出 .aegix 容器；
        // 文本框只服务“直接输入文本”的场景，不再把文件内容包成 Base64 文本。
        addFilesToFileList(files);
    }

    // ============== 文件列表选择变化处理 ==============

    /**
     * 更新输入文件详情显示
     */
    private void updateFileDetails() {
        // 清空详情显示
        fileDetailsBox.removeAll();

        int selectedCount = fileListComponent.getSelectedIndices().length;
        int totalCount = fileSelection.size();

        if (selectedCount == 1) {
            java.io.File selectedFile = fileListComponent.getSelectedValue();
            securecomm.controller.FileSelectionController.ItemView view =
                    fileSelection.describe(selectedFile);
            if (view != null) {
                JLabel fileNameLabel = new JLabel("文件: " + view.getDisplayName());
                fileNameLabel.setFont(AppFonts.getNormalFont());
                fileNameLabel.setForeground(ThemeManager.getTextColor());
                fileDetailsBox.add(fileNameLabel);

                JLabel sizeLabel = new JLabel("大小: " + view.getSizeText());
                sizeLabel.setFont(AppFonts.getNormalFont());
                sizeLabel.setForeground(ThemeManager.getTextColor());
                fileDetailsBox.add(sizeLabel);

                JLabel pathLabel = new JLabel("路径: " + view.getPath());
                pathLabel.setFont(AppFonts.getNormalFont());
                pathLabel.setForeground(ThemeManager.getTextColor());
                fileDetailsBox.add(pathLabel);
            }
        } else {
            // 没有选中文件或选中多个文件时，显示总数量
            JLabel label = new JLabel("共 " + totalCount + " 个文件");
            label.setFont(AppFonts.getNormalFont());
            label.setForeground(ThemeManager.getTextColor());
            fileDetailsBox.add(label);
        }

        // 刷新UI
        fileDetailsBox.revalidate();
        fileDetailsBox.repaint();
        updatePackageChoiceState();
    }

    /**
     * 只有“至少两个普通文件、且不含文件夹临时 ZIP”才允许打包；
     * 每次从未满足变为满足时套用设置页的默认值，用户手动改动后保持。
     */
    private void updatePackageChoiceState() {
        if (packageMultiFilesCheckBox == null) {
            return;
        }
        boolean eligible = fileSelection.canPackage();
        if (eligible && !packageChoiceWasEligible) {
            packageMultiFilesCheckBox.setSelected(SettingsStorage.isPackageMultiFilesDefault());
        } else if (!eligible) {
            packageMultiFilesCheckBox.setSelected(false);
        }
        packageChoiceWasEligible = eligible;
        packageMultiFilesCheckBox.setEnabled(eligible);
    }

    // ========== 文件处理 Worker ==========
    /** DR-2：添加文件 Worker 的界面动作，全部委托回面板现有方法，行为不变。 */
    private class PanelAddFilesHost implements securecomm.ui.worker.AddFilesHost {
        @Override
        public boolean isAlreadySelected(java.io.File file) {
            return fileSelection.contains(file);
        }

        @Override
        public void addSelectedFile(java.io.File file) {
            if (fileSelection.add(file)) {
                fileListModel.addElement(file);
            }
        }

        @Override
        public void showStatus(String text, boolean error) {
            verifyResultLabel.setText(text);
            verifyResultLabel.setForeground(error
                    ? UIConstants.ERROR_TEXT_COLOR
                    : ThemeManager.getTextColor());
        }

        @Override
        public void finishAdd(int addedCount) {
            if (addedCount > 0) {
                cardLayout.show(centerPanel, "FILE_MODE");
                updateFileDetails();
                verifyResultLabel.setText("成功添加" + addedCount + "个文件");
                verifyResultLabel.setForeground(UIConstants.SUCCESS_TEXT_COLOR);
                progressBar.setValue(100);
                progressBar.setString("文件处理完成");
            }
            updateSaveButtonState();
        }
    }

    /**
     * 由双击 .aegix 或命令行传入文件时调用：与拖入走同一条判型与解密分流。
     */
    public void openExternalFiles(java.util.List<java.io.File> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        addFilesToFileList(files.toArray(new java.io.File[0]));
    }

    /**
     * 把外部传入的文件/文件夹加入输入列表：普通文件直接加入，文件夹先打成临时 ZIP。
     */
    private void addFilesToFileList(java.io.File[] files) {
        // 判型与缺失过滤在 controller；文件夹仍由 Worker 打成临时 ZIP。
        securecomm.controller.FileSelectionController.AddPlan plan = fileSelection.planAdd(files);
        new securecomm.ui.worker.AddFilesWorker(
                new PanelAddFilesHost(), progressBar, plan.getPlainFiles(), plan.getFolders()).execute();
    }



    /**
     * 从文件列表移除选中的文件
     */
    private void removeSelectedFiles() {
        fileSelection.removeByIndices(fileListComponent.getSelectedIndices());
        refreshFileListModel();

        updatePackageChoiceState();

        // 如果列表为空，切换回文本模式；否则更新文件列表提示文字
        if (fileSelection.isEmpty()) {
            setTextMode();
        } else {
            updateFileListHint();
        }
    }

    /** 临时归档被移出选择时的清理；由 controller 回调。 */
    private void discardTemporaryArchive(java.io.File file) {
        if (TempFileManager.isZipFile(file)) {
            TempFileManager.unregisterZipFile(file);
            core.util.FolderZipUtils.safeDelete(file);
        }
    }

    /** 界面模型与 controller 对齐（删除/批量收尾后用）。 */
    private void refreshFileListModel() {
        fileListModel.clear();
        for (java.io.File file : fileSelection.files()) {
            fileListModel.addElement(file);
        }
    }

    /**
     * 更新文件列表提示
     */
    private void updateFileListHint() {
        String volumeLimit = SettingsStorage.getVolumeLimitPreset().getLabel();
        inputArea.setText("已选择 " + fileSelection.size() + " 个文件\n"
                + "加密按钮用于加密；解密支持 .aegix 单文件与单卷外部包\n"
                + securecomm.service.PartSetPromptText.volumeSelectionHint() + "\n"
                + "超过当前分卷大小（" + volumeLimit + "/卷）会自动拆成多卷，请把全部分卷一起发送；可在「设置 → 文件处理」修改");
    }

    private JPanel createOutputPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBackground(ThemeManager.getPanelBackgroundColor());

        JPanel labelPanel = new JPanel(new BorderLayout(5, 0));
        labelPanel.setBackground(ThemeManager.getPanelBackgroundColor());

        JLabel label = new JLabel("输出区");
        label.setFont(AppFonts.getNormalFont());
        labelPanel.add(label, BorderLayout.WEST);

        JPanel buttonContainer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        buttonContainer.setBackground(ThemeManager.getPanelBackgroundColor());

        saveButton = createSmallButton("保存");
        saveButton.addActionListener(e -> saveToFile());
        saveButton.setVisible(false); // 初始隐藏
        buttonContainer.add(saveButton);

        copyButton = createSmallButton("复制");
        copyButton.addActionListener(e -> {
            outputArea.selectAll();
            outputArea.copy();
        });
        copyButton.setVisible(false); // 初始隐藏
        buttonContainer.add(copyButton);

        openLocationButton = createSmallButton("打开文件所在位置");
        openLocationButton.addActionListener(e -> openOutputFileLocation());
        openLocationButton.setVisible(false); // 初始隐藏
        buttonContainer.add(openLocationButton);

        labelPanel.add(buttonContainer, BorderLayout.EAST);

        panel.add(labelPanel, BorderLayout.NORTH);

        // 使用 CardLayout 实现文本和文件模式的切换
        outputCenterPanel = new JPanel(new java.awt.CardLayout(0, 5));
        outputCenterPanel.setBackground(ThemeManager.getPanelBackgroundColor());

        // 文本模式输出区
        outputArea = createReadOnlyTextArea();
        JScrollPane outputTextScrollPane = new JScrollPane(outputArea);
        // 为 outputArea 添加右键菜单，支持复制和发送
        JPopupMenu outputAreaPopup = new JPopupMenu();
        JMenuItem copyOutputMenuItem = new JMenuItem("复制");
        copyOutputMenuItem.setFont(AppFonts.getNormalFont());
        copyOutputMenuItem.addActionListener(e -> {
            outputArea.selectAll();
            outputArea.copy();
        });
        outputAreaPopup.add(copyOutputMenuItem);
        JMenuItem sendToSteganoFromText = new JMenuItem("发送到隐写面板");
        sendToSteganoFromText.setFont(AppFonts.getNormalFont());
        sendToSteganoFromText.addActionListener(e -> sendOutputToSteganoPanel(false));
        outputAreaPopup.add(sendToSteganoFromText);
        outputArea.setComponentPopupMenu(outputAreaPopup);
        outputCenterPanel.add(outputTextScrollPane, "TEXT_MODE");

        // 文件模式输出区
        outputCombinedPanel = new JPanel(new BorderLayout(0, 5));
        outputCombinedPanel.setBackground(ThemeManager.getPanelBackgroundColor());

        // 文件列表模型
        outputFileListModel = new DefaultListModel<>();
        outputFileListComponent = new JList<>(outputFileListModel);
        outputFileListComponent.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        outputFileListComponent.setFont(AppFonts.getNormalFont());

        // 自定义渲染器，支持文件夹和分卷文件的显示
        outputFileListComponent.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setFont(AppFonts.getNormalFont());
                if (value instanceof FileInfo) {
                    FileInfo fileInfo = (FileInfo) value;
                    String fileName = fileInfo.getFileName();
                    long fileSize = fileInfo.getFileSize();
                    boolean isFolder = fileInfo.getIsFolder();
                    boolean isSplit = fileInfo.getIsSplit();
                    Integer volumeCount = fileInfo.getVolumeCount();
                    Integer currentVolume = fileInfo.getCurrentVolume();

                    String sizeStr;
                    if (fileSize < 1024) {
                        sizeStr = fileSize + " B";
                    } else if (fileSize < 1024 * 1024) {
                        sizeStr = String.format("%.1f KB", fileSize / 1024.0);
                    } else if (fileSize < 1024 * 1024 * 1024) {
                        sizeStr = String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
                    } else {
                        sizeStr = String.format("%.1f GB", fileSize / (1024.0 * 1024.0 * 1024.0));
                    }

                    String displayText;
                    if (isSplit && volumeCount != null && currentVolume != null) {
                        displayText = "[分卷 " + currentVolume + "/" + volumeCount + "] "
                                + fileName + " (" + sizeStr + ")";
                    } else if (isSplit && volumeCount != null) {
                        displayText = "[分卷文件] " + fileName + " (" + volumeCount + " 个分卷)";
                    } else if (isFolder) {
                        displayText = "[文件夹] " + fileName + " (" + sizeStr + ")";
                    } else {
                        displayText = fileName + " (" + sizeStr + ")";
                    }

                    setText(displayText);
                }
                return this;
            }
        });

        outputFileListScrollPane = new JScrollPane(outputFileListComponent);
        outputCombinedPanel.add(outputFileListScrollPane, BorderLayout.CENTER);

        // 文件详情显示区域
        JPanel outputFileDetailsPanel = new JPanel(new BorderLayout(5, 0));
        outputFileDetailsPanel.setBackground(ThemeManager.getPanelBackgroundColor());

        // 用 Box 实现垂直布局，支持 HTML 标签
        this.outputFileDetailsBox = Box.createVerticalBox();
        this.outputFileDetailsBox.setBackground(ThemeManager.getPanelBackgroundColor());
        outputFileDetailsLabel = new JLabel("未选择文件");
        outputFileDetailsLabel.setFont(AppFonts.getNormalFont());
        outputFileDetailsLabel.setForeground(ThemeManager.getTextColor());
        this.outputFileDetailsBox.add(outputFileDetailsLabel);
        outputFileDetailsPanel.add(this.outputFileDetailsBox, BorderLayout.WEST);

        // 滚动区域支持垂直滚动
        JScrollPane outputFileDetailsScrollPane = new JScrollPane(outputFileDetailsPanel);
        outputFileDetailsScrollPane.setPreferredSize(new Dimension(0, 130));
        outputFileDetailsScrollPane.setBorder(ThemeManager.createTextAreaBorder());
        outputFileDetailsScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        outputFileDetailsScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        outputFileDetailsScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        outputFileDetailsScrollPane.getHorizontalScrollBar().setUnitIncrement(16);

        outputCombinedPanel.add(outputFileDetailsScrollPane, BorderLayout.SOUTH);

        outputCenterPanel.add(outputCombinedPanel, "FILE_MODE");

        // 姒涙顓婚弰鍓с仛閺傚洦婀板Ο鈥崇础
        outputCardLayout = (java.awt.CardLayout) outputCenterPanel.getLayout();

        // 鏉堟挸鍤弬鍥︽閸掓銆冮柅澶夎厬閻╂垵鎯?
        outputFileListComponent.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateOutputFileDetails();
            }
        });

        // 添加输出文件列表的双击事件处理
        outputFileListComponent.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int index = outputFileListComponent.locationToIndex(e.getPoint());
                    if (index >= 0) {
                        FileInfo fileInfo = outputFileListModel.getElementAt(index);

                        // 检查签名是否验证失败，如果失败则提示用户
                        if (fileInfo.getSignatureValid() != null && !fileInfo.getSignatureValid()) {
                            String[] options = {"打开文件", "删除文件", "取消"};
                            int option = javax.swing.JOptionPane.showOptionDialog(
                                SecureTextPanel.this,
                                "签名验证失败，该文件可能被篡改或来源不可信。您确定要打开或删除吗？",
                                "警告",
                                javax.swing.JOptionPane.DEFAULT_OPTION,
                                javax.swing.JOptionPane.WARNING_MESSAGE,
                                null,
                                options,
                                options[0]
                            );

                            if (option == 0) {
                                // 打开文件，继续后续逻辑
                            } else if (option == 1) {
                                // 删除文件
                                String filePath = fileInfo.getFilePath();
                                if (filePath != null) {
                                    java.io.File file = new java.io.File(filePath);
                                    if (file.exists()) {
                                        boolean deleted = core.util.FolderZipUtils.safeDelete(file);

                                        if (deleted) {
                                            // 从列表中移除并刷新显示
                                            outputFilesList.remove(fileInfo);
                                            outputFileListModel.removeElement(fileInfo);
                                            updateOutputFileDetails();
                                            javax.swing.JOptionPane.showMessageDialog(SecureTextPanel.this, "文件已成功删除", "提示", javax.swing.JOptionPane.INFORMATION_MESSAGE);
                                        } else {
                                            javax.swing.JOptionPane.showMessageDialog(SecureTextPanel.this, "文件删除失败，请检查权限", "错误", javax.swing.JOptionPane.ERROR_MESSAGE);
                                        }
                                    }
                                }
                                return;
                            } else {
                                // 非删除操作
                                return;
                            }
                        }

                        String filePath = fileInfo.getFilePath();
                        try {
                            if (filePath != null) {
                                java.io.File file = new java.io.File(filePath);
                                if (file.exists()) {
                                    java.awt.Desktop.getDesktop().open(file);
                                } else {
                                    verifyResultLabel.setText("文件不存在，无法打开");
                                    verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                                }
                            }
                        } catch (Exception ex) {
                            verifyResultLabel.setText("打开文件时发生错误");
                            verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                            core.util.LogManager.error("打开文件时发生错误", ex);
                        }
                    }
                }
            }
        });

        // 添加到输出文件列表的右键菜单
        JPopupMenu outputFilePopup = new JPopupMenu();
        JMenuItem copyFileMenuItem = new JMenuItem("复制选中文件");
        copyFileMenuItem.setFont(AppFonts.getNormalFont());
        copyFileMenuItem.addActionListener(e -> copySelectedOutputFiles());
        outputFilePopup.add(copyFileMenuItem);

        JMenuItem sendToSteganoFromFileList = new JMenuItem("发送到隐写面板");
        sendToSteganoFromFileList.setFont(AppFonts.getNormalFont());
        sendToSteganoFromFileList.addActionListener(e -> sendOutputToSteganoPanel(true));
        outputFilePopup.add(sendToSteganoFromFileList);

        outputFileListComponent.setComponentPopupMenu(outputFilePopup);

        // 启用拖拽功能，支持将输出文件拖到桌面或其他位置
        outputFileListComponent.setDragEnabled(true);
        outputFileListComponent.setTransferHandler(new TransferHandler() {
            @Override
            protected Transferable createTransferable(JComponent c) {
                JList<?> list = (JList<?>) c;
                Object[] values = list.getSelectedValues();
                java.util.List<java.io.File> files = new java.util.ArrayList<>();
                for (Object value : values) {
                    if (value instanceof FileInfo) {
                        FileInfo fileInfo = (FileInfo) value;
                        String filePath = fileInfo.getFilePath();
                        if (filePath != null) {
                            java.io.File file = new java.io.File(filePath);
                            if (file.exists()) {
                                files.add(file);
                            }
                        }
                    }
                }
                return new FileTransferable(files);
            }

            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }
        });

        panel.add(outputCenterPanel, BorderLayout.CENTER);

        // 妤犲矁鐦夌紒鎾寸亯閺嶅洨顒?
        verifyResultLabel = new JLabel(" ");
        verifyResultLabel.setFont(AppFonts.getBoldFont());
        verifyResultLabel.setHorizontalAlignment(SwingConstants.CENTER);
        verifyResultLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(verifyResultLabel, BorderLayout.SOUTH);

        return panel;
    }

    private void openOutputFileLocation() {
        if (!outputFilesList.isEmpty()) {
            try {
                FileInfo fileInfo = outputFilesList.get(0);
                String filePath = fileInfo.getFilePath();
                if (filePath != null) {
                    java.io.File file = new java.io.File(filePath);
                    if (file.exists()) {
                        java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                        if (desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
                            if (fileInfo.getIsFolder()) {
                                // 婵″倹鐏夐弰顖滄窗瑜版洩绱濋惄瀛樺复閹垫挸绱戞潻娆庨嚋閻╊喖缍?
                                desktop.open(file);
                            } else {
                                // 婵″倹鐏夐弰顖涙瀮娴犺绱濋幍鎾崇磻鐎瑰啰娈戦悥鍓佹窗瑜?
                                desktop.open(file.getParentFile());
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                verifyResultLabel.setText("打开文件所在文件夹失败");
                verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                LogManager.error("打开文件所在文件夹失败", ex);
            }
        }
    }

    /**
     * 将加密输出或解密结果发送到隐写面板（SteganoPanel）进行嵌入。
     *
     * @param fromFileList true 表示从输出文件列表选中的文件发送；false 表示从文本框中的内容发送
     */
    private void sendOutputToSteganoPanel(boolean fromFileList) {
        try {
            byte[] payload = null;
            String payloadDesc = null;

            if (fromFileList) {
                // 从输出文件列表中获取选中的文件
                int[] indices = outputFileListComponent.getSelectedIndices();
                if (indices == null || indices.length == 0) {
                    // 如果没有选中项目，且列表为空则提示
                    if (outputFilesList.isEmpty()) {
                        JOptionPane.showMessageDialog(this,
                            "当前没有可发送到隐写面板的文件",
                            "提示", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    indices = new int[]{0};
                }
                // 默认使用第一个选中的文件作为隐写负载
                FileInfo info = outputFilesList.get(indices[0]);
                java.io.File f = new java.io.File(info.getFilePath());
                if (!f.exists()) {
                    JOptionPane.showMessageDialog(this,
                        "文件不存在: " + info.getFileName(),
                        "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                payload = java.nio.file.Files.readAllBytes(f.toPath());
                payloadDesc = info.getFileName();
            } else {
                // 从文本框中提取内容作为隐写负载
                String text = outputArea.getText();
                if (text == null || text.isEmpty()) {
                    JOptionPane.showMessageDialog(this,
                        "文本内容为空，没有可发送的数据",
                        "提示", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                // 智能判断文本是否为 Base64 编码：如果可以解码则认为是加密后的数据，否则按 UTF-8 原文处理
                byte[] decoded = SecureTextStringUtils.tryDecodeBase64(text);
                if (decoded != null) {
                    payload = decoded;
                    payloadDesc = "加密文本(Base64解码后)";
                } else {
                    payload = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    payloadDesc = "文本内容";
                }
            }

            // 在当前组件的父组件树中查找 SteganoPanel 和承载它的 JTabbedPane
            java.awt.Component current = this;
            stegano.ui.SteganoPanel stegano = null;
            JTabbedPane tabbedPane = null;
            while (current != null) {
                if (current instanceof JTabbedPane && tabbedPane == null) {
                    tabbedPane = (JTabbedPane) current;
                }
                if (current instanceof stegano.ui.SteganoPanel) {
                    stegano = (stegano.ui.SteganoPanel) current;
                    break;
                }
                current = current.getParent();
            }

            // 如果在直接父组件中没有找到，尝试在整个 MainFrame 窗口中递归查找（应对多层嵌套布局的情况）
            if (stegano == null) {
                // 通过窗口祖先递归搜索 SteganoPanel 的实例
                java.awt.Window window = SwingUtilities.getWindowAncestor(this);
                if (window != null) {
                    java.util.List<java.awt.Component> queue = new java.util.ArrayList<>();
                    queue.add(window);
                    while (!queue.isEmpty() && stegano == null) {
                        java.awt.Component c = queue.remove(0);
                        if (c instanceof stegano.ui.SteganoPanel) {
                            stegano = (stegano.ui.SteganoPanel) c;
                            break;
                        }
                        if (c instanceof java.awt.Container) {
                            for (java.awt.Component child : ((java.awt.Container) c).getComponents()) {
                                queue.add(child);
                            }
                        }
                    }
                }
            }

            if (stegano == null) {
                JOptionPane.showMessageDialog(this,
                    "未找到隐写面板，请先打开隐写功能", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // 将数据作为隐写的"消息+附加描述"传递给隐写面板，使用当前密钥对名称作为关联标识
            stegano.loadExternalPayload(payload, payloadDesc, currentKeyPairName);

            // 切换到隐写面板的选项卡
            if (tabbedPane != null) {
                int idx = tabbedPane.indexOfComponent(stegano);
                if (idx >= 0) {
                    tabbedPane.setSelectedIndex(idx);
                }
            } else {
                // 通过窗口祖先递归查找承载 stegano 的 JTabbedPane
                java.awt.Window window = SwingUtilities.getWindowAncestor(stegano);
                if (window != null) {
                    java.util.List<JTabbedPane> candidates = new java.util.ArrayList<>();
                    java.util.List<java.awt.Component> queue = new java.util.ArrayList<>();
                    queue.add(window);
                    while (!queue.isEmpty()) {
                        java.awt.Component c = queue.remove(0);
                        if (c instanceof JTabbedPane) {
                            JTabbedPane tp = (JTabbedPane) c;
                            int idx = tp.indexOfComponent(stegano);
                            if (idx >= 0) {
                                tp.setSelectedIndex(idx);
                                break;
                            }
                        }
                        if (c instanceof java.awt.Container) {
                            for (java.awt.Component child : ((java.awt.Container) c).getComponents()) {
                                queue.add(child);
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            LogManager.error("发送到隐写面板时发生错误", ex);
            JOptionPane.showMessageDialog(this,
                "发送到隐写面板时发生错误: " + ex.getMessage(),
                "错误", JOptionPane.ERROR_MESSAGE);
        }
    }



    /**
     * 根据当前选中的联系人加载其绑定的密钥对名称。
     * 用于在用户选择联系人时，自动同步使用联系人绑定的密钥对来进行加密解密操作，保持数据来源一致性。
     *
     * @param contactName 联系人名称
     * 鐎靛棛鐖滄潏鎾冲弳瀵ゆ儼绻滈崚鎵仯閸戠粯鎼锋担婊勫瘻闁筋喗妞?
     */
    private void handleContactSelectionChange() {
        handleContactSelectionChangeImpl(false);
    }

    private void handleContactSelectionChangeSilent() {
        handleContactSelectionChangeImpl(true);
    }

    private void handleContactSelectionChangeImpl(boolean silent) {
        if (!isInitialized) {
            return;
        }

        String selectedContact = (String) otherPublicKeyCombo.getSelectedItem();

        // 1. 检查是否未选择联系人
        if (selectedContact == null) {
            if (currentKeyPairName != null) {
                crypto.key.PrivateKeyAccessService.clearPasswordCache(currentKeyPairName);
            }
            currentKeyPairName = null;
            return;
        }

        // 2. 检查是否为"我的密钥"项
        if (selectedContact.startsWith("閹存垹娈戦崗顒勬寽 - ")) {
            // 从文本中提取密钥对名称
            String keyName = selectedContact.substring("閹存垹娈戦崗顒勬寽 - ".length());

            // 检查是否切换了密钥对
            if (!keyName.equals(currentKeyPairName)) {
                if (currentKeyPairName != null) {
                    crypto.key.PrivateKeyAccessService.clearPasswordCache(currentKeyPairName);
                }
                currentKeyPairName = keyName;
            }

            // 我的密钥对不需要进一步处理公钥加载
            return;
        }

        // 3. 处理联系人选择
        if (isContactSelection(selectedContact)) {
            String contactName = getContactNameFromSelection(selectedContact);

            try {
                // 获取联系人绑定的密钥对名称
                String boundKeyName = ContactStorage.getBoundKeyPair(contactName);

                if (boundKeyName == null || boundKeyName.trim().isEmpty()) {
                    // 联系人未绑定密钥对
                    if (!silent) {
                        JOptionPane.showMessageDialog(this,
                            "该联系人尚未绑定任何密钥对，请先为联系人绑定密钥对后再使用加密功能。",
                            "错误",
                            JOptionPane.ERROR_MESSAGE);
                    }
                    if (currentKeyPairName != null) {
                        crypto.key.PrivateKeyAccessService.clearPasswordCache(currentKeyPairName);
                    }
                    currentKeyPairName = null;
                    return;
                }

                // 检查当前密钥对是否匹配
                List<KeyPairInfo> keyPairs = KeyPairStorage.loadAllKeyPairs();
                boolean keyExists = false;
                for (KeyPairInfo keyPair : keyPairs) {
                    if (keyPair.getName().equals(boundKeyName)) {
                        keyExists = true;
                        break;
                    }
                }

                if (!keyExists) {
                    if (!silent) {
                        JOptionPane.showMessageDialog(this,
                            "联系人绑定的密钥对不存在: " + boundKeyName + "，请先创建对应的密钥对后再使用加密功能。",
                            "错误",
                            JOptionPane.ERROR_MESSAGE);
                    }
                    if (currentKeyPairName != null) {
                        crypto.key.PrivateKeyAccessService.clearPasswordCache(currentKeyPairName);
                    }
                    currentKeyPairName = null;
                    return;
                }

                // 检查绑定的密钥对是否与当前使用的密钥对一致
                if (!boundKeyName.equals(currentKeyPairName)) {
                    // 切换到联系人绑定的密钥对，清除旧的密码缓存
                    if (currentKeyPairName != null) {
                        crypto.key.PrivateKeyAccessService.clearPasswordCache(currentKeyPairName);
                    }
                    currentKeyPairName = boundKeyName;
                }

                // 更新联系人最后使用时间，用于在联系人列表中按使用频率排序

            } catch (Exception ex) {
                LogManager.error("根据联系人加载密钥对时发生错误", ex);
                JOptionPane.showMessageDialog(this,
                    "加载联系人密钥对时发生错误: " + ex.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // 私钥访问+密码缓存 相关工作由 crypto.key.PrivateKeyAccessService 统一处理

    // 切换回文本模式的方法
    private void setTextMode() {
        // 清空输入文件列表
        fileSelection.clear();
        if (fileListModel != null) {
            fileListModel.clear();
        }

        // 清空输出文件列表
        outputFilesList.clear();
        if (outputFileListModel != null) {
            outputFileListModel.clear();
        }

        // ========== 清理临时加密解密文件 ==========
        TempFileManager.cleanAll();

        // 如果安全级别设置为严格模式，清除缓存的密码，强制下次使用重新输入
        String securityLevel = settings.storage.SettingsStorage.getSecurityLevel();
        if (settings.storage.SettingsStorage.SECURITY_LEVEL_STRICT.equals(securityLevel)) {
            crypto.key.PrivateKeyAccessService.clearAllPasswordCaches();
        }

        // 切换到文本模式
        cardLayout.show(centerPanel, "TEXT_MODE");
        inputArea.setEditable(true);

        // 切换输出区到文本模式
        outputCardLayout.show(outputCenterPanel, "TEXT_MODE");

        inputArea.setText("");
        outputArea.setText("");
        verifyResultLabel.setText(" "); // 清空验证结果
        progressBar.setValue(0); // 重置进度条
        progressBar.setString(""); // 清空进度条文本
        updateSaveButtonState(); // 更新保存按钮状态
        }

    /**
     * 更新输出文件详情区域的显示内容（如文件名称、大小等信息）
     */
    private void updateOutputFileDetails() {
        // 清空详情显示
        outputFileDetailsBox.removeAll();

        int selectedCount = outputFileListComponent.getSelectedIndices().length;
        int totalCount = outputFilesList.size();

        if (selectedCount == 1) {
            // 单个文件选中时，显示该文件的详细信息
            FileInfo selectedFileInfo = outputFileListComponent.getSelectedValue();
            if (selectedFileInfo != null) {
                long fileSize = selectedFileInfo.getFileSize();
                String sizeStr;
                if (fileSize < 1024) {
                    sizeStr = fileSize + " B";
                } else if (fileSize < 1024 * 1024) {
                    sizeStr = String.format("%.1f KB", fileSize / 1024.0);
                } else {
                    sizeStr = String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
                }

                String filePath = selectedFileInfo.getFilePath();
                String parentPath = "";
                if (filePath != null && !filePath.isEmpty()) {
                    java.io.File fileObj = new java.io.File(filePath);
                    if (fileObj.getParent() != null) {
                        parentPath = fileObj.getParent();
                    }
                }

                // 创建并添加文件名称标签
                JLabel fileNameLabel = new JLabel("文件: " + selectedFileInfo.getFileName());
                fileNameLabel.setFont(AppFonts.getNormalFont());
                fileNameLabel.setForeground(ThemeManager.getTextColor());
                outputFileDetailsBox.add(fileNameLabel);

                // 创建并添加文件大小标签
                JLabel sizeLabel = new JLabel("大小: " + sizeStr);
                sizeLabel.setFont(AppFonts.getNormalFont());
                sizeLabel.setForeground(ThemeManager.getTextColor());
                outputFileDetailsBox.add(sizeLabel);

                // 创建并添加文件路径标签
                JLabel pathLabel = new JLabel("路径: " + parentPath);
                pathLabel.setFont(AppFonts.getNormalFont());
                pathLabel.setForeground(ThemeManager.getTextColor());
                outputFileDetailsBox.add(pathLabel);

                if (selectedFileInfo.getIsSplit()
                        && selectedFileInfo.getVolumeCount() != null) {
                    Integer currentVolume = selectedFileInfo.getCurrentVolume();
                    String volumeText = currentVolume == null
                            ? String.valueOf(selectedFileInfo.getVolumeCount())
                            : currentVolume + "/" + selectedFileInfo.getVolumeCount();
                    JLabel volumeLabel = new JLabel("分卷: " + volumeText);
                    volumeLabel.setFont(AppFonts.getNormalFont());
                    volumeLabel.setForeground(ThemeManager.getTextColor());
                    outputFileDetailsBox.add(volumeLabel);
                }

                // 当签名验证失败时，显示警告提示
                if (selectedFileInfo.getSignatureValid() != null && !selectedFileInfo.getSignatureValid()) {
                    JLabel warningLabel = new JLabel("⚠ 签名验证失败：此文件可能被篡改或来源不可信！");
                    warningLabel.setFont(AppFonts.getBoldFont());
                    warningLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                    outputFileDetailsBox.add(warningLabel);
                }
            }
        } else {
            // 没有选中文件或选中多个文件时，显示文件总数量
            JLabel label = new JLabel("共 " + totalCount + " 个文件");
            label.setFont(AppFonts.getNormalFont());
            label.setForeground(ThemeManager.getTextColor());
            outputFileDetailsBox.add(label);
        }

        // 刷新UI
        outputFileDetailsBox.revalidate();
        outputFileDetailsBox.repaint();
    }

    /**
     * 复制选中的输出文件到剪贴板或文件系统（支持多选）
     */
    private void copySelectedOutputFiles() {
        int[] selectedIndices = outputFileListComponent.getSelectedIndices();
        if (selectedIndices.length == 0) {
            return;
        }

        try {
            java.util.List<java.io.File> files = new java.util.ArrayList<>();
            for (int index : selectedIndices) {
                FileInfo fileInfo = outputFileListModel.getElementAt(index);
                String filePath = fileInfo.getFilePath();
                if (filePath != null) {
                    java.io.File file = new java.io.File(filePath);
                    if (file.exists()) {
                        files.add(file);
                    }
                }
            }

            // 閸掓稑缂?Transferable 閻劋绨弬鍥︽娴肩姾绶?
            java.awt.datatransfer.Transferable transferable = new java.awt.datatransfer.Transferable() {
                @Override
                public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
                    return new java.awt.datatransfer.DataFlavor[]{java.awt.datatransfer.DataFlavor.javaFileListFlavor};
                }

                @Override
                public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
                    return java.awt.datatransfer.DataFlavor.javaFileListFlavor.equals(flavor);
                }

                @Override
                public Object getTransferData(java.awt.datatransfer.DataFlavor flavor) {
                    return files;
                }
            };

            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(transferable, null);
        } catch (Exception e) {
            LogManager.error("复制文件时发生错误", e);
        }
    }

    // 切换到文件模式的方法，用于在处理完文件后显示文件列表
    private void setFileMode(java.io.File file) {
        addFilesToFileList(new java.io.File[]{file});
    }



    // 为输入文件列表/文本区域添加自定义右键菜单，支持删除/粘贴操作
    private void addCustomRightClickMenu(javax.swing.JComponent component) {
        JPopupMenu popupMenu;

        // 如果是输入文件列表，添加"删除"菜单项，用于移除已添加的文件
        if (component == fileListComponent) {
            popupMenu = new JPopupMenu();
            JMenuItem removeMenuItem = new JMenuItem("移除文件");
            removeMenuItem.setFont(AppFonts.getNormalFont());
            removeMenuItem.addActionListener(e -> removeSelectedFiles());
            popupMenu.add(removeMenuItem);
            popupMenu.addSeparator();
        } else {
            popupMenu = new JPopupMenu();
        }

        // 添加粘贴菜单项
        JMenuItem pasteMenuItem = new JMenuItem("粘贴");
        pasteMenuItem.setFont(AppFonts.getNormalFont());
        pasteMenuItem.addActionListener(e -> {
            try {
                java.awt.datatransfer.Clipboard clipboard =
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
                java.awt.datatransfer.Transferable contents = clipboard.getContents(null);

                if (contents != null &&
                    contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                    // 閸擃亣鍒涢弶鎸庢Ц閺傚洣娆?
                    @SuppressWarnings("unchecked")
                    java.util.List<java.io.File> files = (java.util.List<java.io.File>)
                        contents.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);

                    if (!files.isEmpty()) {
                        // ========== 閺傛澘顤冮敍姘辩埠娑撯偓鐠嬪啰鏁ら弲楦垮厴婢跺嫮鎮?==========
                        handleFilesIntelligently(files.toArray(new java.io.File[0]));
                    }
                } else if (component instanceof JTextArea) {
                    // 閸擃亣鍒涢弶鎸庢Ц閺傚洤鐡ч敍灞肩瑬缂佸嫪娆㈤弰顖涙瀮閺堫剙灏崺鐕傜礉濮濓絽鐖剁划妯垮垱
                    ((JTextArea) component).paste();
                }
            } catch (Exception ex) {
                LogManager.error("粘贴失败", ex);
            }
        });

        popupMenu.add(pasteMenuItem);
        component.setComponentPopupMenu(popupMenu);
    }


    private void encryptAndSign() {
        try {
            // ========== 检查是否为文件加密模式 ==========
            if (!fileSelection.isEmpty()) {
                encryptMultipleFiles();
                return;
            }
            // ==============================================================

            // 1. 获取当前的密钥对名称
            String myKeyPairName = currentKeyPairName;
            String otherKeySelection = (String) otherPublicKeyCombo.getSelectedItem();

            if (myKeyPairName == null || myKeyPairName.isEmpty()) {
                verifyResultLabel.setText("请先选择一个密钥对");
                verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                return;
            }
            if (otherKeySelection == null || otherKeySelection.equals("选择联系人")) {
                verifyResultLabel.setText("请选择对方联系人");
                verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                return;
            }

            // 2. 确定联系人名称
            final String contactNameFinal;
            if (isContactSelection(otherKeySelection)) {
                contactNameFinal = getContactNameFromSelection(otherKeySelection);
                try {
                    ContactStorage.updateLastUsed(contactNameFinal);
                } catch (Exception e) {
                    LogManager.warning("更新联系人最后使用时间失败: " + contactNameFinal);
                }
            } else {
                contactNameFinal = null;
            }

            // 3. 检查是否有输入
            if (inputArea.getText().trim().isEmpty()) {
                verifyResultLabel.setText("输入区为空，请输入文本或选择文件");
                verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                return;
            }

            // 4. 启动加密前，需要确认私钥密码（通过 PrivateKeyAccessService 的缓存机制自动处理，若无密码则需用户输入，系统会自动弹出密码输入对话框）

            progressBar.setString("正在进行加密...");
            progressBar.setValue(0);
            verifyResultLabel.setText(" ");

            final String plaintextFinal = inputArea.getText().trim();
            final String myKeyPairNameFinal = myKeyPairName;
            final long startTime = System.currentTimeMillis();

            // 预先加载公钥（在面板中完成），便于后续使用独立 Worker 类
            final java.security.PublicKey otherPublicKey = loadPublicKeyFromSelection(otherKeySelection);
            if (otherPublicKey == null) {
                verifyResultLabel.setText("加载公钥失败");
                verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                return;
            }

            // 使用新版独立 EncryptTextWorker + WorkerCallback
            WorkerCallback<SignedAndEncryptedData> encryptCallback = new WorkerCallback<SignedAndEncryptedData>() {
                @Override
                public void onProgress(int progress, String stepText) { /* 由基类统一处理 */ }

                @Override
                public void onComplete(SignedAndEncryptedData signedAndEncryptedData) {
                    if (signedAndEncryptedData == null) {
                        verifyResultLabel.setText("加密处理失败");
                        verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                        progressBar.setString("加密处理失败");
                        return;
                    }
                    outputFilesList.clear();
                    outputFileListModel.clear();
                    byte[] data = signedAndEncryptedData.toBytes();
                    String base64Data = KeyUtils.encodeBase64(data);
                    outputArea.setText(base64Data);
                    outputCardLayout.show(outputCenterPanel, "TEXT_MODE");
                    updateSaveButtonState();
                    progressBar.setString("文本加密完成");
                    long duration = System.currentTimeMillis() - startTime;
                    addHistoryRecordEnhanced(HistoryType.ENCRYPT, contactNameFinal,
                            RecordCategory.TEXT, "文本加密", true, "加密",
                            null, plaintextFinal, new ArrayList<>(), base64Data, new ArrayList<>(),
                            myKeyPairNameFinal, duration);
                    verifyResultLabel.setText("文本加密成功");
                    verifyResultLabel.setForeground(UIConstants.SUCCESS_TEXT_COLOR);
                }

                @Override
                public void onError(Exception ex) {
                    verifyResultLabel.setText("加密处理失败");
                    verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                    progressBar.setString("加密处理失败");
                    LogManager.error("加密文本处理异常", ex);
                }
            };

            new securecomm.ui.worker.EncryptTextWorker(plaintextFinal, myKeyPairNameFinal,
                    otherPublicKey, progressBar, encryptCallback).execute();

        } catch (Exception ex) {
            showError("加密操作失败: " + ex.getMessage());
            LogManager.error("加密文本处理异常", ex);
        }
    }


    private void decryptAndVerify() {
        try {
            // ========== 检查是否为文件解密模式 ==========
            if (!fileSelection.isEmpty()) {
                decryptMultipleFiles();
                return;
            }
            // ==============================================================

            // 1. 获取当前的密钥对名称
            String myKeyPairName = currentKeyPairName;
            String otherKeySelection = (String) otherPublicKeyCombo.getSelectedItem();

            if (myKeyPairName == null || myKeyPairName.isEmpty()) {
                verifyResultLabel.setText("请先选择一个密钥对");
                verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                return;
            }
            if (otherKeySelection == null || otherKeySelection.equals("选择联系人")) {
                verifyResultLabel.setText("请选择对方联系人");
                verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                return;
            }

            // 2. 确定联系人名称
            final String contactNameFinal;
            if (isContactSelection(otherKeySelection)) {
                contactNameFinal = getContactNameFromSelection(otherKeySelection);
                try {
                    ContactStorage.updateLastUsed(contactNameFinal);
                } catch (Exception e) {
                    LogManager.warning("更新联系人最后使用时间失败: " + contactNameFinal);
                }
            } else {
                contactNameFinal = null;
            }

            // 3. 检查是否有输入
            if (inputArea.getText().trim().isEmpty()) {
                verifyResultLabel.setText("请输入要解密的加密文本或选择加密文件");
                verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                return;
            }

            // 4. 启动解密前，需要确认私钥密码（通过 PrivateKeyAccessService 的缓存机制自动处理，若无密码则需用户输入，系统会自动弹出密码输入对话框）

            progressBar.setString("正在进行解密...");
            progressBar.setValue(0);
            verifyResultLabel.setText("解密中...");
            verifyResultLabel.setForeground(ThemeManager.getTextColor());

            final String ciphertextBase64Final = inputArea.getText().trim();
            final String myKeyPairNameFinal = myKeyPairName;
            final long startTime = System.currentTimeMillis();

            // 预先加载公钥
            final java.security.PublicKey otherPublicKey = loadPublicKeyFromSelection(otherKeySelection);
            if (otherPublicKey == null) {
                verifyResultLabel.setText("加载公钥失败");
                verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                return;
            }

            // 使用新版独立 DecryptTextWorker + WorkerCallback
            WorkerCallback<DecryptResult> decryptCallback = new WorkerCallback<DecryptResult>() {
                @Override
                public void onProgress(int progress, String stepText) { /* 由基类统一处理 */ }

                @Override
                public void onComplete(DecryptResult result) {
                    if (result == null) {
                        verifyResultLabel.setText("解密处理失败");
                        verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                        return;
                    }
                    outputFilesList.clear();
                    outputFileListModel.clear();
                    if (result.isFile && result.outputFile != null) {
                        FileInfo outFileInfo = new FileInfo(result.outputFile.getName(),
                                result.outputFile.getAbsolutePath(), result.outputFile.length(), "加密后");
                        outFileInfo.setSignatureValid(result.signatureValid);
                        if (result.outputFile.isDirectory()) {
                            outFileInfo.setIsFolder(true);
                            outFileInfo.setFileSize(core.util.FolderZipUtils.calculateFolderSize(result.outputFile));
                        }
                        outputFilesList.add(outFileInfo);
                        outputFileListModel.addElement(outFileInfo);
                        updateOutputFileDetails();
                        outputCardLayout.show(outputCenterPanel, "FILE_MODE");
                    } else {
                        outputArea.setText(result.plaintext);
                        outputCardLayout.show(outputCenterPanel, "TEXT_MODE");
                    }
                    updateSaveButtonState();
                    if (result.signatureValid) {
                        verifyResultLabel.setText("签名验证成功，解密内容完整可靠");
                        verifyResultLabel.setForeground(UIConstants.SUCCESS_TEXT_COLOR);
                    } else {
                        verifyResultLabel.setText("签名验证失败，文件内容可能已被篡改");
                        verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                    }
                    progressBar.setString("文件解密完成");
                    long duration = System.currentTimeMillis() - startTime;
                    String inputText = !result.isFile ? ciphertextBase64Final : null;
                    String outputText = !result.isFile ? result.plaintext : null;
                    java.util.List<FileInfo> inputFiles = new java.util.ArrayList<>();
                    java.util.List<FileInfo> outputFilesListRecord = new java.util.ArrayList<>();
                    if (!result.isFile) {
                        FileInfo inFileInfo = new FileInfo("输入文本", "", 0, "加密后", null, result.signatureValid);
                        inputFiles.add(inFileInfo);
                    }
                    if (result.isFile && result.outputFile != null) {
                        FileInfo outFileInfo = new FileInfo(result.outputFile.getName(),
                                result.outputFile.getAbsolutePath(), result.outputFile.length(), "加密后");
                        outFileInfo.setSignatureValid(result.signatureValid);
                        outputFilesListRecord.add(outFileInfo);
                    }
                    String resultText = result.signatureValid ? "解密内容完整" : "解密内容被篡改";
                    addHistoryRecordEnhanced(HistoryType.DECRYPT, contactNameFinal,
                            result.isFile ? RecordCategory.BATCH : RecordCategory.TEXT,
                            "解密" + (result.isFile ? "文件" : "文本"),
                            true, resultText, null, inputText, inputFiles,
                            outputText, outputFilesListRecord, myKeyPairNameFinal, duration);
                }

                @Override
                public void onError(Exception ex) {
                    String userMessage = securecomm.ui.util.SecureTextStringUtils.getUserFriendlyErrorMessage(ex);
                    verifyResultLabel.setText(userMessage);
                    verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                    progressBar.setString("文件解密失败");
                    LogManager.error("文件解密处理异常", ex);
                }
            };

            new securecomm.ui.worker.DecryptTextWorker(ciphertextBase64Final, myKeyPairNameFinal,
                    otherPublicKey, progressBar, decryptCallback).execute();

        } catch (Exception ex) {
            verifyResultLabel.setText("解密操作失败");
            verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
            LogManager.error("解密文本处理异常", ex);
        }
    }

    // 更新保存按钮状态
    private void updateSaveButtonState() {
        // 检查输出区是否有内容
        boolean hasOutput = !outputFilesList.isEmpty() || !outputArea.getText().trim().isEmpty();

        // 是否显示文本按钮（输出区非空且文件列表为空）
        boolean showTextButtons = outputFilesList.isEmpty() && !outputArea.getText().trim().isEmpty();

        // 显示保存按钮
        saveButton.setEnabled(showTextButtons);
        saveButton.setVisible(showTextButtons);

        // 显示复制按钮
        copyButton.setVisible(showTextButtons);

        // 显示打开文件所在位置按钮（当有输出文件时）
        boolean showOpenLocation = !outputFilesList.isEmpty();
        openLocationButton.setVisible(showOpenLocation);
    }

    private PublicKey loadPublicKeyFromSelection(String selection) throws Exception {
        if (isContactSelection(selection)) {
            String contactName = getContactNameFromSelection(selection);
            Contact contact = ContactStorage.loadContact(contactName);
            return KeyUtils.getPublicKeyFromBytes(KeyUtils.decodeBase64(contact.getPublicKey()));
        } else if (selection.startsWith("閹存垹娈戦崗顒勬寽 - ")) {
            String keyPairName = selection.substring("閹存垹娈戦崗顒勬寽 - ".length());
            List<KeyPairInfo> keyPairs = KeyPairStorage.loadAllKeyPairs();
            for (KeyPairInfo keyPair : keyPairs) {
                if (keyPair.getName().equals(keyPairName)) {
                    return KeyUtils.getPublicKeyFromBytes(KeyUtils.decodeBase64(keyPair.getPublicKey()));
                }
            }
            throw new Exception("未找到对应的密钥对: " + keyPairName);
        }
        throw new Exception("无效的公钥选择: " + selection);
    }

    /**
     * 收集本机与联系人公钥，交给共享内核识别签名发送者。
     * 单个公钥损坏不影响解密，只记录警告。
     */
    private List<PublicKey> loadSenderCandidates() {
        List<PublicKey> candidates = new ArrayList<>();
        Object selection = otherPublicKeyCombo == null
                ? null
                : otherPublicKeyCombo.getSelectedItem();
        if (selection != null) {
            try {
                addSenderCandidate(candidates, loadPublicKeyFromSelection(selection.toString()));
            } catch (Exception error) {
                LogManager.warning("无法加载当前选择联系人的公钥", error);
            }
        }
        try {
            for (KeyPairInfo keyPair : KeyPairStorage.loadAllKeyPairs()) {
                try {
                    addSenderCandidate(candidates, KeyUtils.getPublicKeyFromBytes(
                            KeyUtils.decodeBase64(keyPair.getPublicKey())));
                } catch (Exception error) {
                    LogManager.warning("跳过无法解析的本机公钥: " + keyPair.getName(), error);
                }
            }
        } catch (Exception error) {
            LogManager.warning("加载本机公钥失败，签名可能显示为未验证", error);
        }
        try {
            for (Contact contact : ContactStorage.loadAllContacts()) {
                try {
                    addSenderCandidate(candidates, KeyUtils.getPublicKeyFromBytes(
                            KeyUtils.decodeBase64(contact.getPublicKey())));
                } catch (Exception error) {
                    LogManager.warning("跳过无法解析的联系人公钥: " + contact.getName(), error);
                }
            }
        } catch (Exception error) {
            LogManager.warning("加载联系人公钥失败，签名可能显示为未验证", error);
        }
        return candidates;
    }

    private static void addSenderCandidate(List<PublicKey> candidates, PublicKey key) {
        if (key == null) {
            return;
        }
        byte[] encoded = key.getEncoded();
        for (PublicKey existing : candidates) {
            if (java.util.Arrays.equals(existing.getEncoded(), encoded)) {
                return;
            }
        }
        candidates.add(key);
    }

    private void saveToFile() {
        String content = outputArea.getText().trim();
        if (content.isEmpty()) {
            verifyResultLabel.setText("输出区为空，没有可保存的内容");
            verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
            return;
        }

        try {
            // 获取默认输出目录
            String outputDir = SettingsManager.getDefaultOutputDirectory();
            if (outputDir == null || outputDir.trim().isEmpty()) {
                // 如果没有配置默认目录，则使用系统临时目录
                outputDir = System.getProperty("java.io.tmpdir");
            }

            // 确保目录存在
            java.io.File dir = new java.io.File(outputDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // 生成文件名
            String defaultFileName = "secure_message_" +
                    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".txt";
            java.io.File outputFile = new java.io.File(dir, defaultFileName);

            // 将base64内容写入文件
            java.nio.file.Files.write(outputFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

            // 清空旧的文件列表显示
            outputFilesList.clear();
            outputFileListModel.clear();

            // 在文件列表中添加新文件
            FileInfo fileInfo = new FileInfo(
                outputFile.getName(),
                outputFile.getAbsolutePath(),
                outputFile.length(),
                "加密后"
            );
            fileInfo.setSignatureValid(true);
            displayOutputFiles(java.util.Collections.singletonList(fileInfo));

            verifyResultLabel.setText("文件保存成功");
            verifyResultLabel.setForeground(UIConstants.SUCCESS_TEXT_COLOR);

        } catch (Exception ex) {
            verifyResultLabel.setText("文件保存失败");
            verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
            LogManager.error("保存文件时发生错误", ex);
        }
    }

    private void loadFromFile() {
        // 选择文件，支持多选
        fileChooser.setFileFilter(null);
        fileChooser.setMultiSelectionEnabled(true);

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            try {
                java.io.File[] selectedFiles = fileChooser.getSelectedFiles();

                // ========== 根据文件类型智能处理 ==========
                handleFilesIntelligently(selectedFiles);

            } catch (Exception ex) {
                verifyResultLabel.setText("加载文件失败");
                verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                LogManager.error("加载文件处理异常", ex);
            }
        }
    }

    @Override
    public void refreshData() {
        // 刷新对方公钥下拉框（联系人+测试模式的密钥对）
        loadAllPublicKeys(otherPublicKeyCombo);
        // 分卷大小可能在设置页被改过，文件列表提示跟着更新。
        if (!fileSelection.isEmpty()) {
            updateFileListHint();
        }
    }

    private void loadAllPublicKeys(JComboBox<String> comboBox) {
        comboBox.removeAllItems();

        try {
            // 在测试模式下，添加所有本地密钥对作为可选公钥
            if (SettingsStorage.isTestMode()) {
                List<KeyPairInfo> keyPairs = KeyPairStorage.loadAllKeyPairs();
                for (KeyPairInfo keyPair : keyPairs) {
                    comboBox.addItem("閹存垹娈戦崗顒勬寽 - " + keyPair.getName());
                }
            }

            // 添加所有联系人（对方公钥）
            List<Contact> contacts = ContactStorage.loadAllContactsSortedByLastUsed();
            for (Contact contact : contacts) {
                comboBox.addItem("联系人 - " + contact.getName());
            }

            // 如果有可用项，则默认选中第一个
            if (comboBox.getItemCount() > 0) {
                comboBox.setSelectedIndex(0);
            }

        } catch (Exception ex) {
            LogManager.error("加载公钥列表失败", ex);
        }
    }

    /** DR-2 复看：批量加/解密的界面收尾合并成一份，方向与历史标题由构造参数区分。 */
    private class PanelBatchHost
            implements securecomm.ui.worker.EncryptBatchHost, securecomm.ui.worker.DecryptBatchHost {
        private final HistoryType historyType;
        private final String historyTitle;

        PanelBatchHost(HistoryType historyType, String historyTitle) {
            this.historyType = historyType;
            this.historyTitle = historyTitle;
        }

        @Override
        public PublicKey loadPublicKey(String selection) throws Exception {
            return loadPublicKeyFromSelection(selection);
        }

        @Override
        public List<PublicKey> resolveSenderCandidates(String selection) throws Exception {
            loadPublicKeyFromSelection(selection);
            return loadSenderCandidates();
        }

        @Override
        public void showOutputs(List<FileInfo> outputs) {
            displayOutputFiles(outputs);
        }

        @Override
        public void showResult(String text, boolean error) {
            verifyResultLabel.setText(text);
            verifyResultLabel.setForeground(error
                    ? UIConstants.ERROR_TEXT_COLOR
                    : UIConstants.SUCCESS_TEXT_COLOR);
        }

        @Override
        public void recordHistory(
                String contactName,
                String resultText,
                int fileCount,
                List<FileInfo> inputRecords,
                List<FileInfo> outputRecords,
                String keyPairName,
                long elapsedMs) {
            addHistoryRecordEnhanced(
                    historyType,
                    contactName,
                    RecordCategory.BATCH,
                    historyTitle + fileCount + " 个文件",
                    true,
                    resultText,
                    null,
                    null,
                    inputRecords,
                    null,
                    outputRecords,
                    keyPairName,
                    elapsedMs);
        }

        @Override
        public String contactNameOrNull(String selection) {
            return isContactSelection(selection) ? getContactNameFromSelection(selection) : null;
        }

        @Override
        public void batchFinished() {
            endBatchRun();
        }

        @Override
        public void afterBatch() {
            fileSelection.removeTemporaryArchives();
            refreshFileListModel();

            updateFileDetails();
            updateSaveButtonState();
        }
    }

    // ============== 批量处理方法（新增） ==============

    /**
     * 批量加密文件列表中的所有文件
     */
    private void encryptMultipleFiles() {
        try {
            String myKeyPairName = currentKeyPairName;
            String otherKeySelection = (String) otherPublicKeyCombo.getSelectedItem();

            if (!validateBatchOperationPrerequisites(myKeyPairName, otherKeySelection)) {
                return;
            }

            // ========== 检测到已加密文件，确认是否跳过 ==========
            List<java.io.File> encFiles = new java.util.ArrayList<>();
            for (java.io.File file : fileSelection.files()) {
                if (securecomm.service.FileTypeClassifier.isEncryptedFileName(file.getName())) {
                    encFiles.add(file);
                }
            }
            final boolean skipEncFiles;
            if (!encFiles.isEmpty()) {
                Object[] options = {"跳过 .aegix 文件", "仍然处理"};
                int choice = JOptionPane.showOptionDialog(
                        this,
                        "检测到" + encFiles.size() + " 个 .aegix 加密文件，可能已经加密过。是否跳过这些文件？",
                        "检测到已加密文件",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE,
                        null,
                        options,
                        options[0]
                );
                skipEncFiles = (choice == 0);
            } else {
                skipEncFiles = false;
            }
            // ==========================================

            boolean packageFiles = packageMultiFilesCheckBox != null
                    && packageMultiFilesCheckBox.isSelected()
                    && fileSelection.canPackage();
            if (packageFiles) {
                int candidates = 0;
                for (java.io.File file : fileSelection.files()) {
                    if (!(skipEncFiles
                            && securecomm.service.FileTypeClassifier.isEncryptedFileName(file.getName()))) {
                        candidates++;
                    }
                }
                packageFiles = candidates >= 2;
            }
            String archiveRootName = securecomm.service.ArchiveNaming.newArchiveRootName();
            if (!confirmVolumePreview(
                    fileSelection.files(), skipEncFiles, otherKeySelection, packageFiles, archiveRootName)) {
                return;
            }

            progressBar.setString("正在进行批量加密...");
            progressBar.setValue(0);
            verifyResultLabel.setText(" ");

            final String myKeyPairNameFinal = myKeyPairName;
            final String otherKeySelectionFinal = otherKeySelection;
            final List<java.io.File> filesToEncrypt = new java.util.ArrayList<>(fileSelection.files());
            final boolean finalSkipEncFiles = skipEncFiles;
            final long startTime = System.currentTimeMillis();

            new securecomm.ui.worker.EncryptFilesWorker(
                    new PanelBatchHost(HistoryType.ENCRYPT, "批量加密 "),
                    progressBar,
                    filesToEncrypt,
                    myKeyPairNameFinal,
                    otherKeySelectionFinal,
                    startTime,
                    finalSkipEncFiles,
                    packageFiles,
                    archiveRootName).execute();

        } catch (Exception ex) {
            showError("批量加密失败：" + ex.getMessage());
                LogManager.error("批量加密时发生错误", ex);
        }
    }

    /**
     * 开始前按当前分卷大小预估卷数；只有会拆卷时才弹窗，普通单文件不被多余确认打断。
     *
     * <p>返回 false 表示用户取消，或预估失败（已提示原因），调用方应直接返回、不要启动后台任务。</p>
     */
    private boolean confirmVolumePreview(
            List<File> files,
            boolean skipEncFiles,
            String otherKeySelection,
            boolean packageFiles,
            String archiveRootName
    ) {
        PublicKey recipientKey;
        try {
            recipientKey = loadPublicKeyFromSelection(otherKeySelection);
        } catch (Exception error) {
            showError("无法加载接收方公钥：" + error.getMessage());
            return false;
        }

        securecomm.service.VolumePreviewPlanner.Plan plan;
        try {
            plan = securecomm.service.VolumePreviewPlanner.plan(
                    files,
                    skipEncFiles,
                    packageFiles,
                    archiveRootName,
                    recipientKey,
                    SettingsStorage.getVolumeLimitBytes(),
                    new securecomm.service.VolumePreviewPlanner.ArchiveLookup() {
                        @Override
                        public boolean isFolderZip(File file) {
                            return TempFileManager.isFolderZip(file);
                        }

                        @Override
                        public String folderName(File file) {
                            return TempFileManager.resolveFolderName(file);
                        }

                        @Override
                        public String registeredArchive(File file) {
                            return TempFileManager.getArchiveName(file);
                        }
                    });
        } catch (Exception error) {
            showError("无法计算分卷：" + error.getMessage());
            return false;
        }
        if (plan.isEmpty()) {
            return true;
        }

        JTextArea area = new JTextArea(
                plan.buildMessage(SettingsStorage.getVolumeLimitPreset().getLabel()));
        area.setFont(app.theme.AppFonts.getNormalFont());
        area.setForeground(app.theme.ThemeManager.getTextColor());
        area.setBackground(app.theme.ThemeManager.getPanelBackgroundColor());
        area.setEditable(false);
        area.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);

        Object[] options = {"开始加密", "取消"};
        int choice = JOptionPane.showOptionDialog(
                this,
                area,
                "分卷预览",
                JOptionPane.YES_NO_OPTION,
                plan.isWarning() ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);
        return choice == 0;
    }

    /** 把一批产物放进结果列表并切到文件卡：批量加解密与单文件保存共用同一收尾顺序。 */
    private void displayOutputFiles(List<FileInfo> outputs) {
        outputFilesList.clear();
        outputFileListModel.clear();

        if (outputs != null) {
            for (FileInfo fileInfo : outputs) {
                outputFilesList.add(fileInfo);
                outputFileListModel.addElement(fileInfo);
            }
        }

        updateOutputFileDetails();
        outputCardLayout.show(outputCenterPanel, "FILE_MODE");
        updateSaveButtonState();
    }

    /**
     * 批量解密文件列表中的所有文件
     */
    private void decryptMultipleFiles() {
        try {
            String myKeyPairName = currentKeyPairName;
            String otherKeySelection = (String) otherPublicKeyCombo.getSelectedItem();

            if (!validateBatchOperationPrerequisites(myKeyPairName, otherKeySelection)) {
                return;
            }

            progressBar.setString("正在进行批量解密...");
            progressBar.setValue(0);
            verifyResultLabel.setText(" ");

            final String myKeyPairNameFinal = myKeyPairName;
            final String otherKeySelectionFinal = otherKeySelection;
            final List<java.io.File> filesToDecrypt = new java.util.ArrayList<>(fileSelection.files());
            final long startTime = System.currentTimeMillis();
            final java.util.concurrent.atomic.AtomicBoolean cancelled = beginBatchRun();

            new securecomm.ui.worker.DecryptFilesWorker(
                    new PanelBatchHost(HistoryType.DECRYPT, "批量解密 "), progressBar, filesToDecrypt,
                    myKeyPairNameFinal, otherKeySelectionFinal, startTime,
                    new securecomm.service.DefaultFileTaskService(), cancelled).execute();

        } catch (Exception ex) {
            showError("操作失败：" + ex.getMessage());
            LogManager.error("批量解密操作失败", ex);
        }
    }

    /** 批量任务的取消按钮：只置标记，停在哪一步由后台任务自己判断。 */
    private void requestBatchCancel() {
        java.util.concurrent.atomic.AtomicBoolean flag = batchCancelled;
        if (flag != null && !flag.getAndSet(true)) {
            cancelButton.setEnabled(false);
            progressBar.setString("正在取消...");
        }
    }

    /** 开始一次批量任务：重置取消标记并显示取消按钮。 */
    private java.util.concurrent.atomic.AtomicBoolean beginBatchRun() {
        java.util.concurrent.atomic.AtomicBoolean flag =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        batchCancelled = flag;
        if (cancelButton != null) {
            cancelButton.setEnabled(true);
            cancelButton.setVisible(true);
        }
        return flag;
    }

    /** 批量任务结束（成功、失败、取消都走这里）：隐藏取消按钮。 */
    private void endBatchRun() {
        batchCancelled = null;
        if (cancelButton != null) {
            cancelButton.setVisible(false);
            cancelButton.setEnabled(true);
        }
    }

    /**
     * 检查是否是联系人选择项
     */
    private boolean isContactSelection(String selection) {
        return selection != null && selection.startsWith("联系人 - ");
    }

    /**
     * 从联系人选择项中提取联系人名称
     */
    private String getContactNameFromSelection(String selection) {
        if (isContactSelection(selection)) {
            return selection.substring("联系人 - ".length());
        }
        return null;
    }

    /**
     * 批量操作前置验证
     */
    private boolean validateBatchOperationPrerequisites(String myKeyName, String otherSelection) {
        securecomm.controller.FileSelectionController.BatchBlocker blocker =
                fileSelection.validateBatch(myKeyName, otherSelection);
        if (blocker != securecomm.controller.FileSelectionController.BatchBlocker.NONE) {
            verifyResultLabel.setText(
                    blocker == securecomm.controller.FileSelectionController.BatchBlocker.NO_KEY_PAIR
                            ? "请先选择密钥对！"
                            : "请选择联系人！");
            verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
            return false;
        }

        // 更新联系人的最后使用时间
        if (isContactSelection(otherSelection)) {
            String contactName = getContactNameFromSelection(otherSelection);
            try {
                contacts.storage.ContactStorage.updateLastUsed(contactName);
            } catch (Exception e) {
                LogManager.warning("更新联系人最后使用时间失败: " + contactName);
            }
        }

        // 验证联系人绑定的私钥与当前状态一致
        return true;
    }
}
