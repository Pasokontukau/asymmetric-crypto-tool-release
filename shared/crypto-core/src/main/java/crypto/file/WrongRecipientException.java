package crypto.file;

import java.io.IOException;

/** 当前私钥解不开文件里的 AES 密钥，说明文件不是加密给本机密钥的。 */
public class WrongRecipientException extends IOException {

    public WrongRecipientException(String message) {
        super(message);
    }

    public WrongRecipientException(String message, Throwable cause) {
        super(message, cause);
    }
}
