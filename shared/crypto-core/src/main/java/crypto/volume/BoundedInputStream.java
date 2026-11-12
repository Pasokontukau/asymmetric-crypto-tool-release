package crypto.volume;

import java.io.IOException;
import java.io.InputStream;

/**
 * 读取上限为 [limit] 的输入流；`close()` **不关闭底层流**，因为同一个底层流还要
 * 继续读取下一卷。调用方在全部卷结束后关闭真正的输入流。
 */
final class BoundedInputStream extends InputStream {

    private final InputStream delegate;
    private long remaining;

    BoundedInputStream(InputStream delegate, long limit) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate 不能为空");
        }
        if (limit < 0) {
            throw new IllegalArgumentException("limit 不能为负");
        }
        this.delegate = delegate;
        this.remaining = limit;
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) return -1;
        int value = delegate.read();
        if (value >= 0) remaining--;
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (remaining <= 0) return -1;
        int requested = (int) Math.min((long) length, remaining);
        int read = delegate.read(buffer, offset, requested);
        if (read > 0) remaining -= read;
        return read;
    }

    @Override
    public long skip(long count) throws IOException {
        if (remaining <= 0 || count <= 0) return 0;
        long skipped = delegate.skip(Math.min(count, remaining));
        if (skipped > 0) remaining -= skipped;
        return skipped;
    }

    @Override
    public int available() throws IOException {
        long available = delegate.available();
        return (int) Math.min(available, Math.min(remaining, Integer.MAX_VALUE));
    }

    @Override
    public void close() {
        // 刻意不关闭底层流；由 VolumeCryptoEngine 在所有卷处理完后关闭。
    }

    long remaining() {
        return remaining;
    }

    boolean isFullyConsumed() {
        return remaining == 0;
    }
}
