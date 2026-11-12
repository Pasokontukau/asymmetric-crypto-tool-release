package securecomm.ui.util;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 加密产物先写同目录临时文件，成功后再改名到最终文件。
 *
 * <p>直接写最终 {@code .aegix} 时，磁盘不足或进程中断会留下半成品；
 * 这里失败只删除临时文件，目标文件要么不存在，要么已经是完整内容。</p>
 */
public final class AtomicFileOutput {

    public interface Writer {
        void writeTo(OutputStream output) throws Exception;
    }

    /**
     * 仅用于真实 Swing 验收：显式设置该属性时，在写入 1 KiB 后模拟磁盘不足。
     * 正常启动不会设置它，不影响用户行为。
     */
    static final String FAIL_WRITE_PROPERTY = "aegix.test.failWrite";
    private static final String FAIL_WRITE_DISK_FULL = "disk-full";
    private static final String FAIL_WRITE_FILE_IN_USE = "file-in-use";
    private static final int FAIL_AFTER_BYTES = 1024;
    private static final int MAX_NAME_CHARS = 255;
    private static final String PATH_TOO_LONG_MESSAGE =
            "文件名或路径过长，请缩短文件名或改用更短的输出目录（Windows 单个路径最多 260 个字符）。";

    private AtomicFileOutput() {
    }

    public static File write(File directory, String fileName, Writer writer) throws Exception {
        if (directory == null) {
            throw new IOException("输出目录不能为空");
        }
        Path directoryPath = directory.toPath().toAbsolutePath();
        validatePath(directoryPath, fileName);
        if (!Files.isDirectory(directoryPath)) {
            Files.createDirectories(directoryPath);
        }
        if (!Files.isDirectory(directoryPath)) {
            throw new IOException("输出目录不是文件夹: " + directoryPath);
        }

        File target = new File(directoryPath.toFile(), fileName);
        File temporary = File.createTempFile("aegix-write-", ".tmp", directoryPath.toFile());
        try {
            try (OutputStream raw = new FileOutputStream(temporary);
                 OutputStream output = new BufferedOutputStream(wrapForAcceptanceTest(raw))) {
                writer.writeTo(output);
            }
            failBeforeMoveForAcceptanceTest(target);
            Files.move(temporary.toPath(), target.toPath());
            return target;
        } finally {
            if (temporary.exists()) {
                // 半成品不能留着；原始失败原因比删除失败更重要。
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
        }
    }

    /**
     * Windows 单个文件名/目录名上限 255 个字符；超过时直接给可读提示，
     * 不要等到底层只报“系统找不到指定的路径”。
     */
    private static void validatePath(Path directoryPath, String fileName) throws IOException {
        if (fileName == null || fileName.trim().isEmpty() || fileName.length() > MAX_NAME_CHARS) {
            throw new IOException(PATH_TOO_LONG_MESSAGE);
        }
        for (Path part : directoryPath) {
            if (part.toString().length() > MAX_NAME_CHARS) {
                throw new IOException(PATH_TOO_LONG_MESSAGE);
            }
        }
    }

    private static OutputStream wrapForAcceptanceTest(OutputStream output) {
        if (FAIL_WRITE_DISK_FULL.equals(System.getProperty(FAIL_WRITE_PROPERTY))) {
            return new DiskFullStream(output);
        }
        return output;
    }

    /**
     * 仅用于真实 Swing 验收：模拟目标文件在临时文件写完后被其它进程占用。
     * 正常启动不会设置该属性，不影响用户行为。
     */
    private static void failBeforeMoveForAcceptanceTest(File target) throws IOException {
        if (FAIL_WRITE_FILE_IN_USE.equals(System.getProperty(FAIL_WRITE_PROPERTY))) {
            throw new FileSystemException(
                    target.getAbsolutePath(), null, "另一个程序正在使用此文件，进程无法访问。");
        }
    }

    private static final class DiskFullStream extends OutputStream {
        private final OutputStream delegate;
        private int remaining = FAIL_AFTER_BYTES;

        DiskFullStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            delegate.write(value);
            remaining--;
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            ensureCapacity(length);
            delegate.write(buffer, offset, length);
            remaining -= length;
        }

        private void ensureCapacity(int length) throws IOException {
            if (length > remaining) {
                throw new IOException("There is not enough space on the disk");
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
