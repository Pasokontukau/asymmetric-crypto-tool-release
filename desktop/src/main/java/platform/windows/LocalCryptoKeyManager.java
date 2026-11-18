package platform.windows;

import core.security.MemorySecurityUtils;
import core.util.LogManager;
import crypto.config.CryptoConfig;
import crypto.key.KeyUtils;
import crypto.provider.SecurityProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import platform.windows.WindowsDPAPI;
import settings.storage.SettingsStorage;

public class LocalCryptoKeyManager {
    
    private static final String KEY_FILENAME = "local_key.bin";
    /** 旧版内部密钥名；只在首次启动时读取一次并迁移，不再继续使用。 */
    private static final String LEGACY_KEY_FILENAME = "local_key.enc";
    private static final int AES_KEY_SIZE = 256;
    private static final int GCM_IV_LENGTH = CryptoConfig.GCM_IV_LENGTH;
    private static final int GCM_TAG_LENGTH = CryptoConfig.GCM_TAG_LENGTH;
    
    private static volatile SecretKey cachedKey = null;
    private static volatile boolean initialized = false;
    
    private LocalCryptoKeyManager() {}
    
    public static boolean isKeyAvailable() {
        try {
            getOrCreateLocalKey();
            return true;
        } catch (Exception e) {
            LogManager.warning("本地加密密钥不可用", e);
            return false;
        }
    }
    
    public static synchronized SecretKey getOrCreateLocalKey() throws Exception {
        if (cachedKey != null) {
            return cachedKey;
        }
        
        Path keyPath = getKeyFilePath();
        Path legacyPath = getLegacyKeyFilePath();

        if (Files.exists(keyPath)) {
            cachedKey = loadEncryptedKey(keyPath);
            LogManager.info("成功加载本地加密密钥");
        } else if (Files.exists(legacyPath)) {
            // 旧密钥只读取一次：迁移到新文件名后删除旧文件，之后只认 local_key.bin。
            cachedKey = loadEncryptedKey(legacyPath);
            saveEncryptedKey(cachedKey, keyPath);
            Files.deleteIfExists(legacyPath);
            LogManager.info("已迁移本地加密密钥到 " + KEY_FILENAME);
        } else {
            cachedKey = generateAndSaveLocalKey(keyPath);
            LogManager.info("成功生成并保存新的本地加密密钥");
        }

        return cachedKey;
    }
    
    private static Path getKeyFilePath() {
        String dataDir = core.storage.StorageManager.getDataDir();
        return Paths.get(dataDir, KEY_FILENAME);
    }

    private static Path getLegacyKeyFilePath() {
        String dataDir = core.storage.StorageManager.getDataDir();
        return Paths.get(dataDir, LEGACY_KEY_FILENAME);
    }
    
    private static SecretKey generateAndSaveLocalKey(Path keyPath) throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(AES_KEY_SIZE, SecurityProvider.STRONG_RANDOM);
        SecretKey key = keyGen.generateKey();
        
        saveEncryptedKey(key, keyPath);
        
        return key;
    }
    
    private static void saveEncryptedKey(SecretKey key, Path keyPath) throws Exception {
        byte[] keyBytes = key.getEncoded();
        
        byte[] encryptedKey;
        if (WindowsDPAPI.isAvailable()) {
            encryptedKey = WindowsDPAPI.encrypt(keyBytes);
        } else {
            encryptedKey = encryptKeyWithPassword(keyBytes);
        }
        
        Files.createDirectories(keyPath.getParent());
        Files.write(keyPath, encryptedKey);
    }
    
    private static SecretKey loadEncryptedKey(Path keyPath) throws Exception {
        byte[] encryptedKeyBytes = Files.readAllBytes(keyPath);
        
        byte[] keyBytes;
        if (WindowsDPAPI.isAvailable()) {
            keyBytes = WindowsDPAPI.decrypt(encryptedKeyBytes);
        } else {
            keyBytes = decryptKeyWithPassword(encryptedKeyBytes);
        }
        
        return new SecretKeySpec(keyBytes, "AES");
    }
    
    private static byte[] encryptKeyWithPassword(byte[] keyBytes) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        
        SecretKey derivedKey = getDerivedKeyForNonWindows();
        Cipher cipher = Cipher.getInstance(CryptoConfig.AES_ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        cipher.init(Cipher.ENCRYPT_MODE, derivedKey, gcmSpec);
        byte[] encrypted = cipher.doFinal(keyBytes);
        
        byte[] result = new byte[4 + iv.length + 4 + encrypted.length];
        int offset = 0;
        
        System.arraycopy(KeyUtils.intToBytes(iv.length), 0, result, offset, 4);
        offset += 4;
        System.arraycopy(iv, 0, result, offset, iv.length);
        offset += iv.length;
        System.arraycopy(KeyUtils.intToBytes(encrypted.length), 0, result, offset, 4);
        offset += 4;
        System.arraycopy(encrypted, 0, result, offset, encrypted.length);
        
        return result;
    }
    
    private static byte[] decryptKeyWithPassword(byte[] encryptedData) throws Exception {
        int offset = 0;
        
        int ivLength = KeyUtils.bytesToInt(encryptedData, offset);
        offset += 4;
        
        byte[] iv = new byte[ivLength];
        System.arraycopy(encryptedData, offset, iv, 0, ivLength);
        offset += ivLength;
        
        int encryptedLength = KeyUtils.bytesToInt(encryptedData, offset);
        offset += 4;
        
        byte[] encrypted = new byte[encryptedLength];
        System.arraycopy(encryptedData, offset, encrypted, 0, encryptedLength);
        
        SecretKey derivedKey = getDerivedKeyForNonWindows();
        Cipher cipher = Cipher.getInstance(CryptoConfig.AES_ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        cipher.init(Cipher.DECRYPT_MODE, derivedKey, gcmSpec);
        
        return cipher.doFinal(encrypted);
    }
    
    private static SecretKey getDerivedKeyForNonWindows() throws Exception {
        String machineId = getMachineIdentifier();
        byte[] salt = "LocalCryptoKeySalt".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        
        javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
            machineId.toCharArray(), salt, 65536, 256);
        
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }
    
    private static String getMachineIdentifier() {
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        String hostname = System.getenv("COMPUTERNAME");
        if (hostname == null) {
            hostname = System.getenv("HOSTNAME");
        }
        if (hostname == null) {
            hostname = "unknown-host";
        }
        String user = System.getProperty("user.name", "unknown-user");
        
        return os + "-" + hostname + "-" + user;
    }
    
    public static void resetLocalKey() throws Exception {
        if (cachedKey != null) {
            core.security.MemorySecurityUtils.destroySecretKey(cachedKey);
        }
        cachedKey = null;
        Files.deleteIfExists(getKeyFilePath());
        Files.deleteIfExists(getLegacyKeyFilePath());
    }

    /** 测试钩子：只清内存缓存，不动磁盘上的密钥文件。 */
    static synchronized void resetCacheForTests() {
        if (cachedKey != null) {
            core.security.MemorySecurityUtils.destroySecretKey(cachedKey);
        }
        cachedKey = null;
    }
}
