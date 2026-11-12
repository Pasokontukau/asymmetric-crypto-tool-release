package core.security;

import core.util.LogManager;
import java.util.Arrays;
import javax.crypto.SecretKey;
import javax.security.auth.Destroyable;

public class MemorySecurityUtils {

    /**
     * 清除字符数组内容（用零填充）
     * 使用零填充而非空格，更安全
     * @param chars 要清除的字符数组
     */
    public static void clearCharArray(char[] chars) {
        if (chars != null) {
            Arrays.fill(chars, '\u0000');
        }
    }

    /**
     * 清除字节数组内容（用0填充）
     * @param bytes 要清除的字节数组
     */
    public static void clearByteArray(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * 销毁 SecretKey
     * @param key 要销毁的密钥
     */
    public static void destroySecretKey(SecretKey key) {
        if (key == null) {
            return;
        }
        
        // 先尝试正式销毁
        if (key instanceof Destroyable) {
            try {
                ((Destroyable) key).destroy();
            } catch (Exception e) {
                LogManager.warning("销毁密钥失败：" + e.getMessage());
            }
        }
        
        // 无论如何，都尝试清除编码内容（双重安全保障）
        byte[] encoded = key.getEncoded();
        if (encoded != null) {
            clearByteArray(encoded);
        }
    }

    /**
     * 安全比较字符数组（防止时序攻击）
     * 使用常量时间比较，无论数据内容如何，执行时间保持一致
     * @param a 第一个数组
     * @param b 第二个数组
     * @return 是否相等
     */
    public static boolean constantTimeEquals(char[] a, char[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        
        int result = a.length ^ b.length;
        int minLength = Math.min(a.length, b.length);
        for (int i = 0; i < minLength; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    /**
     * 安全比较字节数组（防止时序攻击）
     * @param a 第一个数组
     * @param b 第二个数组
     * @return 是否相等
     */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        
        int result = a.length ^ b.length;
        int minLength = Math.min(a.length, b.length);
        for (int i = 0; i < minLength; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
}
