package keymanagement.service;

import core.security.MemorySecurityUtils;
import crypto.config.CryptoConfig;
import crypto.key.KeyPairManager;
import crypto.key.KeyUtils;
import crypto.provider.SecurityProvider;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import keymanagement.model.KeyPairInfo;
import keymanagement.storage.KeyPairStorage;

/**
 * 桌面端密钥备份：与 Android 共用 `aegix-key-bundle` 文本格式。
 *
 * <p>外层是 {@link Properties} 文本，公钥与身份元数据明文保存；内层
 * `privateKeyBackup` 是共享内核 GCM1 口令加密块的 Base64。导入时只信任私钥：
 * 重新推导公钥并与备份中的公钥逐字节比较，不一致就拒绝。</p>
 */
public final class KeyBackupService {

    public static final String FORMAT = "aegix-key-bundle";
    public static final int MIN_PASSWORD_LENGTH = 8;
    public static final int MAX_BACKUP_BYTES = 256 * 1024;
    private static final String VERSION = "1";
    private static final int MAX_NAME_CHARS = 64;

    private KeyBackupService() {
    }

    /** 只用于导入前的界面提示；不代表口令一定正确。 */
    public static final class Preview {
        private final String name;
        private final String publicKey;
        private final String fingerprint;
        private final int keySize;
        private final long createdAt;

        Preview(String name, String publicKey, String fingerprint, int keySize, long createdAt) {
            this.name = name;
            this.publicKey = publicKey;
            this.fingerprint = fingerprint;
            this.keySize = keySize;
            this.createdAt = createdAt;
        }

        public String getName() {
            return name;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public String getFingerprint() {
            return fingerprint;
        }

        public int getKeySize() {
            return keySize;
        }

        public long getCreatedAt() {
            return createdAt;
        }
    }

    /** 导出完整备份文本；不写文件，由界面选择保存位置。 */
    public static String exportBundle(
            KeyPairInfo info,
            PrivateKey privateKey,
            char[] backupPassword
    ) throws Exception {
        if (info == null || privateKey == null) {
            throw new IllegalArgumentException("密钥对或私钥为空");
        }
        requirePassword(backupPassword);

        PublicKey derivedPublic = derivePublicKey(privateKey);
        String publicKeyBase64 = Base64.getEncoder().encodeToString(derivedPublic.getEncoded());
        if (!publicKeyBase64.equals(info.getPublicKey())) {
            throw new IllegalArgumentException("私钥与当前公钥不匹配，无法导出备份");
        }

        byte[] encrypted = KeyPairManager.encryptPrivateKey(
                privateKey.getEncoded(), backupPassword);
        try {
            Properties properties = new Properties();
            properties.setProperty("format", FORMAT);
            properties.setProperty("version", VERSION);
            properties.setProperty("name", info.getName());
            properties.setProperty("publicKey", publicKeyBase64);
            properties.setProperty("fingerprint",
                    KeyUtils.generateKeyFingerprint(derivedPublic));
            properties.setProperty("keySize", String.valueOf(keySize(derivedPublic)));
            properties.setProperty("createdAt",
                    String.valueOf(info.getCreateTimeInstant().toEpochMilli()));
            properties.setProperty("privateKeyBackup",
                    Base64.getEncoder().encodeToString(encrypted));
            StringWriter writer = new StringWriter();
            properties.store(writer, null);
            return writer.toString();
        } finally {
            MemorySecurityUtils.clearByteArray(encrypted);
        }
    }

    /** 读取备份文件；只接受严格 UTF-8、去 BOM、无 NUL，且不超过 256 KB。 */
    public static String readText(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("备份文件不存在");
        }
        if (file.length() <= 0 || file.length() > MAX_BACKUP_BYTES) {
            throw new IOException("备份文件大小必须在 1 字节到 256 KB 之间");
        }
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length > MAX_BACKUP_BYTES) {
            throw new IOException("备份文件超过 256 KB");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IOException("备份文件不是 UTF-8 文本", error);
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (text.trim().isEmpty()) {
            throw new IOException("备份文件为空");
        }
        if (text.indexOf('\u0000') >= 0) {
            throw new IOException("备份文件包含二进制内容");
        }
        return text;
    }

    /** 解析备份结构，供界面预填名称与显示密钥长度。 */
    public static Preview inspect(String text) throws IOException {
        Bundle bundle = decode(text);
        return new Preview(
                bundle.name,
                bundle.publicKey,
                bundle.fingerprint,
                bundle.keySize,
                bundle.createdAt);
    }

    /**
     * 导入备份并保存为桌面密钥对。
     *
     * @param requestedName 用户确认的名称；为空时用备份里的名称
     * @return 实际保存的密钥对（名称可能因重名追加 `_2`）
     */
    public static KeyPairInfo importBundle(
            String text,
            String requestedName,
            char[] backupPassword
    ) throws Exception {
        requirePassword(backupPassword);
        Bundle bundle = decode(text);
        byte[] encryptedPrivateKey = Base64.getDecoder().decode(bundle.privateKeyBackup);
        byte[] plainPrivateKey = null;
        byte[] salt = null;
        byte[] encryptedSalt = null;
        try {
            plainPrivateKey = KeyPairManager.decryptPrivateKey(
                    encryptedPrivateKey, backupPassword);
            PrivateKey privateKey = KeyUtils.getPrivateKeyFromBytes(plainPrivateKey);
            PublicKey publicKey = derivePublicKey(privateKey);
            String publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
            if (!publicKeyBase64.equals(bundle.publicKey)) {
                throw new IllegalArgumentException("备份中的公钥与私钥不一致");
            }
            int size = keySize(publicKey);
            if (size < 2048) {
                throw new IllegalArgumentException("备份中的 RSA 密钥不足 2048 位");
            }

            String baseName = requestedName == null || requestedName.trim().isEmpty()
                    ? bundle.name
                    : requestedName;
            String name = uniqueName(sanitizeName(baseName));
            Instant createdAt = bundle.createdAt > 0
                    ? Instant.ofEpochMilli(bundle.createdAt)
                    : Instant.now();

            salt = new byte[CryptoConfig.SALT_LENGTH];
            SecurityProvider.STRONG_RANDOM.nextBytes(salt);
            encryptedSalt = KeyPairManager.encryptPrivateKey(salt, backupPassword);

            KeyPairInfo info = new KeyPairInfo(
                    name,
                    createdAt,
                    "RSA",
                    size,
                    publicKeyBase64,
                    name + ".pem");
            info.setEncryptedSalt(encryptedSalt);
            KeyPairStorage.saveKeyPair(name, info, encryptedPrivateKey);
            return info;
        } finally {
            MemorySecurityUtils.clearByteArray(encryptedPrivateKey);
            MemorySecurityUtils.clearByteArray(plainPrivateKey);
            MemorySecurityUtils.clearByteArray(salt);
            MemorySecurityUtils.clearByteArray(encryptedSalt);
        }
    }

    /** 只保留桌面端密钥文件名允许的字符，并限制长度。 */
    public static String sanitizeName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        name = name.replaceAll("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]", "_");
        if (name.isEmpty()) {
            name = "imported_key";
        }
        if (name.length() > MAX_NAME_CHARS) {
            name = name.substring(0, MAX_NAME_CHARS);
        }
        return name;
    }

    /** 重名时追加 `_2`、`_3`，不覆盖现有密钥。 */
    public static String uniqueName(String baseName) throws IOException {
        List<KeyPairInfo> existing = KeyPairStorage.loadAllKeyPairs();
        String candidate = baseName;
        int suffix = 2;
        while (containsIgnoreCase(existing, candidate)) {
            candidate = baseName + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    private static boolean containsIgnoreCase(List<KeyPairInfo> keyPairs, String name) {
        for (KeyPairInfo keyPair : keyPairs) {
            if (keyPair.getName() != null && keyPair.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static Bundle decode(String text) throws IOException {
        if (text == null || text.trim().isEmpty()) {
            throw new IOException("备份内容为空");
        }
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(text));
        } catch (IOException error) {
            throw new IOException("备份文本无法解析", error);
        }
        if (!FORMAT.equals(properties.getProperty("format"))) {
            throw new IOException("不是当前密钥备份格式");
        }
        if (!VERSION.equals(properties.getProperty("version"))) {
            throw new IOException("不支持的密钥备份版本");
        }
        String publicKey = required(properties, "publicKey");
        String privateKeyBackup = required(properties, "privateKeyBackup");
        int keySize = parseInt(required(properties, "keySize"), "密钥长度");
        long createdAt = parseLong(properties.getProperty("createdAt"), 0L);
        String name = properties.getProperty("name", "").trim();
        String fingerprint = properties.getProperty("fingerprint", "").trim();
        return new Bundle(name, publicKey, fingerprint, keySize, createdAt, privateKeyBackup);
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("备份缺少字段：" + key);
        }
        return value.trim();
    }

    private static int parseInt(String value, String label) throws IOException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IOException("备份中的" + label + "无效", error);
        }
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "备份口令至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
    }

    private static PublicKey derivePublicKey(PrivateKey privateKey) throws Exception {
        if (!(privateKey instanceof RSAPrivateCrtKey)) {
            throw new IllegalArgumentException("只支持 RSA 私钥");
        }
        RSAPrivateCrtKey rsa = (RSAPrivateCrtKey) privateKey;
        return KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
    }

    private static int keySize(PublicKey publicKey) {
        if (!(publicKey instanceof RSAPublicKey)) {
            throw new IllegalArgumentException("只支持 RSA 公钥");
        }
        return ((RSAPublicKey) publicKey).getModulus().bitLength();
    }

    private static final class Bundle {
        final String name;
        final String publicKey;
        final String fingerprint;
        final int keySize;
        final long createdAt;
        final String privateKeyBackup;

        Bundle(
                String name,
                String publicKey,
                String fingerprint,
                int keySize,
                long createdAt,
                String privateKeyBackup
        ) {
            this.name = name;
            this.publicKey = publicKey;
            this.fingerprint = fingerprint;
            this.keySize = keySize;
            this.createdAt = createdAt;
            this.privateKeyBackup = privateKeyBackup;
        }
    }
}
