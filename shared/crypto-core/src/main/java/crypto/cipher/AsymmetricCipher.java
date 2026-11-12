package crypto.cipher;

import java.security.PrivateKey;
import java.security.PublicKey;

public interface AsymmetricCipher {
    
    /**
     * 使用公钥加密数据
     * @param data 待加密的明文数据
     * @param publicKey 公钥
     * @return 加密后的密文
     * @throws Exception 加密失败时抛出
     */
    byte[] encrypt(byte[] data, PublicKey publicKey) throws Exception;
    
    /**
     * 使用私钥解密数据
     * @param data 待解密的密文数据
     * @param privateKey 私钥
     * @return 解密后的明文
     * @throws Exception 解密失败时抛出
     */
    byte[] decrypt(byte[] data, PrivateKey privateKey) throws Exception;
}