package crypto.cipher;

import crypto.config.CryptoConfig;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * RSA-OAEP 参数兼容层。
 *
 * <p>桌面端（JDK SunJCE）使用算法串
 * {@code RSA/ECB/OAEPWithSHA-256AndMGF1Padding} 时，MGF1 的默认摘要为 <b>SHA-1</b>；
 * Android Conscrypt 在相同算法串下使用 <b>SHA-256</b> 作为 MGF1 摘要，导致两端
 * 互相无法解包 AES 密钥（实测：桌面密文在设备上报
 * {@code OAEP_DECODING_ERROR}）。</p>
 *
 * <p>因此 Android 端显式指定与桌面端一致的参数：
 * 主摘要 SHA-256、MGF1-SHA1、空 PSource。该参数只影响 RSA 解包，
 * 不改变 AES-GCM 或消息序列化格式。</p>
 */
public final class OaepCompat {

    private static final OAEPParameterSpec OAEP_SHA256_MGF1_SHA1 = new OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA1,
            PSource.PSpecified.DEFAULT);

    private OaepCompat() {
    }

    public static Cipher encryptCipher(PublicKey publicKey) throws Exception {
        Cipher cipher = Cipher.getInstance(CryptoConfig.RSA_CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP_SHA256_MGF1_SHA1);
        return cipher;
    }

    public static Cipher decryptCipher(PrivateKey privateKey) throws Exception {
        Cipher cipher = Cipher.getInstance(CryptoConfig.RSA_CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, privateKey, OAEP_SHA256_MGF1_SHA1);
        return cipher;
    }
}
