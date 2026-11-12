package crypto.signature;

import java.security.PrivateKey;
import java.security.PublicKey;

public interface Signer {
    
    /**
     * 使用私钥对数据进行签名
     * @param data 待签名的数据
     * @param privateKey 私钥
     * @return 签名数据
     * @throws Exception 签名失败时抛出
     */
    byte[] sign(byte[] data, PrivateKey privateKey) throws Exception;
    
    /**
     * 使用公钥验证签名
     * @param data 原始数据
     * @param signature 签名数据
     * @param publicKey 公钥
     * @return 签名有效返回 true，否则返回 false
     * @throws Exception 验证失败时抛出
     */
    boolean verify(byte[] data, byte[] signature, PublicKey publicKey) throws Exception;
}