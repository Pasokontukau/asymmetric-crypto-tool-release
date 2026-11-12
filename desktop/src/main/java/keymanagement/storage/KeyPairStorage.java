package keymanagement.storage;

import core.event.PasswordCacheClearListener;
import core.storage.StorageManager;
import core.util.LogManager;
import crypto.key.KeyPairManager;
import crypto.key.KeyUtils;
import keymanagement.model.KeyPairInfo;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import keymanagement.storage.KeychainStorage;

public class KeyPairStorage {

    private static final String PUBLIC_KEY_EXTENSION = ".pub";
    private static final String PRIVATE_KEY_EXTENSION = ".pem";
    private static final String SALT_EXTENSION = ".salt";

    private static final List<core.event.PasswordCacheClearListener> listeners = new CopyOnWriteArrayList<>();

    public static void addPasswordCacheClearListener(core.event.PasswordCacheClearListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removePasswordCacheClearListener(core.event.PasswordCacheClearListener listener) {
        listeners.remove(listener);
    }

    public static void notifyClearPasswordCache(String keyPairName) {
        for (core.event.PasswordCacheClearListener listener : listeners) {
            try {
                listener.onClearPasswordCache(keyPairName);
            } catch (Exception e) {
                core.util.LogManager.error("通知清除密码缓存失败", e);
            }
        }
    }

    public static void notifyClearAllPasswordCaches() {
        for (core.event.PasswordCacheClearListener listener : listeners) {
            try {
                listener.onClearAllPasswordCaches();
            } catch (Exception e) {
                core.util.LogManager.error("通知清除所有密码缓存失败", e);
            }
        }
    }

    public static void saveKeyPair(String name, KeyPairInfo keyPairInfo, byte[] encryptedPrivateKey) throws IOException {
        String publicKeyPath = core.storage.StorageManager.getKeypairsDir() + File.separator + name + PUBLIC_KEY_EXTENSION;
        String privateKeyPath = core.storage.StorageManager.getKeypairsDir() + File.separator + name + PRIVATE_KEY_EXTENSION;

        core.storage.StorageManager.writeTextFile(publicKeyPath, keyPairInfo.getPublicKey());
        core.storage.StorageManager.writeFile(privateKeyPath, encryptedPrivateKey);

        // Phase 2：若有 encrypted salt 也一并保存（用于"复制我的 salt"功能）
        if (keyPairInfo.getEncryptedSalt() != null && keyPairInfo.getEncryptedSalt().length > 0) {
            String saltPath = core.storage.StorageManager.getKeypairsDir() + File.separator + name + SALT_EXTENSION;
            core.storage.StorageManager.writeFile(saltPath, keyPairInfo.getEncryptedSalt());
        }
    }

    public static List<KeyPairInfo> loadAllKeyPairs() throws IOException {
        List<KeyPairInfo> keyPairs = new ArrayList<>();
        List<String> publicKeyFiles = core.storage.StorageManager.listFiles(core.storage.StorageManager.getKeypairsDir(), PUBLIC_KEY_EXTENSION);

        for (String publicKeyFile : publicKeyFiles) {
            String name = publicKeyFile.replace(PUBLIC_KEY_EXTENSION, "");
            String publicKeyPath = core.storage.StorageManager.getKeypairsDir() + File.separator + publicKeyFile;
            String privateKeyPath = core.storage.StorageManager.getKeypairsDir() + File.separator + name + PRIVATE_KEY_EXTENSION;

            if (new File(privateKeyPath).exists()) {
                String publicKey = core.storage.StorageManager.readTextFile(publicKeyPath);

                File file = new File(privateKeyPath);
                Instant createTime = Instant.ofEpochMilli(file.lastModified());

                byte[] publicKeyBytes = KeyUtils.decodeBase64(publicKey);
                int keySize = 2048;
                try {
                    keySize = KeyUtils.getRSAPublicKeySize(publicKeyBytes);
                } catch (Exception e) {
                    core.util.LogManager.error("解析密钥长度失败", e);
                }

                KeyPairInfo keyPairInfo = new KeyPairInfo(
                        name,
                        createTime,
                        "RSA",
                        keySize,
                        publicKey,
                        privateKeyPath
                );

                // Phase 2：若有 salt 文件也一并加载
                byte[] encSalt = loadEncryptedSalt(name);
                if (encSalt != null) keyPairInfo.setEncryptedSalt(encSalt);

                keyPairs.add(keyPairInfo);
            }
        }

        return keyPairs;
    }

    public static byte[] loadEncryptedPrivateKey(String name) throws IOException {
        String privateKeyPath = core.storage.StorageManager.getKeypairsDir() + File.separator + name + PRIVATE_KEY_EXTENSION;
        return core.storage.StorageManager.readFile(privateKeyPath);
    }

    public static byte[] loadEncryptedSalt(String name) throws IOException {
        String saltPath = core.storage.StorageManager.getKeypairsDir() + File.separator + name + SALT_EXTENSION;
        File saltFile = new File(saltPath);
        if (!saltFile.exists()) return null;
        return core.storage.StorageManager.readFile(saltPath);
    }

    public static void setDefaultKeyPair(String name) throws IOException {
        String configPath = core.storage.StorageManager.getConfigDir() + File.separator + "default_keypair.txt";
        core.storage.StorageManager.writeTextFile(configPath, name);
    }

    public static String getDefaultKeyPair() throws IOException {
        String configPath = core.storage.StorageManager.getConfigDir() + File.separator + "default_keypair.txt";
        Path path = Paths.get(configPath);
        if (Files.exists(path)) {
            return core.storage.StorageManager.readTextFile(configPath);
        }
        return null;
    }

    public static boolean deleteKeyPair(String name) {
        boolean success = true;

        KeychainStorage.deletePassword(name);

        notifyClearPasswordCache(name);

        String publicKeyPath = core.storage.StorageManager.getKeypairsDir() + File.separator + name + PUBLIC_KEY_EXTENSION;
        String privateKeyPath = core.storage.StorageManager.getKeypairsDir() + File.separator + name + PRIVATE_KEY_EXTENSION;
        String saltPath = core.storage.StorageManager.getKeypairsDir() + File.separator + name + SALT_EXTENSION;

        if (!core.storage.StorageManager.deleteFile(publicKeyPath)) {
            success = false;
        }

        if (!core.storage.StorageManager.deleteFile(privateKeyPath)) {
            success = false;
        }

        // Phase 2：同时删除 salt 文件
        core.storage.StorageManager.deleteFile(saltPath);

        return success;
    }

    public static void clearAllKeyPairs() {
        try {
            KeychainStorage.clearAllPasswords();

            notifyClearAllPasswordCaches();

            File dir = new File(core.storage.StorageManager.getKeypairsDir());
            if (dir.exists()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        file.delete();
                    }
                }
            }
        } catch (Exception e) {
            core.util.LogManager.error("清空密钥对失败", e);
        }
    }
}
