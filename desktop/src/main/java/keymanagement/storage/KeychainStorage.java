package keymanagement.storage;

import core.security.MemorySecurityUtils;
import core.util.LogManager;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import platform.windows.WindowsDPAPI;

public class KeychainStorage {
    
    private static final Preferences PREFS = Preferences.userNodeForPackage(KeychainStorage.class);
    private static final String KEY_PREFIX = "keychain_pwd_";
    
    /**
     * 检查密码保存功能是否可用
     * 只有Windows且DPAPI可用时才启用
     */
    public static boolean isAvailable() {
        return platform.windows.WindowsDPAPI.isAvailable();
    }
    
    /**
     * 保存密码到系统密钥链（仅Windows）
     * @param keyName 密钥名称
     * @param password 密码（char[] 会立即清理）
     */
    public static void savePassword(String keyName, char[] password) {
        if (!isAvailable()) {
            LogManager.warning("系统不支持密码保存功能（仅Windows）");
            return;
        }
        
        try {
            // 使用标准 Charset API，但尽量减少 String 生命周期
            java.nio.CharBuffer charBuffer = java.nio.CharBuffer.wrap(password);
            java.nio.ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode(charBuffer);
            
            byte[] passwordBytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(passwordBytes);
            
            // 清除 ByteBuffer 的内容
            byteBuffer.rewind();
            int bufferLimit = byteBuffer.limit();
            for (int i = 0; i < bufferLimit; i++) {
                byteBuffer.put(i, (byte) 0);
            }
            
            // 使用 Windows DPAPI 加密
            byte[] encrypted = platform.windows.WindowsDPAPI.encrypt(passwordBytes);
            
            // 清理密码字节数组
            MemorySecurityUtils.clearByteArray(passwordBytes);
            
            // Base64 编码后存储到 Preferences
            String encoded = java.util.Base64.getEncoder().encodeToString(encrypted);
            PREFS.put(KEY_PREFIX + keyName, encoded);
            
            LogManager.info("密码已保存到Windows密钥链: " + keyName);
        } catch (Exception e) {
            LogManager.error("保存密码到系统密钥链失败", e);
        }
    }
    
    /**
     * 从系统密钥链获取密码（仅Windows）
     * @param keyName 密钥名称
     * @return 密码（char[]），不存在或非Windows返回 null
     */
    public static char[] getPassword(String keyName) {
        if (!isAvailable()) {
            return null;
        }
        
        try {
            String encodedStr = PREFS.get(KEY_PREFIX + keyName, null);
            if (encodedStr == null) {
                return null;
            }
            
            // Base64 解码
            byte[] encrypted = java.util.Base64.getDecoder().decode(encodedStr);
            
            // 使用 Windows DPAPI 解密
            byte[] decryptedBytes = platform.windows.WindowsDPAPI.decrypt(encrypted);
            
            // 使用标准 Charset API，但尽量减少中间对象的生命周期
            java.nio.ByteBuffer byteBuffer = java.nio.ByteBuffer.wrap(decryptedBytes);
            java.nio.CharBuffer charBuffer = StandardCharsets.UTF_8.decode(byteBuffer);
            
            char[] password = new char[charBuffer.remaining()];
            charBuffer.get(password);
            
            // 清除 CharBuffer 的内容
            charBuffer.rewind();
            int charBufferLimit = charBuffer.limit();
            for (int i = 0; i < charBufferLimit; i++) {
                charBuffer.put(i, '\u0000');
            }
            
            // 清理临时字节数组
            MemorySecurityUtils.clearByteArray(decryptedBytes);
            
            LogManager.debug("成功从Windows密钥链获取密码: " + keyName);
            
            return password;
        } catch (Exception e) {
            LogManager.error("从系统密钥链获取密码失败", e);
            return null;
        }
    }
    
    /**
     * 从系统密钥链删除密码
     * @param keyName 密钥名称
     */
    public static void deletePassword(String keyName) {
        try {
            PREFS.remove(KEY_PREFIX + keyName);
            PREFS.flush();
            LogManager.info("已从系统密钥链删除密码: " + keyName);
        } catch (BackingStoreException e) {
            LogManager.error("从系统密钥链删除密码失败", e);
        }
    }
    
    /**
     * 清空系统密钥链中的所有密码
     */
    public static void clearAllPasswords() {
        try {
            String[] keys = PREFS.keys();
            for (String key : keys) {
                if (key.startsWith(KEY_PREFIX)) {
                    PREFS.remove(key);
                }
            }
            PREFS.flush();
            LogManager.info("已清空系统密钥链中的所有密码");
        } catch (BackingStoreException e) {
            LogManager.error("清空系统密钥链失败", e);
        }
    }
    
    /**
     * 检查系统密钥链中是否有指定密钥的密码
     * @param keyName 密钥名称
     * @return 是否存在
     */
    public static boolean hasPassword(String keyName) {
        if (!isAvailable()) {
            return false;
        }
        
        try {
            return PREFS.get(KEY_PREFIX + keyName, null) != null;
        } catch (Exception e) {
            return false;
        }
    }
}
