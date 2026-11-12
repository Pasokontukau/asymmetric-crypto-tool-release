package crypto.volume;

import crypto.file.FileFormatException;

/** VOLU 容器版本不是当前支持的 v1。 */
public class UnsupportedVolumeVersionException extends FileFormatException {

    private final int version;

    public UnsupportedVolumeVersionException(int version) {
        super("不支持的 VOLU 分卷版本: " + version + "（当前支持 v1）");
        this.version = version;
    }

    public int getVersion() {
        return version;
    }
}
