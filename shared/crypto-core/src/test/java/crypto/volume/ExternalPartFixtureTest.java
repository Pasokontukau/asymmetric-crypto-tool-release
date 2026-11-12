package crypto.volume;

import crypto.key.KeyUtils;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 当前外部分卷格式的固定夹具测试。
 *
 * <p>夹具由共享内核 {@link VolumePartWriter} 生成，与 Android/桌面读的是同一批字节；
 * 测试不使用“现场生成再读”的方式，避免写入器和读取器一起改坏时仍然通过。</p>
 */
public class ExternalPartFixtureTest {

    private static final String PROPERTIES = "/external/external-part-fixture.properties";

    @Test
    public void multiPartFixtureDecryptsAndBindsEachSlice() throws Exception {
        Properties properties = properties();
        byte[] archive = fixture(properties.getProperty("multi.archive.file"));
        String archiveName = properties.getProperty("multi.archive.name");
        String setId = properties.getProperty("multi.set.id");
        int partCount = Integer.parseInt(properties.getProperty("multi.part.count"));
        List<String> partFiles = Arrays.asList(
                properties.getProperty("multi.part.files").split(","));
        List<String> plainSizes = Arrays.asList(
                properties.getProperty("multi.part.plain.sizes").split(","));

        assertEquals(3, partCount);
        assertEquals(partCount, partFiles.size());
        assertEquals(partCount, plainSizes.size());

        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        int offset = 0;
        for (int index = 0; index < partCount; index++) {
            int partIndex = index + 1;
            long expectedPlain = Long.parseLong(plainSizes.get(index));
            byte[] encrypted = fixture(partFiles.get(index));
            byte[] expectedSlice = Arrays.copyOfRange(
                    archive, offset, offset + (int) expectedPlain);

            VolumeInfo info = VolumeCryptoEngine.readInfo(
                    new ByteArrayInputStream(encrypted),
                    recipientPrivate(properties));
            String logicalName = archiveName + ".part"
                    + String.format(Locale.ROOT, "%03d", partIndex);

            assertEquals(1, info.getVolumeCount());
            assertEquals(logicalName, info.getOriginalFileName());
            assertEquals(partIndex, info.getPartIndex());
            assertEquals(partCount, info.getPartCount());
            assertEquals(setId, info.getSetId());
            assertEquals(archiveName, info.getArchiveName());
            assertEquals(archive.length, info.getArchiveSize());
            assertEquals(properties.getProperty("multi.archive.sha256"),
                    info.getArchiveSha256());
            assertFalse(info.getArchiveSourceIsFolder());
            assertTrue(info.getSourceIsArchive());
            assertEquals(3, info.getSourceFileCount());
            assertFalse(info.isSourceIsFolder());
            assertEquals(expectedPlain, info.getOriginalFileSize());
            assertEquals(expectedPlain, info.getVolumeSize());
            assertEquals(1, info.getEncryptedVolumeSizes().size());
            long expectedInnerBytes = encrypted.length - 12 - readInt(encrypted, 8);
            assertEquals(expectedInnerBytes,
                    info.getEncryptedVolumeSizes().get(0).longValue());
            assertEquals(sha256(expectedSlice), info.getSha256Hash());

            ByteArrayOutputStream plaintext = new ByteArrayOutputStream();
            VolumeDecryptResult result = VolumeCryptoEngine.decrypt(
                    new ByteArrayInputStream(encrypted),
                    recipientPrivate(properties),
                    Collections.singletonList(senderPublic(properties)),
                    plaintext,
                    null,
                    null);

            assertArrayEquals(expectedSlice, plaintext.toByteArray());
            assertEquals(logicalName, result.getOriginalFileName());
            assertEquals(1, result.getVolumeCount());
            assertTrue(result.isSignatureValid());
            assertTrue(result.isContentHashVerified());
            assertTrue(result.isPartNamesVerified());
            assertFalse(result.isSourceIsFolder());
            assertEquals(setId, result.getSetId());
            assertEquals(partIndex, result.getPartIndex());
            assertEquals(partCount, result.getPartCount());
            assertEquals(archiveName, result.getArchiveName());
            assertEquals(archive.length, result.getArchiveSize());
            assertEquals(properties.getProperty("multi.archive.sha256"),
                    result.getArchiveSha256());
            assertFalse(result.getArchiveSourceIsFolder());
            assertTrue(result.getSourceIsArchive());
            assertEquals(3, result.getSourceFileCount());
            assertNotNull(result.getMatchedSender());

            joined.write(plaintext.toByteArray());
            offset += expectedPlain;
        }

        assertArrayEquals(archive, joined.toByteArray());
        assertEquals(properties.getProperty("multi.archive.sha256"),
                sha256(joined.toByteArray()));
    }

    @Test
    public void singlePartFolderFixtureKeepsFolderSemantics() throws Exception {
        Properties properties = properties();
        byte[] folder = fixture(properties.getProperty("single.archive.file"));
        String archiveName = properties.getProperty("single.archive.name");

        byte[] encrypted = fixture(properties.getProperty("single.part.file"));
        VolumeInfo info = VolumeCryptoEngine.readInfo(
                new ByteArrayInputStream(encrypted),
                recipientPrivate(properties));

        assertEquals(1, info.getVolumeCount());
        assertEquals(archiveName, info.getOriginalFileName());
        assertEquals(1, info.getPartIndex());
        assertEquals(1, info.getPartCount());
        assertEquals(properties.getProperty("multi.set.id"), info.getSetId());
        assertEquals(archiveName, info.getArchiveName());
        assertEquals(folder.length, info.getArchiveSize());
        assertEquals(properties.getProperty("single.archive.sha256"), info.getArchiveSha256());
        assertTrue(info.getArchiveSourceIsFolder());
        assertTrue(info.getSourceIsArchive());
        assertEquals(2, info.getSourceFileCount());
        assertTrue(info.isSourceIsFolder());
        assertEquals(folder.length, info.getOriginalFileSize());
        assertEquals(folder.length, info.getVolumeSize());
        assertEquals(1, info.getEncryptedVolumeSizes().size());
        assertEquals(sha256(folder), info.getSha256Hash());

        ByteArrayOutputStream plaintext = new ByteArrayOutputStream();
        VolumeDecryptResult result = VolumeCryptoEngine.decrypt(
                new ByteArrayInputStream(encrypted),
                recipientPrivate(properties),
                Collections.singletonList(senderPublic(properties)),
                plaintext,
                null,
                null);

        assertArrayEquals(folder, plaintext.toByteArray());
        assertEquals(archiveName, result.getOriginalFileName());
        assertEquals(1, result.getVolumeCount());
        assertTrue(result.isSignatureValid());
        assertTrue(result.isContentHashVerified());
        assertTrue(result.isPartNamesVerified());
        assertTrue(result.isSourceIsFolder());
        assertEquals(archiveName, result.getArchiveName());
        assertTrue(result.getArchiveSourceIsFolder());
        assertTrue(result.getSourceIsArchive());
        assertEquals(2, result.getSourceFileCount());
    }

    @Test
    public void fixtureMetadataAndOuterNamesDoNotLeakArchiveName() throws Exception {
        Properties properties = properties();
        assertNoPlainName(
                fixture(properties.getProperty("multi.part.files").split(",")[0]),
                properties.getProperty("multi.archive.name"));
        assertNoPlainName(
                fixture(properties.getProperty("single.part.file")),
                properties.getProperty("single.archive.name"));

        String multiName = properties.getProperty("multi.part.files").split(",")[0];
        String singleName = properties.getProperty("single.part.file");
        assertFalse(multiName.contains(properties.getProperty("multi.archive.name")));
        assertFalse(singleName.contains(properties.getProperty("single.archive.name")));
        assertTrue(multiName.startsWith("secure_file_"));
        assertTrue(multiName.endsWith(".aegix"));
        assertTrue(singleName.startsWith("secure_file_"));
        assertTrue(singleName.endsWith(".aegix"));
    }

    @Test
    public void fixtureHeaderIsVersionOneVolu() throws Exception {
        Properties properties = properties();
        byte[] encrypted = fixture(properties.getProperty("multi.part.files").split(",")[0]);

        assertEquals((byte) 'V', encrypted[0]);
        assertEquals((byte) 'O', encrypted[1]);
        assertEquals((byte) 'L', encrypted[2]);
        assertEquals((byte) 'U', encrypted[3]);
        assertEquals(1, readInt(encrypted, 4));
        assertTrue(readInt(encrypted, 8) > 0);
    }

    @Test
    public void futureVolumeVersionIsRejectedWithExplicitError() throws Exception {
        Properties properties = properties();
        byte[] encrypted = fixture(properties.getProperty("single.part.file"));
        // 版本字段在 VOLU 头偏移 4；改成 2 模拟未来容器版本。
        encrypted[4] = 0;
        encrypted[5] = 0;
        encrypted[6] = 0;
        encrypted[7] = 2;

        try {
            VolumeCryptoEngine.readInfo(
                    new ByteArrayInputStream(encrypted), recipientPrivate(properties));
            fail("未来版本必须明确拒绝，不能按版本 1 猜测");
        } catch (UnsupportedVolumeVersionException expected) {
            assertTrue(expected.getMessage().contains("2"));
        }
    }

    private static void assertNoPlainName(byte[] data, String name) {
        byte[] needle = name.getBytes(StandardCharsets.UTF_8);
        assertTrue(
                "密文/头部不应出现明文文件名: " + name,
                indexOf(data, needle) < 0
        );
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int index = 0; index + needle.length <= haystack.length; index++) {
            boolean match = true;
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return index;
            }
        }
        return -1;
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }

    private static Properties properties() throws Exception {
        InputStream input = ExternalPartFixtureTest.class.getResourceAsStream(PROPERTIES);
        assertNotNull("缺少夹具 " + PROPERTIES, input);
        try {
            Properties properties = new Properties();
            properties.load(input);
            return properties;
        } finally {
            input.close();
        }
    }

    private static byte[] fixture(String name) throws Exception {
        InputStream input = ExternalPartFixtureTest.class.getResourceAsStream(
                "/external/" + name);
        assertNotNull("缺少夹具 /external/" + name, input);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static PrivateKey recipientPrivate(Properties properties) throws Exception {
        return KeyUtils.getPrivateKeyFromBytes(
                Base64.getDecoder().decode(
                        properties.getProperty("recipient.private.pkcs8.b64")));
    }

    private static PublicKey senderPublic(Properties properties) throws Exception {
        return KeyUtils.getPublicKeyFromBytes(
                Base64.getDecoder().decode(
                        properties.getProperty("sender.public.x509.b64")));
    }

    private static String sha256(byte[] data) throws Exception {
        java.security.MessageDigest digest =
                java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest(data)) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }
}
