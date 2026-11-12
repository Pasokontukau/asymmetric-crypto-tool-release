package crypto.volume;

import crypto.cipher.Decryptor;
import crypto.cipher.Encryptor;
import crypto.file.FileCancelledException;
import crypto.file.FileProgressListener;
import org.junit.After;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 外部分卷写入器测试。
 *
 * <p>用真实 RSA/AES 生成 3 卷，逐卷独立解密后拼接；并验证元数据字段、单卷文件夹
 * 语义、4096 位预算，以及超限、长度不一致、取消时不留下半套临时文件。</p>
 */
public class VolumePartWriterTest {

    private static final int MB = 1_000_000;
    private static final String SET_ID = "9f2c4a7d1b8e4c6f9a0d3e5b7c1f2a44";
    private static final String ARCHIVE_NAME = "季度 报告.zip";

    private final List<File> tempDirs = new ArrayList<File>();

    @After
    public void cleanUp() {
        for (File dir : tempDirs) {
            deleteRecursively(dir);
        }
        tempDirs.clear();
    }

    @Test
    public void threePartsRoundTripAndEachPartDecryptsIndependently() throws Exception {
        KeyPair sender = keyPair(2048);
        KeyPair receiver = keyPair(2048);
        byte[] archive = randomBytes(2_000);
        File dir = tempDir();
        File zip = writeFile(dir, "archive.zip", archive);

        VolumePartPlan plan = VolumePartPlan.create(
                archive.length,
                5_000,
                ARCHIVE_NAME.getBytes(StandardCharsets.UTF_8).length + 8,
                2048
        );
        assertEquals(3, plan.getPartCount());
        String archiveHash = sha256(archive);

        List<File> parts = new ArrayList<File>();
        for (int index = 1; index <= plan.getPartCount(); index++) {
            File part = new File(dir, "part-" + index + ".aegix");
            File written = VolumePartWriter.writePart(
                    zip,
                    plan,
                    index,
                    SET_ID,
                    ARCHIVE_NAME,
                    archiveHash,
                    true,
                    2,
                    part,
                    sender.getPrivate(),
                    receiver.getPublic(),
                    null,
                    new AtomicBoolean(false)
            );
            assertEquals(part.getAbsoluteFile(), written);
            assertTrue("每卷最终大小必须 <= 上限",
                    written.length() <= plan.getVolumeLimitBytes());
            assertTrue("每卷必须至少包含 VOLU 头", written.length() > 12);
            assertFalse(
                    "卷内不得出现明文 archiveName",
                    containsBytes(
                            Files.readAllBytes(written.toPath()),
                            ARCHIVE_NAME.getBytes(StandardCharsets.UTF_8)));
            parts.add(written);
        }

        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (int index = 0; index < parts.size(); index++) {
            File part = parts.get(index);
            ByteArrayOutputStream plaintext = new ByteArrayOutputStream();
            VolumeDecryptResult result = VolumeCryptoEngine.decrypt(
                    new FileInputStream(part),
                    receiver.getPrivate(),
                    Collections.singletonList(sender.getPublic()),
                    plaintext,
                    null,
                    new AtomicBoolean(false)
            );

            int partIndex = index + 1;
            long expectedPlain = plan.getPartPlaintextBytes(partIndex);
            assertArrayEquals(
                    Arrays.copyOfRange(archive,
                            (int) plan.getPartOffset(partIndex),
                            (int) (plan.getPartOffset(partIndex) + expectedPlain)),
                    plaintext.toByteArray()
            );
            assertEquals(expectedPlain, result.getPlaintextBytes());
            assertEquals(1, result.getVolumeCount());
            assertTrue(result.isSignatureValid());
            assertTrue(result.isContentHashVerified());
            assertTrue(result.isPartNamesVerified());
            assertFalse("多卷包的 sourceIsFolder 必须为 false", result.isSourceIsFolder());
            assertEquals(
                    ARCHIVE_NAME + ".part" + String.format(Locale.ROOT, "%03d", partIndex),
                    result.getOriginalFileName()
            );

            InfoBox infoBox = readInfo(part, receiver.getPrivate());
            VolumeInfo info = infoBox.info;
            assertEquals(SET_ID, info.getSetId());
            assertEquals(partIndex, info.getPartIndex());
            assertEquals(3, info.getPartCount());
            assertEquals(ARCHIVE_NAME + ".part" + String.format(Locale.ROOT, "%03d", partIndex),
                    info.getOriginalFileName());
            assertEquals(ARCHIVE_NAME, info.getArchiveName());
            assertEquals(archive.length, info.getArchiveSize());
            assertEquals(archiveHash, info.getArchiveSha256());
            assertTrue(info.getArchiveSourceIsFolder());
            assertTrue(info.getSourceIsArchive());
            assertEquals(2, info.getSourceFileCount());
            assertEquals(1, info.getVolumeCount());
            assertEquals(expectedPlain, info.getOriginalFileSize());
            assertEquals(expectedPlain, info.getVolumeSize());
            assertFalse(info.isSourceIsFolder());
            assertEquals(1, info.getEncryptedVolumeSizes().size());
            assertEquals(part.length() - 12 - infoBox.metadataLength,
                    info.getEncryptedVolumeSizes().get(0).longValue());
            assertEquals(sha256(plaintext.toByteArray()), info.getSha256Hash());
            joined.write(plaintext.toByteArray());
        }
        assertArrayEquals(archive, joined.toByteArray());
    }

    private static boolean containsBytes(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    @Test
    public void singlePartKeepsFolderSemanticsAndArchiveName() throws Exception {
        KeyPair sender = keyPair(2048);
        KeyPair receiver = keyPair(2048);
        byte[] archive = randomBytes(1_000);
        File dir = tempDir();
        File zip = writeFile(dir, "archive.zip", archive);

        VolumePartPlan plan = VolumePartPlan.create(
                archive.length,
                50L * MB,
                ARCHIVE_NAME.getBytes(StandardCharsets.UTF_8).length + 1,
                2048
        );
        assertEquals(1, plan.getPartCount());
        File part = new File(dir, "secure_file.aegix");
        VolumePartWriter.writePart(
                zip,
                plan,
                1,
                SET_ID,
                ARCHIVE_NAME,
                sha256(archive),
                true,
                0,
                part,
                sender.getPrivate(),
                receiver.getPublic(),
                null,
                new AtomicBoolean(false)
        );

        ByteArrayOutputStream plaintext = new ByteArrayOutputStream();
        VolumeDecryptResult result = VolumeCryptoEngine.decrypt(
                new FileInputStream(part),
                receiver.getPrivate(),
                Collections.singletonList(sender.getPublic()),
                plaintext,
                null,
                new AtomicBoolean(false)
        );
        assertArrayEquals(archive, plaintext.toByteArray());
        assertEquals(1, result.getVolumeCount());
        assertTrue(result.isSourceIsFolder());
        assertTrue(result.isContentHashVerified());
        assertTrue(result.isPartNamesVerified());
        assertEquals(ARCHIVE_NAME, result.getOriginalFileName());

        InfoBox infoBox = readInfo(part, receiver.getPrivate());
        assertEquals(ARCHIVE_NAME, infoBox.info.getOriginalFileName());
        assertEquals(1, infoBox.info.getPartCount());
        assertTrue(infoBox.info.isSourceIsFolder());
        assertTrue(infoBox.info.getArchiveSourceIsFolder());
        assertEquals(archive.length, infoBox.info.getArchiveSize());
        assertEquals(sha256(archive), infoBox.info.getArchiveSha256());
        assertEquals(sha256(archive), infoBox.info.getSha256Hash());
    }

    @Test
    public void fourThousandNinetySixBitKeysFitTheBudget() throws Exception {
        KeyPair sender = keyPair(4096);
        KeyPair receiver = keyPair(4096);
        byte[] archive = randomBytes(10_000);
        File dir = tempDir();
        File zip = writeFile(dir, "archive.zip", archive);

        VolumePartPlan plan = VolumePartPlan.create(
                archive.length,
                50L * MB,
                ARCHIVE_NAME.getBytes(StandardCharsets.UTF_8).length + 1,
                4096
        );
        File part = new File(dir, "4096.aegix");
        VolumePartWriter.writePart(
                zip,
                plan,
                1,
                SET_ID,
                ARCHIVE_NAME,
                sha256(archive),
                false,
                1,
                part,
                sender.getPrivate(),
                receiver.getPublic(),
                null,
                new AtomicBoolean(false)
        );

        assertTrue(part.length() <= plan.getVolumeLimitBytes());
        ByteArrayOutputStream plaintext = new ByteArrayOutputStream();
        VolumeDecryptResult result = VolumeCryptoEngine.decrypt(
                new FileInputStream(part),
                receiver.getPrivate(),
                Collections.singletonList(sender.getPublic()),
                plaintext,
                null,
                new AtomicBoolean(false)
        );
        assertArrayEquals(archive, plaintext.toByteArray());
        assertTrue(result.isSignatureValid());
        assertTrue(result.isContentHashVerified());
        assertEquals(1, readInfo(part, receiver.getPrivate()).info.getPartCount());
    }

    @Test
    public void existingOutputIsRejectedBeforeWritingTempFiles() throws Exception {
        KeyPair sender = keyPair(2048);
        KeyPair receiver = keyPair(2048);
        File dir = tempDir();
        File zip = writeFile(dir, "archive.zip", randomBytes(100));
        File output = writeFile(dir, "existing.aegix", new byte[] {1, 2, 3});

        VolumePartPlan plan = VolumePartPlan.create(100, 50L * MB, 24, 2048);
        try {
            VolumePartWriter.writePart(
                    zip,
                    plan,
                    1,
                    SET_ID,
                    ARCHIVE_NAME,
                    sha256(new byte[100]),
                    false,
                    0,
                    output,
                    sender.getPrivate(),
                    receiver.getPublic(),
                    null,
                    new AtomicBoolean(false)
            );
            fail("已存在的输出文件不应被覆盖");
        } catch (VolumePartException expected) {
            assertEquals(VolumePartException.Code.INVALID_PLAN, expected.getCode());
        }
        assertEquals(3, output.length());
        assertNoTemps(dir);
    }

    @Test
    public void oversizedFinalFileLeavesNoOutputOrTemps() throws Exception {
        KeyPair sender = keyPair(4096);
        KeyPair receiver = keyPair(4096);
        File dir = tempDir();
        File zip = writeFile(dir, "archive.zip", randomBytes(800));
        // 1024 字符的归档名 + 4096 位密钥会让实际头部明显超过 5000 B；
        // 计划本身仍允许单卷（容量 904 B）。
        String longName = repeat('a', VolumeInfo.MAX_FILE_NAME_CHARS);
        VolumePartPlan plan = VolumePartPlan.create(800, 5_000, 1_030, 4096);
        assertEquals(1, plan.getPartCount());
        File output = new File(dir, "too-large.aegix");

        try {
            VolumePartWriter.writePart(
                    zip,
                    plan,
                    1,
                    SET_ID,
                    longName,
                    sha256(readAll(zip)),
                    false,
                    0,
                    output,
                    sender.getPrivate(),
                    receiver.getPublic(),
                    null,
                    new AtomicBoolean(false)
            );
            fail("最终文件超过单卷上限时不应成功");
        } catch (VolumePartException expected) {
            assertEquals(VolumePartException.Code.SIZE_MISMATCH, expected.getCode());
        }
        assertFalse(output.exists());
        assertNoTemps(dir);
    }

    @Test
    public void cancellationLeavesNoOutputOrTemps() throws Exception {
        KeyPair sender = keyPair(2048);
        KeyPair receiver = keyPair(2048);
        File dir = tempDir();
        File zip = writeFile(dir, "archive.zip", randomBytes(2 * 1024 * 1024));
        VolumePartPlan plan = VolumePartPlan.create(
                zip.length(),
                50L * MB,
                24,
                2048
        );
        File output = new File(dir, "cancelled.aegix");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        FileProgressListener listener = (phase, processed, total) -> {
            if (processed > 0) {
                cancelled.set(true);
            }
        };

        try {
            VolumePartWriter.writePart(
                    zip,
                    plan,
                    1,
                    SET_ID,
                    ARCHIVE_NAME,
                    sha256(readAll(zip)),
                    false,
                    0,
                    output,
                    sender.getPrivate(),
                    receiver.getPublic(),
                    listener,
                    cancelled
            );
            fail("取消后不应成功写出分卷");
        } catch (FileCancelledException expected) {
            // 预期
        }
        assertFalse(output.exists());
        assertNoTemps(dir);
    }

    @Test
    public void zipLengthMismatchIsRejected() throws Exception {
        KeyPair sender = keyPair(2048);
        KeyPair receiver = keyPair(2048);
        File dir = tempDir();
        File zip = writeFile(dir, "archive.zip", randomBytes(1_001));
        VolumePartPlan plan = VolumePartPlan.create(1_000, 50L * MB, 24, 2048);
        File output = new File(dir, "mismatch.aegix");

        try {
            VolumePartWriter.writePart(
                    zip,
                    plan,
                    1,
                    SET_ID,
                    ARCHIVE_NAME,
                    sha256(readAll(zip)),
                    false,
                    0,
                    output,
                    sender.getPrivate(),
                    receiver.getPublic(),
                    null,
                    new AtomicBoolean(false)
            );
            fail("ZIP 长度与计划不一致时应拒绝");
        } catch (VolumePartException expected) {
            assertEquals(VolumePartException.Code.SIZE_MISMATCH, expected.getCode());
        }
        assertFalse(output.exists());
        assertNoTemps(dir);
    }

    @Test
    public void rawFilePartsPreserveFileSemanticsInsteadOfArchiveSemantics() throws Exception {
        KeyPair sender = keyPair(2048);
        KeyPair receiver = keyPair(2048);
        byte[] data = randomBytes(2_000);
        File dir = tempDir();
        File sourceFile = writeFile(dir, "movie.mkv", data);
        VolumePartPlan plan = VolumePartPlan.createForFile(
                data.length, 5_000, "movie.mkv".length() + 8, 2048);
        assertEquals(3, plan.getPartCount());

        String hash = sha256(data);
        List<File> parts = new ArrayList<File>();
        VolumePartSource source = filePartSource(sourceFile);
        for (int index = 1; index <= plan.getPartCount(); index++) {
            File part = new File(dir, "raw-" + index + ".aegix");
            parts.add(VolumePartWriter.writePartFromSource(
                    source,
                    plan,
                    index,
                    SET_ID,
                    "movie.mkv",
                    hash,
                    false,
                    false,
                    1,
                    part,
                    sender.getPrivate(),
                    receiver.getPublic(),
                    null,
                    new AtomicBoolean(false)));
        }

        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (int index = 0; index < parts.size(); index++) {
            File part = parts.get(index);
            ByteArrayOutputStream plaintext = new ByteArrayOutputStream();
            VolumeDecryptResult result = VolumeCryptoEngine.decrypt(
                    new FileInputStream(part),
                    receiver.getPrivate(),
                    Collections.singletonList(sender.getPublic()),
                    plaintext,
                    null,
                    new AtomicBoolean(false));
            assertEquals(1, result.getVolumeCount());
            assertTrue(result.isSignatureValid());
            assertTrue(result.isContentHashVerified());
            assertFalse(result.getSourceIsArchive());
            assertFalse(result.isSourceIsFolder());
            assertEquals("movie.mkv", result.getArchiveName());
            assertEquals(data.length, result.getArchiveSize());
            assertEquals(1, result.getSourceFileCount());
            joined.write(plaintext.toByteArray());

            VolumeInfo info = readInfo(part, receiver.getPrivate()).info;
            assertFalse(info.isExternalPart());
            assertTrue(info.isFilePartSet());
            assertFalse(info.getArchiveSourceIsFolder());
            assertFalse(info.isSourceIsFolder());
            assertEquals(1, info.getSourceFileCount());
            assertEquals("movie.mkv", info.getArchiveName());
            assertEquals(data.length, info.getArchiveSize());
        }
        assertArrayEquals(data, joined.toByteArray());
    }

    private static VolumePartSource filePartSource(final File file) {
        return new VolumePartSource() {
            @Override
            public long length() {
                return file.length();
            }

            @Override
            public long lastModified() {
                return file.lastModified();
            }

            @Override
            public InputStream openAt(long offset) throws IOException {
                FileInputStream input = new FileInputStream(file);
                input.getChannel().position(offset);
                return input;
            }
        };
    }

    // region helpers

    private File tempDir() throws IOException {
        File dir = Files.createTempDirectory("volu-part-writer").toFile();
        tempDirs.add(dir);
        return dir;
    }

    private static File writeFile(File dir, String name, byte[] data) throws IOException {
        File file = new File(dir, name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(data);
        }
        return file;
    }

    private static byte[] readAll(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(20261103).nextBytes(data);
        return data;
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static KeyPair keyPair(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static InfoBox readInfo(File part, PrivateKey recipientKey) throws Exception {
        try (InputStream input = new BufferedInputStream(new FileInputStream(part))) {
            byte[] header = new byte[12];
            readFully(input, header);
            int magic = ((header[0] & 0xff) << 24)
                    | ((header[1] & 0xff) << 16)
                    | ((header[2] & 0xff) << 8)
                    | (header[3] & 0xff);
            int version = ((header[4] & 0xff) << 24)
                    | ((header[5] & 0xff) << 16)
                    | ((header[6] & 0xff) << 8)
                    | (header[7] & 0xff);
            int metadataLength = ((header[8] & 0xff) << 24)
                    | ((header[9] & 0xff) << 16)
                    | ((header[10] & 0xff) << 8)
                    | (header[11] & 0xff);
            assertEquals(0x564F4C55, magic);
            assertEquals(1, version);
            assertTrue(metadataLength > 0);
            byte[] metadata = new byte[metadataLength];
            readFully(input, metadata);
            Encryptor.EncryptedData encrypted = Encryptor.EncryptedData.fromBytes(metadata);
            byte[] json = Decryptor.decryptData(encrypted, recipientKey);
            return new InfoBox(
                    VolumeInfoParser.parse(new String(json, StandardCharsets.UTF_8)),
                    metadataLength
            );
        }
    }

    private static void readFully(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = input.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                throw new IOException("测试读取提前结束");
            }
            offset += read;
        }
    }

    private static void assertNoTemps(File dir) {
        File[] files = dir.listFiles();
        assertNotNull(files);
        for (File file : files) {
            String name = file.getName();
            assertFalse("失败后不应残留临时文件: " + name,
                    name.contains(".inner.tmp") || name.contains(".part.tmp"));
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        // 测试清理失败不应掩盖测试结果。
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static final class InfoBox {
        final VolumeInfo info;
        final int metadataLength;

        InfoBox(VolumeInfo info, int metadataLength) {
            this.info = info;
            this.metadataLength = metadataLength;
        }
    }

    // endregion
}
