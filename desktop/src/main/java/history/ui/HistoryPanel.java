package history.ui;

import app.theme.AppFonts;
import app.theme.ThemeManager;
import app.theme.UIConstants;
import core.model.FileInfo;
import core.util.ExportPasswordDialog;
import core.util.FolderZipUtils;
import core.util.LogManager;
import history.model.HistoryRecord;
import history.model.HistoryType;
import history.model.RecordCategory;
import history.storage.HistoryExport;
import history.storage.HistoryStorage;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

public class HistoryPanel extends JPanel {
    
    private JTable historyTable;
    private DefaultTableModel tableModel;
    private JList<String> contactList;
    private DefaultListModel<String> contactListModel;
    private JTextField searchField;
    private JComboBox<String> categoryCombo;
    private JToggleButton starButton;
    private JTextArea statsLabel;
    
    private String currentContactFilter = null;
    private String currentTypeFilter = null;
    private boolean starFilterActive = false;
    private List<HistoryRecord> currentDisplayedRecords = new ArrayList<>();

    public HistoryPanel() {
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout(10, 10));
        setBackground(ThemeManager.getBackgroundColor());
        setBorder(new EmptyBorder(15, 15, 15, 15));

        add(createTopPanel(), BorderLayout.NORTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setLeftComponent(createLeftPanel());
        splitPane.setRightComponent(createRightPanel());
        splitPane.setResizeWeight(0.25);
        add(splitPane, BorderLayout.CENTER);

        loadHistory();
        loadContacts();
    }

    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.setBackground(ThemeManager.getBackgroundColor());

        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        filterPanel.setBackground(ThemeManager.getBackgroundColor());
        
        JLabel searchLabel = new JLabel("搜索:");
        searchLabel.setFont(AppFonts.getNormalFont());
        searchLabel.setForeground(ThemeManager.getTextColor());
        filterPanel.add(searchLabel);
        
        searchField = new JTextField(20);
        searchField.setFont(AppFonts.getNormalFont());
        searchField.setBorder(ThemeManager.createRoundedBorder(1));
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filterHistory(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filterHistory(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterHistory(); }
        });
        filterPanel.add(searchField);
        
        JLabel categoryLabel = new JLabel("类型:");
        categoryLabel.setFont(AppFonts.getNormalFont());
        categoryLabel.setForeground(ThemeManager.getTextColor());
        filterPanel.add(categoryLabel);
        
        categoryCombo = new JComboBox<>(new String[]{"全部", "文本加密", "文本解密", "文件加密", "文件解密"});
        categoryCombo.setFont(AppFonts.getNormalFont());
        categoryCombo.addActionListener(e -> {
            String selected = (String) categoryCombo.getSelectedItem();
            if ("全部".equals(selected)) {
                currentTypeFilter = null;
            } else {
                currentTypeFilter = selected;
            }
            filterHistory();
        });
        filterPanel.add(categoryCombo);
        
        // 仅收藏按钮
        starButton = new JToggleButton("仅收藏");
        starButton.setFont(AppFonts.getNormalFont());
        starButton.setSelected(false);
        starButton.addActionListener(e -> {
            starFilterActive = starButton.isSelected();
            if (starFilterActive) {
                starButton.setBackground(UIConstants.PRIMARY_BUTTON_COLOR);
                starButton.setForeground(Color.WHITE);
            } else {
                starButton.setBackground(null);
                starButton.setForeground(ThemeManager.getTextColor());
            }
            filterHistory();
        });
        filterPanel.add(starButton);

        panel.add(filterPanel, BorderLayout.WEST);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttonPanel.setBackground(ThemeManager.getBackgroundColor());
        
        JButton exportButton = ThemeManager.createPrimaryButton("导出");
        exportButton.addActionListener(e -> exportHistory());
        buttonPanel.add(exportButton);
        
        JButton clearButton = ThemeManager.createDangerButton("清空历史");
        clearButton.addActionListener(e -> clearHistory());
        buttonPanel.add(clearButton);

        panel.add(buttonPanel, BorderLayout.EAST);

        return panel;
    }

    private JPanel createLeftPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(ThemeManager.getBackgroundColor());
        panel.setPreferredSize(new Dimension(67, 0));
        panel.setMinimumSize(new Dimension(60, 0));
        panel.setMaximumSize(new Dimension(83, Integer.MAX_VALUE));
        
        contactListModel = new DefaultListModel<>();
        contactList = new JList<>(contactListModel);
        contactList.setFont(AppFonts.getNormalFont());
        contactList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        contactList.setBackground(ThemeManager.getPanelBackgroundColor());
        contactList.setForeground(ThemeManager.getTextColor());
        contactList.setFixedCellHeight(40);
        contactList.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        contactList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String selected = contactList.getSelectedValue();
                if (selected == null || selected.equals("全部")) {
                    currentContactFilter = null;
                } else {
                    currentContactFilter = selected;
                }
                filterHistory();
            }
        });
        
        JScrollPane scrollPane = new JScrollPane(contactList);
        TitledBorder border = new TitledBorder("联系人列表");
        border.setTitleFont(AppFonts.getLittleTitleFont());
        scrollPane.setBorder(border);
        scrollPane.setBackground(ThemeManager.getPanelBackgroundColor());
        panel.add(scrollPane, BorderLayout.CENTER);
        
        // 使用JTextArea代替HTML JLabel，避免字体问题
        statsLabel = new JTextArea("统计");
        statsLabel.setFont(AppFonts.getNormalFont());
        statsLabel.setForeground(ThemeManager.getTextColor());
        statsLabel.setBackground(ThemeManager.getPanelBackgroundColor());
        statsLabel.setEditable(false);
        statsLabel.setBorder(new EmptyBorder(10, 10, 10, 10));
        panel.add(statsLabel, BorderLayout.SOUTH);
        
        return panel;
    }

    private JPanel createRightPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ThemeManager.getBackgroundColor());
        
        String[] columnNames = {"时间", "描述", "结果"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return String.class;
            }
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        
        historyTable = new JTable(tableModel);
        historyTable.setFont(AppFonts.getNormalFont());
        historyTable.getTableHeader().setFont(AppFonts.getBoldFont());
        historyTable.getTableHeader().setBackground(ThemeManager.getButtonBackgroundColor());
        historyTable.getTableHeader().setForeground(ThemeManager.getTextColor());
        historyTable.setRowHeight(30);
        historyTable.setBackground(ThemeManager.getPanelBackgroundColor());
        historyTable.setForeground(ThemeManager.getTextColor());
        historyTable.setGridColor(ThemeManager.getBorderColor());
        historyTable.setSelectionBackground(UIConstants.PRIMARY_BUTTON_COLOR);
        historyTable.setSelectionForeground(Color.WHITE);
        historyTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        
        // 时间列固定宽度
        historyTable.getColumnModel().getColumn(0).setPreferredWidth(180);
        historyTable.getColumnModel().getColumn(0).setMaxWidth(180);
        historyTable.getColumnModel().getColumn(0).setMinWidth(180);
        
        // 结果列固定宽度
        historyTable.getColumnModel().getColumn(2).setPreferredWidth(200);
        historyTable.getColumnModel().getColumn(2).setMaxWidth(200);
        historyTable.getColumnModel().getColumn(2).setMinWidth(200);
        
        // 内容居中的渲染器（只给时间和结果列）
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        historyTable.getColumnModel().getColumn(0).setCellRenderer(centerRenderer);
        // 描述列保持默认（左对齐），暂不设置
        // 结果列在后面有自定义渲染器
        
        // 自定义结果列的单元格渲染器
        historyTable.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            {
                setHorizontalAlignment(JLabel.CENTER);
            }
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, 
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                
                if (value != null) {
                    String result = value.toString().trim();
                    Color foregroundColor = ThemeManager.getTextColor();
                    
                    // 简单高效的判断
                    boolean hasSuccess = result.contains("成功");
                    boolean hasFail = result.contains("失败");
                    boolean hasVerifyFail = result.contains("验证失败");
                    boolean hasInvalid = result.contains("无效");
                    boolean hasValid = result.contains("有效");
                    
                    // 快速检查是否有0的情况
                    boolean isZeroSuccess = result.contains("成功 0") || result.contains("成功0");
                    boolean isZeroFail = result.contains("失败 0") || result.contains("失败0");
                    boolean isZeroVerifyFail = result.contains("验证失败 0") || result.contains("验证失败0");
                    
                    boolean realSuccess = hasSuccess && !isZeroSuccess;
                    boolean realFail = (hasFail && !isZeroFail) || (hasVerifyFail && !isZeroVerifyFail);
                    
                    if (hasValid) realSuccess = true;
                    if (hasInvalid) realFail = true;
                    
                    if (realSuccess && realFail) {
                        foregroundColor = new Color(200, 150, 0); // 黄色
                    } else if (realFail) {
                        foregroundColor = UIConstants.ERROR_TEXT_COLOR; // 红色
                    } else if (realSuccess) {
                        foregroundColor = UIConstants.SUCCESS_TEXT_COLOR; // 绿色
                    }
                    
                    c.setForeground(isSelected ? Color.WHITE : foregroundColor);
                    c.setBackground(isSelected ? UIConstants.PRIMARY_BUTTON_COLOR : ThemeManager.getPanelBackgroundColor());
                }
                
                return c;
            }
        });
        
        historyTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = historyTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        showDetailsDialog(getRecordAtRow(row));
                    }
                }
            }
        });
        
        JPopupMenu popupMenu = createPopupMenu();
        historyTable.setComponentPopupMenu(popupMenu);
        historyTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = historyTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        historyTable.setRowSelectionInterval(row, row);
                    }
                }
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = historyTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        historyTable.setRowSelectionInterval(row, row);
                    }
                }
            }
        });
        
        JScrollPane scrollPane = new JScrollPane(historyTable);
        scrollPane.setBackground(ThemeManager.getPanelBackgroundColor());
        scrollPane.getViewport().setBackground(ThemeManager.getPanelBackgroundColor());
        scrollPane.setBorder(ThemeManager.createRoundedBorder(1));
        panel.add(scrollPane, BorderLayout.CENTER);
        
        return panel;
    }

    private JPopupMenu createPopupMenu() {
        JPopupMenu popup = new JPopupMenu();
        
        JMenuItem openFileItem = new JMenuItem("打开文件位置");
        openFileItem.setFont(AppFonts.getNormalFont());
        openFileItem.addActionListener(e -> openFileLocation());
        popup.add(openFileItem);
        
        popup.addSeparator();
        
        JMenuItem notesItem = new JMenuItem("添加备注");
        notesItem.setFont(AppFonts.getNormalFont());
        notesItem.addActionListener(e -> addNotes());
        popup.add(notesItem);
        
        popup.addSeparator();
        
        JMenuItem deleteItem = new JMenuItem("删除");
        deleteItem.setFont(AppFonts.getNormalFont());
        deleteItem.addActionListener(e -> deleteRecord());
        popup.add(deleteItem);
        
        return popup;
    }



    public void refreshData() {
        loadHistory();
        loadContacts();
    }
    
    private void loadHistory() {
        List<HistoryRecord> records = HistoryStorage.loadHistory();
        displayRecords(records);
        updateStats(records);
    }

    private void loadContacts() {
        contactListModel.clear();
        contactListModel.addElement("全部");
        
        Set<String> contacts = HistoryStorage.loadAllContactsFromHistory();
        List<String> sortedContacts = new ArrayList<>(contacts);
        Collections.sort(sortedContacts);
        for (String contact : sortedContacts) {
            contactListModel.addElement(contact);
        }
    }

    private void filterHistory() {
        String searchText = searchField.getText();
        List<HistoryRecord> records;
        
        if (searchText != null && !searchText.trim().isEmpty()) {
            records = HistoryStorage.searchHistory(searchText);
        } else {
            records = HistoryStorage.loadHistory();
        }
        
        if (currentContactFilter != null) {
            records = HistoryStorage.loadHistoryByContact(currentContactFilter);
        }
        
        if (currentTypeFilter != null) {
            records = records.stream()
                    .filter(r -> {
                        if ("文本加密".equals(currentTypeFilter)) {
                            return r.getType() == HistoryType.ENCRYPT && r.getCategory() == RecordCategory.TEXT;
                        } else if ("文本解密".equals(currentTypeFilter)) {
                            return r.getType() == HistoryType.DECRYPT && r.getCategory() == RecordCategory.TEXT;
                        } else if ("文件加密".equals(currentTypeFilter)) {
                            return r.getType() == HistoryType.ENCRYPT && r.getCategory() == RecordCategory.BATCH;
                        } else if ("文件解密".equals(currentTypeFilter)) {
                            return r.getType() == HistoryType.DECRYPT && r.getCategory() == RecordCategory.BATCH;
                        }
                        return true;
                    })
                    .collect(java.util.stream.Collectors.toList());
        }
        
        if (starFilterActive) {
            records = records.stream()
                    .filter(HistoryRecord::isStarred)
                    .collect(java.util.stream.Collectors.toList());
        }
        
        displayRecords(records);
        updateStats(records);
    }
    
    /**
     * 生成描述列的显示内容
     */
    private String generateDisplayDescription(HistoryRecord record) {
        if (record.getCategory() == RecordCategory.TEXT) {
            // 文本模式
            String text;
            // 加密操作显示输入文本，解密操作显示输出文本
            if (record.getType() == HistoryType.ENCRYPT) {
                text = record.getInputText();
            } else {
                text = record.getOutputText();
            }
            
            if (text != null && !text.isEmpty()) {
                if (text.length() > 30) {
                    return text.substring(0, 30) + "...";
                }
                return text;
            }
        } else if (record.getCategory() == RecordCategory.BATCH) {
            // 文件模式：加密显示输入文件，解密显示输出文件
            List<FileInfo> files;
            if (record.getType() == HistoryType.ENCRYPT) {
                files = record.getInputFiles();
            } else {
                files = record.getOutputFiles();
            }
            
            if (!files.isEmpty()) {
                if (files.size() == 1) {
                    FileInfo file = files.get(0);
                    StringBuilder sb = new StringBuilder();
                    if (Boolean.TRUE.equals(file.getIsSplit())) {
                        sb.append("[已分卷] ");
                    }
                    if (Boolean.TRUE.equals(file.getIsFolder())) {
                        sb.append("[文件夹] ");
                    }
                    sb.append(file.getFileName());
                    if (Boolean.TRUE.equals(file.getIsSplit()) && file.getVolumeCount() != null) {
                        sb.append(" (").append(file.getVolumeCount()).append(" 部分)");
                    }
                    return sb.toString();
                } else {
                    // 多个文件时，显示前两个 + "等 N 个文件"
                    StringBuilder sb = new StringBuilder();
                    FileInfo firstFile = files.get(0);
                    if (Boolean.TRUE.equals(firstFile.getIsSplit())) {
                        sb.append("[已分卷] ");
                    }
                    if (Boolean.TRUE.equals(firstFile.getIsFolder())) {
                        sb.append("[文件夹] ").append(firstFile.getFileName());
                    } else {
                        sb.append(firstFile.getFileName());
                    }
                    if (files.size() > 1) {
                        FileInfo secondFile = files.get(1);
                        sb.append(", ");
                        if (Boolean.TRUE.equals(secondFile.getIsSplit())) {
                            sb.append("[已分卷] ");
                        }
                        if (Boolean.TRUE.equals(secondFile.getIsFolder())) {
                            sb.append("[文件夹] ").append(secondFile.getFileName());
                        } else {
                            sb.append(secondFile.getFileName());
                        }
                    }
                    if (files.size() > 2) {
                        sb.append(" 等 ").append(files.size()).append(" 个文件/文件夹");
                    }
                    return sb.toString();
                }
            }
        }
        // 默认返回原描述
        return record.getDescription();
    }

    private void displayRecords(List<HistoryRecord> records) {
        currentDisplayedRecords = new ArrayList<>(records);
        tableModel.setRowCount(0);
        for (HistoryRecord record : records) {
            String displayTime = record.getTimestamp();
            // 如果是yyyy-MM-dd HH:mm:ss格式，去掉年份
            if (displayTime != null && displayTime.length() > 10 && displayTime.charAt(4) == '-') {
                displayTime = displayTime.substring(5); // 从第5个字符开始，也就是MM-dd HH:mm:ss
            }
            Object[] row = {
                displayTime,
                generateDisplayDescription(record),
                record.getResult()
            };
            tableModel.addRow(row);
        }
    }

    private HistoryRecord getRecordAtRow(int row) {
        if (row >= 0 && row < currentDisplayedRecords.size()) {
            return currentDisplayedRecords.get(row);
        }
        return null;
    }

    private void updateStats(List<HistoryRecord> records) {
        int total = records.size();
        int encrypt = 0, decrypt = 0;
        
        for (HistoryRecord record : records) {
            if (record.getType() == HistoryType.ENCRYPT) encrypt++;
            if (record.getType() == HistoryType.DECRYPT) decrypt++;
        }
        
        String statsText = String.format(
            "统计\n" +
            "总记录: %d\n" +
            "加密: %d, 解密: %d",
            total, encrypt, decrypt
        );
        statsLabel.setText(statsText);
    }

    private void showDetailsDialog(HistoryRecord record) {
        if (record == null) return;
        
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "历史记录详情", true);
        dialog.setSize(750, 600);
        dialog.setLocationRelativeTo(this);
        dialog.getContentPane().setBackground(ThemeManager.getBackgroundColor());
        
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBackground(ThemeManager.getBackgroundColor());
        panel.setBorder(new EmptyBorder(15, 15, 15, 15));
        
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBackground(ThemeManager.getBackgroundColor());
        StringBuilder info = new StringBuilder();
        info.append("操作ID: ").append(record.getId()).append("\n");
        info.append("时间: ").append(record.getTimestamp()).append("\n");
        if (record.getDurationMs() > 0) {
            info.append("耗时: ").append(record.getDurationMs()).append(" ms\n");
        }
        info.append("类型: ").append(record.getType().getDisplayName()).append("\n");
        if (record.getContactName() != null) {
            info.append("联系人: ").append(record.getContactName()).append("\n");
        }
        if (record.getKeyPairName() != null) {
            info.append("密钥对: ").append(record.getKeyPairName()).append("\n");
        }
        info.append("结果: ").append(record.getResult());
        
        JTextArea infoArea = new JTextArea(info.toString());
        infoArea.setFont(AppFonts.getNormalFont());
        infoArea.setForeground(ThemeManager.getTextColor());
        infoArea.setBackground(ThemeManager.getBackgroundColor());
        infoArea.setEditable(false);
        infoArea.setBorder(null);
        topPanel.add(infoArea, BorderLayout.CENTER);
        

        
        panel.add(topPanel, BorderLayout.NORTH);
        
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(AppFonts.getNormalFont());
        tabbedPane.setBackground(ThemeManager.getPanelBackgroundColor());
        
        if (record.getCategory() == RecordCategory.TEXT && record.getInputText() != null) {
            JTextArea inputTextArea = new JTextArea(record.getInputText());
            inputTextArea.setEditable(false);
            inputTextArea.setFont(AppFonts.getNormalFont());
            inputTextArea.setBackground(ThemeManager.getPanelBackgroundColor());
            inputTextArea.setForeground(ThemeManager.getTextColor());
            inputTextArea.setBorder(ThemeManager.createTextAreaBorder());
            inputTextArea.setLineWrap(true);
            inputTextArea.setWrapStyleWord(true);
            JScrollPane inputScrollPane = new JScrollPane(inputTextArea);
            inputScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            inputScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
            tabbedPane.addTab("输入", inputScrollPane);
        } else if (!record.getInputFiles().isEmpty()) {
            tabbedPane.addTab("输入文件", createFileListPanel(record.getInputFiles(), null, record.getType(), false));
        }
        
        if (record.getCategory() == RecordCategory.TEXT && record.getOutputText() != null) {
            JTextArea outputTextArea = new JTextArea(record.getOutputText());
            outputTextArea.setEditable(false);
            outputTextArea.setFont(AppFonts.getNormalFont());
            outputTextArea.setBackground(ThemeManager.getPanelBackgroundColor());
            outputTextArea.setForeground(ThemeManager.getTextColor());
            outputTextArea.setBorder(ThemeManager.createTextAreaBorder());
            outputTextArea.setLineWrap(true);
            outputTextArea.setWrapStyleWord(true);
            JScrollPane outputScrollPane = new JScrollPane(outputTextArea);
            outputScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            outputScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
            tabbedPane.addTab("输出", outputScrollPane);
        } else if (!record.getOutputFiles().isEmpty()) {
            tabbedPane.addTab("输出文件", createFileListPanel(record.getOutputFiles(), record.getInputFiles(), record.getType(), true));
        }
        
        JTextArea notesTextArea = new JTextArea(record.getNotes() != null ? record.getNotes() : "");
        notesTextArea.setFont(AppFonts.getNormalFont());
        notesTextArea.setBackground(ThemeManager.getPanelBackgroundColor());
        notesTextArea.setForeground(ThemeManager.getTextColor());
        notesTextArea.setBorder(new EmptyBorder(10, 10, 10, 10));
        notesTextArea.setLineWrap(true);
        notesTextArea.setWrapStyleWord(true);
        JScrollPane notesScrollPane = new JScrollPane(notesTextArea);
        notesScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        notesScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        tabbedPane.addTab("备注", notesScrollPane);
        
        if (tabbedPane.getTabCount() == 0) {
            JTextArea detailsArea = new JTextArea(record.getDetails() != null ? record.getDetails() : "");
            detailsArea.setEditable(false);
            detailsArea.setFont(AppFonts.getNormalFont());
            detailsArea.setBackground(ThemeManager.getPanelBackgroundColor());
            detailsArea.setForeground(ThemeManager.getTextColor());
            detailsArea.setBorder(ThemeManager.createTextAreaBorder());
            detailsArea.setLineWrap(true);
            detailsArea.setWrapStyleWord(true);
            JScrollPane detailsScrollPane = new JScrollPane(detailsArea);
            detailsScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            detailsScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
            tabbedPane.addTab("详情", detailsScrollPane);
        }
        
        panel.add(tabbedPane, BorderLayout.CENTER);
        
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        buttonPanel.setBackground(ThemeManager.getBackgroundColor());
        
        JButton starButton = ThemeManager.createPrimaryButton(record.isStarred() ? "已收藏" : "收藏");
        starButton.addActionListener(e -> {
            boolean newStarred = !record.isStarred();
            HistoryStorage.setStarred(record.getId(), newStarred);
            record.setStarred(newStarred);
            starButton.setText(newStarred ? "已收藏" : "收藏");
            loadHistory();
        });
        buttonPanel.add(starButton);
        
        JButton deleteButton = ThemeManager.createDangerButton("删除");
        deleteButton.addActionListener(e -> {
            int option = JOptionPane.showConfirmDialog(dialog, "确定要删除这条记录吗？", "确认", JOptionPane.YES_NO_OPTION);
            if (option == JOptionPane.YES_OPTION) {
                HistoryStorage.deleteHistoryRecord(record.getId());
                dialog.dispose();
                loadHistory();
            }
        });
        buttonPanel.add(deleteButton);
        
        JButton closeButton = ThemeManager.createPrimaryButton("关闭");
        closeButton.addActionListener(e -> {
            String notes = notesTextArea.getText();
            if (!notes.isEmpty() || record.getNotes() != null) {
                HistoryStorage.addNotes(record.getId(), notes.isEmpty() ? null : notes);
            }
            dialog.dispose();
            loadHistory();
        });
        buttonPanel.add(closeButton);
        
        panel.add(buttonPanel, BorderLayout.SOUTH);
        
        dialog.add(panel);
        dialog.setVisible(true);
    }

    private JPanel createFileListPanel(List<FileInfo> files, List<FileInfo> inputFiles, HistoryType type, boolean isOutputFile) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ThemeManager.getBackgroundColor());
        
        String[] columns;
        if (isOutputFile) {
            // 输出文件：显示原文件名、文件名
            columns = new String[]{"原文件名", "文件名"};
        } else if (type == HistoryType.DECRYPT) {
            // 输入文件（解密操作）：显示文件名、大小、结果、签名
            columns = new String[]{"文件名", "大小", "结果", "签名"};
        } else {
            // 输入文件（加密操作）：显示文件名、大小、结果
            columns = new String[]{"文件名", "大小", "结果"};
        }
        
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        for (int i = 0; i < files.size(); i++) {
            FileInfo file = files.get(i);
            Object[] rowData;
            if (isOutputFile) {
                // 输出文件显示原文件名和文件名
                String originalFileName = file.getOriginalFileName();
                if (originalFileName == null) originalFileName = file.getFileName();
                String displayFileName = "";
                if (Boolean.TRUE.equals(file.getIsSplit())) {
                    displayFileName += "[已分卷] ";
                }
                if (Boolean.TRUE.equals(file.getIsFolder())) {
                    displayFileName += "[文件夹] " + file.getFileName();
                } else {
                    displayFileName += file.getFileName();
                }
                if (Boolean.TRUE.equals(file.getIsSplit()) && file.getVolumeCount() != null) {
                    displayFileName += " (" + file.getVolumeCount() + " 部分)";
                }
                rowData = new Object[]{originalFileName, displayFileName};
            } else if (type == HistoryType.DECRYPT) {
                // 输入文件（解密操作）
                String signatureText = "";
                if (file.getSignatureValid() != null) {
                    signatureText = file.getSignatureValid() ? "有效" : "无效";
                }
                String displayFileName = "";
                if (Boolean.TRUE.equals(file.getIsSplit())) {
                    displayFileName += "[已分卷] ";
                }
                if (Boolean.TRUE.equals(file.getIsFolder())) {
                    displayFileName += "[文件夹] " + file.getFileName();
                } else {
                    displayFileName += file.getFileName();
                }
                if (Boolean.TRUE.equals(file.getIsSplit()) && file.getVolumeCount() != null) {
                    displayFileName += " (" + file.getVolumeCount() + " 部分)";
                }
                String sizeText;
                if (Boolean.TRUE.equals(file.getIsSplit()) && file.getOriginalFileSize() != null) {
                    sizeText = formatFileSize(file.getOriginalFileSize());
                } else {
                    sizeText = formatFileSize(file.getFileSize());
                }
                rowData = new Object[]{
                    displayFileName,
                    sizeText,
                    file.getStatus(),
                    signatureText
                };
            } else {
                // 输入文件（加密操作）
                String displayFileName = "";
                if (Boolean.TRUE.equals(file.getIsSplit())) {
                    displayFileName += "[已分卷] ";
                }
                if (Boolean.TRUE.equals(file.getIsFolder())) {
                    displayFileName += "[文件夹] " + file.getFileName();
                } else {
                    displayFileName += file.getFileName();
                }
                if (Boolean.TRUE.equals(file.getIsSplit()) && file.getVolumeCount() != null) {
                    displayFileName += " (" + file.getVolumeCount() + " 部分)";
                }
                String sizeText;
                if (Boolean.TRUE.equals(file.getIsSplit()) && file.getOriginalFileSize() != null) {
                    sizeText = formatFileSize(file.getOriginalFileSize());
                } else {
                    sizeText = formatFileSize(file.getFileSize());
                }
                rowData = new Object[]{
                    displayFileName,
                    sizeText,
                    file.getStatus()
                };
            }
            model.addRow(rowData);
        }
        JTable table = new JTable(model);
        table.setFont(AppFonts.getNormalFont());
        table.getTableHeader().setFont(AppFonts.getBoldFont());
        table.getTableHeader().setBackground(ThemeManager.getButtonBackgroundColor());
        table.getTableHeader().setForeground(ThemeManager.getTextColor());
        table.setRowHeight(30);
        table.setBackground(ThemeManager.getPanelBackgroundColor());
        table.setForeground(ThemeManager.getTextColor());
        table.setGridColor(ThemeManager.getBorderColor());
        
        // 设置自动调整模式为调整所有列，和主表格一致
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        
        // 设置列宽和对齐
        if (!isOutputFile) {
            if (type == HistoryType.DECRYPT) {
                // 解密输入文件：文件名、大小(120)、结果(70)、签名(70)
                javax.swing.table.TableColumnModel columnModel = table.getColumnModel();
                // 文件名：不限制宽度，不居中，设置最大宽度为无限大
                javax.swing.table.TableColumn fileNameColumn = columnModel.getColumn(0);
                fileNameColumn.setMaxWidth(Integer.MAX_VALUE);
                // 大小列
                javax.swing.table.TableColumn sizeColumn = columnModel.getColumn(1);
                sizeColumn.setPreferredWidth(120);
                sizeColumn.setMaxWidth(120);
                sizeColumn.setMinWidth(120);
                sizeColumn.setCellRenderer(new CenteredTableCellRenderer());
                // 结果列
                javax.swing.table.TableColumn resultColumn = columnModel.getColumn(2);
                resultColumn.setPreferredWidth(70);
                resultColumn.setMaxWidth(70);
                resultColumn.setMinWidth(70);
                resultColumn.setCellRenderer(new CenteredTableCellRenderer());
                // 签名列
                javax.swing.table.TableColumn signColumn = columnModel.getColumn(3);
                signColumn.setPreferredWidth(70);
                signColumn.setMaxWidth(70);
                signColumn.setMinWidth(70);
                signColumn.setCellRenderer(new CenteredTableCellRenderer());
            } else if (type == HistoryType.ENCRYPT) {
                // 加密输入文件：文件名、大小(120)、结果(70)
                javax.swing.table.TableColumnModel columnModel = table.getColumnModel();
                // 文件名：不限制宽度，不居中，设置最大宽度为无限大
                javax.swing.table.TableColumn fileNameColumn = columnModel.getColumn(0);
                fileNameColumn.setMaxWidth(Integer.MAX_VALUE);
                // 大小列
                javax.swing.table.TableColumn sizeColumn = columnModel.getColumn(1);
                sizeColumn.setPreferredWidth(120);
                sizeColumn.setMaxWidth(120);
                sizeColumn.setMinWidth(120);
                sizeColumn.setCellRenderer(new CenteredTableCellRenderer());
                // 结果列
                javax.swing.table.TableColumn resultColumn = columnModel.getColumn(2);
                resultColumn.setPreferredWidth(70);
                resultColumn.setMaxWidth(70);
                resultColumn.setMinWidth(70);
                resultColumn.setCellRenderer(new CenteredTableCellRenderer());
            }
        }
        
        // 根据是否为输出文件设置不同的选择样式
        if (isOutputFile) {
            table.setSelectionBackground(UIConstants.PRIMARY_BUTTON_COLOR);
            table.setSelectionForeground(Color.WHITE);
            table.setRowSelectionAllowed(true);
        } else {
            table.setSelectionBackground(ThemeManager.getPanelBackgroundColor());
            table.setSelectionForeground(ThemeManager.getTextColor());
            table.setRowSelectionAllowed(false);
        }
        
        table.setColumnSelectionAllowed(false);
        
        // 为输出文件添加右键菜单和双击事件
        if (isOutputFile) {
            table.setCellSelectionEnabled(true);
            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        int row = table.rowAtPoint(e.getPoint());
                        int col = table.columnAtPoint(e.getPoint());
                        if (row >= 0 && row < files.size()) {
                            if (col == 0) {
                                // 双击第一列：尝试打开原文件
                                openOriginalFile(files.get(row), inputFiles, row);
                            } else if (col == 1) {
                                // 双击第二列：打开输出文件
                                openFile(files.get(row));
                            }
                        }
                    }
                }
                
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        int row = table.rowAtPoint(e.getPoint());
                        int col = table.columnAtPoint(e.getPoint());
                        if (row >= 0) {
                            table.setRowSelectionInterval(row, row);
                            table.setColumnSelectionInterval(col, col);
                            JPopupMenu popupMenu = createFilePopupMenu(files, inputFiles, table);
                            popupMenu.show(e.getComponent(), e.getX(), e.getY());
                        }
                    }
                }
                
                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        int row = table.rowAtPoint(e.getPoint());
                        int col = table.columnAtPoint(e.getPoint());
                        if (row >= 0) {
                            table.setRowSelectionInterval(row, row);
                            table.setColumnSelectionInterval(col, col);
                            JPopupMenu popupMenu = createFilePopupMenu(files, inputFiles, table);
                            popupMenu.show(e.getComponent(), e.getX(), e.getY());
                        }
                    }
                }
            });
        } else {
            table.setCellSelectionEnabled(false);
        }
        
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBackground(ThemeManager.getPanelBackgroundColor());
        scrollPane.getViewport().setBackground(ThemeManager.getPanelBackgroundColor());
        scrollPane.setBorder(ThemeManager.createRoundedBorder(1));
        panel.add(scrollPane, BorderLayout.CENTER);
        
        return panel;
    }
    
    /**
     * 创建文件列表右键菜单
     */
    private JPopupMenu createFilePopupMenu(List<FileInfo> files, List<FileInfo> inputFiles, JTable table) {
        JPopupMenu popup = new JPopupMenu();
        int selectedColumn = table.getSelectedColumn();
        int selectedRow = table.getSelectedRow();
        FileInfo fileInfo = (selectedRow >= 0 && selectedRow < files.size()) ? files.get(selectedRow) : null;
        boolean isFolder = fileInfo != null && Boolean.TRUE.equals(fileInfo.getIsFolder());
        
        if (selectedColumn == 0) {
            // 右键第一列（原文件名）的菜单
            JMenuItem copyOriginalFileNameItem = new JMenuItem("复制原文件名");
            copyOriginalFileNameItem.setFont(AppFonts.getNormalFont());
            copyOriginalFileNameItem.addActionListener(e -> {
                int row = table.getSelectedRow();
                if (row >= 0 && row < files.size()) {
                    String originalFileName = files.get(row).getOriginalFileName();
                    if (originalFileName == null) originalFileName = files.get(row).getFileName();
                    copyToClipboard(originalFileName);
                    JOptionPane.showMessageDialog(this, "原文件名已复制到剪贴板", "成功", JOptionPane.INFORMATION_MESSAGE);
                }
            });
            popup.add(copyOriginalFileNameItem);
            
            // 根据是否是文件夹动态调整菜单项文字
            String openOriginalLabel = (isFolder) ? "打开原文件夹" : "打开原文件";
            JMenuItem openOriginalFileItem = new JMenuItem(openOriginalLabel);
            openOriginalFileItem.setFont(AppFonts.getNormalFont());
            openOriginalFileItem.addActionListener(e -> {
                int row = table.getSelectedRow();
                if (row >= 0 && row < files.size()) {
                    openOriginalFile(files.get(row), inputFiles, row);
                }
            });
            popup.add(openOriginalFileItem);
        } else if (selectedColumn == 1) {
            // 右键第二列（输出文件名）的菜单
            JMenuItem copyFileNameItem = new JMenuItem("复制文件名");
            copyFileNameItem.setFont(AppFonts.getNormalFont());
            copyFileNameItem.addActionListener(e -> {
                int row = table.getSelectedRow();
                if (row >= 0 && row < files.size()) {
                    copyToClipboard(files.get(row).getFileName());
                    JOptionPane.showMessageDialog(this, "文件名已复制到剪贴板", "成功", JOptionPane.INFORMATION_MESSAGE);
                }
            });
            popup.add(copyFileNameItem);
            
            JMenuItem copyFilePathItem = new JMenuItem("复制文件路径");
            copyFilePathItem.setFont(AppFonts.getNormalFont());
            copyFilePathItem.addActionListener(e -> {
                int row = table.getSelectedRow();
                if (row >= 0 && row < files.size()) {
                    copyToClipboard(files.get(row).getFilePath());
                    JOptionPane.showMessageDialog(this, "文件路径已复制到剪贴板", "成功", JOptionPane.INFORMATION_MESSAGE);
                }
            });
            popup.add(copyFilePathItem);
            
            // 根据是否是文件夹动态调整菜单项文字
            String openFileLabel = (isFolder) ? "打开文件夹" : "打开文件";
            String openLocationLabel = (isFolder) ? "打开文件夹所在位置" : "打开文件所在位置";
            
            JMenuItem openFileItem = new JMenuItem(openFileLabel);
            openFileItem.setFont(AppFonts.getNormalFont());
            openFileItem.addActionListener(e -> {
                int row = table.getSelectedRow();
                if (row >= 0 && row < files.size()) {
                    openFile(files.get(row));
                }
            });
            popup.add(openFileItem);
            
            JMenuItem openLocationItem = new JMenuItem(openLocationLabel);
            openLocationItem.setFont(AppFonts.getNormalFont());
            openLocationItem.addActionListener(e -> {
                int row = table.getSelectedRow();
                if (row >= 0 && row < files.size()) {
                    openFileLocation(files.get(row));
                }
            });
            popup.add(openLocationItem);
        }
        
        return popup;
    }
    
    /**
     * 打开文件或文件夹
     */
    private void openFile(FileInfo fileInfo) {
        String path = fileInfo.getFilePath();
        if (path == null || path.isEmpty()) {
            JOptionPane.showMessageDialog(this, "文件路径不存在", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        // 检查签名是否有效
        if (fileInfo.getSignatureValid() != null && !fileInfo.getSignatureValid()) {
            String[] options = {"打开文件", "删除文件", "取消"};
            int option = JOptionPane.showOptionDialog(
                this,
                "这是签名校验失败的文件，可能有危险数据，确认是否打开？",
                "警告",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]
            );
            
            if (option == 0) {
                // 打开文件，继续执行
            } else if (option == 1) {
                // 删除文件
                File file = new File(path);
                if (file.exists()) {
                    boolean deleted = core.util.FolderZipUtils.safeDelete(file);
                    
                    if (deleted) {
                        JOptionPane.showMessageDialog(this, "文件已删除", "提示", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(this, "文件删除失败", "错误", JOptionPane.ERROR_MESSAGE);
                    }
                }
                return;
            } else {
                // 取消
                return;
            }
        }
        
        try {
            File file = new File(path);
            if (file.exists()) {
                Desktop.getDesktop().open(file);
            } else {
                String typeLabel = Boolean.TRUE.equals(fileInfo.getIsFolder()) ? "文件夹" : "文件";
                JOptionPane.showMessageDialog(this, typeLabel + "不存在: " + path, "提示", JOptionPane.WARNING_MESSAGE);
            }
        } catch (Exception e) {
            String typeLabel = Boolean.TRUE.equals(fileInfo.getIsFolder()) ? "文件夹" : "文件";
            LogManager.error("打开" + typeLabel + "失败", e);
            JOptionPane.showMessageDialog(this, "打开" + typeLabel + "失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    /**
     * 打开文件或文件夹所在位置
     */
    private void openFileLocation(FileInfo fileInfo) {
        String path = fileInfo.getFilePath();
        if (path == null || path.isEmpty()) {
            JOptionPane.showMessageDialog(this, "文件路径不存在", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            File file = new File(path);
            if (file.exists()) {
                if (file.isDirectory()) {
                    // 文件夹：打开父目录
                    Desktop.getDesktop().open(file.getParentFile());
                } else {
                    // 文件：打开父目录
                    Desktop.getDesktop().open(file.getParentFile());
                }
            } else {
                String typeLabel = Boolean.TRUE.equals(fileInfo.getIsFolder()) ? "文件夹" : "文件";
                JOptionPane.showMessageDialog(this, typeLabel + "不存在: " + path, "提示", JOptionPane.WARNING_MESSAGE);
            }
        } catch (Exception e) {
            String typeLabel = Boolean.TRUE.equals(fileInfo.getIsFolder()) ? "文件夹" : "文件";
            LogManager.error("打开" + typeLabel + "位置失败", e);
            JOptionPane.showMessageDialog(this, "打开" + typeLabel + "位置失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    /**
     * 尝试打开原文件
     */
    private void openOriginalFile(FileInfo outputFileInfo, List<FileInfo> inputFiles, int row) {
        // 先看看同一行的输入文件有没有路径
        String originalPath = null;
        if (inputFiles != null && row < inputFiles.size()) {
            originalPath = inputFiles.get(row).getFilePath();
        }
        
        if (originalPath == null || originalPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "原文件路径不存在", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        try {
            File file = new File(originalPath);
            if (file.exists()) {
                Desktop.getDesktop().open(file);
            } else {
                JOptionPane.showMessageDialog(this, "原文件不存在: " + originalPath, "提示", JOptionPane.WARNING_MESSAGE);
            }
        } catch (Exception e) {
            LogManager.error("打开原文件失败", e);
            JOptionPane.showMessageDialog(this, "打开原文件失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    /**
     * 复制文本到剪贴板
     */
    private void copyToClipboard(String text) {
        if (text != null && !text.isEmpty()) {
            StringSelection selection = new StringSelection(text);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
        }
    }

    private String formatFileSize(long size) {
        return LogManager.formatBytes(size);
    }

    private void toggleStar() {
        int row = historyTable.getSelectedRow();
        if (row < 0) return;
        HistoryRecord record = getRecordAtRow(row);
        if (record != null) {
            HistoryStorage.setStarred(record.getId(), !record.isStarred());
            loadHistory();
        }
    }

    private void copyRecordContent() {
        int row = historyTable.getSelectedRow();
        if (row < 0) return;
        HistoryRecord record = getRecordAtRow(row);
        if (record != null) {
            copyRecordContent(record);
        }
    }

    private void copyRecordContent(HistoryRecord record) {
        StringBuilder content = new StringBuilder();
        if (record.getInputText() != null) {
            content.append(record.getInputText());
        } else if (record.getOutputText() != null) {
            content.append(record.getOutputText());
        } else if (!record.getOutputFiles().isEmpty()) {
            for (FileInfo file : record.getOutputFiles()) {
                content.append(file.getFilePath()).append("\n");
            }
        }
        
        if (content.length() > 0) {
            StringSelection selection = new StringSelection(content.toString());
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
            JOptionPane.showMessageDialog(this, "内容已复制到剪贴板");
        }
    }

    private void openFileLocation() {
        int row = historyTable.getSelectedRow();
        if (row < 0) return;
        HistoryRecord record = getRecordAtRow(row);
        if (record == null) return;
        
        try {
            String path = null;
            if (!record.getOutputFiles().isEmpty()) {
                path = record.getOutputFiles().get(0).getFilePath();
            } else if (!record.getInputFiles().isEmpty()) {
                path = record.getInputFiles().get(0).getFilePath();
            }
            
            if (path != null) {
                File file = new File(path);
                if (file.exists()) {
                    Desktop.getDesktop().open(file.getParentFile());
                } else {
                    JOptionPane.showMessageDialog(this, "文件不存在: " + path);
                }
            }
        } catch (Exception e) {
            LogManager.error("打开文件位置失败", e);
            JOptionPane.showMessageDialog(this, "打开失败: " + e.getMessage());
        }
    }

    private void addNotes() {
        int row = historyTable.getSelectedRow();
        if (row < 0) return;
        HistoryRecord record = getRecordAtRow(row);
        if (record == null) return;
        
        String notes = JOptionPane.showInputDialog(this, "添加备注:", record.getNotes());
        if (notes != null) {
            HistoryStorage.addNotes(record.getId(), notes.isEmpty() ? null : notes);
            loadHistory();
        }
    }

    private void deleteRecord() {
        int row = historyTable.getSelectedRow();
        if (row < 0) return;
        HistoryRecord record = getRecordAtRow(row);
        if (record == null) return;
        
        int option = JOptionPane.showConfirmDialog(this, "确定要删除这条记录吗？", "确认", JOptionPane.YES_NO_OPTION);
        if (option == JOptionPane.YES_OPTION) {
            HistoryStorage.deleteHistoryRecord(record.getId());
            loadHistory();
        }
    }

    private void setFontForAllComponents(Container container, Font font) {
        for (Component component : container.getComponents()) {
            component.setFont(font);
            if (component instanceof Container) {
                setFontForAllComponents((Container) component, font);
            }
        }
    }
    
    private void exportHistory() {
        Object[] options = {"加密格式（推荐）", "JSON（明文）", "CSV（明文）", "HTML（明文）", "取消"};
        int choice = JOptionPane.showOptionDialog(this,
            "请选择导出格式。加密导出不会在磁盘上留下文件名、路径或联系人。", "导出历史记录",
            JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
            null, options, options[0]);
        
        if (choice == 4 || choice < 0) return;
        boolean encrypted = choice == 0;
        if (!encrypted) {
            int confirm = JOptionPane.showConfirmDialog(this,
                "明文导出会把历史中的文件名、路径和联系人写成可直接阅读的文件。确定继续吗？",
                "明文导出提醒", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;
        }
        
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("导出历史记录");
        String defaultFileName = "history_export";
        switch (choice) {
            case 0:
                fileChooser.setSelectedFile(new File(defaultFileName + ".aexp"));
                break;
            case 1:
                fileChooser.setSelectedFile(new File(defaultFileName + ".json"));
                break;
            case 2:
                fileChooser.setSelectedFile(new File(defaultFileName + ".csv"));
                break;
            case 3:
                fileChooser.setSelectedFile(new File(defaultFileName + ".html"));
                break;
        }
        
        setFontForAllComponents(fileChooser, AppFonts.getNormalFont());
        fileChooser.setPreferredSize(new Dimension(1000, 700));
        fileChooser.setMinimumSize(new Dimension(800, 600));
        
        int userSelection = fileChooser.showSaveDialog(this);
        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File fileToSave = fileChooser.getSelectedFile();
            try {
                List<HistoryRecord> recordsToExport;
                // 获取当前筛选后的记录
                if (starFilterActive) {
                    recordsToExport = HistoryStorage.loadStarredHistory();
                    if (currentTypeFilter != null) {
                        recordsToExport = recordsToExport.stream()
                                .filter(r -> {
                                    if ("文本加密".equals(currentTypeFilter)) {
                                        return r.getType() == HistoryType.ENCRYPT && r.getCategory() == RecordCategory.TEXT;
                                    } else if ("文本解密".equals(currentTypeFilter)) {
                                        return r.getType() == HistoryType.DECRYPT && r.getCategory() == RecordCategory.TEXT;
                                    } else if ("文件加密".equals(currentTypeFilter)) {
                                        return r.getType() == HistoryType.ENCRYPT && r.getCategory() == RecordCategory.BATCH;
                                    } else if ("文件解密".equals(currentTypeFilter)) {
                                        return r.getType() == HistoryType.DECRYPT && r.getCategory() == RecordCategory.BATCH;
                                    }
                                    return true;
                                })
                                .collect(java.util.stream.Collectors.toList());
                    }
                    if (currentContactFilter != null) {
                        recordsToExport = recordsToExport.stream()
                                .filter(r -> Objects.equals(r.getContactName(), currentContactFilter))
                                .collect(java.util.stream.Collectors.toList());
                    }
                } else if (currentContactFilter != null) {
                    recordsToExport = HistoryStorage.loadHistoryByContact(currentContactFilter);
                    if (currentTypeFilter != null) {
                        recordsToExport = recordsToExport.stream()
                                .filter(r -> {
                                    if ("文本加密".equals(currentTypeFilter)) {
                                        return r.getType() == HistoryType.ENCRYPT && r.getCategory() == RecordCategory.TEXT;
                                    } else if ("文本解密".equals(currentTypeFilter)) {
                                        return r.getType() == HistoryType.DECRYPT && r.getCategory() == RecordCategory.TEXT;
                                    } else if ("文件加密".equals(currentTypeFilter)) {
                                        return r.getType() == HistoryType.ENCRYPT && r.getCategory() == RecordCategory.BATCH;
                                    } else if ("文件解密".equals(currentTypeFilter)) {
                                        return r.getType() == HistoryType.DECRYPT && r.getCategory() == RecordCategory.BATCH;
                                    }
                                    return true;
                                })
                                .collect(java.util.stream.Collectors.toList());
                    }
                } else if (currentTypeFilter != null) {
                    recordsToExport = HistoryStorage.loadHistory().stream()
                            .filter(r -> {
                                if ("文本加密".equals(currentTypeFilter)) {
                                    return r.getType() == HistoryType.ENCRYPT && r.getCategory() == RecordCategory.TEXT;
                                } else if ("文本解密".equals(currentTypeFilter)) {
                                    return r.getType() == HistoryType.DECRYPT && r.getCategory() == RecordCategory.TEXT;
                                } else if ("文件加密".equals(currentTypeFilter)) {
                                    return r.getType() == HistoryType.ENCRYPT && r.getCategory() == RecordCategory.BATCH;
                                } else if ("文件解密".equals(currentTypeFilter)) {
                                    return r.getType() == HistoryType.DECRYPT && r.getCategory() == RecordCategory.BATCH;
                                }
                                return true;
                            })
                            .collect(java.util.stream.Collectors.toList());
                } else {
                    recordsToExport = HistoryStorage.loadHistory();
                }
                
                if (encrypted) {
                    char[] password = ExportPasswordDialog.ask(this, "导出历史记录", true);
                    if (password == null) {
                        return;
                    }
                    try {
                        HistoryExport.writeEncrypted(fileToSave, recordsToExport, password);
                    } finally {
                        java.util.Arrays.fill(password, '\0');
                    }
                    JOptionPane.showMessageDialog(this, "历史记录已加密导出到: " + fileToSave.getAbsolutePath());
                } else {
                    String content;
                    switch (choice) {
                        case 1:
                            content = HistoryExport.toJson(recordsToExport);
                            break;
                        case 2:
                            content = HistoryExport.toCsv(recordsToExport);
                            break;
                        case 3:
                            content = HistoryExport.toHtml(recordsToExport);
                            break;
                        default:
                            throw new IllegalStateException("未知导出格式: " + choice);
                    }
                    java.nio.file.Files.write(fileToSave.toPath(), content.getBytes("UTF-8"));
                    JOptionPane.showMessageDialog(this, "导出成功！文件已保存到: " + fileToSave.getAbsolutePath());
                }
            } catch (Exception e) {
                LogManager.error("导出历史记录失败", e);
                JOptionPane.showMessageDialog(this, "导出失败: " + e.getMessage());
            }
        }
    }
    
    private void clearHistory() {
        int option = JOptionPane.showConfirmDialog(this, "确定要清空所有历史记录吗？此操作不可恢复！", 
            "警告", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (option == JOptionPane.YES_OPTION) {
            HistoryStorage.clearHistory();
            loadHistory();
        }
    }
    
    /**
     * 居中对齐的单元格渲染器
     */
    private static class CenteredTableCellRenderer extends DefaultTableCellRenderer {
        public CenteredTableCellRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
        }
    }
}
