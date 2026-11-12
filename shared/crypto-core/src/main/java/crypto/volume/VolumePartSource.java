package crypto.volume;

import java.io.IOException;
import java.io.InputStream;

/**
 * 外部分卷写入器读取源字节的抽象。
 *
 * <p>Android 的原文件来自 SAF，可能没有可直接定位的 {@code File}；桌面端和 ZIP 则是普通文件。
 * 写入器每次只要求一个“从指定偏移开始”的流，不把整个源文件读进内存。实现方应尽量使用
 * 可定位的文件描述符；无法定位时至少保证返回的流从 {@code offset} 开始。</p>
 *
 * <p>这个接口不携带文件名或 URI，避免把隐私信息带进共享内核。</p>
 */
public interface VolumePartSource {

    /** 源文件总字节数。 */
    long length() throws IOException;

    /**
     * 源文件最后修改时间；未知时返回负数。
     *
     * <p>写入器只在返回值非负时用它检测“写卷期间源文件被修改”。</p>
     */
    default long lastModified() {
        return -1L;
    }

    /** 打开一个从 {@code offset} 开始的新流；调用方负责关闭。 */
    InputStream openAt(long offset) throws IOException;
}
