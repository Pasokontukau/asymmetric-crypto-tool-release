package platform.windows;

import core.util.LogManager;
import crypto.config.CryptoConfig;
import crypto.key.KeyUtils;
import crypto.provider.SecurityProvider;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import platform.windows.LocalCryptoKeyManager;

public class LocalDataEncryptor {
    
    private static final int GCM_IV_LENGTH = CryptoConfig.GCM_IV_LENGTH;
    private static final int GCM_TAG_LENGTH = CryptoConfig.GCM_TAG_LENGTH;
    private static final byte[] MAGIC_HEADER_HISTORY = { 'H', 'I', 'S', 'T' };
    private static final int VERSION = 1;
    
    public static boolean isEncryptedHistoryFile(byte[] data) {
        return checkMagicHeader(data, MAGIC_HEADER_HISTORY);
    }
    
    public static byte[] encryptHistoryData(String jsonData) throws Exception {
        return encryptData(jsonData.getBytes(StandardCharsets.UTF_8), MAGIC_HEADER_HISTORY);
    }
    
    public static String decryptHistoryData(byte[] encryptedData) throws Exception {
        byte[] decryptedBytes = decryptData(encryptedData, MAGIC_HEADER_HISTORY);
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }
    
    private static byte[] encryptData(byte[] data, byte[] magicHeader) throws Exception {
        SecretKey key = LocalCryptoKeyManager.getOrCreateLocalKey();
        
        byte[] iv = new byte[GCM_IV_LENGTH];
        SecurityProvider.REGULAR_RANDOM.nextBytes(iv);
        
        Cipher cipher = Cipher.getInstance(CryptoConfig.AES_ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);
        byte[] encrypted = cipher.doFinal(data);
        
        int totalSize = 4 + 4 + 4 + iv.length + 4 + encrypted.length;
        byte[] result = new byte[totalSize];
        int offset = 0;
        
        System.arraycopy(magicHeader, 0, result, offset, 4);
        offset += 4;
        
        System.arraycopy(KeyUtils.intToBytes(VERSION), 0, result, offset, 4);
        offset += 4;
        
        System.arraycopy(KeyUtils.intToBytes(iv.length), 0, result, offset, 4);
        offset += 4;
        
        System.arraycopy(iv, 0, result, offset, iv.length);
        offset += iv.length;
        
        System.arraycopy(KeyUtils.intToBytes(encrypted.length), 0, result, offset, 4);
        offset += 4;
        
        System.arraycopy(encrypted, 0, result, offset, encrypted.length);
        
        return result;
    }
    
    private static byte[] decryptData(byte[] encryptedData, byte[] expectedMagic) throws Exception {
        if (encryptedData == null || encryptedData.length < 20) {
            throw new IllegalArgumentException("加密数据格式无效");
        }
        
        int offset = 0;
        
        for (int i = 0; i < 4; i++) {
            if (encryptedData[offset + i] != expectedMagic[i]) {
                throw new IllegalArgumentException("无效的文件头");
            }
        }
        offset += 4;
        
        int version = KeyUtils.bytesToInt(encryptedData, offset);
        offset += 4;
        
        if (version != VERSION) {
            LogManager.warning("检测到不同版本的加密数据文件，版本: " + version);
        }
        
        int ivLength = KeyUtils.bytesToInt(encryptedData, offset);
        offset += 4;
        
        if (ivLength != GCM_IV_LENGTH) {
            throw new IllegalArgumentException("IV 长度无效: " + ivLength);
        }
        
        byte[] iv = new byte[ivLength];
        System.arraycopy(encryptedData, offset, iv, 0, ivLength);
        offset += ivLength;
        
        int encryptedLength = KeyUtils.bytesToInt(encryptedData, offset);
        offset += 4;
        
        byte[] encrypted = new byte[encryptedLength];
        System.arraycopy(encryptedData, offset, encrypted, 0, encryptedLength);
        
        SecretKey key = LocalCryptoKeyManager.getOrCreateLocalKey();
        Cipher cipher = Cipher.getInstance(CryptoConfig.AES_ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
        
        return cipher.doFinal(encrypted);
    }
    
    private static boolean checkMagicHeader(byte[] data, byte[] expectedMagic) {
        if (data == null || data.length < 4) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (data[i] != expectedMagic[i]) {
                return false;
            }
        }
        return true;
    }
}
