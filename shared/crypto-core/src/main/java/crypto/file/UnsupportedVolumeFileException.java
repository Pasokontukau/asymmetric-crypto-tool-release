package crypto.file;

/**
 * 检测到桌面端分卷容器（VOLU）时抛出。
 *
 * <p>当前阶段只实现单文件 .enc；分卷容器需要读取 VolumeInfo 并按分卷长度拆分，
 * 留到下一阶段，不能把它误当成普通单文件解析。</p>
 */
public class UnsupportedVolumeFileException extends FileFormatException {

    public UnsupportedVolumeFileException(String message) {
        super(message);
    }
}
