package crypto.file;

import java.security.PublicKey;

/**
 * 单文件解密结果。
 *
 * <p>GCM 认证失败会抛异常，不会产生本对象；签名不匹配只标记
 * {@link #isSignatureValid()} 为 false，调用方决定是否保留明文并提示“发送者未识别”。</p>
 */
public final class FileDecryptResult {

    private final String originalFileName;
    private final boolean signatureValid;
    private final PublicKey matchedSender;
    private final long plaintextBytes;

    public FileDecryptResult(
            String originalFileName,
            boolean signatureValid,
            PublicKey matchedSender,
            long plaintextBytes
    ) {
        this.originalFileName = originalFileName;
        this.signatureValid = signatureValid;
        this.matchedSender = matchedSender;
        this.plaintextBytes = plaintextBytes;
    }

    /** 文件内加密保存的原始文件名；无法解开时为 null。 */
    public String getOriginalFileName() {
        return originalFileName;
    }

    public boolean isSignatureValid() {
        return signatureValid;
    }

    /** 验签成功的发送者公钥；未识别时为 null。 */
    public PublicKey getMatchedSender() {
        return matchedSender;
    }

    public long getPlaintextBytes() {
        return plaintextBytes;
    }
}
