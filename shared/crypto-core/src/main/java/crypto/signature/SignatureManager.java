package crypto.signature;

import core.util.ValidationUtils;
import crypto.config.CryptoConfig;
import crypto.key.KeyUtils;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

public class SignatureManager {
    
    private static final String SIGNATURE_ALGORITHM = CryptoConfig.SIGNATURE_ALGORITHM;
    
    /**
     * 对数据进行签名，返回 Base64 编码的签名
     * @see #signBytes(byte[], PrivateKey)
     */
    public static String sign(byte[] data, PrivateKey privateKey) throws Exception {
        return KeyUtils.encodeBase64(signBytes(data, privateKey));
    }
    
    /**
     * 验证 Base64 编码的签名
     * @see #verifyBytes(byte[], byte[], PublicKey)
     */
    public static boolean verify(byte[] data, String signatureString, PublicKey publicKey) throws Exception {
        return verifyBytes(data, KeyUtils.decodeBase64(signatureString), publicKey);
    }
    
    public static String signText(String text, PrivateKey privateKey) throws Exception {
        ValidationUtils.notNullOrEmpty(text, "text");
        ValidationUtils.notNull(privateKey, "privateKey");
        
        return sign(text.getBytes(StandardCharsets.UTF_8), privateKey);
    }
    
    public static boolean verifyText(String text, String signatureString, PublicKey publicKey) throws Exception {
        ValidationUtils.notNullOrEmpty(text, "text");
        ValidationUtils.notNullOrEmpty(signatureString, "signatureString");
        ValidationUtils.notNull(publicKey, "publicKey");
        
        return verify(text.getBytes(StandardCharsets.UTF_8), signatureString, publicKey);
    }
    
    public static byte[] signBytes(byte[] data, PrivateKey privateKey) throws Exception {
        ValidationUtils.notNullOrEmpty(data, "data");
        ValidationUtils.notNull(privateKey, "privateKey");
        
        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initSign(privateKey);
        signature.update(data);
        return signature.sign();
    }
    
    public static boolean verifyBytes(byte[] data, byte[] signatureBytes, PublicKey publicKey) throws Exception {
        ValidationUtils.notNullOrEmpty(data, "data");
        ValidationUtils.notNullOrEmpty(signatureBytes, "signatureBytes");
        ValidationUtils.notNull(publicKey, "publicKey");
        
        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initVerify(publicKey);
        signature.update(data);
        return signature.verify(signatureBytes);
    }
}
