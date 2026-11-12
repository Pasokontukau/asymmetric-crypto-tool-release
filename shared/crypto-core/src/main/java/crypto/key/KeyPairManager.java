package crypto.key;

import core.security.MemorySecurityUtils;
import core.util.ValidationUtils.ValidationResult;
import core.util.ValidationUtils;
import crypto.config.CryptoConfig;
import crypto.key.KeyUtils;
import crypto.provider.SecurityProvider;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class KeyPairManager {
    
    // GCM 模式相关常量
    private static final String GCM_ALGORITHM = CryptoConfig.AES_ALGORITHM;
    private static final String CBC_ALGORITHM = CryptoConfig.CBC_ALGORITHM;
    private static final int GCM_IV_LENGTH = CryptoConfig.GCM_IV_LENGTH;
    private static final int GCM_TAG_LENGTH = CryptoConfig.GCM_TAG_LENGTH;
    private static final byte[] GCM_VERSION_MAGIC = new byte[] { 'G', 'C', 'M', '1' };
    
    // PBKDF2 和 AES 常量
    private static final int PBKDF2_ITERATIONS = CryptoConfig.PBKDF2_ITERATIONS;
    private static final int AES_KEY_SIZE = CryptoConfig.AES_KEY_SIZE;
    private static final int SALT_LENGTH = CryptoConfig.SALT_LENGTH;
    
    // 密钥过期时间相关常量
    private static final long ONE_DAY_MS = CryptoConfig.ONE_DAY_MS;
    private static final long DEFAULT_KEY_EXPIRY_MS = CryptoConfig.DEFAULT_KEY_EXPIRY_MS; // 6个月
    private static final long EXPIRING_SOON_DAYS = CryptoConfig.EXPIRING_SOON_DAYS;
    
    public static KeyPair generateKeyPair(int keySize) throws Exception {
        // 参数验证
        ValidationUtils.validRSAKeySize(keySize);
        
        // 检查密钥强度是否符合安全标准
        core.util.ValidationUtils.ValidationResult strengthCheckResult = KeyUtils.checkRSAKeyStrengthDetail(keySize);
        if (!strengthCheckResult.isValid) {
            throw new IllegalArgumentException(strengthCheckResult.errorMessage);
        }
        
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(keySize, SecurityProvider.STRONG_RANDOM);
        return keyGen.generateKeyPair();
    }
    
    /**
     * 使用 char[] 密码加密私钥（更安全）
     * @param privateKeyBytes 私钥字节数组
     * @param password 密码字符数组
     * @return 加密后的字节数组
     */
    public static byte[] encryptPrivateKey(byte[] privateKeyBytes, char[] password) throws Exception {
        // 参数验证
        ValidationUtils.notNullOrEmpty(privateKeyBytes, "privateKeyBytes");
        ValidationUtils.notNullOrEmpty(password, "password");
        
        byte[] salt = null;
        byte[] iv = null;
        SecretKeySpec secretKey = null;
        PBEKeySpec spec = null;
        
        try {
            salt = new byte[SALT_LENGTH];
            SecurityProvider.REGULAR_RANDOM.nextBytes(salt);

            iv = new byte[GCM_IV_LENGTH];
            SecurityProvider.REGULAR_RANDOM.nextBytes(iv);

            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, AES_KEY_SIZE);
            secretKey = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");

            Cipher cipher = Cipher.getInstance(GCM_ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);
            byte[] encryptedPrivateKey = cipher.doFinal(privateKeyBytes);

            // 格式：[magic(4)][salt(16)][iv(12)][encryptedData+tag]
            byte[] result = new byte[GCM_VERSION_MAGIC.length + 16 + GCM_IV_LENGTH + encryptedPrivateKey.length];
            System.arraycopy(GCM_VERSION_MAGIC, 0, result, 0, GCM_VERSION_MAGIC.length);
            System.arraycopy(salt, 0, result, GCM_VERSION_MAGIC.length, 16);
            System.arraycopy(iv, 0, result, GCM_VERSION_MAGIC.length + 16, GCM_IV_LENGTH);
            System.arraycopy(encryptedPrivateKey, 0, result, GCM_VERSION_MAGIC.length + 16 + GCM_IV_LENGTH, encryptedPrivateKey.length);

            return result;
        } finally {
            if (salt != null) MemorySecurityUtils.clearByteArray(salt);
            if (iv != null) MemorySecurityUtils.clearByteArray(iv);
            if (secretKey != null) MemorySecurityUtils.destroySecretKey(secretKey);
            if (spec != null) spec.clearPassword(); // 清除 PBEKeySpec 中的密码
        }
    }
    
    /**
     * 使用 char[] 密码解密私钥（更安全）
     * @param encryptedPrivateKeyData 加密的私钥数据
     * @param password 密码字符数组
     * @return 解密后的私钥字节数组
     */
    public static byte[] decryptPrivateKey(byte[] encryptedPrivateKeyData, char[] password) throws Exception {
        // 参数验证
        ValidationUtils.notNullOrEmpty(encryptedPrivateKeyData, "encryptedPrivateKeyData");
        ValidationUtils.notNullOrEmpty(password, "password");
        
        // 验证数据长度
        if (encryptedPrivateKeyData.length < 32) {
            throw new IllegalArgumentException("加密数据长度不足，至少需要 32 字节");
        }
        
        byte[] salt = null;
        byte[] iv = null;
        SecretKeySpec secretKey = null;
        PBEKeySpec spec = null;
        
        try {
            boolean isGcmFormat = encryptedPrivateKeyData.length >= GCM_VERSION_MAGIC.length &&
                    Arrays.equals(Arrays.copyOf(encryptedPrivateKeyData, GCM_VERSION_MAGIC.length), GCM_VERSION_MAGIC);

            if (isGcmFormat) {
                salt = new byte[SALT_LENGTH];
                System.arraycopy(encryptedPrivateKeyData, GCM_VERSION_MAGIC.length, salt, 0, SALT_LENGTH);

                iv = new byte[GCM_IV_LENGTH];
                System.arraycopy(encryptedPrivateKeyData, GCM_VERSION_MAGIC.length + SALT_LENGTH, iv, 0, GCM_IV_LENGTH);

                int encryptedDataOffset = GCM_VERSION_MAGIC.length + SALT_LENGTH + GCM_IV_LENGTH;
                byte[] encryptedPrivateKey = new byte[encryptedPrivateKeyData.length - encryptedDataOffset];
                System.arraycopy(encryptedPrivateKeyData, encryptedDataOffset, encryptedPrivateKey, 0, encryptedPrivateKey.length);

                SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, AES_KEY_SIZE);
                secretKey = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");

                Cipher cipher = Cipher.getInstance(GCM_ALGORITHM);
                GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
                cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

                return cipher.doFinal(encryptedPrivateKey);
            } else {
                salt = new byte[SALT_LENGTH];
                System.arraycopy(encryptedPrivateKeyData, 0, salt, 0, SALT_LENGTH);

                iv = new byte[SALT_LENGTH]; // CBC 模式 IV 也是 16 字节
                System.arraycopy(encryptedPrivateKeyData, SALT_LENGTH, iv, 0, SALT_LENGTH);

                byte[] encryptedPrivateKey = new byte[encryptedPrivateKeyData.length - SALT_LENGTH * 2];
                System.arraycopy(encryptedPrivateKeyData, SALT_LENGTH * 2, encryptedPrivateKey, 0, encryptedPrivateKey.length);

                SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, AES_KEY_SIZE);
                secretKey = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");

                Cipher cipher = Cipher.getInstance(CBC_ALGORITHM);
                cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));

                return cipher.doFinal(encryptedPrivateKey);
            }
        } finally {
            if (salt != null) MemorySecurityUtils.clearByteArray(salt);
            if (iv != null) MemorySecurityUtils.clearByteArray(iv);
            if (secretKey != null) MemorySecurityUtils.destroySecretKey(secretKey);
            if (spec != null) spec.clearPassword(); // 清除 PBEKeySpec 中的密码
        }
    }
    
    /**
     * 验证密码是否正确
     * @param encryptedPrivateKeyData 加密的私钥数据
     * @param password 密码字符数组
     * @return true表示密码正确，false表示密码错误
     */
    public static boolean verifyPassword(byte[] encryptedPrivateKeyData, char[] password) {
        try {
            // 尝试解密私钥验证密码
            decryptPrivateKey(encryptedPrivateKeyData, password);
            return true;
        } catch (Exception e) {
            // 任何异常都表示密码错误或数据损坏
            return false;
        }
    }
}