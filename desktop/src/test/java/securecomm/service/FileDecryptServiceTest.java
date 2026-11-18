package securecomm.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import crypto.file.FileCryptoEngine;
import crypto.volume.VolumePartPlan;
import crypto.volume.VolumePartWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 桌面文件页解密：单文件、单卷 VOLU、多卷拒绝、文件名清洗与取消。 */
public class FileDecryptServiceTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void decryptsSingleAegixAndKeepsOriginalName() throws Exception {
        KeyPair keys = keys();
        File plain = new File(temporaryFolder.newFolder("plain"), "note.txt");
        Files.write(plain.toPath(), "hello aegix".getBytes(StandardCharsets.UTF_8));
        File encrypted = encryptSingle(plain, "note.txt", keys);

        File outputDir = temporaryFolder.newFolder("out-single");
        FileDecryptService.Result result = FileDecryptService.decrypt(
                encrypted,
                keys.getPrivate(),
                Collections.singletonList(keys.getPublic()),
                outputDir,
                null,
                null,
                new AtomicBoolean(false));

        assertEquals("note.txt", result.getOutput().getName());
        assertEquals("hello aegix", new String(
                Files.readAllBytes(result.getOutput().toPath()),
                StandardCharsets.UTF_8));
        assertTrue(result.isSignatureValid());
        assertFalse(result.isFolder());
        assertEquals(0, result.getVolumeCount());
        assertFalse(result.isExternalPart());
    }

    @Test
    public void decryptsFolderZipWithoutDoubleNesting() throws Exception {
        KeyPair keys = keys();
        File zip = zip("Docs.zip",
                "Docs/note.txt", "hello folder",
                "Docs/sub/inner.txt", "nested");
        File encrypted = encryptSingle(zip, "Docs.folder", keys);

        File outputDir = temporaryFolder.newFolder("out-folder");
        FileDecryptService.Result result = FileDecryptService.decrypt(
                encrypted,
                keys.getPrivate(),
                Collections.singletonList(keys.getPublic()),
                outputDir,
                null,
                null,
                new AtomicBoolean(false));

        assertTrue(result.isFolder());
        assertEquals("Docs", result.getOutput().getName());
        assertEquals("hello folder", new String(Files.readAllBytes(
                new File(result.getOutput(), "note.txt").toPath()), StandardCharsets.UTF_8));
        assertEquals("nested", new String(Files.readAllBytes(
                new File(result.getOutput(), "sub/inner.txt").toPath()),
                StandardCharsets.UTF_8));
        assertFalse("不能出现与目标同名的第二层目录",
                new File(result.getOutput(), "Docs").exists());
    }

    @Test
    public void decryptsSinglePartVoluAsZip() throws Exception {
        KeyPair keys = keys();
        File zip = zip("Photos.zip",
                "Photos/one.txt", randomText(11, 200),
                "Photos/two.txt", randomText(12, 200));
        long limit = zip.length() + VolumePartPlan.HEADER_RESERVE_BYTES + 1024L;
        assertEquals(1, partCount(zip, limit));
        File part = writePart(zip, "set-single", 1, limit, keys);

        File outputDir = temporaryFolder.newFolder("out-single-part");
        FileDecryptService.Result result = FileDecryptService.decrypt(
                part,
                keys.getPrivate(),
                Collections.singletonList(keys.getPublic()),
                outputDir,
                null,
                null,
                new AtomicBoolean(false));

        assertEquals("Photos.zip", result.getOutput().getName());
        assertArrayEquals(Files.readAllBytes(zip.toPath()),
                Files.readAllBytes(result.getOutput().toPath()));
        assertTrue(result.isSignatureValid());
        assertFalse(result.isFolder());
        assertEquals(1, result.getVolumeCount());
        assertTrue(result.isExternalPart());
    }

    @Test
    public void rejectsMultiPartVoluAndLeavesNoOutput() throws Exception {
        KeyPair keys = keys();
        File zip = zip("Photos.zip", "Photos/one.txt", randomText(21, 2000));
        long limit = VolumePartPlan.HEADER_RESERVE_BYTES + 256L;
        int expectedCount = partCount(zip, limit);
        assertTrue("测试夹具必须切成多卷", expectedCount > 1);
        File first = writePart(zip, "set-multi", 1, limit, keys);

        File outputDir = temporaryFolder.newFolder("out-multi-part");
        try {
            FileDecryptService.decrypt(
                    first,
                    keys.getPrivate(),
                    Collections.singletonList(keys.getPublic()),
                    outputDir,
                    null,
                    null,
                    new AtomicBoolean(false));
            fail("多卷外部分卷不应该被单独解密");
        } catch (FileDecryptService.ExternalPartsException expected) {
            assertEquals(Integer.valueOf(1), expected.getPartIndex());
            assertEquals(expectedCount, expected.getPartCount());
            assertTrue(expected.getMessage().contains("批量解密"));
        }
        assertEquals(0, outputDir.listFiles().length);
    }

    @Test
    public void sanitizesDecryptedFileNameAndStaysInOutputDirectory() throws Exception {
        KeyPair keys = keys();
        File plain = new File(temporaryFolder.newFolder("plain-bad-name"), "payload.bin");
        Files.write(plain.toPath(), "safe content".getBytes(StandardCharsets.UTF_8));
        File encrypted = encryptSingle(plain, "..\\evil.txt", keys);

        File outputDir = temporaryFolder.newFolder("out-sanitized");
        FileDecryptService.Result result = FileDecryptService.decrypt(
                encrypted,
                keys.getPrivate(),
                Collections.singletonList(keys.getPublic()),
                outputDir,
                null,
                null,
                new AtomicBoolean(false));

        assertEquals(outputDir.getCanonicalFile(),
                result.getOutput().getParentFile().getCanonicalFile());
        assertFalse(result.getOutput().getName().contains("\\"));
        assertFalse(result.getOutput().getName().contains("/"));
        assertTrue(result.getOutput().getName().endsWith(".txt"));
        assertEquals("safe content", new String(
                Files.readAllBytes(result.getOutput().toPath()),
                StandardCharsets.UTF_8));
    }

    @Test
    public void cancelLeavesNoTemporaryOutput() throws Exception {
        KeyPair keys = keys();
        File plain = new File(temporaryFolder.newFolder("plain-cancel"), "note.txt");
        Files.write(plain.toPath(), "cancel me".getBytes(StandardCharsets.UTF_8));
        File encrypted = encryptSingle(plain, "note.txt", keys);

        File outputDir = temporaryFolder.newFolder("out-cancelled");
        try {
            FileDecryptService.decrypt(
                    encrypted,
                    keys.getPrivate(),
                    Collections.singletonList(keys.getPublic()),
                    outputDir,
                    null,
                    null,
                    new AtomicBoolean(true));
            fail("取消标记应立即生效");
        } catch (crypto.file.FileCancelledException expected) {
            assertTrue(expected.getMessage().contains("取消"));
        }
        assertEquals(0, outputDir.listFiles().length);
    }

    private File encryptSingle(File plain, String originalName, KeyPair keys) throws Exception {
        File encrypted = new File(
                temporaryFolder.newFolder("enc-" + System.nanoTime()),
                "payload.aegix");
        try (InputStream signatureInput = new FileInputStream(plain);
             InputStream plaintextInput = new FileInputStream(plain);
             OutputStream output = new FileOutputStream(encrypted)) {
            FileCryptoEngine.encrypt(
                    signatureInput,
                    plaintextInput,
                    plain.length(),
                    originalName,
                    keys.getPrivate(),
                    keys.getPublic(),
                    output,
                    null,
                    new AtomicBoolean(false));
        }
        return encrypted;
    }

    private File writePart(
            File zip,
            String setId,
            int index,
            long volumeLimit,
            KeyPair keys
    ) throws Exception {
        VolumePartPlan plan = plan(zip, volumeLimit);
        File outputDir = temporaryFolder.newFolder(
                "part-" + setId + "-" + index + "-" + System.nanoTime());
        File output = new File(outputDir, zip.getName() + ".part"
                + String.format("%03d", index) + ".aegix");
        return VolumePartWriter.writePart(
                zip,
                plan,
                index,
                setId,
                zip.getName(),
                sha256(zip),
                false,
                1,
                output,
                keys.getPrivate(),
                keys.getPublic(),
                null,
                new AtomicBoolean(false));
    }

    private int partCount(File zip, long volumeLimit) throws Exception {
        return plan(zip, volumeLimit).getPartCount();
    }

    private VolumePartPlan plan(File zip, long volumeLimit) throws Exception {
        byte[] archiveNameBytes = zip.getName().getBytes(StandardCharsets.UTF_8);
        return VolumePartPlan.create(
                zip.length(),
                volumeLimit,
                archiveNameBytes.length + 32,
                2048);
    }

    private File zip(String name, Object... entries) throws Exception {
        File file = new File(temporaryFolder.newFolder("zip-" + System.nanoTime()), name);
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
}
