package crypto.config;

import crypto.config.CryptoConfig;



public final class CryptoConfig {
    
    // 缓冲区大小
    public static final int BUFFER_SIZE = 8192;
    
    // PBKDF2 配置
    public static final int PBKDF2_ITERATIONS = 65536;
    public static final int AES_KEY_SIZE = 256;
    
    // GCM 模式配置
    public static final int GCM_IV_LENGTH = 12;
    public static final int GCM_TAG_LENGTH = 16;
    
    // 盐长度
    public static final int SALT_LENGTH = 16;
    
    // 密钥过期时间配置
    public static final long ONE_DAY_MS = 24 * 60 * 60 * 1000L;
    public static final long DEFAULT_KEY_EXPIRY_MS = 6 * 30 * ONE_DAY_MS; // 6个月
    public static final long EXPIRING_SOON_DAYS = 30;
    
    // 签名算法
    public static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    
    // AES算法
    public static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    public static final String CBC_ALGORITHM = "AES/CBC/PKCS5Padding";
    
    // RSA算法
    public static final String RSA_ALGORITHM = "RSA";
    public static final String RSA_CIPHER = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    
    private CryptoConfig() {
        // 禁止实例化
    }
}
