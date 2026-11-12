package crypto.volume;

import crypto.file.FileFormatException;

/** 分卷数量/顺序/大小/SHA-256 等完整性校验失败；调用方必须删除已写出的明文。 */
public class VolumeIntegrityException extends FileFormatException {

    public VolumeIntegrityException(String message) {
        super(message);
    }

    public VolumeIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
