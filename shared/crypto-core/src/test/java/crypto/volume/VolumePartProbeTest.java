package crypto.volume;

import crypto.file.WrongRecipientException;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * VOLU 元数据探针测试。
 *
 * <p>覆盖多卷、单卷文件夹、错误私钥、截断头，以及“只读元数据、不消费
 * payload”的计数流断言；同时验证 decrypt 结果透传集合字段。</p>
 */
public class VolumePartProbeTest {

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
    public void multiPartProbeExposesCollectionFieldsAndDecryptTransparently() throws Exception {
        KeyPair sender = keyPair();
        KeyPair receiver = keyPair();
        byte[] archive = randomBytes(2_000);
        File dir = tempDir();
        File zip = writeFile(dir, "archive.zip", archive);
        VolumePartPlan plan = VolumePartPlan.create(
                archive.length, 5_000, ARCHIVE_NAME.getBytes("UTF-8").length + 16, 2048);
        assertEquals(3, plan.getPartCount());
        File part = writePart(
                direction(plan, zip, 2, archive, sender, receiver, true, dir));

        VolumeInfo info = VolumePartProbe.probe(part, receiver.getPrivate());
        assertTrue(info.isExternalPart());
        assertEquals(SET_ID, info.getSetId());
        assertEquals(2, info.getPartIndex());
        assertEquals(3, info.getPartCount());
        assertEquals(ARCHIVE_NAME + ".part002", info.getOriginalFileName());
        assertEquals(ARCHIVE_NAME, info.getArchiveName());
        assertEquals(archive.length, info.getArchiveSize());
        assertEquals(sha256(archive), info.getArchiveSha256());
        assertTrue(info.getArchiveSourceIsFolder());
        assertTrue(info.getSourceIsArchive());
        assertEquals(3, info.getSourceFileCount());
        assertEquals(1, info.getVolumeCount());
        assertFalse(info.isSourceIsFolder());

        ByteArrayOutputStream plaintext = new ByteArrayOutputStream();
        VolumeDecryptResult result = VolumeCryptoEngine.decrypt(
                new FileInputStream(part),
                receiver.getPrivate(),
                Collections.singletonList(sender.getPublic()),
                plaintext,
                null,
                new AtomicBoolean(false)
        );
        assertEquals(SET_ID, result.getSetId());
        assertEquals(2, result.getPartIndex());
        assertEquals(3, result.getPartCount());
        assertEquals(ARCHIVE_NAME, result.getArchiveName());
        assertEquals(archive.length, result.getArchiveSize());
        assertEquals(sha256(archive), result.getArchiveSha256());
        assertTrue(result.getArchiveSourceIsFolder());
        assertEquals(3, result.getSourceFileCount());
        assertTrue(result.getSourceIsArchive());
        assertEquals(plan.getPartPlaintextBytes(2), result.getPlaintextBytes());
    }

    @Test
    public void singlePartProbeKeepsFolderSemantics() throws Exception {
        KeyPair sender = keyPair();
        KeyPair receiver = keyPair();
        byte[] archive = randomBytes(1_000);
        File dir = tempDir();
        File zip = writeFile(dir, "archive.zip", archive);
        VolumePartPlan plan = VolumePartPlan.create(
                archive.length, 50L * MB, ARCHIVE_NAME.getBytes("UTF-8").length + 1, 2048);
        assertEquals(1, plan.getPartCount());
        File part = writePart(
                direction(plan, zip, 1, archive, sender, receiver, true, dir));

        VolumeInfo info = VolumePartProbe.probe(part, receiver.getPrivate());
        assertTrue(info.isExternalPart());
        assertEquals(1, info.getPartCount());
        assertTrue(info.isSourceIsFolder());
        assertTrue(info.getArchiveSourceIsFolder());
        assertEquals(ARCHIVE_NAME, info.getOriginalFileName());
        assertEquals(archive.length, info.getArchiveSize());
    }

    @Test
    public void wrongPrivateKeyIsRejected() throws Exception {
        KeyPair sender = keyPair();
        KeyPair receiver = keyPair();
        KeyPair other = keyPair();
        byte[] archive = randomBytes(800);
        File dir = tempDir();
        File zip = writeFile(dir, "archive.zip", archive);
        VolumePartPlan plan = VolumePartPlan.create(
                archive.length, 50L * MB, ARCHIVE_NAME.getBytes("UTF-8").length + 1, 2048);
        File part = writePart(
                direction(plan, zip, 1, archive, sender, receiver, false, dir));

        try {
            VolumePartProbe.probe(part, other.getPrivate());
            fail("错误私钥不应能解开 VolumeInfo");
        } catch (WrongRecipientException expected) {
            // 预期
        }
    }

    @Test
    public void probeReadsOnlyTheMetadataPrefix() throws Exception {
        KeyPair sender = keyPair();
        KeyPair receiver = keyPair();
        byte[] archive = randomBytes(5_000);
        File dir = tempDir();
        File zip = writeFile(dir, "archive.zip", archive);
        VolumePartPlan plan = VolumePartPlan.create(
                archive.length, 50L * MB, ARCHIVE_NAME.getBytes("UTF-8").length + 1, 2048);
        File part = writePart(
                direction(plan, zip, 1, archive, sender, receiver, false, dir));

        int metadataLength = readMetadataLength(part);
        CountingInputStream counting = new CountingInputStream(new FileInputStream(part));
        VolumeInfo info;
        try {
            info = VolumePartProbe.probe(counting, receiver.getPrivate());
        } finally {
            counting.close();
        }
        assertEquals(1, info.getPartCount());
        assertEquals(12L + metadataLength, counting.count);
        assertTrue("探针不能读取 payload", counting.count < part.length());
    }

    @Test
    public void truncatedHeaderIsRejected() throws Exception {
        byte[] magicOnly = new byte[] {0x56, 0x4F, 0x4C, 0x55};
        try {
            VolumePartProbe.probe(
                    new ByteArrayInputStream(magicOnly),
                    keyPair().getPrivate()
            );
            fail("截断的 VOLU 头应当被拒绝");
        } catch (VolumeFormatException expected) {
            // 预期
        }
    }

    // region helpers

    private WriteRequest direction(
            VolumePartPlan plan,
            File zip,
            int partIndex,
            byte[] archive,
            KeyPair sender,
            KeyPair receiver,
            boolean sourceIsFolder,
            File dir
    ) throws Exception {
        return new WriteRequest(
                plan,
                zip,
                partIndex,
                ARCHIVE_NAME,
                sha256(archive),
                sourceIsFolder,
                3,
                new File(dir, "part-" + partIndex + ".aegix"),
                sender,
                receiver
        );
    }

    private static File writePart(WriteRequest request) throws Exception {
        return VolumePartWriter.writePart(
                request.zip,
                request.plan,
                request.partIndex,
                SET_ID,
                request.archiveName,
                request.archiveSha256,
                request.sourceIsFolder,
                request.sourceFileCount,
                request.output,
                request.sender.getPrivate(),
                request.receiver.getPublic(),
                null,
                new AtomicBoolean(false)
        );
    }

    private static int readMetadataLength(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            byte[] header = new byte[12];
            readFully(input, header);
            return ((header[8] & 0xff) << 24)
                    | ((header[9] & 0xff) << 16)
                    | ((header[10] & 0xff) << 8)
                    | (header[11] & 0xff);
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

    private File tempDir() throws IOException {
        File dir = Files.createTempDirectory("volu-probe").toFile();
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

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(20261104).nextBytes(data);
        return data;
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest(data)) {
            builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return builder.toString();
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

    private static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }
    }

    private static final class WriteRequest {
        final VolumePartPlan plan;
        final File zip;
        final int partIndex;
        final String archiveName;
        final String archiveSha256;
        final boolean sourceIsFolder;
        final int sourceFileCount;
        final File output;
        final KeyPair sender;
        final KeyPair receiver;

        WriteRequest(
                VolumePartPlan plan,
                File zip,
                int partIndex,
                String archiveName,
                String archiveSha256,
                boolean sourceIsFolder,
                int sourceFileCount,
                File output,
                KeyPair sender,
                KeyPair receiver
        ) {
            this.plan = plan;
            this.zip = zip;
            this.partIndex = partIndex;
            this.archiveName = archiveName;
            this.archiveSha256 = archiveSha256;
            this.sourceIsFolder = sourceIsFolder;
            this.sourceFileCount = sourceFileCount;
            this.output = output;
            this.sender = sender;
            this.receiver = receiver;
        }
    }

    // endregion
}
