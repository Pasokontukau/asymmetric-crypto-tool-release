import app.AppMain;
import app.MainFrame;
import core.util.LogManager;
import crypto.key.KeyPairManager;
import crypto.volume.VolumePartPlan;
import crypto.volume.VolumePartWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import securecomm.ui.SecureTextPanel;
import settings.manager.SettingsManager;
import settings.storage.SettingsStorage;

/**
 * 真实界面里的多卷批量解密自测。
 *
 * <p>模式：{@code merge}、{@code incomplete}、{@code mixed}、{@code single}、
 * {@code duplicate}、{@code mixedSets}、{@code cancel}。
 * 用法：{@code BatchMergeDriver <mode> <home>}。</p>
 */
public final class BatchMergeDriver {

    private static final String KEY_NAME = "e2e-merge";
    private static final String PASSWORD = "e2e-pass-123";
    private static final long TIMEOUT_MS = 60_000L;

    private BatchMergeDriver() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "usage: BatchMergeDriver <merge|incomplete|mixed|single|duplicate|mixedSets|cancel> <home>");
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

        File zip = "cancel".equals(mode)
                ? folderArchive(new File(home, "Photos.zip"), 3, 20000)
                : folderArchive(new File(home, "Photos.zip"), 2, 5000);
        List<File> parts = writeParts(
                zip, keys, new File(home, "parts"), 6000L, "set-e2e-merge", "Photos_e2e1");
        if (parts.size() < 2) {
            UiDriverSupport.fail("夹具没有切成多卷：" + parts.size());
        }
        UiDriverSupport.pass("夹具已生成：" + parts.size() + " 卷，原文件 " + zip.length() + " 字节");

        List<File> selection = new ArrayList<File>(parts);
        switch (mode) {
            case "incomplete":
                selection = new ArrayList<File>(parts.subList(0, parts.size() - 1));
                UiDriverSupport.pass("本次故意少选最后一卷");
                break;
            case "mixed":
                File plain = new File(home, "note.txt");
                Files.write(plain.toPath(), "plain".getBytes(StandardCharsets.UTF_8));
                selection.add(plain);
                UiDriverSupport.pass("本次故意混入普通文件 note.txt");
                break;
            case "single":
                selection = new ArrayList<File>(parts.subList(0, 1));
                UiDriverSupport.pass("本次只选第 1 卷");
                break;
            case "duplicate":
                File copy = new File(home, "Photos_e2e2_vol002.aegix");
                Files.copy(parts.get(1).toPath(), copy.toPath());
                selection.add(copy);
                UiDriverSupport.pass("本次把第 2 卷复制成另一个文件名一起选中");
                break;
            case "mixedSets":
                File docs = folderArchive(new File(home, "Docs.zip"), 2, 5000);
                List<File> docsParts = writeParts(
                        docs, keys, new File(home, "docs-parts"), 6000L, "set-e2e-docs", "Docs_e2e2");
                selection.addAll(docsParts);
                UiDriverSupport.pass("本次再混入另一套 " + docsParts.size() + " 卷");
                break;
            case "merge":
            case "cancel":
                break;
            default:
                UiDriverSupport.fail("未知模式：" + mode);
        }

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
        UiDriverSupport.addFiles(panel, selection.toArray(new File[0]));
        UiDriverSupport.pass("界面文件列表已有 " + selection.size() + " 项");

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

        if ("cancel".equals(mode)) {
            JButton cancel = (JButton) UiDriverSupport.field(panel, "cancelButton");
            if (!UiDriverSupport.waitFor(cancel::isShowing, 10_000L)) {
                UiDriverSupport.fail("取消按钮没有出现");
            }
            Thread.sleep(150L);
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

        List<?> outputList = (List<?>) UiDriverSupport.field(panel, "outputFilesList");
        switch (mode) {
            case "incomplete":
            case "single":
            case "duplicate":
            case "mixedSets":
            case "mixed":
                assertNoOutput(outputList, outputDir);
                UiDriverSupport.pass("请求被拦截，未产生输出");
                break;
            case "cancel":
                JButton cancel = (JButton) UiDriverSupport.field(panel, "cancelButton");
                if (!UiDriverSupport.waitFor(() -> !cancel.isShowing(), 10_000L)) {
                    UiDriverSupport.fail("任务结束后取消按钮仍然可见");
                }
                assertNoOutput(outputList, outputDir);
                UiDriverSupport.pass("取消后无输出、无落盘，取消按钮已隐藏");
                break;
            default:
                assertMerged(outputList, outputDir, zip, parts.size(), resultText);
        }

        SwingUtilities.invokeAndWait(frame::dispose);
        System.out.println("BATCH_MERGE_PASS");
        System.exit(0);
    }

    private static boolean expectedText(String mode, String text) {
        switch (mode) {
            case "incomplete":
            case "single":
                return text.contains("分卷不齐") || text.contains("不能单独解密");
            case "mixed":
                return text.contains("分卷请单独选择");
            case "duplicate":
                return text.contains("同一卷号被选择了两次");
            case "mixedSets":
                return text.contains("不属于同一个包");
            case "cancel":
                return text.contains("已取消");
            default:
                return text.contains("批量解密完成") || text.contains("批量解密失败");
        }
    }

    private static void assertMerged(List<?> outputList, File outputDir, File zip,
            int partCount, String resultText) throws Exception {
        if (resultText.contains("失败") || !resultText.contains("已合并 " + partCount + " 卷")) {
            UiDriverSupport.fail("合并结果不符：" + resultText);
        }
        if (outputList.size() != 1) {
            UiDriverSupport.fail("输出列表应有 1 项，实际 " + outputList.size());
        }
        UiDriverSupport.pass("输出列表 1 项");

        File product = new File(outputDir, "Photos.zip");
        if (!product.isFile() || product.length() != zip.length()) {
            UiDriverSupport.fail("合并产物缺失或大小不符：" + product);
        }
        try (ZipFile archive = new ZipFile(product)) {
            if (archive.getEntry("Photos/file1.txt") == null) {
                UiDriverSupport.fail("合并产物缺少 Photos/file1.txt");
            }
        }
        UiDriverSupport.pass("产物是原文件夹 ZIP，大小与源一致");

        File[] leftovers = outputDir.listFiles((dir, name) ->
                name.endsWith(".tmp") || name.endsWith(".part") || name.endsWith(".aegix"));
        if (leftovers != null && leftovers.length > 0) {
            UiDriverSupport.fail("输出目录有残留：" + java.util.Arrays.toString(leftovers));
        }
        UiDriverSupport.pass("输出目录无临时/分卷残留");
    }

    private static File folderArchive(File file, int entries, int bytesPerEntry) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            for (int index = 1; index <= entries; index++) {
                output.putNextEntry(new ZipEntry("Photos/file" + index + ".txt"));
                output.write(UiDriverSupport.randomText(30 + index, bytesPerEntry)
                        .getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return file;
    }

    private static List<File> writeParts(File zip, KeyPair keys, File outputDir, long volumeLimit,
            String setId, String partPrefix) throws Exception {
        Files.createDirectories(outputDir.toPath());
        String archiveName = zip.getName();
        VolumePartPlan plan = VolumePartPlan.create(
                zip.length(),
                volumeLimit,
                archiveName.getBytes(StandardCharsets.UTF_8).length + 32,
                4096);
        List<File> parts = new ArrayList<File>();
        for (int index = 1; index <= plan.getPartCount(); index++) {
            File output = new File(
                    outputDir,
                    partPrefix + "_vol" + String.format("%03d", index) + ".aegix");
            parts.add(VolumePartWriter.writePart(
                    zip,
                    plan,
                    index,
                    setId,
                    archiveName,
                    UiDriverSupport.sha256(zip),
                    true,
                    2,
                    output,
                    keys.getPrivate(),
                    keys.getPublic(),
                    null,
                    new AtomicBoolean(false)));
        }
        return parts;
    }

    private static void assertNoOutput(Object outputList, File outputDir) {
        if (!((List<?>) outputList).isEmpty()) {
            UiDriverSupport.fail("被拦截时输出列表应为空");
        }
        File[] files = outputDir.listFiles();
        if (files != null && files.length > 0) {
            UiDriverSupport.fail("被拦截时输出目录应干净：" + java.util.Arrays.toString(files));
        }
    }
}
