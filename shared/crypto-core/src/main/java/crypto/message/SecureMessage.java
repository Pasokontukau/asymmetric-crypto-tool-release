package crypto.message;

import core.model.SignedAndEncryptedData;
import crypto.cipher.Decryptor;
import crypto.cipher.Encryptor;
import crypto.signature.SignatureManager;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;

public class SecureMessage {
    
    /**
     * 先签名再加密（发送方使用）
     * @param plaintext 明文数据
     * @param senderPrivateKey 发送方私钥（用于签名）
     * @param receiverPublicKey 接收方公钥（用于加密）
     * @return 签名+加密的结果
     * @throws Exception 任何异常
     */
    public static SignedAndEncryptedData signAndEncrypt(
            byte[] plaintext, 
            PrivateKey senderPrivateKey, 
            PublicKey receiverPublicKey
    ) throws Exception {
        
        // 第一步：先签名
        byte[] signature = SignatureManager.signBytes(plaintext, senderPrivateKey);
        
        // 第二步：再加密
        Encryptor.EncryptedData encryptedData = Encryptor.encryptData(plaintext, receiverPublicKey);
        
        return new SignedAndEncryptedData(signature, encryptedData);
    }
    
    /**
     * 先签名再加密（文本版本）
     */
    public static SignedAndEncryptedData signAndEncryptText(
            String plaintext,
            PrivateKey senderPrivateKey,
            PublicKey receiverPublicKey
    ) throws Exception {
        return signAndEncrypt(plaintext.getBytes(StandardCharsets.UTF_8), senderPrivateKey, receiverPublicKey);
    }
    
    /**
     * 先解密再验证（接收方使用）
     * @param signedAndEncryptedData 签名+加密的数据
     * @param senderPublicKey 发送方公钥（用于验证签名）
     * @param receiverPrivateKey 接收方私钥（用于解密）
     * @return 解密+验证的结果
     * @throws Exception 任何异常
     */
    public static DecryptAndVerifyResult decryptAndVerify(
            SignedAndEncryptedData signedAndEncryptedData,
            PublicKey senderPublicKey,
            PrivateKey receiverPrivateKey
    ) throws Exception {
        
        // 第一步：先解密
        byte[] plaintext = Decryptor.decryptData(
                signedAndEncryptedData.getEncryptedData(), 
                receiverPrivateKey
        );
        
        // 第二步：再验证签名
        boolean signatureValid = SignatureManager.verifyBytes(
                plaintext,
                signedAndEncryptedData.getSignature(),
                senderPublicKey
        );
        
        return new DecryptAndVerifyResult(plaintext, signatureValid);
    }
    
    /**
     * 解密验证结果
     */
    public static class DecryptAndVerifyResult {
        private final byte[] plaintext;
        private final boolean signatureValid;
        
        public DecryptAndVerifyResult(byte[] plaintext, boolean signatureValid) {
            this.plaintext = plaintext;
            this.signatureValid = signatureValid;
        }
        
        public byte[] getPlaintext() {
            return plaintext;
        }
        
        public String getPlaintextAsString() throws Exception {
            return new String(plaintext, StandardCharsets.UTF_8);
        }
        
        public boolean isSignatureValid() {
            return signatureValid;
        }
    }
}
