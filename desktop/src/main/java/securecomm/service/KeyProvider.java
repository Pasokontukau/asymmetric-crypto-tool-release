package securecomm.service;

import java.security.PrivateKey;
import java.util.List;
import keymanagement.model.KeyPairInfo;

/**
 * 密钥访问的薄边界：界面/Worker 只依赖它，不直接调静态存储。
 * 默认实现见 {@link DefaultKeyProvider}，不改动 {@code KeyPairStorage} / {@code PrivateKeyAccessService} 内部。
 */
public interface KeyProvider {

    /** 列出本机所有密钥对；失败由调用方决定如何提示。 */
    List<KeyPairInfo> list() throws Exception;

    /** 按名称取私钥；口令缺失或用户取消时返回 {@code null}。 */
    PrivateKey privateKey(String keyName);

    /** 清掉某个密钥对的内存口令缓存。 */
    void clearPasswordCache(String keyName);

    /** 清掉所有密钥对的内存口令缓存。 */
    void clearAllPasswordCaches();
}
