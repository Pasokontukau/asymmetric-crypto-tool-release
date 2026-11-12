package core.util;

import java.io.IOException;
import java.io.InputStream;

public class IOUtils {

    private IOUtils() {
        // 禁止实例化
    }

    /**
     * 从输入流中完全读取指定长度的字节到缓冲区
     * @param in 输入流
     * @param buffer 目标缓冲区
     * @throws IOException 如果读取过程中发生 I/O 错误或流意外结束
     */
    public static void readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read < 0) {
                throw new IOException("Unexpected end of stream");
            }
            total += read;
        }
    }
}