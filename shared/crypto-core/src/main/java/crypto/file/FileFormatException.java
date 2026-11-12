package crypto.file;

import java.io.IOException;

/** 文件头、长度字段或密文结构不合法。 */
public class FileFormatException extends IOException {

    public FileFormatException(String message) {
        super(message);
    }

    public FileFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
