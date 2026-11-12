package stegano.service;

import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import stegano.model.SteganoConfig;
import stegano.service.SteganoException;

public class SteganoPbkdf2 {

    /**
     * 嵌入时调用：随机生成盐，派生种子。
     * @return SeedResult 包含种子和盐（盐需嵌入载体，供提取时使用）
     */
    public static SeedResult deriveSeed(char[] password) throws SteganoException {
        byte[] salt = new byte[SteganoConfig.SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        byte[] seed = derive(password, salt);
        return new SeedResult(seed, salt);
    }

    /**
     * 提取时调用：使用已知盐值派生种子。
     */
    public static byte[] deriveSeed(char[] password, byte[] salt) throws SteganoException {
        return derive(password, salt);
    }

    /**
     * 从密钥字节（如私钥 PKCS#8 编码）派生种子。
     * 与 char[] 密码版本保持相同的 PBKDF2 参数。
     */
    public static SeedResult deriveSeed(byte[] keyBytes) throws SteganoException {
        byte[] salt = new byte[SteganoConfig.SALT_LENGTH];
        new java.security.SecureRandom().nextBytes(salt);
        byte[] seed = deriveFromBytes(keyBytes, salt);
        return new SeedResult(seed, salt);
    }

    /**
     * 从密钥字节派生种子（已知盐值，提取时使用）。
     */
    public static byte[] deriveSeed(byte[] keyBytes, byte[] salt) throws SteganoException {
        return deriveFromBytes(keyBytes, salt);
    }

    private static byte[] derive(char[] password, byte[] salt) throws SteganoException {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt,
                    SteganoConfig.PBKDF2_ITERATIONS, SteganoConfig.SEED_LENGTH * 8);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] seed = skf.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return seed;
        } catch (Exception e) {
            throw new SteganoException("PRNG seed derivation failed", e);
        }
    }

    /**
     * 从 byte[] 密钥材料派生种子。
     * 为避免依赖 Java 不保证的 PBEKeySpec(byte[], ...) 重载，此处将字节数组
     * 安全地转换为十六进制字符数组，再复用现有的 char[] 派生流程。
     */
    private static byte[] deriveFromBytes(byte[] keyBytes, byte[] salt) throws SteganoException {
        char[] hexChars = bytesToHexChars(keyBytes);
        try {
            return derive(hexChars, salt);
        } finally {
            java.util.Arrays.fill(hexChars, '\0');
        }
    }

    /**
     * 将字节数组转换为十六进制字符数组。
     * 注意：调用方负责在使用后清除返回的 char[]（Arrays.fill(chars, '\0')）。
     */
    private static char[] bytesToHexChars(byte[] bytes) {
        final char[] HEX = "0123456789abcdef".toCharArray();
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2] = HEX[v >>> 4];
            hex[i * 2 + 1] = HEX[v & 0x0F];
        }
        return hex;
    }

    /** 管道清理：清空种子数组 */
    public static void clearSeed(byte[] seed) {
        if (seed != null) {
            java.util.Arrays.fill(seed, (byte) 0);
        }
    }

    /**
     * 种子派生结果。
     */
    public static class SeedResult {
        public final byte[] seed;
        public final byte[] salt;

        SeedResult(byte[] seed, byte[] salt) {
            this.seed = seed;
            this.salt = salt;
        }
    }
}