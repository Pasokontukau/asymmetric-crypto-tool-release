package crypto.cipher;

import javax.crypto.SecretKey;

public interface SymmetricCipher {
    
    /**
     * 使用密钥加密数据
     * @param data 待加密的明文数据
     * @param key 对称密钥
     * @return 加密后的密文（包含 IV 和认证标签）
     * @throws Exception 加密失败时抛出
     */
    byte[] encrypt(byte[] data, SecretKey key) throws Exception;
    
    /**
     * 使用密钥解密数据
     * @param data 待解密的密文数据（包含 IV 和认证标签）
     * @param key 对称密钥
     * @return 解密后的明文
     * @throws Exception 解密失败时抛出
     */
    byte[] decrypt(byte[] data, SecretKey key) throws Exception;
}