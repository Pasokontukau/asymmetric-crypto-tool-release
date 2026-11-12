package securecomm.ui.util;

import java.io.File;

/**
 * 解密结果容器类
 */
public class DecryptResult {
    public boolean isFile = false;
    public File outputFile = null;
    public String plaintext = null;
    public boolean signatureValid = false;
}
