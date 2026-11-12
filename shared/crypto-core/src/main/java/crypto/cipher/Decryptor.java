package crypto.cipher;

import core.util.LogManager;
import core.util.OperationMonitor;
import core.util.ValidationUtils;
import crypto.cipher.Encryptor;
import crypto.config.CryptoConfig;
import java.security.PrivateKey;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class Decryptor {
    
    private static final String AES_ALGORITHM = CryptoConfig.AES_ALGORITHM;
    private static final int GCM_TAG_LENGTH = CryptoConfig.GCM_TAG_LENGTH;
    
    public static byte[] decryptData(Encryptor.EncryptedData encryptedData, PrivateKey privateKey) throws Exception {
        ValidationUtils.notNull(encryptedData, "encryptedData");
        ValidationUtils.notNull(privateKey, "privateKey");
        
        final String operationId = OperationMonitor.startOperation(OperationMonitor.OperationType.DATA_DECRYPT, 
            "数据解密", encryptedData.getCiphertext().length);
        
        try {
            OperationMonitor.addContextData(operationId, "privateKeyAlgorithm", privateKey.getAlgorithm());
            OperationMonitor.addContextData(operationId, "privateKeyFormat", privateKey.getFormat());
            OperationMonitor.addContextData(operationId, "encryptedAesKeySize", encryptedData.getEncryptedAesKey().length);
            OperationMonitor.addContextData(operationId, "ivSize", encryptedData.getIv().length);
            
            final long startTime = System.currentTimeMillis();
            LogManager.infoWithResource("开始解密数据 | 密文大小: " + LogManager.formatBytes(encryptedData.getCiphertext().length) + 
                " | 私钥算法: " + privateKey.getAlgorithm() + 
                " | 私钥格式: " + privateKey.getFormat() + 
                " | 加密AES密钥大小: " + LogManager.formatBytes(encryptedData.getEncryptedAesKey().length) + 
                " | IV大小: " + LogManager.formatBytes(encryptedData.getIv().length));
            
            final SecretKey aesKey = decryptAesKey(encryptedData.getEncryptedAesKey(), privateKey);
            LogManager.debug("解密AES密钥成功 | 密钥算法: " + aesKey.getAlgorithm() + 
                " | 密钥格式: " + aesKey.getFormat());
            
            final byte[] plaintext = decryptWithAesKey(encryptedData.getCiphertext(), aesKey, encryptedData.getIv());
            LogManager.debug("AES解密完成 | 密文大小: " + LogManager.formatBytes(encryptedData.getCiphertext().length) + 
                " | 明文大小: " + LogManager.formatBytes(plaintext.length));
            
            final long endTime = System.currentTimeMillis();
            LogManager.infoWithResource("解密完成 | 总耗时: " + (endTime - startTime) + "ms | " +
                "密文大小: " + LogManager.formatBytes(encryptedData.getCiphertext().length) + " | " +
                "明文大小: " + LogManager.formatBytes(plaintext.length));
            
            OperationMonitor.updateProgress(operationId, encryptedData.getCiphertext().length);
            OperationMonitor.endOperation(operationId, true, "数据解密成功");
            
            return plaintext;
        } catch (Exception e) {
            OperationMonitor.recordException(operationId, e);
            throw e;
        }
    }
    
    private static SecretKey decryptAesKey(byte[] encryptedAesKey, PrivateKey privateKey) throws Exception {
        // 显式 OAEP 参数，保证与桌面端 JDK 默认（MGF1-SHA1）互通。
        final Cipher rsaCipher = OaepCompat.decryptCipher(privateKey);
        final byte[] aesKeyBytes = rsaCipher.doFinal(encryptedAesKey);
        return new SecretKeySpec(aesKeyBytes, "AES");
    }
    
    private static byte[] decryptWithAesKey(byte[] ciphertext, SecretKey aesKey, byte[] iv) throws Exception {
        final Cipher aesCipher = Cipher.getInstance(AES_ALGORITHM);
        final GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        aesCipher.init(Cipher.DECRYPT_MODE, aesKey, gcmSpec);
        return aesCipher.doFinal(ciphertext);
    }
}
