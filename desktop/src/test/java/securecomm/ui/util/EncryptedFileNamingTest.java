package securecomm.ui.util;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 单文件加密产物命名：首个文件不带序号，重名从 `_2` 开始，与 Android 一致。
 * 扩展名只能是 {@code .aegix}。
 */
public class EncryptedFileNamingTest {

    @Test
    public void firstOutputHasNoSequenceSuffix() throws Exception {
        File dir = Files.createTempDirectory("aegix-naming-").toFile();

        String name = EncryptedFileNaming.uniqueName(dir, "secure_file_20260101_000000");

        assertEquals("secure_file_20260101_000000.aegix", name);
    }

    @Test
    public void duplicateOutputStartsAtTwo() throws Exception {
        File dir = Files.createTempDirectory("aegix-naming-").toFile();
        Files.createFile(new File(dir, "secure_file_20260101_000000.aegix").toPath());

        String second = EncryptedFileNaming.uniqueName(dir, "secure_file_20260101_000000");

        assertEquals("secure_file_20260101_000000_2.aegix", second);
        Files.createFile(new File(dir, second).toPath());

        String third = EncryptedFileNaming.uniqueName(dir, "secure_file_20260101_000000");
        assertEquals("secure_file_20260101_000000_3.aegix", third);
    }
}
