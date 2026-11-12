package core.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystemException;
import org.junit.Test;

/** 写入失败的用户提示：磁盘不足、路径过长、权限不足与文件占用要能被识别，其他异常保留原始信息。 */
public class FileOperationErrorsTest {

    @Test
    public void detectsChineseDiskFullMessage() {
        IOException error = new IOException("磁盘空间不足。");
        assertTrue(FileOperationErrors.isDiskFull(error));
        assertEquals("磁盘空间不足，请清理磁盘后重试。", FileOperationErrors.userMessage(error));
    }

    @Test
    public void detectsEnglishDiskFullInCauseChain() {
        Exception error = new Exception("写文件失败",
                new IOException("There is not enough space on the disk"));
        assertTrue(FileOperationErrors.isDiskFull(error));
        assertFalse(FileOperationErrors.isPathTooLong(error));
    }

    @Test
    public void detectsChinesePathTooLongMessage() {
        IOException error = new IOException("C:\\out\\x.aegix (文件名或扩展名太长。)");
        assertTrue(FileOperationErrors.isPathTooLong(error));
        assertTrue(FileOperationErrors.userMessage(error).contains("路径过长"));
    }

    @Test
    public void detectsPathTooLongReasonOnFileSystemException() {
        FileSystemException error = new FileSystemException(
                "C:\\out\\x.aegix", null, "文件名或扩展名太长。");
        assertTrue(FileOperationErrors.isPathTooLong(error));
    }

    @Test
    public void detectsAccessDeniedMessage() {
        IOException error = new IOException("C:\\out (拒绝访问。)");
        assertTrue(FileOperationErrors.isAccessDenied(error));
        assertEquals("没有写入权限，请改选可写的输出目录，或检查文件是否被其他程序占用。",
                FileOperationErrors.userMessage(error));
    }

    @Test
    public void detectsFileInUseReasonOnFileSystemException() {
        FileSystemException error = new FileSystemException(
                "C:\\out\\x.aegix", null, "另一个程序正在使用此文件，进程无法访问。");
        assertTrue(FileOperationErrors.isFileInUse(error));
        assertEquals("目标文件正在被其他程序使用，请关闭后重试。",
                FileOperationErrors.userMessage(error));
    }

    @Test
    public void keepsUnknownErrorMessage() {
        IOException error = new IOException("设备未就绪。");
        assertFalse(FileOperationErrors.isDiskFull(error));
        assertFalse(FileOperationErrors.isPathTooLong(error));
        assertFalse(FileOperationErrors.isAccessDenied(error));
        assertEquals("设备未就绪。", FileOperationErrors.userMessage(error));
    }

    @Test
    public void emptyMessageFallsBackToGenericAdvice() {
        assertEquals("文件操作失败，请检查磁盘和权限后重试。",
                FileOperationErrors.userMessage(new IOException()));
    }
}
