package securecomm.ui.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import core.util.FileOperationErrors;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 加密产物写入失败时不能留下半成品。 */
public class AtomicFileOutputTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @After
    public void clearFaultInjection() {
        System.clearProperty(AtomicFileOutput.FAIL_WRITE_PROPERTY);
    }

    @Test
    public void writesAndRenamesOnSuccess() throws Exception {
        File directory = temporaryFolder.newFolder("out-ok");

        File result = AtomicFileOutput.write(directory, "x.aegix",
                output -> output.write("hello".getBytes(StandardCharsets.UTF_8)));

        assertEquals(new File(directory, "x.aegix"), result);
        assertEquals("hello", new String(
                Files.readAllBytes(result.toPath()), StandardCharsets.UTF_8));
        assertEquals(1, directory.listFiles().length);
    }

    @Test
    public void diskFullLeavesNoTargetOrTemporaryFile() throws Exception {
        File directory = temporaryFolder.newFolder("out-full");
        System.setProperty(AtomicFileOutput.FAIL_WRITE_PROPERTY, "disk-full");

        try {
            AtomicFileOutput.write(directory, "x.aegix", output -> {
                byte[] chunk = new byte[4096];
                for (int i = 0; i < 8; i++) {
                    output.write(chunk);
                }
            });
            fail("磁盘不足时应该抛出异常");
        } catch (Exception error) {
            assertTrue(FileOperationErrors.isDiskFull(error));
            assertEquals("磁盘空间不足，请清理磁盘后重试。",
                    FileOperationErrors.userMessage(error));
        }

        assertFalse(new File(directory, "x.aegix").exists());
        assertArrayEquals(new File[0], directory.listFiles());
    }

    @Test
    public void occupiedTargetLeavesNoTargetOrTemporaryFile() throws Exception {
        File directory = temporaryFolder.newFolder("out-in-use");
        System.setProperty(AtomicFileOutput.FAIL_WRITE_PROPERTY, "file-in-use");

        try {
            AtomicFileOutput.write(directory, "x.aegix",
                    output -> output.write("hello".getBytes(StandardCharsets.UTF_8)));
            fail("目标文件被占用时应该抛出异常");
        } catch (Exception error) {
            assertTrue(FileOperationErrors.isFileInUse(error));
            assertEquals("目标文件正在被其他程序使用，请关闭后重试。",
                    FileOperationErrors.userMessage(error));
        }

        assertFalse(new File(directory, "x.aegix").exists());
        assertArrayEquals(new File[0], directory.listFiles());
    }

    @Test
    public void overlongDirectoryComponentFailsBeforeCreatingAnything() throws Exception {
        File parent = temporaryFolder.newFolder("out-long-parent");
        StringBuilder component = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            component.append('d');
        }
        File directory = new File(parent, component.toString());

        try {
            AtomicFileOutput.write(directory, "x.aegix", output -> output.write(1));
            fail("超长路径应该被拒绝");
        } catch (IOException error) {
            assertTrue(FileOperationErrors.isPathTooLong(error));
        }

        assertFalse(directory.exists());
        assertArrayEquals(new File[0], parent.listFiles());
    }

    @Test
    public void overlongFileNameIsRejected() throws Exception {
        File directory = temporaryFolder.newFolder("out-long-name");
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            name.append('f');
        }

        try {
            AtomicFileOutput.write(directory, name + ".aegix", output -> output.write(1));
            fail("超长文件名应该被拒绝");
        } catch (IOException error) {
            assertTrue(FileOperationErrors.isPathTooLong(error));
        }

        assertArrayEquals(new File[0], directory.listFiles());
    }

    @Test
    public void writerFailureLeavesNoTargetOrTemporaryFile() throws Exception {
        File directory = temporaryFolder.newFolder("out-fail");

        try {
            AtomicFileOutput.write(directory, "x.aegix", output -> {
                throw new IOException("boom");
            });
            fail("写入异常时应该继续抛出");
        } catch (IOException expected) {
            assertEquals("boom", expected.getMessage());
        }

        assertFalse(new File(directory, "x.aegix").exists());
        assertArrayEquals(new File[0], directory.listFiles());
    }

    /** 写了一半再失败（取消/磁盘问题）时，临时文件必须被删掉，目标文件不能出现。 */
    @Test
    public void partialWriteThenFailureLeavesNoHalfFile() throws Exception {
        File directory = temporaryFolder.newFolder("out-partial");

        try {
            AtomicFileOutput.write(directory, "x.aegix", output -> {
                output.write(new byte[4096]);
                throw new IOException("cancelled");
            });
            fail("写入中途失败时应该抛出异常");
        } catch (IOException expected) {
            assertEquals("cancelled", expected.getMessage());
        }

        assertFalse(new File(directory, "x.aegix").exists());
        assertArrayEquals(new File[0], directory.listFiles());
    }
}
