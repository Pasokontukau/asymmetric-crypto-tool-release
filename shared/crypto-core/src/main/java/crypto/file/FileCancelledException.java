package crypto.file;

import java.io.IOException;

/** 用户取消或线程被中断。 */
public class FileCancelledException extends IOException {

    public FileCancelledException(String message) {
        super(message);
    }
}
