package core.ui;

import app.theme.AppFonts;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;

public class ListManagerPanel extends JPanel {
    
    protected JTable dataTable;
    protected DefaultTableModel tableModel;
    
    /**
     * 构造函数
     * @param title 面板标题
     * @param columnNames 表格列名
     */
    public ListManagerPanel(String title, String[] columnNames) {
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        initComponents(title, columnNames);
    }
    
    /**
     * 初始化组件
     */
    private void initComponents(String title, String[] columnNames) {
        // 列表面板
        JPanel listPanel = new JPanel(new BorderLayout(10, 10));
        TitledBorder border = BorderFactory.createTitledBorder(title);
        border.setTitleFont(AppFonts.getLittleTitleFont());
        listPanel.setBorder(border);
        
        // 数据表格
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false; // 所有列都不可编辑
            }
        };
        
        dataTable = new JTable(tableModel);
        dataTable.setFont(AppFonts.getNormalFont());
        dataTable.getTableHeader().setFont(AppFonts.getBoldFont());
        dataTable.setRowHeight(35);
        dataTable.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        
        // 设置表格自动调整列宽模式
        dataTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        
        // 添加鼠标事件监听器，子类可以通过重写onRightClick方法来处理右键菜单
        dataTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = dataTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        onDoubleClick(row);
                    }
                }
            }
            
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }
            
            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }
            
            private void maybeShowPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = dataTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        // 只有当右键点击的行不在当前选择范围内时，才重置为单选
                        if (!dataTable.isRowSelected(row)) {
                            dataTable.setRowSelectionInterval(row, row);
                        }
                        int[] selectedRows = dataTable.getSelectedRows();
                        JPopupMenu popup = createPopupMenu(selectedRows);
                        if (popup != null) {
                            popup.show(e.getComponent(), e.getX(), e.getY());
                        }
                    }
                }
            }
        });
        
        JScrollPane tableScrollPane = new JScrollPane(dataTable);
        listPanel.add(tableScrollPane, BorderLayout.CENTER);
        
        // 组装主面板
        add(listPanel, BorderLayout.CENTER);
    }
    
    /**
     * 添加底部按钮面板
     * @param buttons 按钮数组
     */
    protected void addButtonPanel(JButton... buttons) {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 8));
        for (JButton button : buttons) {
            buttonPanel.add(button);
        }
        add(buttonPanel, BorderLayout.SOUTH);
    }
    
    /**
     * 双击表格行时触发的事件，子类需要重写此方法
     * @param row 被双击的行索引
     */
    protected void onDoubleClick(int row) {
        // 默认实现，子类可以重写
    }
    
    /**
     * 创建右键菜单，子类需要重写此方法
     * @param selectedRows 当前选中的所有行索引
     * @return 右键菜单，如果返回null则不显示菜单
     */
    protected JPopupMenu createPopupMenu(int[] selectedRows) {
        // 默认实现，子类可以重写
        return null;
    }
    
    /**
     * 获取选中的行索引
     * @return 选中的行索引，-1表示没有选中
     */
    protected int getSelectedRow() {
        return dataTable.getSelectedRow();
    }
    
    /**
     * 获取表格模型
     * @return 表格模型
     */
    public DefaultTableModel getTableModel() {
        return tableModel;
    }
    
    /**
     * 获取表格
     * @return 表格
     */
    public JTable getDataTable() {
        return dataTable;
    }
    
    /**
     * 自动调整所有列的宽度，使其适应内容
     */
    protected void packAllColumns() {
        SwingUtilities.invokeLater(() -> {
            // 确保表格已添加到容器中
            if (dataTable.isDisplayable()) {
                for (int column = 0; column < dataTable.getColumnCount(); column++) {
                    // 计算列的首选宽度
                    int columnWidth = 0;
                    
                    // 考虑表头宽度
                    int headerWidth = dataTable.getTableHeader().getColumnModel().getColumn(column).getHeaderValue().toString().length() * 10;
                    columnWidth = Math.max(columnWidth, headerWidth);
                    
                    // 考虑内容宽度
                    for (int row = 0; row < dataTable.getRowCount(); row++) {
                        Object value = dataTable.getValueAt(row, column);
                        if (value != null) {
                            int cellWidth = value.toString().length() * 10;
                            columnWidth = Math.max(columnWidth, cellWidth);
                        }
                    }
                    
                    // 设置列宽，添加一些缓冲空间
                    columnWidth = Math.min(columnWidth + 20, 500); // 最大宽度限制为500像素
                    dataTable.getColumnModel().getColumn(column).setPreferredWidth(columnWidth);
                }
            }
        });
    }
    
    /**
     * 将时间格式化为易读字符串（2026-06-12 11:22:33）
     * 仅用于显示，底层存储不变
     */
    protected static String formatDateTime(Date date) {
        if (date == null) return "未知";
        return date.toInstant()
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
    
    protected static String formatDateTime(Instant instant) {
        if (instant == null) return "未知";
        return instant
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
    
    /**
     * 重写表格模型的数据设置方法，确保在数据加载后自动调整列宽
     */
    public void setTableModel(DefaultTableModel tableModel) {
        this.tableModel = tableModel;
        dataTable.setModel(tableModel);
        packAllColumns();
    }
}
