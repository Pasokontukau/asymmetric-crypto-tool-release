package contacts.model;

public class Contact {
    private String name;
    private String publicKey;
    private String algorithm;
    private String fingerprint;
    private String boundKeyPairName; // 绑定的密钥对名称
    private long lastUsedTime;       // 最后使用时间（毫秒时间戳）
    private byte[] salt;             // 联系人专属 salt（用于 seed0 = SHA256(salt)）
    
    public Contact(String name, String publicKey, String algorithm, String fingerprint) {
        this(name, publicKey, algorithm, fingerprint, null);
    }
    
    public Contact(String name, String publicKey, String algorithm, String fingerprint, String boundKeyPairName) {
        this.name = name;
        this.publicKey = publicKey;
        this.algorithm = algorithm;
        this.fingerprint = fingerprint;
        this.boundKeyPairName = boundKeyPairName;
        this.lastUsedTime = 0; // 默认从未使用过
    }
    
    // Getters and setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public String getBoundKeyPairName() { return boundKeyPairName; }
    public void setBoundKeyPairName(String boundKeyPairName) { this.boundKeyPairName = boundKeyPairName; }
    public long getLastUsedTime() { return lastUsedTime; }
    public void setLastUsedTime(long lastUsedTime) { this.lastUsedTime = lastUsedTime; }

    // --- Phase 2: 隐写专属 salt（seed0 = SHA256(salt)） ---
    public byte[] getSalt() { return salt; }
    public void setSalt(byte[] salt) { this.salt = salt; }
}
