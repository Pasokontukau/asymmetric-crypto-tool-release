package usage.ui;

import app.theme.AppFonts;
import app.theme.ThemeManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

public class UsagePanel extends JPanel {

    public UsagePanel() {
        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(20, 20, 20, 20));

        JScrollPane scrollPane = new JScrollPane();
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(16);

        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(new EmptyBorder(0, 10, 0, 10));

        addHeader(contentPanel);
        addQuickStart(contentPanel);
        addKeyPairGuide(contentPanel);
        addContactGuide(contentPanel);
        addSecureCommGuide(contentPanel);
        addHistoryGuide(contentPanel);
        addSettingsGuide(contentPanel);
        addFAQSection(contentPanel);

        scrollPane.setViewportView(contentPanel);
        add(scrollPane, BorderLayout.CENTER);
    }

    private void addHeader(JPanel parent) {
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
        headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel titleLabel = new JLabel("使用指南");
        titleLabel.setFont(AppFonts.getTitleFont());
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleLabel.setBorder(new EmptyBorder(10, 0, 5, 0));

        headerPanel.add(titleLabel);
        headerPanel.add(createDivider());

        parent.add(headerPanel);
    }

    private void addQuickStart(JPanel parent) {
        JPanel sectionPanel = createSectionPanel();

        JLabel sectionTitle = createSectionTitle("1. 快速开始");
        sectionPanel.add(sectionTitle);

        String[] steps = {
            "第一步：在「密钥对管理」中创建自己的密钥",
            "第二步：在「通讯录」中添加好友并关联密钥",
            "第三步：在「安全通信」中给好友发加密消息或加密文件"
        };

        for (int i = 0; i < steps.length; i++) {
            sectionPanel.add(createStepItem(i + 1, steps[i]));
        }

        parent.add(sectionPanel);
    }

    private void addKeyPairGuide(JPanel parent) {
        JPanel sectionPanel = createSectionPanel();

        JLabel sectionTitle = createSectionTitle("2. 密钥对管理");
        sectionPanel.add(sectionTitle);

        String[] steps = {
            "点击「密钥对管理」标签页",
            "点击「生成密钥对」按钮",
            "输入密钥名称（方便自己记住就行）",
            "设置密码（一定要记住！）",
            "选择密钥长度（选4096更安全）",
            "点击「生成」按钮完成",
            "右键点击已生成的密钥，可以复制公钥发给好友"
        };

        for (int i = 0; i < steps.length; i++) {
            sectionPanel.add(createSimpleStep(i + 1, steps[i]));
        }

        parent.add(sectionPanel);
    }

    private void addContactGuide(JPanel parent) {
        JPanel sectionPanel = createSectionPanel();

        JLabel sectionTitle = createSectionTitle("3. 通讯录管理");
        sectionPanel.add(sectionTitle);

        String[] steps = {
            "点击「通讯录」标签页",
            "点击「添加联系人」按钮",
            "输入联系人名称（方便记住是谁）",
            "粘贴好友给你的公钥",
            "选择关联的密钥（这样签名验证时就不用再选了）",
            "点击「添加」完成",
            "右键点击联系人，可以复制公钥或删除联系人"
        };

        for (int i = 0; i < steps.length; i++) {
            sectionPanel.add(createSimpleStep(i + 1, steps[i]));
        }

        sectionPanel.add(createSpacer(15));
        sectionPanel.add(createNoteItem("提示：一定要给联系人关联密钥，不然无法使用签名和验证功能哦！"));

        parent.add(sectionPanel);
    }

    private void addSecureCommGuide(JPanel parent) {
        JPanel sectionPanel = createSectionPanel();

        JLabel sectionTitle = createSectionTitle("4. 安全通信");
        sectionPanel.add(sectionTitle);

        JLabel subTitle1 = createSubTitle("选择联系人");
        sectionPanel.add(subTitle1);

        String[] contactSteps = {
            "在顶部下拉框选择要发送给哪个联系人",
            "密钥会根据你之前设置的关联关系自动选择"
        };

        for (int i = 0; i < contactSteps.length; i++) {
            sectionPanel.add(createSimpleStep(i + 1, contactSteps[i]));
        }

        sectionPanel.add(createSpacer(15));

        JLabel subTitle2 = createSubTitle("发送文字消息");
        sectionPanel.add(subTitle2);

        String[] textSteps = {
            "在输入区域输入你要发送的内容",
            "点击「签名并加密」按钮",
            "如果需要密码就输入，输入时可以点眼睛图标看一下输错没",
            "加密后的内容会显示在输出区域，可以复制发给好友",
            "收到密文后，点击「解密并验证」按钮，输入密码就能看到内容"
        };

        for (int i = 0; i < textSteps.length; i++) {
            sectionPanel.add(createSimpleStep(i + 1, textSteps[i]));
        }

        sectionPanel.add(createSpacer(15));

        JLabel subTitle3 = createSubTitle("发送文件");
        sectionPanel.add(subTitle3);

        String[] fileSteps = {
            "可以直接把文件拖到输入区域，也可以点按钮选择文件",
            "多个文件也可以一起拖进去",
            "大文件会自动拆分处理，不用你管",
            "点击「签名并加密」或「解密并验证」按钮",
            "完成后可以点「打开输出目录」查看结果",
            "想清空文件列表重新来，点「清空」按钮就行"
        };

        for (int i = 0; i < fileSteps.length; i++) {
            sectionPanel.add(createSimpleStep(i + 1, fileSteps[i]));
        }

        parent.add(sectionPanel);
    }

    private void addHistoryGuide(JPanel parent) {
        JPanel sectionPanel = createSectionPanel();

        JLabel sectionTitle = createSectionTitle("5. 历史记录");
        sectionPanel.add(sectionTitle);

        String[] steps = {
            "点击「历史记录」标签页查看以前的操作",
            "在顶部搜索框输入关键字可以快速找到记录",
            "在类型下拉框可以只看加密或解密记录",
            "在左侧选择联系人，只看和某个联系人的记录",
            "想标记重要记录？点一下星星图标就行",
            "想给记录写个备注？右键点击选择「添加备注」",
            "点「导出」可以把历史记录保存下来",
            "点「清空历史」会删除所有记录，会让你确认一下"
        };

        for (int i = 0; i < steps.length; i++) {
            sectionPanel.add(createSimpleStep(i + 1, steps[i]));
        }

        parent.add(sectionPanel);
    }

    private void addSettingsGuide(JPanel parent) {
        JPanel sectionPanel = createSectionPanel();

        JLabel sectionTitle = createSectionTitle("6. 设置");
        sectionPanel.add(sectionTitle);

        JLabel subTitle1 = createSubTitle("主题设置");
        sectionPanel.add(subTitle1);

        String[] themeSteps = {
            "选择浅色、深色或跟系统一样的主题",
            "调整字体大小让自己看着舒服",
            "修改后马上就能看到效果"
        };

        for (int i = 0; i < themeSteps.length; i++) {
            sectionPanel.add(createSimpleStep(i + 1, themeSteps[i]));
        }

        sectionPanel.add(createSpacer(15));

        JLabel subTitle2 = createSubTitle("安全设置");
        sectionPanel.add(subTitle2);

        String[] securitySteps = {
            "选择安全级别：严格模式或平衡模式",
            "严格模式：每次操作都要输密码，最安全但有点麻烦",
            "平衡模式：打开软件后输一次密码，暂时记住不用每次都输",
            "可以设置密码记住多久后自动忘记",
            "切换到严格模式会自动清掉所有记住的密码"
        };

        for (int i = 0; i < securitySteps.length; i++) {
            sectionPanel.add(createSimpleStep(i + 1, securitySteps[i]));
        }

        sectionPanel.add(createSpacer(15));

        JLabel subTitle3 = createSubTitle("存储设置");
        sectionPanel.add(subTitle3);

        String[] storageSteps = {
            "设置默认输出目录：加密解密后的文件默认保存在这里",
            "更改数据目录：你的密钥、联系人等信息保存在这里",
            "更改数据目录后要重新打开软件才能生效"
        };

        for (int i = 0; i < storageSteps.length; i++) {
            sectionPanel.add(createSimpleStep(i + 1, storageSteps[i]));
        }

        sectionPanel.add(createSpacer(15));

        JLabel subTitle4 = createSubTitle("其他设置");
        sectionPanel.add(subTitle4);

        String[] otherSteps = {
            "便捷模式（仅Windows）：在开发者选项里，Windows系统帮你记住密码",
            "日志管理：可以查看软件运行记录",
            "清空所有数据：想重新开始可以用这个，会删除所有东西",
            "关于：看看软件版本信息"
        };

        for (int i = 0; i < otherSteps.length; i++) {
            sectionPanel.add(createSimpleStep(i + 1, otherSteps[i]));
        }

        parent.add(sectionPanel);
    }

    private void addFAQSection(JPanel parent) {
        JPanel sectionPanel = createSectionPanel();

        JLabel sectionTitle = createSectionTitle("7. 常见问题");
        sectionPanel.add(sectionTitle);

        String[][] faqs = {
            {"忘记密码怎么办？", "很抱歉，密码忘了就找不回了！一定要把密码记在安全的地方！"},
            {"安全级别选哪个好？", "严格模式：每次都要输密码，最安全但麻烦；平衡模式：打开软件后输一次，记住一段时间；便捷模式（仅Windows）：在开发者选项里，Windows帮你记住，不用再输。"},
            {"为什么找不到选密钥的地方？", "不用选了！你给联系人关联了密钥后，软件会自动帮你选好。"},
            {"一定要给联系人关联密钥吗？", "可以不关联，但这样就用不了签名和验证功能了，建议还是关联上。"},
            {"公钥和私钥有什么区别？", "公钥给别人，别人用它加密给你的消息；私钥自己留好，用来解密别人给你的消息。"},
            {"怎么把公钥给别人？", "在密钥对管理里右键点击你的密钥，选「复制公钥」，然后通过微信、QQ等发给对方。"},
            {"最大能处理多大的文件？", "一般来说都可以处理，太大的文件软件会自动拆分处理。"},
            {"能直接拖文件进去吗？", "可以！在安全通信页面直接把文件拖进去就行。"},
            {"更改数据目录后要重启吗？", "是的，更改后要重新打开软件才能生效。"},
            {"大文件处理是什么？", "太大的文件软件会自动分成几个小部分处理，完成后自动合并。"},
            {"需要我自己合并文件吗？", "不需要！软件会自动处理好的。"}
        };

        for (String[] faq : faqs) {
            sectionPanel.add(createFAQCard(faq[0], faq[1]));
        }

        parent.add(sectionPanel);
    }

    private JPanel createSectionPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(new EmptyBorder(20, 0, 20, 0));
        return panel;
    }

    private JLabel createSectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setFont(AppFonts.getSubTitleFont());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(new EmptyBorder(0, 0, 15, 0));
        return label;
    }

    private JLabel createSubTitle(String text) {
        JLabel label = new JLabel(text);
        label.setFont(AppFonts.getBoldFont());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(new EmptyBorder(0, 0, 10, 0));
        return label;
    }

    private JPanel createStepItem(int number, String text) {
        JPanel stepPanel = new JPanel(new BorderLayout(15, 0));
        stepPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        stepPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));

        JPanel numPanel = new JPanel();
        numPanel.setPreferredSize(new Dimension(45, 45));
        numPanel.setLayout(new BorderLayout());
        
        JLabel numLabel = new JLabel(String.valueOf(number), SwingConstants.CENTER);
        numLabel.setFont(AppFonts.getSubTitleFont());
        numLabel.setOpaque(true);
        numLabel.setBackground(ThemeManager.getProgressColor());
        numLabel.setForeground(Color.WHITE);
        numLabel.setBorder(new EmptyBorder(0, 0, 2, 0));
        numPanel.add(numLabel, BorderLayout.CENTER);

        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setBorder(ThemeManager.createPanelBorder());
        
        // 使用JTextArea代替HTML JLabel，既可以自动换行又可以正确设置字体
        JTextArea contentArea = new JTextArea(text);
        contentArea.setFont(AppFonts.getNormalFont());
        contentArea.setBackground(ThemeManager.getPanelBackgroundColor());
        contentArea.setForeground(ThemeManager.getTextColor());
        contentArea.setLineWrap(true);
        contentArea.setWrapStyleWord(true);
        contentArea.setEditable(false);
        contentArea.setBorder(null);
        contentPanel.add(contentArea, BorderLayout.CENTER);

        stepPanel.add(numPanel, BorderLayout.WEST);
        stepPanel.add(contentPanel, BorderLayout.CENTER);
        stepPanel.setBorder(new EmptyBorder(8, 0, 8, 0));

        return stepPanel;
    }

    private JPanel createSimpleStep(int number, String text) {
        JPanel stepPanel = new JPanel(new BorderLayout(10, 0));
        stepPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        stepPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));

        JLabel numLabel = new JLabel(number + ".", SwingConstants.RIGHT);
        numLabel.setFont(AppFonts.getBoldFont());
        numLabel.setPreferredSize(new Dimension(30, 25));
        numLabel.setAlignmentX(Component.TOP_ALIGNMENT);

        // 使用JTextArea代替HTML JLabel，既可以自动换行又可以正确设置字体
        JTextArea contentArea = new JTextArea(text);
        contentArea.setFont(AppFonts.getNormalFont());
        contentArea.setBackground(ThemeManager.getBackgroundColor());
        contentArea.setForeground(ThemeManager.getTextColor());
        contentArea.setLineWrap(true);
        contentArea.setWrapStyleWord(true);
        contentArea.setEditable(false);
        contentArea.setBorder(null);

        stepPanel.add(numLabel, BorderLayout.WEST);
        stepPanel.add(contentArea, BorderLayout.CENTER);
        stepPanel.setBorder(new EmptyBorder(5, 0, 5, 0));

        return stepPanel;
    }

    private JPanel createNoteItem(String text) {
        JPanel notePanel = new JPanel(new BorderLayout(15, 0));
        notePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        notePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));
        notePanel.setBorder(new EmptyBorder(5, 0, 5, 0));

        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setBorder(ThemeManager.createPanelBorder());
        
        // 使用JTextArea代替HTML JLabel，既可以自动换行又可以正确设置字体
        JTextArea contentArea = new JTextArea(text);
        contentArea.setFont(AppFonts.getNormalFont());
        contentArea.setBackground(ThemeManager.getPanelBackgroundColor());
        contentArea.setForeground(ThemeManager.getTextColor());
        contentArea.setLineWrap(true);
        contentArea.setWrapStyleWord(true);
        contentArea.setEditable(false);
        contentArea.setBorder(null);
        contentPanel.add(contentArea, BorderLayout.CENTER);

        notePanel.add(contentPanel, BorderLayout.CENTER);

        return notePanel;
    }

    private JPanel createFAQCard(String question, String answer) {
        JPanel card = new JPanel(new BorderLayout());
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));
        card.setBorder(new EmptyBorder(8, 0, 8, 0));

        JPanel innerPanel = new JPanel();
        innerPanel.setLayout(new BoxLayout(innerPanel, BoxLayout.Y_AXIS));
        innerPanel.setBorder(ThemeManager.createPanelBorder());

        JLabel qLabel = new JLabel("问：" + question);
        qLabel.setFont(AppFonts.getBoldFont());
        qLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // 使用JTextArea代替HTML JLabel，既可以自动换行又可以正确设置字体
        JTextArea aArea = new JTextArea("答：" + answer);
        aArea.setFont(AppFonts.getNormalFont());
        aArea.setBackground(ThemeManager.getPanelBackgroundColor());
        aArea.setForeground(ThemeManager.getTextColor());
        aArea.setLineWrap(true);
        aArea.setWrapStyleWord(true);
        aArea.setEditable(false);
        aArea.setBorder(new EmptyBorder(8, 0, 0, 0));
        aArea.setAlignmentX(Component.LEFT_ALIGNMENT);

        innerPanel.add(qLabel);
        innerPanel.add(aArea);

        card.add(innerPanel, BorderLayout.CENTER);

        return card;
    }

    private Component createSpacer(int height) {
        return Box.createVerticalStrut(height);
    }

    private JComponent createDivider() {
        JSeparator separator = new JSeparator();
        separator.setAlignmentX(Component.LEFT_ALIGNMENT);
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
        return separator;
    }

    public void updateStyleSheet() {
        revalidate();
        repaint();
    }
}
