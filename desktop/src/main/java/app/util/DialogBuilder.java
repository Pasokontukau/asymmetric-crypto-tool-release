package app.util;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Window;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;

public class DialogBuilder {
    private String title = "";
    private JComponent content;
    private boolean modal = true;
    private boolean resizable = false;
    private Dimension size = null;
    
    /**
     * 设置对话框标题
     */
    public DialogBuilder title(String title) {
        this.title = title;
        return this;
    }
    
    /**
     * 设置对话框内容
     */
    public DialogBuilder content(JComponent content) {
        this.content = content;
        return this;
    }
    
    /**
     * 设置对话框是否模态
     */
    public DialogBuilder modal(boolean modal) {
        this.modal = modal;
        return this;
    }
    
    /**
     * 设置对话框是否可调整大小
     */
    public DialogBuilder resizable(boolean resizable) {
        this.resizable = resizable;
        return this;
    }
    
    /**
     * 设置对话框大小
     */
    public DialogBuilder size(int width, int height) {
        this.size = new Dimension(width, height);
        return this;
    }
    
    /**
     * 构建并显示对话框
     * @param parent 父组件，用于居中定位
     * @return 创建的对话框
     */
    public JDialog show(Component parent) {
        // 获取父窗口
        Window parentWindow = SwingUtilities.getWindowAncestor(parent);
        
        // 创建对话框
        JDialog dialog;
        if (parentWindow instanceof Frame) {
            dialog = new JDialog((Frame) parentWindow, title, modal);
        } else if (parentWindow instanceof Dialog) {
            dialog = new JDialog((Dialog) parentWindow, title, modal);
        } else {
            dialog = new JDialog();
            dialog.setTitle(title);
            dialog.setModal(modal);
        }
        
        // 设置内容
        if (content != null) {
            dialog.add(content, BorderLayout.CENTER);
        }
        
        // 设置是否可调整大小
        dialog.setResizable(resizable);
        
        // 设置大小或自动调整
        if (size != null) {
            dialog.setSize(size);
        } else {
            dialog.pack();
        }
        
        // 居中显示（关键：在设置好大小之后调用）
        dialog.setLocationRelativeTo(parent);
        
        // 显示对话框
        dialog.setVisible(true);
        
        return dialog;
    }
    
    /**
     * 仅构建对话框但不显示
     * @param parent 父组件
     * @return 创建的对话框
     */
    public JDialog build(Component parent) {
        Window parentWindow = SwingUtilities.getWindowAncestor(parent);
        
        JDialog dialog;
        if (parentWindow instanceof Frame) {
            dialog = new JDialog((Frame) parentWindow, title, modal);
        } else if (parentWindow instanceof Dialog) {
            dialog = new JDialog((Dialog) parentWindow, title, modal);
        } else {
            dialog = new JDialog();
            dialog.setTitle(title);
            dialog.setModal(modal);
        }
        
        if (content != null) {
            dialog.add(content, BorderLayout.CENTER);
        }
        
        dialog.setResizable(resizable);
        
        if (size != null) {
            dialog.setSize(size);
        }
        
        return dialog;
    }
}
