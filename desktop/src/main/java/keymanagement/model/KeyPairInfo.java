package keymanagement.model;

import java.time.Instant;
import java.util.Date;

public class KeyPairInfo {
    private static final long DEFAULT_KEY_EXPIRY_MS = crypto.config.CryptoConfig.DEFAULT_KEY_EXPIRY_MS;
    private static final long EXPIRING_SOON_DAYS = crypto.config.CryptoConfig.EXPIRING_SOON_DAYS;

    private String name;
    private Instant createTimeInstant;
    private Instant expireTimeInstant;
    private String algorithm;
    private int keySize;
    private String publicKey;
    private String privateKeyPath;
    private byte[] encryptedSalt; // 我的 salt（用私钥密码 AES-GCM 加密后存储）
    
    // 保持向后兼容的构造方法
    public KeyPairInfo(String name, Date createTime, String algorithm, int keySize, String publicKey, String privateKeyPath) {
        this(name, createTime.toInstant(), algorithm, keySize, publicKey, privateKeyPath);
    }
    
    // 保持向后兼容的构造方法（带过期时间）
    public KeyPairInfo(String name, Date createTime, Date expireTime, String algorithm, int keySize, String publicKey, String privateKeyPath) {
        this(name, createTime.toInstant(), expireTime.toInstant(), algorithm, keySize, publicKey, privateKeyPath);
    }
    
    // 新增：使用java.time.Instant的构造方法
    public KeyPairInfo(String name, Instant createTime, String algorithm, int keySize, String publicKey, String privateKeyPath) {
        this.name = name;
        this.createTimeInstant = createTime;
        // 默认过期时间为6个月后
        this.expireTimeInstant = createTime.plusMillis(DEFAULT_KEY_EXPIRY_MS);
        this.algorithm = algorithm;
        this.keySize = keySize;
        this.publicKey = publicKey;
        this.privateKeyPath = privateKeyPath;
    }
    
    // 使用Instant的完整构造方法
    public KeyPairInfo(String name, Instant createTime, Instant expireTime, String algorithm, int keySize, String publicKey, String privateKeyPath) {
        this.name = name;
        this.createTimeInstant = createTime;
        this.expireTimeInstant = expireTime;
        this.algorithm = algorithm;
        this.keySize = keySize;
        this.publicKey = publicKey;
        this.privateKeyPath = privateKeyPath;
    }
    
    // Getters and setters - 保持向后兼容性
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    // 向后兼容的Date getter/setter
    public Date getCreateTime() { return Date.from(createTimeInstant); }
    public void setCreateTime(Date createTime) { 
        this.createTimeInstant = createTime.toInstant();
    }
    
    public Date getExpireTime() { return Date.from(expireTimeInstant); }
    public void setExpireTime(Date expireTime) { 
        this.expireTimeInstant = expireTime.toInstant();
    }
    
    // java.time的getter/setter
    public Instant getCreateTimeInstant() { return createTimeInstant; }
    public void setCreateTimeInstant(Instant createTimeInstant) {
        this.createTimeInstant = createTimeInstant;
    }
    
    public Instant getExpireTimeInstant() { return expireTimeInstant; }
    public void setExpireTimeInstant(Instant expireTimeInstant) {
        this.expireTimeInstant = expireTimeInstant;
    }
    
    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
    public int getKeySize() { return keySize; }
    public void setKeySize(int keySize) { this.keySize = keySize; }
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    public String getPrivateKeyPath() { return privateKeyPath; }
    public void setPrivateKeyPath(String privateKeyPath) { this.privateKeyPath = privateKeyPath; }

    public byte[] getEncryptedSalt() { return encryptedSalt; }
    public void setEncryptedSalt(byte[] encryptedSalt) { this.encryptedSalt = encryptedSalt; }
    
    /**
     * 检查密钥对是否已过期
     * @return true表示已过期，false表示未过期
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expireTimeInstant);
    }
    
    /**
     * 检查密钥对是否即将过期（剩余时间少于30天）
     * @return true表示即将过期，false表示未即将过期
     */
    public boolean isExpiringSoon() {
        long remainingDays = java.time.Duration.between(Instant.now(), expireTimeInstant).toDays();
        return remainingDays < EXPIRING_SOON_DAYS;
    }
}
