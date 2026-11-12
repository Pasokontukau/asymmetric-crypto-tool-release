package crypto.cipher;

import core.util.LogManager;
import core.util.OperationMonitor;
import core.util.ValidationUtils;
import crypto.config.CryptoConfig;
import crypto.key.KeyUtils;
import crypto.provider.SecurityProvider;
import java.security.PublicKey;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class Encryptor {
    private static final String AES_ALGORITHM = CryptoConfig.AES_ALGORITHM;
    private static final int GCM_IV_LENGTH = CryptoConfig.GCM_IV_LENGTH;
    private static final int GCM_TAG_LENGTH = CryptoConfig.GCM_TAG_LENGTH;
    
    public static SecretKey generateAesKey() throws Exception {
        final KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, SecurityProvider.STRONG_RANDOM);
        return keyGen.generateKey();
    }
    
    public static class EncryptResult {
        public final EncryptedData encryptedData;
        public final SecretKey aesKey;
        
        public EncryptResult(EncryptedData encryptedData, SecretKey aesKey) {
            this.encryptedData = encryptedData;
            this.aesKey = aesKey;
        }
    }
    
    public static EncryptResult encryptDataWithKey(byte[] plaintext, PublicKey publicKey) throws Exception {
        return doEncrypt(plaintext, publicKey);
    }
    
    /**
     * 加密数据（不返回 AES 密钥，用于不需要密钥的场景）
     */
    public static EncryptedData encryptData(byte[] plaintext, PublicKey publicKey) throws Exception {
        return doEncrypt(plaintext, publicKey).encryptedData;
    }
    
    private static EncryptResult doEncrypt(byte[] plaintext, PublicKey publicKey) throws Exception {
        ValidationUtils.notNullOrEmpty(plaintext, "plaintext");
        ValidationUtils.notNull(publicKey, "publicKey");
        
        final String operationId = OperationMonitor.startOperation(OperationMonitor.OperationType.DATA_ENCRYPT,
            "数据加密", plaintext.length);
        
        try {
            OperationMonitor.addContextData(operationId, "publicKeyAlgorithm", publicKey.getAlgorithm());
            OperationMonitor.addContextData(operationId, "publicKeyFormat", publicKey.getFormat());
            
            final long startTime = System.currentTimeMillis();
            LogManager.infoWithResource("开始加密数据 | 数据大小: " + LogManager.formatBytes(plaintext.length) + 
                " | 公钥算法: " + publicKey.getAlgorithm() + 
                " | 公钥格式: " + publicKey.getFormat());
            
            final SecretKey aesKey = generateAesKey();
            LogManager.debug("生成AES密钥成功 | 密钥算法: " + aesKey.getAlgorithm() + 
                " | 密钥格式: " + aesKey.getFormat());
            
            final byte[] iv = new byte[GCM_IV_LENGTH];
            SecurityProvider.REGULAR_RANDOM.nextBytes(iv);
            LogManager.debug("生成IV成功 | IV长度: " + iv.length + "字节");
            
            final Cipher aesCipher = Cipher.getInstance(AES_ALGORITHM);
            final GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
            aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec);
            LogManager.debug("初始化AES加密器成功 | 算法: " + AES_ALGORITHM);
            
            final byte[] ciphertext = aesCipher.doFinal(plaintext);
            LogManager.debug("AES加密完成 | 明文大小: " + LogManager.formatBytes(plaintext.length) + 
                " | 密文大小: " + LogManager.formatBytes(ciphertext.length));
            
            final byte[] encryptedAesKey = encryptAesKey(aesKey, publicKey);
            LogManager.debug("RSA加密AES密钥完成 | 加密后密钥大小: " + LogManager.formatBytes(encryptedAesKey.length));
            
            final long endTime = System.currentTimeMillis();
            LogManager.infoWithResource("加密完成 | 总耗时: " + (endTime - startTime) + "ms | " +
                "明文大小: " + LogManager.formatBytes(plaintext.length) + " | " +
                "密文大小: " + LogManager.formatBytes(ciphertext.length) + " | " +
                "总输出大小: " + LogManager.formatBytes(encryptedAesKey.length + iv.length + ciphertext.length));
            
            OperationMonitor.updateProgress(operationId, plaintext.length);
            OperationMonitor.endOperation(operationId, true, "数据加密成功");
            
            return new EncryptResult(new EncryptedData(encryptedAesKey, iv, ciphertext), aesKey);
        } catch (Exception e) {
            OperationMonitor.recordException(operationId, e);
            throw e;
        }
    }
    
    private static byte[] encryptAesKey(SecretKey aesKey, PublicKey publicKey) throws Exception {
        // 显式 OAEP 参数，保证与桌面端 JDK 默认（MGF1-SHA1）互通。
        Cipher rsaCipher = OaepCompat.encryptCipher(publicKey);
        return rsaCipher.doFinal(aesKey.getEncoded());
    }
    
    public static class EncryptedData {
        private byte[] encryptedAesKey;
        private byte[] iv;
        private byte[] ciphertext;
        
        public EncryptedData(byte[] encryptedAesKey, byte[] iv, byte[] ciphertext) {
            this.encryptedAesKey = encryptedAesKey;
            this.iv = iv;
            this.ciphertext = ciphertext;
        }
        
        public byte[] getEncryptedAesKey() { return encryptedAesKey; }
        public byte[] getIv() { return iv; }
        public byte[] getCiphertext() { return ciphertext; }
        
        public byte[] toBytes() {
            byte[] result = new byte[
                4 + encryptedAesKey.length +
                4 + iv.length +
                4 + ciphertext.length
            ];
            
            int offset = 0;
            
            System.arraycopy(KeyUtils.intToBytes(encryptedAesKey.length), 0, result, offset, 4);
            offset += 4;
            System.arraycopy(encryptedAesKey, 0, result, offset, encryptedAesKey.length);
            offset += encryptedAesKey.length;
            
            System.arraycopy(KeyUtils.intToBytes(iv.length), 0, result, offset, 4);
            offset += 4;
            System.arraycopy(iv, 0, result, offset, iv.length);
            offset += iv.length;
            
            System.arraycopy(KeyUtils.intToBytes(ciphertext.length), 0, result, offset, 4);
            offset += 4;
            System.arraycopy(ciphertext, 0, result, offset, ciphertext.length);
            
            return result;
        }
        
        public static EncryptedData fromBytes(byte[] data) {
            if (data == null) {
                throw new IllegalArgumentException("数据不能为空");
            }
            if (data.length < 12) {
                throw new IllegalArgumentException("数据太短，格式无效: " + data.length + " 字节");
            }
            
            int offset = 0;
            int remaining = data.length;
            
            int aesKeyLength = KeyUtils.bytesToInt(data, offset);
            offset += 4;
            remaining -= 4;
            
            if (aesKeyLength <= 0) {
                throw new IllegalArgumentException("AES密钥长度无效: " + aesKeyLength);
            }
            if (aesKeyLength > 1024) {
                throw new IllegalArgumentException("AES密钥长度过大: " + aesKeyLength);
            }
            if (aesKeyLength > remaining) {
                throw new IllegalArgumentException("AES密钥长度超出数据范围");
            }
            
            byte[] encryptedAesKey = new byte[aesKeyLength];
            System.arraycopy(data, offset, encryptedAesKey, 0, aesKeyLength);
            offset += aesKeyLength;
            remaining -= aesKeyLength;
            
            if (remaining < 4) {
                throw new IllegalArgumentException("数据不完整，缺少IV长度字段");
            }
            
            int ivLength = KeyUtils.bytesToInt(data, offset);
            offset += 4;
            remaining -= 4;
            
            if (ivLength <= 0) {
                throw new IllegalArgumentException("IV长度无效: " + ivLength);
            }
            if (ivLength > 128) {
                throw new IllegalArgumentException("IV长度过大: " + ivLength);
            }
            if (ivLength > remaining) {
                throw new IllegalArgumentException("IV长度超出数据范围");
            }
            
            byte[] iv = new byte[ivLength];
            System.arraycopy(data, offset, iv, 0, ivLength);
            offset += ivLength;
            remaining -= ivLength;
            
            if (remaining < 4) {
                throw new IllegalArgumentException("数据不完整，缺少密文长度字段");
            }
            
            int ciphertextLength = KeyUtils.bytesToInt(data, offset);
            offset += 4;
            remaining -= 4;
            
            if (ciphertextLength <= 0) {
                throw new IllegalArgumentException("密文长度无效: " + ciphertextLength);
            }
            if (ciphertextLength > remaining) {
                throw new IllegalArgumentException("密文长度超出数据范围");
            }
            
            byte[] ciphertext = new byte[ciphertextLength];
            System.arraycopy(data, offset, ciphertext, 0, ciphertextLength);
            
            return new EncryptedData(encryptedAesKey, iv, ciphertext);
        }
    }
}
