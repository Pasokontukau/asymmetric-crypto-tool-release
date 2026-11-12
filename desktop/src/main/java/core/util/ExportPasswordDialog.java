package core.util;

import java.awt.Component;
import java.util.Arrays;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

/**
 * 导出/导入时的口令输入框。
 *
 * <p>口令只存在于本次操作的字符数组里，不写日志、不落盘；调用方用完应自行清零。</p>
 */
public final class ExportPasswordDialog {

    public static final int MIN_LENGTH = 8;

    private ExportPasswordDialog() {
    }

    /**
     * 弹出口令框；用户取消时返回 {@code null}。
     *
     * @param confirm 导出时需要再输入一次确认
     */
    public static char[] ask(Component parent, String title, boolean confirm) {
        JPasswordField passwordField = new JPasswordField(24);
        JPasswordField confirmField = new JPasswordField(24);
        JPanel panel = new JPanel();
        panel.setLayout(new java.awt.GridLayout(confirm ? 4 : 2, 1, 0, 6));
        panel.add(new JLabel("密码（至少 " + MIN_LENGTH + " 位）："));
        panel.add(passwordField);
        if (confirm) {
            panel.add(new JLabel("再次输入密码："));
            panel.add(confirmField);
        }

        SwingUtilities.invokeLater(passwordField::requestFocusInWindow);

        while (true) {
            int result = JOptionPane.showConfirmDialog(
                    parent, panel, title,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return null;
            }

            char[] password = passwordField.getPassword();
            if (password.length < MIN_LENGTH) {
                JOptionPane.showMessageDialog(parent,
                        "密码至少需要 " + MIN_LENGTH + " 位。",
                        title, JOptionPane.WARNING_MESSAGE);
                continue;
            }
            if (confirm) {
                char[] repeated = confirmField.getPassword();
                boolean same = Arrays.equals(password, repeated);
                Arrays.fill(repeated, '\0');
                if (!same) {
                    Arrays.fill(password, '\0');
                    JOptionPane.showMessageDialog(parent,
                            "两次输入的密码不一致。",
                            title, JOptionPane.WARNING_MESSAGE);
                    continue;
                }
            }
            return password;
        }
    }
}
