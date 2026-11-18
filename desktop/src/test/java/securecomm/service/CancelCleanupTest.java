package securecomm.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import crypto.file.FileCancelledException;
import java.io.File;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import securecomm.ui.util.TempFileManager;
import volumes.service.ExternalVolumeWriter;

/**
 * 取消与失败路径的清理护栏：无论取消发生在开始前还是解密中途，
 * 输出目录都不能留下 .tmp 或半截产物（手工矩阵 #9 的自动化替代）。
 */
public class CancelCleanupTest {

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
    public void cancelledVolumeWriteLeavesNoParts() throws Exception {
        File source = temporaryFolder.newFile("big.bin");
        Files.write(source.toPath(), new byte[300 * 1024]);
        File outputDirectory = temporaryFolder.newFolder("out-parts");

        AtomicBoolean cancelled = new AtomicBoolean(true);
        try {
            ExternalVolumeWriter.write(source, false, "big.bin", false, null, outputDirectory,
                    "secure_file_20260101_000000", keys.getPrivate(), keys.getPublic(),
                    100 * 1024, cancelled);
            fail("取消后不应继续写分卷");
        } catch (FileCancelledException expected) {
            assertNotNull(expected.getMessage());
        }

        assertArrayEquals(new File[0], outputDirectory.listFiles());
    }

    @Test
    public void cancelledTaskServiceEncryptReportsFailureAndWritesNothing() throws Exception {
        File source = temporaryFolder.newFile("big.bin");
        Files.write(source.toPath(), new byte[300 * 1024]);
        File outputDirectory = temporaryFolder.newFolder("out-service");

        EncryptOutcome outcome = new DefaultFileTaskService().encrypt(EncryptRequest
                .singleFile(source, outputDirectory, keys.getPrivate(), keys.getPublic(), 100 * 1024)
                .withCancellation(new AtomicBoolean(true)));

        assertFalse(outcome.isSuccess());
        assertNotNull(outcome.getErrorMessage());
        assertArrayEquals(new File[0], outputDirectory.listFiles());
    }

    @Test
    public void cancelledDecryptLeavesNoOutputOrTemporaryFile() throws Exception {
        File packagePart = createSinglePartPackage("cancel-package.bin");
        File outputDirectory = temporaryFolder.newFolder("out-decrypt");

        try {
            FileDecryptService.decrypt(packagePart, keys.getPrivate(),
                    Collections.singletonList(keys.getPublic()), outputDirectory,
                    null, null, new AtomicBoolean(true));
            fail("取消后不应继续解密");
        } catch (FileCancelledException expected) {
            assertNotNull(expected.getMessage());
        }

        assertArrayEquals(new File[0], outputDirectory.listFiles());
    }

    @Test
    public void cancelDuringDecryptLeavesNoTemporaryFile() throws Exception {
        File packagePart = createSinglePartPackage("mid-cancel.bin");
        File outputDirectory = temporaryFolder.newFolder("out-mid-cancel");
        AtomicBoolean cancelled = new AtomicBoolean(false);

        try {
            FileDecryptService.decrypt(packagePart, keys.getPrivate(),
                    Collections.singletonList(keys.getPublic()), outputDirectory,
                    null,
                    (partNumber, partCount, processed, total) -> {
                        if (processed > 0) {
                            cancelled.set(true);
                        }
                    },
                    cancelled);
            fail("解密中途取消后不应成功");
        } catch (FileCancelledException expected) {
            assertNotNull(expected.getMessage());
        }

        assertArrayEquals(new File[0], outputDirectory.listFiles());
    }

    @Test
    public void cleanAllRemovesRegisteredTemporaryArchives() throws Exception {
        File zip = temporaryFolder.newFile("aegix-archive-cancel.zip");
        Files.write(zip.toPath(), new byte[]{1, 2, 3});
        TempFileManager.registerArchiveZip(zip, "archive_20260101_000000.zip");

        TempFileManager.cleanAll();

        assertFalse(zip.exists());
        assertFalse(TempFileManager.isZipFile(zip));
    }

    /** 生成一个单卷外部包（partCount=1），用于解密取消测试。 */
    private File createSinglePartPackage(String fileName) throws Exception {
        File source = temporaryFolder.newFile(fileName);
        byte[] content = new byte[1024 * 1024];
        new java.util.Random(42).nextBytes(content);
        Files.write(source.toPath(), content);

        File packageDirectory = temporaryFolder.newFolder("pkg-" + fileName);
        List<File> parts = ExternalVolumeWriter.write(
                source, false, fileName, false, null, packageDirectory,
                "secure_file_20260101_000000", keys.getPrivate(), keys.getPublic(),
                50L * 1024 * 1024, null);
        assertTrue(parts.size() == 1);
        return parts.get(0);
    }
}
