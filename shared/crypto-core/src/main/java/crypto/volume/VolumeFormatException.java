package crypto.volume;

import crypto.file.FileFormatException;

/** VOLU 容器头、VolumeInfo JSON、分卷头/长度字段或卷数结构不合法。 */
public class VolumeFormatException extends FileFormatException {

    public VolumeFormatException(String message) {
        super(message);
    }

    public VolumeFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
