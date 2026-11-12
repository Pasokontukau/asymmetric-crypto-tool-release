import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import core.util.FolderZipUtils;
import crypto.key.KeyPairManager;
import crypto.volume.VolumePartPlan;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import volumes.service.ExternalPartMerger;
import volumes.service.ExternalVolumeWriter;

/**
 * 桌面端写当前外部分卷的回归：普通文件直接按卷切片，合并后还原原文件；
 * 文件夹临时 ZIP 直接切片，合并后仍是 ZIP。
 */
public class ExternalVolumeWriterTest {

    private static final long SMALL_VOLUME_LIMIT = 16_000L;

    private static KeyPair keys;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @BeforeClass
    public static void createKeys() throws Exception {
        keys = KeyPairManager.generateKeyPair(4096);
    }

    @Test
    public void slicesLargeFileIntoExternalVolumesAndRestoresOriginal() throws Exception {
        byte[] data = new byte[20_000];
        new Random(7).nextBytes(data);
        File source = temporaryFolder.newFile("big.bin");
        Files.write(source.toPath(), data);
        File outputDirectory = temporaryFolder.newFolder("volumes");

        int previewPartCount = ExternalVolumeWriter.previewPartCount(
                source, false, "big.bin", keys.getPublic(), SMALL_VOLUME_LIMIT);

        List<File> parts = ExternalVolumeWriter.write(
                source,
                false,
                "big.bin",
                false,
                null,
                outputDirectory,
                "secure_file_20261107_120000_1",
                keys.getPrivate(),
                keys.getPublic(),
                SMALL_VOLUME_LIMIT,
                new AtomicBoolean(false));

        assertTrue("20 KB 来源应至少切成两卷", parts.size() >= 2);
        assertEquals("预览卷数应与实际写入一致", parts.size(), previewPartCount);
        assertTrue(parts.get(0).getName().contains("_vol001.aegix"));
        assertFalse("外层名不能包含原始文件名",
                parts.get(0).getName().contains("big.bin"));
        assertTrue(parts.get(parts.size() - 1).getName()
                .endsWith("_vol" + String.format("%03d", parts.size()) + ".aegix"));

        ExternalPartMerger.Inspection inspection = ExternalPartMerger.inspect(
                parts, keys.getPrivate());
        assertEquals(ExternalPartMerger.Status.COMPLETE, inspection.getStatus());
        assertEquals(parts.size(), inspection.getOrderedParts().size());
        assertFalse(inspection.getOrderedParts().get(0).getInfo()
                .getArchiveSourceIsFolder());
        assertFalse("普通文件分卷不应标记为归档",
                inspection.getOrderedParts().get(0).getInfo().getSourceIsArchive());
        assertEquals("big.bin", inspection.getOrderedParts().get(0)
                .getInfo().getArchiveName());
        assertEquals(1, inspection.getOrderedParts().get(0)
                .getInfo().getSourceFileCount());

        File mergedDirectory = temporaryFolder.newFolder("merged");
        ExternalPartMerger.MergeResult result = ExternalPartMerger.merge(
                parts,
                keys.getPrivate(),
                Collections.singletonList(keys.getPublic()),
                mergedDirectory,
                "merge.tmp",
                null,
                new AtomicBoolean(false));

        assertEquals("big.bin", result.getOutput().getName());
        assertFalse(result.isSourceIsArchive());
        assertTrue(result.isSignatureValid());
        assertTrue(result.isPartNamesVerified());
        assertArrayEquals(data, Files.readAllBytes(result.getOutput().toPath()));
    }

    @Test
    public void rejectsMoreThanNinetyNinePartsBeforeWriting() throws Exception {
        File source = temporaryFolder.newFile("too-many.bin");
        Files.write(source.toPath(), new byte[100]);
        File outputDirectory = temporaryFolder.newFolder("too-many-volumes");

        try {
            ExternalVolumeWriter.write(
                    source,
                    false,
                    "too-many.bin",
                    false,
                    null,
                    outputDirectory,
                    "secure_file_20261111_120000_4",
                    keys.getPrivate(),
                    keys.getPublic(),
                    VolumePartPlan.HEADER_RESERVE_BYTES + 1L,
                    new AtomicBoolean(false));
            fail("100 卷应在写入任何分卷前被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期：共享预算在写盘前拒绝超过 99 卷。
        }

        File[] left = outputDirectory.listFiles();
        assertTrue("拒绝后不能留下分卷", left == null || left.length == 0);
    }

    @Test
    public void slicesFolderZipDirectlyAndPreservesFolderMetadata() throws Exception {
        File folder = temporaryFolder.newFolder("Docs");
        byte[] data = new byte[20_000];
        new Random(11).nextBytes(data);
        Files.write(new File(folder, "note.bin").toPath(), data);

        File folderZip = temporaryFolder.newFile("Docs.zip");
        FolderZipUtils.zipFolder(folder, folderZip, null);
        File outputDirectory = temporaryFolder.newFolder("folder-volumes");

        int previewPartCount = ExternalVolumeWriter.previewPartCount(
                folderZip, true, "Docs.zip", keys.getPublic(), SMALL_VOLUME_LIMIT);

        List<File> parts = ExternalVolumeWriter.write(
                folderZip,
                true,
                "Docs.zip",
                true,
                null,
                outputDirectory,
                "secure_file_20261107_120001_2",
                keys.getPrivate(),
                keys.getPublic(),
                SMALL_VOLUME_LIMIT,
                new AtomicBoolean(false));

        assertTrue(parts.size() >= 2);
        assertEquals("文件夹预览卷数应与实际写入一致", parts.size(), previewPartCount);
        assertFalse("外层名不能包含原文件夹名",
                parts.get(0).getName().contains("Docs"));
        ExternalPartMerger.Inspection inspection = ExternalPartMerger.inspect(
                parts, keys.getPrivate());
        assertEquals(ExternalPartMerger.Status.COMPLETE, inspection.getStatus());
        assertTrue(inspection.getOrderedParts().get(0).getInfo()
                .getArchiveSourceIsFolder());
        assertEquals("Docs.zip", inspection.getOrderedParts().get(0)
                .getInfo().getArchiveName());
        assertEquals(1, inspection.getOrderedParts().get(0)
                .getInfo().getSourceFileCount());

        ExternalPartMerger.MergeResult result = ExternalPartMerger.merge(
                parts,
                keys.getPrivate(),
                Collections.singletonList(keys.getPublic()),
                temporaryFolder.newFolder("folder-merged"),
                "merge.tmp",
                null,
                new AtomicBoolean(false));

        assertEquals("Docs.zip", result.getOutput().getName());
        assertTrue(result.isSourceIsArchive());
        try (ZipFile zip = new ZipFile(result.getOutput())) {
            ZipEntry entry = zip.getEntry("Docs/note.bin");
            assertNotNull("合并后的 ZIP 应保留文件夹条目路径", entry);
            assertArrayEquals(data, readAll(zip.getInputStream(entry)));
        }
    }

    @Test
    public void packagesMultipleFilesIntoArchiveRootThenSlicesAndRestoresZip() throws Exception {
        File first = temporaryFolder.newFile("alpha.bin");
        File second = temporaryFolder.newFile("beta.bin");
        byte[] firstBytes = new byte[9_000];
        byte[] secondBytes = new byte[9_000];
        new Random(21).nextBytes(firstBytes);
        new Random(22).nextBytes(secondBytes);
        Files.write(first.toPath(), firstBytes);
        Files.write(second.toPath(), secondBytes);

        String rootName = "archive_20261108_120000";
        String archiveName = rootName + ".zip";
        File packageZip = temporaryFolder.newFile("package.zip");
        FolderZipUtils.zipFiles(Arrays.asList(first, second), packageZip, rootName, null);
        File outputDirectory = temporaryFolder.newFolder("package-volumes");

        int preview = ExternalVolumeWriter.previewPartCountForBytes(
                first.length() + second.length(),
                2,
                true,
                archiveName,
                keys.getPublic(),
                SMALL_VOLUME_LIMIT);

        List<File> parts = ExternalVolumeWriter.write(
                packageZip,
                true,
                archiveName,
                false,
                null,
                outputDirectory,
                "secure_file_20261108_120000_3",
                keys.getPrivate(),
                keys.getPublic(),
                SMALL_VOLUME_LIMIT,
                new AtomicBoolean(false));

        assertTrue(parts.size() >= 2);
        assertEquals("多文件打包预览卷数应与实际写入一致", parts.size(), preview);
        for (File part : parts) {
            assertFalse("外层名不能包含归档名", part.getName().contains("archive_"));
        }

        ExternalPartMerger.Inspection inspection = ExternalPartMerger.inspect(
                parts, keys.getPrivate());
        assertEquals(ExternalPartMerger.Status.COMPLETE, inspection.getStatus());
        assertEquals(archiveName, inspection.getOrderedParts().get(0)
                .getInfo().getArchiveName());
        assertFalse("多文件打包不是文件夹包", inspection.getOrderedParts().get(0)
                .getInfo().getArchiveSourceIsFolder());
        assertTrue("多文件打包应为归档", inspection.getOrderedParts().get(0)
                .getInfo().getSourceIsArchive());
        assertEquals(2, inspection.getOrderedParts().get(0)
                .getInfo().getSourceFileCount());

        ExternalPartMerger.MergeResult result = ExternalPartMerger.merge(
                parts,
                keys.getPrivate(),
                Collections.singletonList(keys.getPublic()),
                temporaryFolder.newFolder("package-merged"),
                "merge.tmp",
                null,
                new AtomicBoolean(false));

        assertEquals(archiveName, result.getOutput().getName());
        assertTrue(result.isSourceIsArchive());
        try (ZipFile zip = new ZipFile(result.getOutput())) {
            assertArrayEquals(firstBytes, readAll(zip.getInputStream(
                    zip.getEntry(rootName + "/alpha.bin"))));
            assertArrayEquals(secondBytes, readAll(zip.getInputStream(
                    zip.getEntry(rootName + "/beta.bin"))));
        }
    }

    private static byte[] readAll(InputStream input) throws Exception {
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
            return output.toByteArray();
        }
    }
}
