package volumes.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import crypto.volume.VolumePartPlan;
import crypto.volume.VolumePartWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 桌面端合并 Android 外部单卷包：齐套、缺卷、混包、重卷、篡改、取消与单卷/旧格式识别。
 *
 * <p>分卷由共享 {@link VolumePartWriter} 生成，确保测试的是真实 Android 线格式；
 * 本测试不修改 android/，只消费共享内核。</p>
 */
public class ExternalPartMergerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void mergesCompleteSetIntoOriginalZip() throws Exception {
        KeyPair keys = keys();
        File zip = zip("Photos.zip", "Photos/one.txt", randomText(11, 2000),
                "Photos/two.txt", randomText(12, 2000));
        List<File> parts = writeParts(zip, "set-a", keys);

        ExternalPartMerger.Inspection inspection = ExternalPartMerger.inspect(
                parts, keys.getPrivate());
        assertEquals(ExternalPartMerger.Status.COMPLETE, inspection.getStatus());
        assertEquals(parts.size(), inspection.getOrderedParts().size());

        File outputDir = temporaryFolder.newFolder("merged");
        ExternalPartMerger.MergeResult result = ExternalPartMerger.merge(
                parts,
                keys.getPrivate(),
                Collections.singletonList(keys.getPublic()),
                outputDir,
                "merged.tmp",
                null,
                new AtomicBoolean(false));

        assertEquals("Photos.zip", result.getOutput().getName());
        assertEquals(parts.size(), result.getPartCount());
        assertEquals(zip.length(), result.getArchiveSize());
        assertTrue(result.isSignatureValid());
        assertTrue(result.isPartNamesVerified());
        assertArrayEquals(Files.readAllBytes(zip.toPath()),
                Files.readAllBytes(result.getOutput().toPath()));
        assertEquals(1, outputDir.listFiles().length);
    }

    @Test
    public void missingPartIsIncompleteAndLeavesNoOutput() throws Exception {
        KeyPair keys = keys();
        File zip = zip("Photos.zip", "Photos/one.txt", randomText(21, 2000),
                "Photos/two.txt", randomText(22, 2000));
        List<File> parts = writeParts(zip, "set-b", keys);
        List<File> incomplete = new ArrayList<File>(parts);
        incomplete.remove(incomplete.size() - 1);

        ExternalPartMerger.Inspection inspection = ExternalPartMerger.inspect(
                incomplete, keys.getPrivate());
        assertEquals(ExternalPartMerger.Status.INCOMPLETE, inspection.getStatus());
        // 卷号是 1-based：删掉最后一卷后，缺失序号应等于总卷数。
        assertEquals(Collections.singletonList(parts.size()),
                inspection.getMissingIndexes());

        File outputDir = temporaryFolder.newFolder("missing");
        assertMergeFails(outputDir, incomplete, keys.getPrivate(),
                ExternalPartMerger.Status.INCOMPLETE);
        assertEquals(0, outputDir.listFiles().length);
    }

    @Test
    public void mixedSetsAreRejected() throws Exception {
        KeyPair keys = keys();
        File zipA = zip("A.zip", "A/one.txt", randomText(31, 2000));
        File zipB = zip("B.zip", "B/one.txt", randomText(32, 2000));
        List<File> partsA = writeParts(zipA, "set-a", keys);
        List<File> partsB = writeParts(zipB, "set-b", keys);

        List<File> mixed = Arrays.asList(partsA.get(0), partsB.get(0));
        ExternalPartMerger.Inspection inspection = ExternalPartMerger.inspect(
                mixed, keys.getPrivate());
        assertEquals(ExternalPartMerger.Status.MIXED, inspection.getStatus());

        File outputDir = temporaryFolder.newFolder("mixed");
        assertMergeFails(outputDir, mixed, keys.getPrivate(),
                ExternalPartMerger.Status.MIXED);
    }

    @Test
    public void duplicatePartIsRejected() throws Exception {
        KeyPair keys = keys();
        File zip = zip("Photos.zip", "Photos/one.txt", randomText(41, 2000));
        List<File> parts = writeParts(zip, "set-c", keys);

        List<File> duplicated = Arrays.asList(parts.get(0), parts.get(0));
        ExternalPartMerger.Inspection inspection = ExternalPartMerger.inspect(
                duplicated, keys.getPrivate());
        assertEquals(ExternalPartMerger.Status.DUPLICATE, inspection.getStatus());

        File outputDir = temporaryFolder.newFolder("duplicate");
        assertMergeFails(outputDir, duplicated, keys.getPrivate(),
                ExternalPartMerger.Status.DUPLICATE);
    }

    @Test
    public void tamperedPartFailsAndCleansTemporaryOutput() throws Exception {
        KeyPair keys = keys();
        File zip = zip("Photos.zip", "Photos/one.txt", randomText(51, 2000));
        List<File> parts = writeParts(zip, "set-d", keys);
        tamperLastByte(parts.get(0));

        File outputDir = temporaryFolder.newFolder("tampered");
        assertMergeFails(outputDir, parts, keys.getPrivate(), null);
        assertEquals(0, outputDir.listFiles().length);
    }

    @Test
    public void cancelLeavesNoOutput() throws Exception {
        KeyPair keys = keys();
        File zip = zip("Photos.zip", "Photos/one.txt", randomText(61, 2000));
        List<File> parts = writeParts(zip, "set-e", keys);

        File outputDir = temporaryFolder.newFolder("cancelled");
        try {
            ExternalPartMerger.merge(
                    parts,
                    keys.getPrivate(),
                    Collections.singletonList(keys.getPublic()),
                    outputDir,
                    "cancelled.tmp",
                    null,
                    new AtomicBoolean(true));
        } catch (ExternalPartMerger.ExternalMergeException expected) {
            assertEquals("已取消", expected.getMessage());
        }
        assertEquals(0, outputDir.listFiles().length);
    }

    @Test
    public void cancelDuringFirstPartLeavesNoOutput() throws Exception {
        KeyPair keys = keys();
        File zip = zip("Photos.zip", "Photos/one.txt", randomText(71, 2000));
        List<File> parts = writeParts(zip, "set-f", keys);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        File outputDir = temporaryFolder.newFolder("cancel-midway");
        try {
            ExternalPartMerger.merge(
                    parts,
                    keys.getPrivate(),
                    Collections.singletonList(keys.getPublic()),
                    outputDir,
                    "cancelled.tmp",
                    (partNumber, partCount, done, total) -> cancelled.set(true),
                    cancelled);
            org.junit.Assert.fail("应当取消合并");
        } catch (ExternalPartMerger.ExternalMergeException expected) {
            assertEquals("已取消", expected.getMessage());
        }
        assertEquals(0, outputDir.listFiles().length);
    }

    @Test
    public void singlePartPackageIsReportedSeparately() throws Exception {
        KeyPair keys = keys();
        File zip = zip("Small.zip", "Small/one.txt", "tiny");
        List<File> parts = writeParts(zip, "set-single", keys, 8L * 1024 * 1024);
        assertEquals(1, parts.size());

        ExternalPartMerger.Inspection inspection = ExternalPartMerger.inspect(
                parts, keys.getPrivate());
        assertEquals(ExternalPartMerger.Status.SINGLE_PART, inspection.getStatus());
    }

    @Test
    public void cancelledMergeLeavesNoPartialFile() throws Exception {
        KeyPair keys = keys();
        File zip = zip("Photos.zip", "Photos/one.txt", randomText(85, 2000));
        List<File> parts = writeParts(zip, "set-cancel", keys);
        File outputDir = temporaryFolder.newFolder("cancelled");

        try {
            ExternalPartMerger.merge(
                    parts,
                    keys.getPrivate(),
                    Collections.singletonList(keys.getPublic()),
                    outputDir,
                    "cancelled.tmp",
                    null,
                    new AtomicBoolean(true));
            org.junit.Assert.fail("应当取消合并");
        } catch (ExternalPartMerger.ExternalMergeException error) {
            assertTrue("取消要说明是取消，而不是损坏",
                    error.getMessage().contains("已取消"));
        }
        assertEquals(0, outputDir.listFiles().length);
    }

    private void assertMergeFails(
            File outputDir,
            List<File> parts,
            PrivateKey key,
            ExternalPartMerger.Status expectedStatus
    ) throws Exception {
        try {
            ExternalPartMerger.merge(
                    parts,
                    key,
                    Collections.<java.security.PublicKey>emptyList(),
                    outputDir,
                    "failed.tmp",
                    null,
                    new AtomicBoolean(false));
            org.junit.Assert.fail("应当拒绝合并");
        } catch (ExternalPartMerger.ExternalMergeException error) {
            assertNotNull(error.getMessage());
            if (expectedStatus != null) {
                assertEquals(expectedStatus, error.getStatus());
            }
        }
    }

    private List<File> writeParts(File zip, String setId, KeyPair keys) throws Exception {
        return writeParts(zip, setId, keys, VolumePartPlan.HEADER_RESERVE_BYTES + 256L);
    }

    @Test
    public void folderArchiveMultiSetMergesBackToZipWithRootFolder() throws Exception {
        KeyPair keys = keys();
        File zip = zip("Photos.zip",
                "Photos/one.txt", randomText(86, 4000),
                "Photos/two.txt", randomText(87, 4000));
        List<File> parts = writeParts(zip, "set-folder", keys, 5000L, true);
        assertTrue("夹具应切成多卷", parts.size() > 1);

        ExternalPartMerger.Inspection inspection = ExternalPartMerger.inspect(
                parts, keys.getPrivate());
        assertEquals(ExternalPartMerger.Status.COMPLETE, inspection.getStatus());

        File outputDir = temporaryFolder.newFolder("folder-merged");
        ExternalPartMerger.MergeResult result = ExternalPartMerger.merge(
                parts,
                keys.getPrivate(),
                Collections.singletonList(keys.getPublic()),
                outputDir,
                null,
                null,
                new AtomicBoolean(false));

        File product = result.getOutput();
        assertTrue("文件夹来源的合并产物应是 ZIP：" + product.getName(),
                product.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip"));
        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(product)) {
            assertNotNull("ZIP 根目录应保留原文件夹", archive.getEntry("Photos/one.txt"));
            assertNotNull(archive.getEntry("Photos/two.txt"));
        }
    }

    private List<File> writeParts(
            File zip,
            String setId,
            KeyPair keys,
            long volumeLimit
    ) throws Exception {
        return writeParts(zip, setId, keys, volumeLimit, false);
    }

    private List<File> writeParts(
            File zip,
            String setId,
            KeyPair keys,
            long volumeLimit,
            boolean archiveSourceIsFolder
    ) throws Exception {
        File outputDir = temporaryFolder.newFolder("parts-" + setId);
        String archiveName = zip.getName();
        String archiveSha = sha256(zip);
        VolumePartPlan plan = VolumePartPlan.create(
                zip.length(),
                volumeLimit,
                archiveName.getBytes(StandardCharsets.UTF_8).length + 32,
                2048);
        List<File> parts = new ArrayList<File>();
        for (int index = 1; index <= plan.getPartCount(); index++) {
            File output = new File(
                    outputDir,
                    archiveName + ".part" + String.format("%03d", index) + ".aegix");
            parts.add(VolumePartWriter.writePart(
                    zip,
                    plan,
                    index,
                    setId,
                    archiveName,
                    archiveSha,
                    archiveSourceIsFolder,
                    1,
                    output,
                    keys.getPrivate(),
                    keys.getPublic(),
                    null,
                    new AtomicBoolean(false)));
        }
        return parts;
    }

    private File zip(String name, Object... entries) throws Exception {
        File file = new File(temporaryFolder.newFolder("zip-" + name), name);
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            for (int index = 0; index < entries.length; index += 2) {
                output.putNextEntry(new ZipEntry((String) entries[index]));
                output.write(((String) entries[index + 1]).getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return file;
    }

    private KeyPair keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** 生成不可压缩文本，确保 ZIP 在 256 B 明文容量下真的切成多卷。 */
    private static String randomText(long seed, int count) {
        Random random = new Random(seed);
        StringBuilder builder = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            builder.append((char) ('a' + random.nextInt(26)));
        }
        return builder.toString();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        int read;
        try (InputStream input = Files.newInputStream(file.toPath())) {
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest()) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static void tamperLastByte(File file) throws Exception {
        byte[] data = Files.readAllBytes(file.toPath());
        data[data.length - 1] = (byte) (data[data.length - 1] ^ 0x5a);
        Files.write(file.toPath(), data);
    }
}
