package securecomm.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * DR-2 护栏：编排层第一次可以脱离 Swing 测试。
 * 覆盖单文件加密→解密往返、失败映射、进度阶段与空分卷。
 */
public class DefaultFileTaskServiceTest {

    private static final long TEN_MEGABYTES = 10L * 1024 * 1024;

    private static KeyPair keys;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @BeforeClass
    public static void createKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
    }

    @Test
    public void singleFileRoundTrip() throws Exception {
        File source = temporaryFolder.newFile("plain.txt");
        byte[] content = "UTF-8 往返：非对称加密工具".getBytes("UTF-8");
        Files.write(source.toPath(), content);
        File outputDirectory = temporaryFolder.newFolder("out");

        FileTaskService service = new DefaultFileTaskService();
        EncryptOutcome encrypted = service.encrypt(EncryptRequest
                .singleFile(source, outputDirectory, keys.getPrivate(), keys.getPublic(), TEN_MEGABYTES)
                .withOriginalFileName("plain.txt"));

        assertTrue("加密应成功：" + encrypted.getErrorMessage(), encrypted.isSuccess());
        assertEquals(1, encrypted.getOutputs().size());
        assertFalse(encrypted.isSplit());
        File container = encrypted.getOutputs().get(0);
        assertTrue(container.isFile());
        assertTrue(container.getName().endsWith(".aegix"));

        File decryptDirectory = temporaryFolder.newFolder("decrypted");
        DecryptOutcome decrypted = service.decrypt(DecryptRequest.singleFile(
                container, keys.getPrivate(),
                Collections.singletonList(keys.getPublic()), decryptDirectory));

        assertTrue("解密应成功：" + decrypted.getErrorMessage(), decrypted.isSuccess());
        assertNotNull(decrypted.getOutput());
        assertTrue(decrypted.isSignatureValid());
        assertEquals("plain.txt", decrypted.getOriginalFileName());
        assertArrayEquals(content, Files.readAllBytes(decrypted.getOutput().toPath()));
    }

    @Test
    public void missingInputMapsToFailureWithoutThrowing() throws Exception {
        File missing = new File(temporaryFolder.getRoot(), "not-there.bin");
        File outputDirectory = temporaryFolder.newFolder("out");

        EncryptOutcome outcome = new DefaultFileTaskService().encrypt(EncryptRequest
                .singleFile(missing, outputDirectory, keys.getPrivate(), keys.getPublic(), TEN_MEGABYTES));

        assertFalse(outcome.isSuccess());
        assertNotNull(outcome.getErrorMessage());
        assertTrue(outcome.getOutputs().isEmpty());
    }

    @Test
    public void progressSinkSeesStartAndFinish() throws Exception {
        File source = temporaryFolder.newFile("progress.txt");
        Files.write(source.toPath(), "progress".getBytes("UTF-8"));
        File outputDirectory = temporaryFolder.newFolder("out");
        final List<Integer> stages = new ArrayList<Integer>();

        ProgressSink sink = new ProgressSink() {
            @Override
            public void stage(int percent, String messageKey, Object... args) {
                stages.add(percent);
            }

            @Override
            public void done() {
            }
        };

        EncryptOutcome outcome = new DefaultFileTaskService().encrypt(EncryptRequest
                .singleFile(source, outputDirectory, keys.getPrivate(), keys.getPublic(), TEN_MEGABYTES)
                .withProgress(sink));

        assertTrue(outcome.isSuccess());
        assertTrue("应至少汇报开始与结束：" + stages, stages.contains(3) && stages.contains(100));
    }

    @Test
    public void mergeWithoutPartsFails() {
        MergeOutcome outcome = new DefaultFileTaskService().mergeParts(
                MergeRequest.of(Collections.<File>emptyList(), keys.getPrivate(),
                        Collections.singletonList(keys.getPublic()),
                        temporaryFolder.getRoot()));

        assertFalse(outcome.isSuccess());
        assertNotNull(outcome.getErrorMessage());
    }
    @Test
    public void inspectPartsReportsMergeReadyForCompleteSet() throws Exception {
        Properties properties = ExternalPartFixtures.properties();
        List<File> parts = fixtureParts(properties, 3);

        MergeInspection inspection = new DefaultFileTaskService()
                .inspectParts(parts, ExternalPartFixtures.recipientPrivate(properties));

        assertTrue("检查应成功：" + inspection.getErrorMessage(), inspection.isSuccess());
        assertEquals(volumes.service.ExternalPartMerger.Status.COMPLETE, inspection.getStatus());
        assertEquals(PartSetRouter.Route.MERGE, inspection.getRoute());
        assertEquals(PartSetRouter.Reason.NONE, inspection.getReason());
        assertTrue(inspection.isMergeReady());
        assertFalse(inspection.requiresReselection());
    }

    @Test
    public void inspectPartsReportsMissingPart() throws Exception {
        Properties properties = ExternalPartFixtures.properties();
        List<File> parts = fixtureParts(properties, 3);
        List<File> incomplete = new ArrayList<File>(parts.subList(0, parts.size() - 1));

        MergeInspection inspection = new DefaultFileTaskService()
                .inspectParts(incomplete, ExternalPartFixtures.recipientPrivate(properties));

        assertTrue(inspection.isSuccess());
        assertEquals(volumes.service.ExternalPartMerger.Status.INCOMPLETE, inspection.getStatus());
        assertEquals(PartSetRouter.Route.NEED_MORE_PARTS, inspection.getRoute());
        assertEquals(PartSetRouter.Reason.MISSING_PARTS, inspection.getReason());
        assertTrue(inspection.requiresReselection());
    }

    @Test
    public void inspectPartsRoutesPlainFilesToSingleDecrypt() throws Exception {
        File plain = temporaryFolder.newFile("plain.txt");
        Files.write(plain.toPath(), "not a volume".getBytes("UTF-8"));
        Properties properties = ExternalPartFixtures.properties();

        MergeInspection inspection = new DefaultFileTaskService().inspectParts(
                Collections.singletonList(plain), ExternalPartFixtures.recipientPrivate(properties));

        assertTrue(inspection.isSuccess());
        assertEquals(volumes.service.ExternalPartMerger.Status.NOT_A_SET, inspection.getStatus());
        assertEquals(PartSetRouter.Route.DECRYPT_SINGLE, inspection.getRoute());
        assertFalse(inspection.isMergeReady());

        MergeInspection empty = new DefaultFileTaskService().inspectParts(
                Collections.<File>emptyList(), ExternalPartFixtures.recipientPrivate(properties));
        assertTrue(empty.isSuccess());
        assertEquals(PartSetRouter.Route.DECRYPT_SINGLE, empty.getRoute());
    }

    @Test
    public void inspectPartsFailsWithoutPrivateKey() {
        MergeInspection inspection = new DefaultFileTaskService()
                .inspectParts(Collections.<File>emptyList(), null);

        assertFalse(inspection.isSuccess());
        assertNotNull(inspection.getErrorMessage());
        assertNull(inspection.getRoute());
        assertFalse(inspection.isMergeReady());
    }

    @Test
    public void mergePartsReportsIntermediateProgress() throws Exception {
        Properties properties = ExternalPartFixtures.properties();
        List<File> parts = fixtureParts(properties, 3);
        final List<Integer> stages = new ArrayList<Integer>();
        ProgressSink sink = new ProgressSink() {
            @Override
            public void stage(int percent, String messageKey, Object... args) {
                stages.add(percent);
            }

            @Override
            public void done() {
            }
        };

        MergeOutcome outcome = new DefaultFileTaskService().mergeParts(
                MergeRequest.of(parts,
                        ExternalPartFixtures.recipientPrivate(properties),
                        Collections.singletonList(ExternalPartFixtures.senderPublic(properties)),
                        temporaryFolder.newFolder("merged"))
                        .withProgress(sink));

        assertTrue("合并应成功：" + outcome.getErrorMessage(), outcome.isSuccess());
        assertTrue("应汇报开始与结束：" + stages, stages.contains(3) && stages.contains(100));
        boolean intermediate = false;
        for (int percent : stages) {
            if (percent > 3 && percent < 100) {
                intermediate = true;
            }
        }
        assertTrue("应汇报分卷中间进度：" + stages, intermediate);
    }

    @Test
    public void mergePercentStaysWithinBounds() {
        assertEquals(5, DefaultFileTaskService.mergePercent(0, 0, 0L, 0L));
        assertEquals(35, DefaultFileTaskService.mergePercent(1, 3, 0L, 0L));
        assertEquals(95, DefaultFileTaskService.mergePercent(3, 3, 0L, 0L));
        assertEquals(50, DefaultFileTaskService.mergePercent(1, 3, 50L, 100L));
        assertEquals(95, DefaultFileTaskService.mergePercent(1, 3, 100L, 100L));
        assertEquals("超过总量也要夹在 95", 95,
                DefaultFileTaskService.mergePercent(1, 3, 500L, 100L));
        assertEquals("负数完成量夹在 5", 5,
                DefaultFileTaskService.mergePercent(1, 3, -50L, 100L));
    }

    @Test
    public void prepareBatchMergesCompleteSetIntoFinalProduct() throws Exception {
        Properties properties = ExternalPartFixtures.properties();
        List<File> parts = fixtureParts(properties, 3);
        File outputDirectory = temporaryFolder.newFolder("prepared");

        BatchDecryptPreparation preparation = new DefaultFileTaskService().routeBatchDecrypt(
                parts,
                ExternalPartFixtures.recipientPrivate(properties),
                Collections.singletonList(ExternalPartFixtures.senderPublic(properties)),
                outputDirectory,
                null,
                null);

        assertTrue("齐套应可继续：" + preparation.getErrorMessage(), preparation.canProceed());
        assertEquals(BatchDecryptPreparation.Action.MERGED, preparation.getAction());
        MergeOutcome merged = preparation.getMerged();
        assertNotNull(merged);
        File product = merged.getOutput();
        assertTrue(product.isFile());
        assertEquals("Photos.zip", product.getName());
        assertEquals("Photos.zip", merged.getArchiveName());
        assertEquals(3, merged.getPartCount());
        assertEquals(Long.parseLong(properties.getProperty("multi.archive.size")),
                product.length());
    }

    @Test
    public void prepareBatchAsksForMorePartsWhenIncomplete() throws Exception {
        Properties properties = ExternalPartFixtures.properties();
        List<File> parts = fixtureParts(properties, 3);
        List<File> incomplete = new ArrayList<File>(parts.subList(0, 2));

        BatchDecryptPreparation preparation = new DefaultFileTaskService().routeBatchDecrypt(
                incomplete,
                ExternalPartFixtures.recipientPrivate(properties),
                Collections.singletonList(ExternalPartFixtures.senderPublic(properties)),
                temporaryFolder.newFolder("prepared"),
                null,
                null);

        assertFalse(preparation.canProceed());
        assertEquals(BatchDecryptPreparation.Action.BLOCKED, preparation.getAction());
        assertEquals(PartSetRouter.Route.NEED_MORE_PARTS,
                preparation.getInspection().getRoute());
        assertNull(preparation.getMerged());
        assertNull("缺卷文案应留给界面", preparation.getErrorMessage());
    }

    @Test
    public void prepareBatchRejectsMixedSelectionAndDuplicateVolume() throws Exception {
        Properties properties = ExternalPartFixtures.properties();
        List<File> parts = fixtureParts(properties, 3);

        List<File> mixed = new ArrayList<File>(parts);
        mixed.add(temporaryFolder.newFile("note.txt"));
        BatchDecryptPreparation rejected = new DefaultFileTaskService().routeBatchDecrypt(
                mixed,
                ExternalPartFixtures.recipientPrivate(properties),
                Collections.singletonList(ExternalPartFixtures.senderPublic(properties)),
                temporaryFolder.newFolder("mixed"),
                null,
                null);

        assertFalse("分卷与普通文件混选应被拒绝", rejected.canProceed());
        assertEquals(BatchDecryptPreparation.Action.BLOCKED, rejected.getAction());
        assertNull(rejected.getInspection());
        assertNull("界面负责提示混选", rejected.getErrorMessage());
        assertNull(rejected.getMerged());

        List<File> duplicated = new ArrayList<File>(parts);
        duplicated.add(parts.get(1));
        BatchDecryptPreparation duplicate = new DefaultFileTaskService().routeBatchDecrypt(
                duplicated,
                ExternalPartFixtures.recipientPrivate(properties),
                Collections.singletonList(ExternalPartFixtures.senderPublic(properties)),
                temporaryFolder.newFolder("duplicate"),
                null,
                null);

        assertFalse("重卷应被拒绝", duplicate.canProceed());
        assertEquals(BatchDecryptPreparation.Action.BLOCKED, duplicate.getAction());
        assertEquals(PartSetRouter.Reason.DUPLICATE_VOLUMES,
                duplicate.getInspection().getReason());
        assertNull(duplicate.getMerged());
    }

    @Test
    public void singlePartPackageDecryptsToZipWithoutFragments() throws Exception {
        Properties properties = ExternalPartFixtures.properties();
        File part = ExternalPartFixtures.copy(
                temporaryFolder.newFolder("single-package"),
                properties.getProperty("single.part.file"));
        File outputDirectory = temporaryFolder.newFolder("single-package-out");

        DecryptOutcome outcome = new DefaultFileTaskService().decrypt(DecryptRequest.singleFile(
                part,
                ExternalPartFixtures.recipientPrivate(properties),
                Collections.singletonList(ExternalPartFixtures.senderPublic(properties)),
                outputDirectory));

        assertTrue("单卷包应解密成功：" + outcome.getErrorMessage(), outcome.isSuccess());
        assertFalse("单卷外部包原样输出 ZIP，不展开成目录", outcome.isFolder());
        File output = outcome.getOutput();
        assertTrue("输出应是 ZIP：" + output.getName(),
                output.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip"));

        File[] produced = outputDirectory.listFiles();
        assertNotNull(produced);
        assertEquals("输出目录只应有这一个产物，不出现分片", 1, produced.length);
        assertEquals(output, produced[0]);
    }

    @Test
    public void prepareBatchStopsWhenCancelledBeforeMerge() throws Exception {
        Properties properties = ExternalPartFixtures.properties();
        List<File> parts = fixtureParts(properties, 3);
        java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean(true);

        BatchDecryptPreparation preparation = new DefaultFileTaskService().routeBatchDecrypt(
                parts,
                ExternalPartFixtures.recipientPrivate(properties),
                Collections.singletonList(ExternalPartFixtures.senderPublic(properties)),
                temporaryFolder.newFolder("cancelled"),
                null,
                cancelled);

        assertFalse("取消后不应继续合并", preparation.canProceed());
        assertEquals(BatchDecryptPreparation.Action.BLOCKED, preparation.getAction());
        assertNotNull(preparation.getErrorMessage());
        assertNull(preparation.getMerged());
    }

    @Test
    public void prepareBatchKeepsPlainSelectionAndSinglePartPackage() throws Exception {
        File first = temporaryFolder.newFile("first.aegix");
        File second = temporaryFolder.newFile("second.aegix");
        List<File> selection = new ArrayList<File>();
        selection.add(first);
        selection.add(second);

        BatchDecryptPreparation plain = new DefaultFileTaskService().routeBatchDecrypt(
                selection,
                keys.getPrivate(),
                Collections.singletonList(keys.getPublic()),
                temporaryFolder.newFolder("plain-out"),
                null,
                null);

        assertTrue(plain.canProceed());
        assertEquals("没有分卷参与时逐个解密", BatchDecryptPreparation.Action.DECRYPT,
                plain.getAction());
        assertNull(plain.getMerged());
        assertNull(plain.getInspection());

        Properties properties = ExternalPartFixtures.properties();
        File singleDirectory = temporaryFolder.newFolder("single");
        File single = ExternalPartFixtures.copy(
                singleDirectory, properties.getProperty("single.part.file"));
        File namedAsVolume = new File(singleDirectory,
                "secure_file_20261107_120000_2f7c1a_vol001.aegix");
        assertTrue(single.renameTo(namedAsVolume));

        BatchDecryptPreparation singlePart = new DefaultFileTaskService().routeBatchDecrypt(
                Collections.singletonList(namedAsVolume),
                ExternalPartFixtures.recipientPrivate(properties),
                Collections.singletonList(ExternalPartFixtures.senderPublic(properties)),
                temporaryFolder.newFolder("single-out"),
                null,
                null);

        assertTrue("单卷包应直接解密：" + singlePart.getErrorMessage(), singlePart.canProceed());
        assertEquals("单卷包仍按单文件解密", BatchDecryptPreparation.Action.DECRYPT,
                singlePart.getAction());
        assertNull(singlePart.getMerged());
    }

    private List<File> fixtureParts(Properties properties, int count) throws Exception {
        String[] names = properties.getProperty("multi.part.files").split(",");
        File directory = temporaryFolder.newFolder("fixtures");
        List<File> parts = new ArrayList<File>();
        for (int index = 0; index < Math.min(count, names.length); index++) {
            parts.add(ExternalPartFixtures.copy(directory, names[index]));
        }
        return parts;
    }
}
