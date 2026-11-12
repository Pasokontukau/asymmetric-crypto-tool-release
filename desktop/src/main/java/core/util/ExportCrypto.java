package core.util;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 桌面端导出文件的密码加密容器。
 *
 * <p>历史记录和设置导出都包含文件名、路径或联系人等敏感信息。默认导出走这里：
 * 用户输入的口令经 PBKDF2-HMAC-SHA256 派生 AES-256 密钥，再用 AES-GCM 加密。
 * 文件头只保存算法参数（迭代次数、盐、IV），不保存任何明文元数据。</p>
 *
 * <p>格式：{@code AEXP | version(4) | iterations(4) | saltLen(4) | salt |
 * ivLen(4) | iv | cipherLen(4) | cipher}。所有整数为大端。</p>
 */
public final class ExportCrypto {

    private static final byte[] MAGIC = {'A', 'E', 'X', 'P'};
    private static final int VERSION = 1;
    private static final int ITERATIONS = 210_000;
    private static final int MIN_ITERATIONS = 10_000;
    private static final int MAX_ITERATIONS = 1_000_000;
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final int HEADER_FIXED_BYTES = 4 + 4 + 4 + 4 + 4 + 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ExportCrypto() {
    }

    public static byte[] encrypt(byte[] plaintext, char[] password) throws GeneralSecurityException {
        if (plaintext == null) {
            throw new IllegalArgumentException("导出内容不能为空");
        }
        requirePassword(password);

        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(iv);

        SecretKey key = deriveKey(password, salt, ITERATIONS);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(MAGIC);
        byte[] ciphertext = cipher.doFinal(plaintext);

        ByteBuffer buffer = ByteBuffer.allocate(
                HEADER_FIXED_BYTES + salt.length + iv.length + ciphertext.length);
        buffer.put(MAGIC);
        buffer.putInt(VERSION);
        buffer.putInt(ITERATIONS);
        buffer.putInt(salt.length);
        buffer.put(salt);
        buffer.putInt(iv.length);
        buffer.put(iv);
        buffer.putInt(ciphertext.length);
        buffer.put(ciphertext);
        return buffer.array();
    }

    public static byte[] decrypt(byte[] data, char[] password) throws GeneralSecurityException {
        if (!isEncrypted(data)) {
            throw new GeneralSecurityException("不是加密导出文件");
        }
        requirePassword(password);
        if (data.length < HEADER_FIXED_BYTES) {
            throw new GeneralSecurityException("加密导出文件已损坏");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);
        byte[] magic = new byte[MAGIC.length];
        buffer.get(magic);
        int version = buffer.getInt();
        if (version != VERSION) {
            throw new GeneralSecurityException("不支持的导出文件版本: " + version);
        }

        int iterations = buffer.getInt();
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new GeneralSecurityException("导出文件的迭代次数无效");
        }

        int saltLength = buffer.getInt();
        if (saltLength != SALT_LENGTH || buffer.remaining() < saltLength + 4) {
            throw new GeneralSecurityException("加密导出文件已损坏");
        }
        byte[] salt = new byte[saltLength];
        buffer.get(salt);

        int ivLength = buffer.getInt();
        if (ivLength != IV_LENGTH || buffer.remaining() < ivLength + 4) {
            throw new GeneralSecurityException("加密导出文件已损坏");
        }
        byte[] iv = new byte[ivLength];
        buffer.get(iv);

        int cipherLength = buffer.getInt();
        if (cipherLength <= 0 || cipherLength != buffer.remaining()) {
            throw new GeneralSecurityException("加密导出文件已损坏");
        }
        byte[] ciphertext = new byte[cipherLength];
        buffer.get(ciphertext);

        SecretKey key = deriveKey(password, salt, iterations);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(MAGIC);
        try {
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            // GCM 校验失败无法区分“密码错”和“文件被改”，统一给出可操作提示。
            throw new GeneralSecurityException("密码错误或导出文件已损坏", e);
        }
    }

    public static boolean isEncrypted(byte[] data) {
        if (data == null || data.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static void requirePassword(char[] password) throws GeneralSecurityException {
        if (password == null || password.length < 8) {
            throw new GeneralSecurityException("导出密码至少需要 8 位");
        }
    }

    private static SecretKey deriveKey(char[] password, byte[] salt, int iterations)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(keyBytes, "AES");
            } finally {
                Arrays.fill(keyBytes, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }
}
