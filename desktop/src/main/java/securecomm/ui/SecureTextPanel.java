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
import core.security.MemorySecurityUtils;
import core.util.FolderZipUtils;
import core.util.FileOperationErrors;
import core.util.LogManager;
import core.util.OperationMonitor;
import crypto.cipher.Decryptor;
import crypto.cipher.Encryptor;
import crypto.file.FileCryptoEngine;
import crypto.key.KeyPairManager;
import crypto.key.KeyUtils;
import crypto.message.SecureMessage;
import crypto.signature.SignatureManager;
import securecomm.ui.util.WorkerCallback;
import history.model.HistoryType;
import history.model.RecordCategory;
import history.storage.HistoryStorage;
import keymanagement.model.KeyPairInfo;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
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
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import keymanagement.storage.KeychainStorage;
import keymanagement.storage.KeyPairStorage;
import securecomm.service.FileDecryptService;
import securecomm.ui.OperationPanel;
import securecomm.ui.util.DecryptResult;
import securecomm.ui.util.FileTransferable;
import securecomm.ui.util.SecureTextFileUtils;
import securecomm.ui.util.SecureTextProgressUpdate;
import securecomm.ui.util.SecureTextStringUtils;
import securecomm.ui.util.SmoothProgressManager;
import securecomm.ui.util.TempFileManager;
import volumes.service.ExternalVolumeWriter;
import settings.manager.SettingsManager;
import settings.storage.SettingsStorage;
import stegano.ui.SteganoPanel;

public class SecureTextPanel extends OperationPanel {

    // 閻劋绨〒鍛倞閻ㄥ嫰娼ら幀浣哥穿閻劌鍨悰?
    private static java.util.List<SecureTextPanel> instances = new java.util.ArrayList<>();

    // 大文件阈值 - 用于判断文件是否需要特殊处理

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // 清理临时文件和缓存（主要通过 PrivateKeyAccessService 进行），确保 JVM 退出时清理敏感数据
            crypto.key.PrivateKeyAccessService.clearAllPasswordCaches();
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

    // 输入区滚动面板
    private JScrollPane inputScrollPane;

    // ============== 文件列表组件声明 ==============
    /** 文件列表 */
    private java.util.List<java.io.File> selectedFiles = new java.util.ArrayList<>();
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
        int totalCount = selectedFiles.size();

        if (selectedCount == 1) {
            // 闁鑵戞稉鈧稉顏呮瀮娴犺绱濋弰鍓с仛鐠囷妇绮忔穱鈩冧紖
            java.io.File selectedFile = fileListComponent.getSelectedValue();
            if (selectedFile != null) {
                String displayName;
                long displaySize;
                if (selectedFile.isDirectory()) {
                    // 閺勵垳娲拌ぐ?
                    displayName = selectedFile.getName();
                    displaySize = core.util.FolderZipUtils.calculateFolderSize(selectedFile);
                } else {
                    // 閺咁噣鈧碍鏋冩禒?
                    displayName = selectedFile.getName();
                    displaySize = selectedFile.length();
                }

                String sizeStr;
                if (displaySize < 1024) {
                    sizeStr = displaySize + " B";
                } else if (displaySize < 1024 * 1024) {
                    sizeStr = String.format("%.1f KB", displaySize / 1024.0);
                } else {
                    sizeStr = String.format("%.1f MB", displaySize / (1024.0 * 1024.0));
                }

                // 创建并添加文件名称标签
                JLabel fileNameLabel = new JLabel("文件: " + displayName);
                fileNameLabel.setFont(AppFonts.getNormalFont());
                fileNameLabel.setForeground(ThemeManager.getTextColor());
                fileDetailsBox.add(fileNameLabel);

                // 创建并添加文件大小标签
                JLabel sizeLabel = new JLabel("大小: " + sizeStr);
                sizeLabel.setFont(AppFonts.getNormalFont());
                sizeLabel.setForeground(ThemeManager.getTextColor());
                fileDetailsBox.add(sizeLabel);

                // 创建并添加文件路径标签
                JLabel pathLabel = new JLabel("路径: " + selectedFile.getParentFile().getAbsolutePath());
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
        boolean eligible = canPackageSelectedFiles();
        if (eligible && !packageChoiceWasEligible) {
            packageMultiFilesCheckBox.setSelected(SettingsStorage.isPackageMultiFilesDefault());
        } else if (!eligible) {
            packageMultiFilesCheckBox.setSelected(false);
        }
        packageChoiceWasEligible = eligible;
        packageMultiFilesCheckBox.setEnabled(eligible);
    }

    private boolean canPackageSelectedFiles() {
        int plainFiles = 0;
        for (java.io.File file : selectedFiles) {
            if (file.isDirectory() || TempFileManager.isZipFile(file)) {
                return false;
            }
            if (file.isFile()) {
                plainFiles++;
            }
        }
        return plainFiles >= 2;
    }

    // ========== 文件处理 Worker ==========
    private class AddFilesWorker extends SwingWorker<java.util.List<Object>, Object[]> {
        private final java.util.List<java.io.File> normalFiles;
        private final java.util.List<java.io.File> foldersToZip;
        private SmoothProgressManager smoothProgress;

        public AddFilesWorker(java.util.List<java.io.File> normalFiles, java.util.List<java.io.File> foldersToZip) {
            this.normalFiles = normalFiles;
            this.foldersToZip = foldersToZip;
        }

        @Override
        protected java.util.List<Object> doInBackground() throws Exception {
            smoothProgress = new SmoothProgressManager(progressBar);
            java.util.List<Object> processedFiles = new java.util.ArrayList<>();
            int totalFiles = normalFiles.size() + foldersToZip.size();
            int currentFileIndex = 0;

            // 1. 处理普通文件（可能分割+哈希）
            for (java.io.File file : normalFiles) {
                currentFileIndex++;
                final int finalCurrentFileIndex = currentFileIndex;
                final int finalTotalFiles = totalFiles;
                final String fileName = file.getName();
                long fileSize = file.length();
                publish(new Object[]{"start_file", fileName, finalCurrentFileIndex, finalTotalFiles});
                try {
                    if (!selectedFiles.contains(file)) {
                        processedFiles.add(file);
                        publish(new Object[]{"complete_file", fileName, finalCurrentFileIndex, finalTotalFiles, finalCurrentFileIndex * 100 / finalTotalFiles});
                    }
                } catch (Exception e) {
                    LogManager.error("add-file failed | bytes=" + file.length(), e);
                    publish(new Object[]{"error", fileName, e.getMessage()});
                }
            }

            // 2. 处理需要压缩的文件夹
            for (int i = 0; i < foldersToZip.size(); i++) {
                currentFileIndex++;
                final int finalCurrentFileIndex = currentFileIndex;
                final int finalTotalFiles = totalFiles;
                java.io.File folder = foldersToZip.get(i);
                final String folderName = folder.getName();
                publish(new Object[]{"start_folder", folderName, finalCurrentFileIndex, finalTotalFiles});
                java.io.File tempZipFile = null;
                try {
                    String tempDir = System.getProperty("java.io.tmpdir");
                    tempZipFile = java.io.File.createTempFile("aegix-folder-", ".zip", new java.io.File(tempDir));
                    tempZipFile.deleteOnExit();
                    core.util.FolderZipUtils.zipFolder(folder, tempZipFile, (current, total, item) -> {
                        int folderProgress = (total > 0) ? (current * 100 / total) : 0;
                        int overallStep = (finalCurrentFileIndex - 1) * 100 / finalTotalFiles;
                        int fileProgress = folderProgress / finalTotalFiles;
                        publish(new Object[]{"progress_folder", folderName, finalCurrentFileIndex, finalTotalFiles, overallStep + fileProgress, item, current, total});
                    });
                    TempFileManager.registerFolderZip(tempZipFile, folderName);
                    processedFiles.add(tempZipFile);
                    tempZipFile = null;
                    publish(new Object[]{"complete_file", folderName + ".zip", finalCurrentFileIndex, finalTotalFiles, finalCurrentFileIndex * 100 / finalTotalFiles});
                } catch (Exception e) {
                    if (tempZipFile != null) {
                        core.util.FolderZipUtils.safeDelete(tempZipFile);
                    }
                    LogManager.error("add-folder failed", e);
                    publish(new Object[]{"error", folder.getName(), FileOperationErrors.userMessage(e)});
                }
            }
            return processedFiles;
        }

        @Override
        protected void process(java.util.List<Object[]> chunks) {
            for (Object[] chunk : chunks) {
                String type = (String) chunk[0];
                switch (type) {
                    case "start_file":
                        String fileName = (String) chunk[1];
                        int idx = (Integer) chunk[2];
                        int total = (Integer) chunk[3];
                        smoothProgress.setTargetProgress((idx - 1) * 100 / total, "正在处理文件 [" + idx + "/" + total + "]: " + fileName);
                        verifyResultLabel.setText("正在进行分卷处理中...");
                        verifyResultLabel.setForeground(ThemeManager.getTextColor());
                        break;
                    case "start_folder":
                        String folderName = (String) chunk[1];
                        int fIdx = (Integer) chunk[2];
                        int fTotal = (Integer) chunk[3];
                        smoothProgress.setTargetProgress((fIdx - 1) * 100 / fTotal, "正在处理文件夹 [" + fIdx + "/" + fTotal + "]: " + folderName);
                        verifyResultLabel.setText("正在进行分卷处理中...");
                        verifyResultLabel.setForeground(ThemeManager.getTextColor());
                        break;
                    case "progress_folder":
                        String folderProgressName = (String) chunk[1];
                        int fpIdx = (Integer) chunk[2];
                        int fpTotal = (Integer) chunk[3];
                        int fpProgress = (Integer) chunk[4];
                        String itemName = (String) chunk[5];
                        smoothProgress.setTargetProgress(fpProgress, "正在处理文件夹项目 [" + fpIdx + "/" + fpTotal + "]: " + folderProgressName + " - " + itemName);
                        break;
                    case "complete_file":
                        String completeFile = (String) chunk[1];
                        int compIdx = (Integer) chunk[2];
                        int compTotal = (Integer) chunk[3];
                        int compProgress = (Integer) chunk[4];
                        smoothProgress.setTargetProgress(compProgress, "文件处理完成 [" + compIdx + "/" + compTotal + "]: " + completeFile);
                        break;
                    case "error":
                        String errFile = (String) chunk[1];
                        String errMsg = (String) chunk[2];
                        verifyResultLabel.setText("文件处理失败: " + errFile + " - " + errMsg);
                        verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                        break;
                }
            }
        }

        @Override
        protected void done() {
            if (smoothProgress != null) {
                smoothProgress.stop();
            }
            try {
                java.util.List<Object> processedFiles = get();
                int addedCount = 0;
                for (Object processedObj : processedFiles) {
                    if (processedObj instanceof java.io.File) {
                        java.io.File file = (java.io.File) processedObj;
                        if (!selectedFiles.contains(file)) {
                            selectedFiles.add(file);
                            fileListModel.addElement(file);
                            addedCount++;
                        }
                    }
                }
                if (addedCount > 0) {
                    cardLayout.show(centerPanel, "FILE_MODE");
                    updateFileDetails();
                    verifyResultLabel.setText("成功添加" + addedCount + "个文件");
                    verifyResultLabel.setForeground(UIConstants.SUCCESS_TEXT_COLOR);
                    progressBar.setValue(100);
                    progressBar.setString("文件处理完成");
                }
                updateSaveButtonState();
            } catch (Exception e) {
                LogManager.error("文件处理失败", e);
                verifyResultLabel.setText("文件处理失败: " + FileOperationErrors.userMessage(e));
                verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
            }
        }
    }

    /**
     * 濞ｈ濮為弬鍥︽閸掔増鏋冩禒璺哄灙鐞涱煉绱欓懛顏勫З婢跺嫮鎮婇弬鍥︽婢剁櫢绱?
     */
    private void addFilesToFileList(java.io.File[] files) {
        // 閸忓牊鏁归梿鍡涙付鐟曚礁顦╅悶鍡欐畱妞ゅ湱娲伴敍灞藉瀻缁傜粯娅橀柅姘瀮娴犺泛鎷伴弬鍥︽婢?
        java.util.List<java.io.File> normalFiles = new java.util.ArrayList<>();
        java.util.List<java.io.File> foldersToZip = new java.util.ArrayList<>();

        for (java.io.File file : files) {
            if (!file.exists()) {
                continue;
            }
            if (file.isDirectory()) {
                foldersToZip.add(file);
            } else {
                normalFiles.add(file);
            }
        }

        // 娴ｈ法鏁?SwingWorker 閸氬骸褰存径鍕倞閹碘偓閺堝鏋冩禒璁圭礄閺咁噣鈧碍鏋冩禒?+ 閺傚洣娆㈡径鐧哥礆
        new AddFilesWorker(normalFiles, foldersToZip).execute();
    }



    /**
     * 从文件列表移除选中的文件
     */
    private void removeSelectedFiles() {
        int[] selectedIndices = fileListComponent.getSelectedIndices();
        // 从后向前删除，避免索引变化导致删除出错
        for (int i = selectedIndices.length - 1; i >= 0; i--) {
            int index = selectedIndices[i];
            java.io.File file = fileListModel.getElementAt(index);
            selectedFiles.remove(file);
            fileListModel.removeElementAt(index);

            // ========== 如果删除的是临时分卷/压缩文件，尝试清理临时文件 ==========

            // 清理添加文件夹时生成的临时 ZIP
            if (TempFileManager.isZipFile(file)) {
                TempFileManager.unregisterZipFile(file);
                core.util.FolderZipUtils.safeDelete(file);
            }
        }

        updatePackageChoiceState();

        // 如果列表为空，切换回文本模式；否则更新文件列表提示文字
        if (selectedFiles.isEmpty()) {
            setTextMode();
        } else {
            updateFileListHint();
        }
    }

    /**
     * 更新文件列表提示
     */
    private void updateFileListHint() {
        String volumeLimit = SettingsStorage.getVolumeLimitPreset().getLabel();
        inputArea.setText("已选择 " + selectedFiles.size() + " 个文件\n"
                + "加密按钮用于加密；解密支持 .aegix 单文件与单卷外部包\n"
                + "多卷外部包请到「分卷合并」页选择全部卷\n"
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
        selectedFiles.clear();
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
            if (!selectedFiles.isEmpty()) {
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
            if (!selectedFiles.isEmpty()) {
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
            outputFilesList.add(fileInfo);
            outputFileListModel.addElement(fileInfo);

            // 更新文件详情显示
            updateOutputFileDetails();

            // 切换到文件模式显示
            outputCardLayout.show(outputCenterPanel, "FILE_MODE");

            // 更新保存按钮状态
            updateSaveButtonState();

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
        if (!selectedFiles.isEmpty()) {
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

    private class EncryptFilesWorker extends SwingWorker<List<FileInfo>, SecureTextProgressUpdate> {
        private List<java.io.File> filesToEncrypt;
        private String myKeyPairName;
        private String otherKeySelection;
        private long startTime;
        private boolean skipEncFiles;
        private boolean packageFiles;
        private final String archiveRootName;
        private final int originalFileCount;
        private int successCount = 0;
        private int failCount = 0;
        private String firstErrorMessage;
        private List<FileInfo> outputFiles = new java.util.ArrayList<>();
        private java.io.File outputDirectory;
        private List<FileInfo> inputFilesRecord = new java.util.ArrayList<>();
        private List<FileInfo> outputFilesRecord = new java.util.ArrayList<>();
        private SmoothProgressManager smoothProgress;

        EncryptFilesWorker(
                List<java.io.File> filesToEncrypt,
                String myKeyPairName,
                String otherKeySelection,
                long startTime,
                boolean skipEncFiles,
                boolean packageFiles,
                String archiveRootName
        ) {
            this.filesToEncrypt = filesToEncrypt;
            this.myKeyPairName = myKeyPairName;
            this.otherKeySelection = otherKeySelection;
            this.startTime = startTime;
            this.skipEncFiles = skipEncFiles;
            this.packageFiles = packageFiles;
            this.archiveRootName = archiveRootName;
            this.originalFileCount = filesToEncrypt.size();
        }

        @Override
        protected List<FileInfo> doInBackground() throws Exception {
            smoothProgress = new SmoothProgressManager(progressBar);

            publish(new SecureTextProgressUpdate(3, "正在初始化..."));

            String outputDir = settings.manager.SettingsManager.getDefaultOutputDirectory();
            if (outputDir == null || outputDir.isEmpty()) {
                outputDirectory = filesToEncrypt.get(0).getParentFile();
            } else {
                outputDirectory = new java.io.File(outputDir);
            }
            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs();
            }

            PrivateKey myPrivateKey = crypto.key.PrivateKeyAccessService.getPrivateKey(myKeyPairName);
            if (myPrivateKey == null) {
                return null;
            }
            publish(new SecureTextProgressUpdate(20, "已加载私钥..."));

            publish(new SecureTextProgressUpdate(20, "正在加载公钥..."));
            PublicKey otherPublicKey = loadPublicKeyFromSelection(otherKeySelection);
            long volumeLimitBytes = SettingsStorage.getVolumeLimitBytes();

            File packagedZip = null;
            try {
                if (packageFiles) {
                    List<java.io.File> members = new java.util.ArrayList<>();
                    for (java.io.File file : filesToEncrypt) {
                        if (skipEncFiles
                                && file.getName().toLowerCase().endsWith(".aegix")) {
                            continue;
                        }
                        members.add(file);
                    }
                    if (members.size() >= 2) {
                        File tempZip = File.createTempFile(
                                "aegix-archive-",
                                ".zip",
                                new File(System.getProperty("java.io.tmpdir")));
                        tempZip.deleteOnExit();
                        packagedZip = tempZip;
                        publish(new SecureTextProgressUpdate(10, "正在打包多个文件..."));
                        FolderZipUtils.zipFiles(members, tempZip, archiveRootName, null);
                        TempFileManager.registerArchiveZip(tempZip, archiveRootName + ".zip");
                        filesToEncrypt = java.util.Collections.singletonList(tempZip);
                    }
                }

                int total = filesToEncrypt.size();
                for (int i = 0; i < total; i++) {
                java.io.File inputFile = filesToEncrypt.get(i);

                try {
                    if (skipEncFiles && inputFile.getName().toLowerCase().endsWith(".aegix")) {
                        continue;
                    }

                    {
                        String originalFileName = inputFile.getName();
                        String archiveZipName = TempFileManager.getArchiveName(inputFile);
                        boolean isArchiveZip = archiveZipName != null;
                        boolean isFolderZip = TempFileManager.isFolderZip(inputFile);
                        String folderName = null;

                        if (isFolderZip) {
                            folderName = folderNameForZip(inputFile);
                            originalFileName = folderName + ".folder";
                        } else if (isArchiveZip) {
                            originalFileName = archiveZipName;
                        }

                        String timeStamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                        // 与 Android 一致：首个文件不带序号，只有同一秒内重名才追加 _2、_3。
                        String baseName = "secure_file_" + timeStamp;

                        if (inputFile.length() > volumeLimitBytes) {
                            String archiveName = isFolderZip
                                    ? (folderName + ".zip")
                                    : (isArchiveZip ? archiveZipName : inputFile.getName());
                            boolean sourceIsZip = isFolderZip || isArchiveZip;
                            List<java.io.File> parts = ExternalVolumeWriter.write(
                                    inputFile,
                                    sourceIsZip,
                                    archiveName,
                                    isFolderZip,
                                    null,
                                    outputDirectory,
                                    baseName,
                                    myPrivateKey,
                                    otherPublicKey,
                                    volumeLimitBytes,
                                    null
                            );
                            int partCount = parts.size();

                            FileInfo inFileInfo = new FileInfo(
                                isFolderZip
                                        ? (folderName + ".zip")
                                        : (isArchiveZip ? archiveZipName : inputFile.getName()),
                                inputFile.getAbsolutePath(),
                                inputFile.length(),
                                "成功"
                            );
                            inFileInfo.setOriginalFileName(isFolderZip
                                    ? folderName
                                    : (isArchiveZip ? archiveZipName : inputFile.getName()));
                            inFileInfo.setIsFolder(isFolderZip);
                            inFileInfo.setIsSplit(partCount > 1);
                            inFileInfo.setVolumeCount(partCount);
                            inputFilesRecord.add(inFileInfo);

                            for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
                                java.io.File part = parts.get(partIndex);
                                FileInfo outFileInfo = new FileInfo(
                                    part.getName(),
                                    part.getAbsolutePath(),
                                    part.length(),
                                    "成功"
                                );
                                outFileInfo.setOriginalFileName(archiveName);
                                outFileInfo.setSignatureValid(true);
                                outFileInfo.setIsSplit(partCount > 1);
                                outFileInfo.setVolumeCount(partCount);
                                outFileInfo.setCurrentVolume(partIndex + 1);
                                outputFilesRecord.add(outFileInfo);
                                outputFiles.add(outFileInfo);
                            }
                            successCount++;
                        } else {
                            String outputFileName = securecomm.ui.util.EncryptedFileNaming
                                    .uniqueName(outputDirectory, baseName);
                            final String headerOriginalFileName = originalFileName;
                            java.io.File outputFile = securecomm.ui.util.AtomicFileOutput.write(
                                    outputDirectory,
                                    outputFileName,
                                    encryptedOutput -> {
                                        try (java.io.InputStream signatureInput = new java.io.BufferedInputStream(
                                                     new java.io.FileInputStream(inputFile));
                                             java.io.InputStream plaintextInput = new java.io.BufferedInputStream(
                                                     new java.io.FileInputStream(inputFile))) {
                                            FileCryptoEngine.encrypt(
                                                    signatureInput,
                                                    plaintextInput,
                                                    inputFile.length(),
                                                    headerOriginalFileName,
                                                    myPrivateKey,
                                                    otherPublicKey,
                                                    encryptedOutput,
                                                    null,
                                                    new java.util.concurrent.atomic.AtomicBoolean(false)
                                            );
                                        }
                                    });

                            FileInfo inFileInfo = new FileInfo(
                                isFolderZip
                                        ? (folderName + ".zip")
                                        : (isArchiveZip ? archiveZipName : inputFile.getName()),
                                inputFile.getAbsolutePath(),
                                inputFile.length(),
                                "成功"
                            );
                            inFileInfo.setOriginalFileName(isFolderZip
                                    ? folderName
                                    : (isArchiveZip ? archiveZipName : inputFile.getName()));
                            inFileInfo.setIsFolder(isFolderZip);

                            FileInfo outFileInfo = new FileInfo(
                                outputFile.getName(),
                                outputFile.getAbsolutePath(),
                                outputFile.length(),
                                "成功"
                            );
                            outFileInfo.setOriginalFileName(originalFileName);
                            outFileInfo.setSignatureValid(true);

                            inputFilesRecord.add(inFileInfo);
                            outputFilesRecord.add(outFileInfo);

                            outputFiles.add(outFileInfo);
                            successCount++;
                        }
                    }

                } catch (Exception e) {
                    LogManager.error("encrypt-file failed | bytes=" + inputFile.length(), e);
                    failCount++;
                    String errorMessage = FileOperationErrors.userMessage(e);
                    if (firstErrorMessage == null) {
                        firstErrorMessage = errorMessage;
                    }
                    FileInfo inFileInfo = new FileInfo(
                        inputFile.getName(),
                        inputFile.getAbsolutePath(),
                        inputFile.length(),
                        "失败",
                        errorMessage
                    );
                    inputFilesRecord.add(inFileInfo);
                }

                int baseProgress = 20;
                int progressPerFile = 75 / total;
                int progress = baseProgress + (i + 1) * progressPerFile;
                String fileName = inputFile.getName();
                if (fileName.length() > 20) {
                    fileName = fileName.substring(0, 17) + "...";
                }
                publish(new SecureTextProgressUpdate(progress, "正在处理: " + fileName));
            }

                publish(new SecureTextProgressUpdate(100, "完成！"));
                return outputFiles;
            } finally {
                if (packagedZip != null) {
                    TempFileManager.unregisterZipFile(packagedZip);
                    FolderZipUtils.safeDelete(packagedZip);
                }
            }
        }

        @Override
        protected void process(List<SecureTextProgressUpdate> chunks) {
            for (SecureTextProgressUpdate update : chunks) {
                if (smoothProgress != null) {
                    if (update.progress == 100) {
                        smoothProgress.setImmediateProgress(100, update.stepText);
                    } else {
                        smoothProgress.setTargetProgress(update.progress, update.stepText);
                    }
                } else {
                    progressBar.setValue(update.progress);
                    progressBar.setString(update.stepText);
                }
            }
        }

        @Override
        protected void done() {
            if (smoothProgress != null) {
                smoothProgress.stop();
            }
            try {
                get();

                outputFilesList.clear();
                outputFileListModel.clear();

                for (FileInfo fileInfo : outputFiles) {
                    outputFilesList.add(fileInfo);
                    outputFileListModel.addElement(fileInfo);
                }

                updateOutputFileDetails();

                outputCardLayout.show(outputCenterPanel, "FILE_MODE");

                updateSaveButtonState();

                progressBar.setString("批量加密完成！");

                String resultText;
                if (failCount == 0) {
                    resultText = "成功 " + successCount;
                } else if (successCount == 0) {
                    resultText = "失败 " + failCount;
                } else {
                    resultText = "成功 " + successCount + "，失败 " + failCount;
                }

                if (successCount == 0 && failCount > 0) {
                    verifyResultLabel.setText("批量加密失败！"
                            + (firstErrorMessage == null ? "" : firstErrorMessage));
                } else {
                    verifyResultLabel.setText("批量加密完成！" + resultText);
                }
                verifyResultLabel.setForeground(failCount > 0
                        ? UIConstants.ERROR_TEXT_COLOR
                        : UIConstants.SUCCESS_TEXT_COLOR);

                String contactName = null;
                if (isContactSelection(otherKeySelection)) {
                    contactName = getContactNameFromSelection(otherKeySelection);
                }

                addHistoryRecordEnhanced(
                        history.model.HistoryType.ENCRYPT,
                        contactName,
                        history.model.RecordCategory.BATCH,
                        "批量加密 " + originalFileCount + " 个文件",
                        true,
                        resultText,
                        null,
                        null,
                        inputFilesRecord,
                        null,
                        outputFilesRecord,
                        myKeyPairName,
                        System.currentTimeMillis() - startTime
                );

            } catch (Exception ex) {
                verifyResultLabel.setText("批量加密失败：" + FileOperationErrors.userMessage(ex));
                verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                progressBar.setString("批量加密失败！");
                LogManager.error("批量加密失败", ex);
            } finally {
                for (int i = selectedFiles.size() - 1; i >= 0; i--) {
                    java.io.File file = selectedFiles.get(i);
                    if (TempFileManager.isZipFile(file)) {
                        TempFileManager.unregisterZipFile(file);
                        core.util.FolderZipUtils.safeDelete(file);
                        selectedFiles.remove(i);
                        fileListModel.removeElementAt(i);
                    }
                }


                updateFileDetails();
                updateSaveButtonState();
            }
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

            // ========== 閺傛澘顤冮敍姘梾濞?enc閺傚洣娆㈤獮鑸靛絹缁€?==========
            List<java.io.File> encFiles = new java.util.ArrayList<>();
            for (java.io.File file : selectedFiles) {
                if (file.getName().toLowerCase().endsWith(".aegix")) {
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
                    && canPackageSelectedFiles();
            if (packageFiles) {
                int candidates = 0;
                for (java.io.File file : selectedFiles) {
                    if (!(skipEncFiles
                            && file.getName().toLowerCase().endsWith(".aegix"))) {
                        candidates++;
                    }
                }
                packageFiles = candidates >= 2;
            }
            String archiveRootName = newArchiveRootName();
            if (!confirmVolumePreview(
                    selectedFiles, skipEncFiles, otherKeySelection, packageFiles, archiveRootName)) {
                return;
            }

            progressBar.setString("正在进行批量加密...");
            progressBar.setValue(0);
            verifyResultLabel.setText(" ");

            final String myKeyPairNameFinal = myKeyPairName;
            final String otherKeySelectionFinal = otherKeySelection;
            final List<java.io.File> filesToEncrypt = new java.util.ArrayList<>(selectedFiles);
            final boolean finalSkipEncFiles = skipEncFiles;
            final long startTime = System.currentTimeMillis();

            new EncryptFilesWorker(
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
        long volumeLimitBytes = SettingsStorage.getVolumeLimitBytes();
        PublicKey recipientKey;
        try {
            recipientKey = loadPublicKeyFromSelection(otherKeySelection);
        } catch (Exception error) {
            showError("无法加载接收方公钥：" + error.getMessage());
            return false;
        }

        List<String> lines = new ArrayList<>();
        int totalParts = 0;
        if (packageFiles) {
            long sourceBytes = 0L;
            int count = 0;
            for (File file : files) {
                if (skipEncFiles
                        && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".aegix")) {
                    continue;
                }
                sourceBytes += file.length();
                count++;
            }
            try {
                int partCount = ExternalVolumeWriter.previewPartCountForBytes(
                        sourceBytes,
                        count,
                        true,
                        archiveRootName + ".zip",
                        recipientKey,
                        volumeLimitBytes);
                if (partCount > 1) {
                    lines.add(count + " 个文件（打包） → 预计 " + partCount + " 卷");
                    totalParts += partCount;
                }
            } catch (Exception error) {
                showError("无法计算分卷：" + error.getMessage());
                return false;
            }
        }
        for (File file : packageFiles ? java.util.Collections.<File>emptyList() : files) {
            String lowerName = file.getName().toLowerCase(java.util.Locale.ROOT);
            if (skipEncFiles && lowerName.endsWith(".aegix")) {
                continue;
            }
            if (file.length() <= volumeLimitBytes) {
                continue;
            }

            boolean isFolderZip = TempFileManager.isFolderZip(file);
            String registeredArchive = TempFileManager.getArchiveName(file);
            boolean sourceIsZip = isFolderZip || registeredArchive != null;
            String archiveName = isFolderZip
                    ? folderNameForZip(file) + ".zip"
                    : (registeredArchive != null ? registeredArchive : file.getName());
            try {
                int partCount = ExternalVolumeWriter.previewPartCount(
                        file, sourceIsZip, archiveName, recipientKey, volumeLimitBytes);
                if (partCount > 1) {
                    lines.add((isFolderZip
                            ? folderNameForZip(file) + "（文件夹）"
                            : file.getName()) + " → " + partCount + " 卷");
                    totalParts += partCount;
                }
            } catch (Exception error) {
                showError("无法计算分卷：" + error.getMessage());
                return false;
            }
        }
        if (lines.isEmpty()) {
            return true;
        }

        StringBuilder message = new StringBuilder();
        message.append("将按当前设置（")
                .append(SettingsStorage.getVolumeLimitPreset().getLabel())
                .append("/卷）生成：\n\n");
        for (String line : lines) {
            message.append("• ").append(line).append('\n');
        }
        message.append("\n共 ").append(totalParts).append(" 卷。");
        if (totalParts > 20) {
            message.append("\n\n卷数较多，建议增大分卷大小或减少文件。");
        }
        message.append("\n\n是否开始加密？");

        JTextArea area = new JTextArea(message.toString());
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
                totalParts > 20 ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);
        return choice == 0;
    }

    /** 多文件归档根名与 Android 一致：`archive_<yyyyMMdd_HHmmss>`，不含用户文件名。 */
    private static String newArchiveRootName() {
        return "archive_" + java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }

    /** 优先取登记时的原文件夹名；只有旧调用点没有登记时才从临时名兜底。 */
    static String folderNameForZip(File zipFile) {
        String registered = TempFileManager.getFolderName(zipFile);
        if (registered != null) {
            return registered;
        }
        String tempName = zipFile.getName();
        String folderName = null;
        if (tempName.contains("_")) {
            int lastUnderscore = tempName.lastIndexOf('_');
            folderName = tempName.substring(0, lastUnderscore);
        }
        if (folderName == null || folderName.isEmpty()) {
            folderName = tempName.replace(".zip", "");
        }
        return folderName;
    }

    private class DecryptFilesWorker extends SwingWorker<List<FileInfo>, SecureTextProgressUpdate> {
        private List<java.io.File> filesToDecrypt;
        private String myKeyPairName;
        private String otherKeySelection;
        private long startTime;
        private int decryptSuccessCount = 0;
        private int decryptFailCount = 0;
        private int signatureFailCount = 0;
        private String firstErrorMessage;
        private List<FileInfo> outputFiles = new java.util.ArrayList<>();
        private java.io.File outputDirectory;
        private List<FileInfo> inputFilesRecord = new java.util.ArrayList<>();
        private List<FileInfo> outputFilesRecord = new java.util.ArrayList<>();
        private SmoothProgressManager smoothProgress;

        DecryptFilesWorker(List<java.io.File> filesToDecrypt, String myKeyPairName, String otherKeySelection, long startTime) {
            this.filesToDecrypt = filesToDecrypt;
            this.myKeyPairName = myKeyPairName;
            this.otherKeySelection = otherKeySelection;
            this.startTime = startTime;
        }

        /** 单文件 .aegix 与单卷外部包统一走共享内核。 */
        private void decryptBinaryFile(
                java.io.File inputFile,
                PrivateKey privateKey,
                List<PublicKey> senderCandidates,
                java.io.File outputDir,
                int baseProgress,
                int progressRange
        ) throws Exception {
            FileDecryptService.Result result = FileDecryptService.decrypt(
                    inputFile,
                    privateKey,
                    senderCandidates,
                    outputDir,
                    (phase, processed, total) -> {
                        int percent = total <= 0
                                ? 0
                                : (int) Math.min(100L, Math.max(0L, processed * 100L / total));
                        publish(new SecureTextProgressUpdate(
                                baseProgress + progressRange * percent / 100,
                                "正在解密: " + inputFile.getName()
                                        + " (" + LogManager.formatBytes(processed)
                                        + "/" + LogManager.formatBytes(total) + ")"));
                    },
                    (partNumber, partCount, processed, total) -> {
                        int percent = total <= 0
                                ? 0
                                : (int) Math.min(100L, Math.max(0L, processed * 100L / total));
                        publish(new SecureTextProgressUpdate(
                                baseProgress + progressRange * percent / 100,
                                "正在解密: " + inputFile.getName()
                                        + " [第 " + partNumber + "/" + partCount + " 卷, "
                                        + LogManager.formatBytes(processed)
                                        + "/" + LogManager.formatBytes(total) + "]"));
                    },
                    null);

            java.io.File output = result.getOutput();
            long outputSize = result.isFolder()
                    ? FolderZipUtils.calculateFolderSize(output)
                    : output.length();

            FileInfo outFileInfo = new FileInfo(
                    output.getName(),
                    output.getAbsolutePath(),
                    outputSize,
                    "成功");
            outFileInfo.setOriginalFileName(result.getOriginalFileName());
            outFileInfo.setIsFolder(result.isFolder());
            outFileInfo.setSignatureValid(result.isSignatureValid());
            if (result.getVolumeCount() > 0) {
                outFileInfo.setIsSplit(true);
                outFileInfo.setVolumeCount(result.getVolumeCount());
            }
            if (result.getSha256Hash() != null) {
                outFileInfo.setSha256Hash(result.getSha256Hash());
            }

            FileInfo inFileInfo = new FileInfo(
                    inputFile.getName(),
                    inputFile.getAbsolutePath(),
                    inputFile.length(),
                    "成功");
            inFileInfo.setOriginalFileName(inputFile.getName());
            inFileInfo.setSignatureValid(result.isSignatureValid());
            if (result.getVolumeCount() > 0) {
                inFileInfo.setIsSplit(true);
                inFileInfo.setVolumeCount(result.getVolumeCount());
            }
            if (!result.isSignatureValid()) {
                inFileInfo.setErrorMessage("签名无效");
                signatureFailCount++;
            }

            inputFilesRecord.add(inFileInfo);
            outputFilesRecord.add(outFileInfo);
            outputFiles.add(outFileInfo);
            decryptSuccessCount++;
            publish(new SecureTextProgressUpdate(
                    baseProgress + progressRange,
                    "已解密: " + inputFile.getName()));
        }

        @Override
        protected List<FileInfo> doInBackground() throws Exception {
            smoothProgress = new SmoothProgressManager(progressBar);

            publish(new SecureTextProgressUpdate(3, "正在初始化..."));

            String outputDir = settings.manager.SettingsManager.getDefaultOutputDirectory();
            if (outputDir == null || outputDir.isEmpty()) {
                outputDirectory = filesToDecrypt.get(0).getParentFile();
            } else {
                outputDirectory = new java.io.File(outputDir);
            }
            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs();
            }

            PrivateKey myPrivateKey = crypto.key.PrivateKeyAccessService.getPrivateKey(myKeyPairName);
            if (myPrivateKey == null) {
                return null;
            }
            publish(new SecureTextProgressUpdate(20, "已加载私钥..."));

            publish(new SecureTextProgressUpdate(20, "正在加载公钥..."));
            PublicKey otherPublicKey = loadPublicKeyFromSelection(otherKeySelection);
            List<PublicKey> senderCandidates = loadSenderCandidates();

            int total = filesToDecrypt.size();
            for (int i = 0; i < total; i++) {
                java.io.File inputFile = filesToDecrypt.get(i);
                final int fileBaseProgress = 20 + (i * 75 / total);
                final int fileProgressRange = Math.max(1, 75 / total);

                try {
                    decryptBinaryFile(
                            inputFile,
                            myPrivateKey,
                            senderCandidates,
                            outputDirectory,
                            fileBaseProgress,
                            fileProgressRange);
                } catch (Exception error) {
                    LogManager.error("decrypt-file failed | bytes=" + inputFile.length(), error);
                    decryptFailCount++;
                    String errorMessage = FileOperationErrors.userMessage(error);
                    if (firstErrorMessage == null) {
                        firstErrorMessage = errorMessage;
                    }
                    FileInfo inFileInfo = new FileInfo(
                            inputFile.getName(),
                            inputFile.getAbsolutePath(),
                            inputFile.length(),
                            "失败",
                            errorMessage,
                            null);
                    inFileInfo.setOriginalFileName(inputFile.getName());
                    inputFilesRecord.add(inFileInfo);
                    publish(new SecureTextProgressUpdate(
                            fileBaseProgress + fileProgressRange,
                            "解密失败: " + inputFile.getName()));
                }

                int baseProgress = 20;
                int progressPerFile = 75 / total;
                int progress = baseProgress + (i + 1) * progressPerFile;
                String fileName = inputFile.getName();
                if (fileName.length() > 20) {
                    fileName = fileName.substring(0, 17) + "...";
                }
                publish(new SecureTextProgressUpdate(progress, "正在处理: " + fileName));
            }

            publish(new SecureTextProgressUpdate(100, "完成！"));
            return outputFiles;
        }

        @Override
        protected void process(List<SecureTextProgressUpdate> chunks) {
            for (SecureTextProgressUpdate update : chunks) {
                if (smoothProgress != null) {
                    if (update.progress == 100) {
                        smoothProgress.setImmediateProgress(100, update.stepText);
                    } else {
                        smoothProgress.setTargetProgress(update.progress, update.stepText);
                    }
                } else {
                    progressBar.setValue(update.progress);
                    progressBar.setString(update.stepText);
                }
            }
        }

        @Override
        protected void done() {
            if (smoothProgress != null) {
                smoothProgress.stop();
            }
            try {
                get();

                outputFilesList.clear();
                outputFileListModel.clear();

                for (FileInfo fileInfo : outputFiles) {
                    outputFilesList.add(fileInfo);
                    outputFileListModel.addElement(fileInfo);
                }

                updateOutputFileDetails();

                outputCardLayout.show(outputCenterPanel, "FILE_MODE");

                updateSaveButtonState();

                progressBar.setString("批量解密完成！");

                String resultText;
                boolean hasDecryptSuccess = decryptSuccessCount > 0;
                boolean hasDecryptFail = decryptFailCount > 0;
                boolean hasSignatureFail = signatureFailCount > 0;

                if (!hasDecryptSuccess && !hasDecryptFail && !hasSignatureFail) {
                    resultText = "";
                } else if (!hasDecryptFail && !hasSignatureFail) {
                    resultText = "成功 " + decryptSuccessCount;
                } else if (!hasDecryptSuccess) {
                    resultText = "";
                    if (hasDecryptFail) {
                        resultText = "失败 " + decryptFailCount;
                    }
                    if (hasSignatureFail) {
                        if (!resultText.isEmpty()) {
                            resultText += "，";
                        }
                        resultText += "验证失败 " + signatureFailCount;
                    }
                } else {
                    resultText = "成功 " + decryptSuccessCount;
                    if (hasDecryptFail) {
                        resultText += "，失败 " + decryptFailCount;
                    }
                    if (hasSignatureFail) {
                        resultText += "，验证失败 " + signatureFailCount;
                    }
                }

                if (decryptSuccessCount == 0 && decryptFailCount > 0) {
                    verifyResultLabel.setText("批量解密失败！"
                            + (firstErrorMessage == null ? "" : firstErrorMessage));
                } else {
                    verifyResultLabel.setText("批量解密完成！" + resultText);
                }
                verifyResultLabel.setForeground(decryptFailCount > 0
                        ? UIConstants.ERROR_TEXT_COLOR
                        : UIConstants.SUCCESS_TEXT_COLOR);

                String contactName = null;
                if (isContactSelection(otherKeySelection)) {
                    contactName = getContactNameFromSelection(otherKeySelection);
                }

                addHistoryRecordEnhanced(
                        history.model.HistoryType.DECRYPT,
                        contactName,
                        history.model.RecordCategory.BATCH,
                        "批量解密 " + filesToDecrypt.size() + " 个文件",
                        true,
                        resultText,
                        null,
                        null,
                        inputFilesRecord,
                        null,
                        outputFilesRecord,
                        myKeyPairName,
                        System.currentTimeMillis() - startTime
                );

            } catch (Exception ex) {
                verifyResultLabel.setText("批量解密失败：" + FileOperationErrors.userMessage(ex));
                verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
                progressBar.setString("批量解密失败！");
                LogManager.error("批量解密失败", ex);
            }
        }
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
            final List<java.io.File> filesToDecrypt = new java.util.ArrayList<>(selectedFiles);
            final long startTime = System.currentTimeMillis();

            new DecryptFilesWorker(filesToDecrypt, myKeyPairNameFinal, otherKeySelectionFinal, startTime).execute();

        } catch (Exception ex) {
            showError("操作失败：" + ex.getMessage());
            LogManager.error("批量解密操作失败", ex);
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
        if (myKeyName == null || myKeyName.isEmpty()) {
            verifyResultLabel.setText("请先选择密钥对！");
            verifyResultLabel.setForeground(UIConstants.ERROR_TEXT_COLOR);
            return false;
        }
        if (otherSelection == null || otherSelection.equals("选择联系人")) {
            verifyResultLabel.setText("请选择联系人！");
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
