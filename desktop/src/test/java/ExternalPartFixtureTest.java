import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import securecomm.service.ExternalPartFixtures;
import securecomm.service.FileDecryptService;
import volumes.service.ExternalPartMerger;

/**
 * 桌面端读取当前外部分卷固定夹具。
 *
 * <p>夹具由共享内核 {@link crypto.volume.VolumePartWriter} 生成，桌面只消费同一批字节；
 * 这验证的是当前 Android 写出的格式能否被桌面合并，而不是旧 VOLU 兼容。</p>
 */
public class ExternalPartFixtureTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File fixtureDirectory;

    @Test
    public void mergesFixedMultiPartFixtureIntoOriginalZip() throws Exception {
        Properties properties = properties();
        List<File> parts = new ArrayList<File>();
        for (String name : properties.getProperty("multi.part.files").split(",")) {
            parts.add(copyFixture(name));
        }

        ExternalPartMerger.Inspection inspection = ExternalPartMerger.inspect(
                parts, recipientPrivate(properties));
        assertEquals(ExternalPartMerger.Status.COMPLETE, inspection.getStatus());
        assertEquals(3, inspection.getOrderedParts().size());
        assertEquals(1,
                inspection.getOrderedParts().get(0).getInfo().getPartIndex());
        assertEquals(3,
                inspection.getOrderedParts().get(2).getInfo().getPartIndex());

        File outputDir = temporaryFolder.newFolder("merged");
        ExternalPartMerger.MergeResult result = ExternalPartMerger.merge(
                parts,
                recipientPrivate(properties),
                Collections.singletonList(senderPublic(properties)),
                outputDir,
                "merge.tmp",
                null,
                new AtomicBoolean(false));

        assertEquals(properties.getProperty("multi.archive.name"),
                result.getOutput().getName());
        assertEquals(3, result.getPartCount());
        assertEquals(Long.parseLong(properties.getProperty("multi.archive.size")),
                result.getArchiveSize());
        assertTrue(result.isSignatureValid());
        assertTrue(result.isPartNamesVerified());
        assertArrayEquals(
                fixture(properties.getProperty("multi.archive.file")),
                Files.readAllBytes(result.getOutput().toPath()));
    }

    @Test
    public void decryptsFixedSinglePartFolderFixtureAsZip() throws Exception {
        Properties properties = properties();
        File part = copyFixture(properties.getProperty("single.part.file"));
        File outputDir = temporaryFolder.newFolder("single");

        FileDecryptService.Result result = FileDecryptService.decrypt(
                part,
                recipientPrivate(properties),
                Collections.singletonList(senderPublic(properties)),
                outputDir,
                null,
                null,
                new AtomicBoolean(false));

        assertTrue(result.isExternalPart());
        assertFalse(result.isFolder());
        assertEquals(1, result.getVolumeCount());
        assertEquals(properties.getProperty("single.archive.name"),
                result.getOutput().getName());
        assertTrue(result.isSignatureValid());
        assertTrue(result.isContentHashVerified());
        assertArrayEquals(
                fixture(properties.getProperty("single.archive.file")),
                Files.readAllBytes(result.getOutput().toPath()));
    }

    @Test
    public void fixedFixtureDoesNotLeakArchiveNameInCiphertext() throws Exception {
        Properties properties = properties();
        assertNoPlainName(
                Files.readAllBytes(copyFixture(
                        properties.getProperty("multi.part.files").split(",")[0]).toPath()),
                properties.getProperty("multi.archive.name"));
        assertNoPlainName(
                Files.readAllBytes(copyFixture(
                        properties.getProperty("single.part.file")).toPath()),
                properties.getProperty("single.archive.name"));
    }

    private File copyFixture(String name) throws Exception {
        if (fixtureDirectory == null) {
            fixtureDirectory = temporaryFolder.newFolder("fixtures");
        }
        return ExternalPartFixtures.copy(fixtureDirectory, name);
    }

    private static Properties properties() throws Exception {
        return ExternalPartFixtures.properties();
    }

    private static PrivateKey recipientPrivate(Properties properties) throws Exception {
        return ExternalPartFixtures.recipientPrivate(properties);
    }

    private static PublicKey senderPublic(Properties properties) throws Exception {
        return ExternalPartFixtures.senderPublic(properties);
    }

    private static byte[] fixture(String name) throws Exception {
        try (InputStream input = ExternalPartFixtureTest.class.getResourceAsStream(
                "/external/" + name)) {
            assertNotNull("缺少夹具 /external/" + name, input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void assertNoPlainName(byte[] data, String name) {
        byte[] needle = name.getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index + needle.length <= data.length; index++) {
            boolean match = true;
            for (int offset = 0; offset < needle.length; offset++) {
                if (data[index + offset] != needle[offset]) {
                    match = false;
                    break;
                }
            }
            assertFalse("密文/头部不应出现明文文件名: " + name, match);
        }
    }
}
