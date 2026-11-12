package crypto.key;

import core.util.ValidationUtils.ValidationResult;
import core.util.ValidationUtils;
import java.nio.ByteBuffer;
import java.security.Key;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class KeyUtils {
    
    // 使用标准Base64编码器，确保跨系统兼容性
    private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();
    private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();
    
    /**
     * 将int转换为byte数组（大端序）
     * @param value int值
     * @return byte数组
     */
    public static byte[] intToBytes(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }
    
    /**
     * 从byte数组中读取int值（大端序）
     * @param bytes byte数组
     * @param offset 偏移量
     * @return int值
     */
    public static int bytesToInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).getInt();
    }
    
    public static String encodeBase64(byte[] data) {
        // 参数验证
        ValidationUtils.notNullOrEmpty(data, "data");
        
        return BASE64_ENCODER.encodeToString(data);
    }
    
    /**
     * 解码Base64字符串，支持标准Base64和URL安全Base64编码
     * @param data Base64编码的字符串
     * @return 解码后的字节数组
     */
    public static byte[] decodeBase64(String data) {
        // 参数验证
        ValidationUtils.notNullOrEmpty(data, "data");
        
        // 首先尝试标准Base64解码
        try {
            return BASE64_DECODER.decode(data);
        } catch (IllegalArgumentException e) {
            // 如果失败，尝试URL安全Base64解码
            return Base64.getUrlDecoder().decode(data);
        }
    }
    
    public static PublicKey getPublicKeyFromBytes(byte[] keyBytes) throws Exception {
        // 参数验证
        ValidationUtils.notNullOrEmpty(keyBytes, "keyBytes");
        
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePublic(keySpec);
    }
    
    public static PrivateKey getPrivateKeyFromBytes(byte[] keyBytes) throws Exception {
        // 参数验证
        ValidationUtils.notNullOrEmpty(keyBytes, "keyBytes");
        
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    }
    
    public static String generateKeyFingerprint(Key key) throws Exception {
        // 参数验证
        ValidationUtils.notNull(key, "key");
        
        byte[] keyBytes = key.getEncoded();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(keyBytes);
        
        StringBuilder fingerprint = new StringBuilder();
        for (int i = 0; i < Math.min(16, hash.length); i++) {
            fingerprint.append(String.format("%02x", hash[i]));
        }
        return fingerprint.toString();
    }
    
    public static String generateKeyFingerprint(String base64Key) throws Exception {
        // 参数验证
        ValidationUtils.notNullOrEmpty(base64Key, "base64Key");
        
        byte[] keyBytes = decodeBase64(base64Key);
        return generateKeyFingerprint(getPublicKeyFromBytes(keyBytes));
    }
    
    /**
     * 从RSA公钥字节中解析密钥长度
     * @param publicKeyBytes RSA公钥字节
     * @return 密钥长度（位）
     * @throws Exception 解析失败时抛出异常
     */
    public static int getRSAPublicKeySize(byte[] publicKeyBytes) throws Exception {
        // 参数验证
        ValidationUtils.notNullOrEmpty(publicKeyBytes, "publicKeyBytes");
        
        // 使用PublicKey对象获取密钥长度
        PublicKey publicKey = getPublicKeyFromBytes(publicKeyBytes);
        return ((java.security.interfaces.RSAPublicKey) publicKey).getModulus().bitLength();
    }
    
    /**
     * 检查RSA密钥强度是否符合安全标准
     * @param keySize 密钥长度（位）
     * @return true表示密钥强度符合标准，false表示不符合
     */
    public static boolean checkRSAKeyStrength(int keySize) {
        // 安全标准：RSA密钥长度至少为2048位
        return keySize >= 2048;
    }
    
    /**
     * 检查RSA密钥强度是否符合安全标准，并返回详细信息
     * @param keySize 密钥长度（位）
     * @return ValidationResult，isValid=true 表示符合标准
     */
    public static core.util.ValidationUtils.ValidationResult checkRSAKeyStrengthDetail(int keySize) {
        if (keySize < 2048) {
            return core.util.ValidationUtils.ValidationResult.fail(
                "RSA密钥长度必须至少为2048位，当前长度为" + keySize + "位");
        } else if (keySize < 4096) {
            return core.util.ValidationUtils.ValidationResult.fail(
                "警告：RSA密钥长度推荐为4096位，当前长度为" + keySize + "位");
        }
        return core.util.ValidationUtils.ValidationResult.ok();
    }
}