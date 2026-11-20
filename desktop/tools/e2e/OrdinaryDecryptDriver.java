import app.AppMain;
import app.MainFrame;
import core.util.LogManager;
import crypto.file.FileCryptoEngine;
import crypto.key.KeyPairManager;
import crypto.volume.VolumePartPlan;
import crypto.volume.VolumePartWriter;
import history.model.HistoryRecord;
import history.storage.HistoryStorage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import securecomm.ui.SecureTextPanel;
import settings.manager.SettingsManager;
import settings.storage.SettingsStorage;

/**
 * 真实界面里的普通解密自测：单卷包、普通多文件批量、批量取消、只读输出目录。
 *
 * <p>用法：{@code OrdinaryDecryptDriver <singlePart|ordinary|ordinaryCancel|readonly> <home>}。</p>
 */
public final class OrdinaryDecryptDriver {

    private static final String KEY_NAME = "e2e-ordinary";
    private static final String PASSWORD = "e2e-pass-123";
    private static final long TIMEOUT_MS = 120_000L;

    private OrdinaryDecryptDriver() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "usage: OrdinaryDecryptDriver <singlePart|ordinary|ordinaryCancel|readonly> <home>");
        }
        String mode = args[0];
        File home = new File(args[1]).getAbsoluteFile();
        Files.createDirectories(home.toPath());
        System.setProperty("app.home", home.getAbsolutePath());
        System.setProperty("user.dir", home.getAbsolutePath());

        LogManager.init();
        SettingsManager.init();
        SettingsStorage.setTestMode(true);

        File outputDir = new File(home, "output-" + mode);
        Files.createDirectories(outputDir.toPath());
        SettingsManager.setDefaultOutputDirectory(outputDir.getAbsolutePath());

        KeyPair keys = KeyPairManager.generateKeyPair(4096);
        UiDriverSupport.presetKeyPair(KEY_NAME, PASSWORD, keys);

        List<File> inputs = fixtures(mode, home, keys);
        UiDriverSupport.pass("夹具已生成：" + inputs.size() + " 个输入");

        boolean readOnly = "readonly".equals(mode);
        if (readOnly) {
            applyReadOnly(outputDir, true);
            // System.exit 不会走 finally，必须用关闭钩子兜底恢复 ACL，
            // 否则 Maven clean 会因目录不可写而失败。
            Runtime.getRuntime().addShutdownHook(
                    new Thread(() -> restoreReadOnlyQuietly(outputDir), "readonly-restore"));
            UiDriverSupport.pass("输出目录已设为只读：" + outputDir);
        }

        try {
            AppMain.main(new String[0]);
            MainFrame frame = UiDriverSupport.waitForFrame();
            if (frame == null) {
                UiDriverSupport.fail("MainFrame 未在超时内显示");
            }
            SecureTextPanel panel = UiDriverSupport.find(frame.getContentPane(), SecureTextPanel.class);
            if (panel == null) {
                UiDriverSupport.fail("未找到 SecureTextPanel");
            }

            UiDriverSupport.selectKey(panel, KEY_NAME);
            UiDriverSupport.addFiles(panel, inputs.toArray(new File[0]));
            UiDriverSupport.pass("界面文件列表已有 " + inputs.size() + " 项");

            JLabel label = (JLabel) UiDriverSupport.field(panel, "verifyResultLabel");
            AtomicBoolean finished = new AtomicBoolean(false);
            Thread pump = new Thread(
                    () -> UiDriverSupport.pumpPasswordDialogs(finished, PASSWORD), "password-pump");
            pump.setDaemon(true);
            pump.start();

            JButton decrypt = UiDriverSupport.findButton(frame, "解密并验证");
            if (decrypt == null) {
                UiDriverSupport.fail("未找到“解密并验证”按钮");
            }
            SwingUtilities.invokeAndWait(decrypt::doClick);

            if ("ordinaryCancel".equals(mode)) {
                JButton cancel = (JButton) UiDriverSupport.field(panel, "cancelButton");
                if (!UiDriverSupport.waitFor(cancel::isShowing, 10_000L)) {
                    UiDriverSupport.fail("取消按钮没有出现");
                }
                Thread.sleep(600L);
                SwingUtilities.invokeAndWait(cancel::doClick);
                UiDriverSupport.pass("已点击取消");
            }

            if (!UiDriverSupport.waitFor(() -> {
                String text = label.getText();
                return text != null && expectedText(mode, text);
            }, TIMEOUT_MS)) {
                UiDriverSupport.fail("操作未在超时内结束，当前提示：" + label.getText());
            }
            finished.set(true);

            String resultText = label.getText() == null ? "" : label.getText();
            UiDriverSupport.pass("结果文案：" + resultText.replace('\n', ' '));

            List<?> outputs = (List<?>) UiDriverSupport.field(panel, "outputFilesList");
            assertNoTempFiles(outputDir);
            switch (mode) {
                case "singlePart":
                    assertSinglePart(outputs, outputDir, home, resultText);
                    break;
                case "ordinary":
                    assertOrdinary(outputs, outputDir, resultText);
                    break;
                case "ordinaryCancel":
                    assertCancelled(panel, outputs, inputs.size());
                    break;
                default:
                    assertReadOnly(outputs, outputDir, resultText);
            }

            SwingUtilities.invokeAndWait(frame::dispose);
            System.out.println("ORDINARY_DECRYPT_PASS");
            System.exit(0);
        } finally {
            if (readOnly) {
                restoreReadOnlyQuietly(outputDir);
            }
        }
    }

    private static List<File> fixtures(String mode, File home, KeyPair keys) throws Exception {
        switch (mode) {
            case "singlePart":
                return Collections.singletonList(singlePartPackage(home, keys));
            case "readonly":
                return Collections.singletonList(encryptFile(home, keys, "Readonly", 64 * 1024));
            case "ordinaryCancel":
                List<File> many = new ArrayList<File>();
                for (int index = 1; index <= 36; index++) {
                    many.add(encryptFile(home, keys, "Cancel" + index, 2 * 1024 * 1024));
                }
                return many;
            case "ordinary":
                List<File> few = new ArrayList<File>();
                for (int index = 1; index <= 3; index++) {
                    few.add(encryptFile(home, keys, "Doc" + index, 32 * 1024));
                }
                return few;
            default:
                UiDriverSupport.fail("未知模式：" + mode);
                return Collections.emptyList();
        }
    }

    private static boolean expectedText(String mode, String text) {
        if ("ordinaryCancel".equals(mode)) {
            return text.contains("已取消");
        }
        if ("readonly".equals(mode)) {
            return text.contains("批量解密失败");
        }
        return text.contains("批量解密完成");
    }

    private static void assertSinglePart(List<?> outputs, File outputDir, File home,
            String resultText) throws Exception {
        if (outputs.size() != 1) {
            UiDriverSupport.fail("单卷包输出应为 1 项，实际 " + outputs.size());
        }
        if (resultText.contains("已合并")) {
            UiDriverSupport.fail("单卷包不应走合并路径：" + resultText);
        }
        File product = onlyFile(outputDir, "单卷包产物");
        byte[] expected = Files.readAllBytes(new File(home, "Single.txt").toPath());
        if (!java.util.Arrays.equals(expected, Files.readAllBytes(product.toPath()))) {
            UiDriverSupport.fail("单卷包解密内容与原文不一致");
        }
        assertHistory("1");
        UiDriverSupport.pass("单卷包按普通解密，内容与原文一致，历史计数 1");
    }

    private static void assertOrdinary(List<?> outputs, File outputDir, String resultText)
            throws Exception {
        if (outputs.size() != 3 || !resultText.contains("成功 3")) {
            UiDriverSupport.fail("普通批量输出/计数不符：" + outputs.size() + "，" + resultText);
        }
        File[] products = outputDir.listFiles();
        if (products == null || products.length != 3) {
            UiDriverSupport.fail("输出目录应有 3 个产物，实际 "
                    + java.util.Arrays.toString(products));
        }
        assertHistory("3");
        UiDriverSupport.pass("普通 3 文件批量解密成功，输出 3 项，历史计数 3");
    }

    private static void assertCancelled(SecureTextPanel panel, List<?> outputs, int total)
            throws Exception {
        if (outputs.size() >= total) {
            UiDriverSupport.fail("取消后不应完成全部文件，实际输出 " + outputs.size());
        }
        JButton cancel = (JButton) UiDriverSupport.field(panel, "cancelButton");
        if (!UiDriverSupport.waitFor(() -> !cancel.isShowing(), 10_000L)) {
            UiDriverSupport.fail("任务结束后取消按钮仍然可见");
        }
        UiDriverSupport.pass("取消后保留 " + outputs.size() + " 项已完成结果，其余未解密");
    }

    private static void assertReadOnly(List<?> outputs, File outputDir, String resultText) {
        if (outputs.size() != 0) {
            UiDriverSupport.fail("只读目录不应有输出，实际 " + outputs.size());
        }
        File[] files = outputDir.listFiles();
        if (files != null && files.length > 0) {
            UiDriverSupport.fail("只读目录不应落盘：" + java.util.Arrays.toString(files));
        }
        if (!resultText.contains("权限") && !resultText.contains("拒绝")
                && !resultText.contains("写入")) {
            UiDriverSupport.fail("只读目录错误不可读：" + resultText);
        }
        UiDriverSupport.pass("只读输出目录给出可读错误，未写任何产物");
    }

    private static File singlePartPackage(File home, KeyPair keys) throws Exception {
        File plain = new File(home, "Single.txt");
        Files.write(plain.toPath(),
                UiDriverSupport.randomText(7, 3000).getBytes(StandardCharsets.UTF_8));
        VolumePartPlan plan = VolumePartPlan.create(
                plain.length(),
                1_000_000L,
                plain.getName().getBytes(StandardCharsets.UTF_8).length + 64,
                4096);
        if (plan.getPartCount() != 1) {
            UiDriverSupport.fail("单卷包夹具没有切成 1 卷：" + plan.getPartCount());
        }
        File output = new File(home, "Single_vol001.aegix");
        return VolumePartWriter.writePart(
                plain,
                plan,
                1,
                "set-e2e-single",
                plain.getName(),
                UiDriverSupport.sha256(plain),
                false,
                1,
                output,
                keys.getPrivate(),
                keys.getPublic(),
                null,
                new AtomicBoolean(false));
    }

    private static File encryptFile(File home, KeyPair keys, String baseName, int bytes)
            throws Exception {
        File plain = new File(home, baseName + ".txt");
        Files.write(plain.toPath(), UiDriverSupport.randomText(baseName.hashCode(), bytes)
                .getBytes(StandardCharsets.UTF_8));
        File encrypted = new File(home, baseName + ".txt.aegix");
        try (InputStream signature = new BufferedInputStream(new FileInputStream(plain));
             InputStream data = new BufferedInputStream(new FileInputStream(plain));
             OutputStream output = new BufferedOutputStream(new FileOutputStream(encrypted))) {
            FileCryptoEngine.encrypt(
                    signature,
                    data,
                    plain.length(),
                    plain.getName(),
                    keys.getPrivate(),
                    keys.getPublic(),
                    output,
                    null,
                    new AtomicBoolean(false));
        }
        return encrypted;
    }

    private static void restoreReadOnlyQuietly(File directory) {
        try {
            applyReadOnly(directory, false);
        } catch (Exception ignored) {
            // 恢复失败不改变测试结论；下次运行前仍可手动 icacls /remove:d。
        }
    }

    private static void applyReadOnly(File directory, boolean readOnly) throws Exception {
        String trustee = System.getProperty("user.name");
        List<String> command = new ArrayList<String>();
        command.add("icacls");
        command.add(directory.getAbsolutePath());
        if (readOnly) {
            command.add("/deny");
            command.add(trustee + ":(OI)(CI)(W)");
        } else {
            command.add("/remove:d");
            command.add(trustee);
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            UiDriverSupport.fail("icacls 失败（" + exit + "）：" + output);
        }
    }

    private static void assertHistory(String count) throws Exception {
        boolean recorded = UiDriverSupport.waitFor(() -> {
            List<HistoryRecord> records = HistoryStorage.loadHistory();
            return !records.isEmpty()
                    && records.get(0).getDescription() != null
                    && records.get(0).getDescription().contains(count);
        }, 10_000L);
        if (!recorded) {
            UiDriverSupport.fail("历史未记录本次批量解密（期望计数 " + count + "）");
        }
    }

    private static void assertNoTempFiles(File outputDir) {
        File[] leftovers = outputDir.listFiles((dir, name) ->
                name.endsWith(".tmp") || name.endsWith(".part"));
        if (leftovers != null && leftovers.length > 0) {
            UiDriverSupport.fail("输出目录有临时残留：" + java.util.Arrays.toString(leftovers));
        }
    }

    private static File onlyFile(File directory, String label) {
        File[] files = directory.listFiles();
        if (files == null || files.length != 1) {
            UiDriverSupport.fail(label + "应有 1 个文件，实际 " + java.util.Arrays.toString(files));
        }
        return files[0];
    }

    private static byte[] readAll(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
