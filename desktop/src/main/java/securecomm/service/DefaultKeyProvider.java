package securecomm.service;

import crypto.key.PrivateKeyAccessService;
import java.security.PrivateKey;
import java.util.List;
import keymanagement.model.KeyPairInfo;
import keymanagement.storage.KeyPairStorage;

/** 直接适配现有静态存储的默认实现；不缓存、不改行为。 */
public class DefaultKeyProvider implements KeyProvider {

    @Override
    public List<KeyPairInfo> list() throws Exception {
        return KeyPairStorage.loadAllKeyPairs();
    }

    @Override
    public PrivateKey privateKey(String keyName) {
        return PrivateKeyAccessService.getPrivateKey(keyName);
    }

    @Override
    public void clearPasswordCache(String keyName) {
        PrivateKeyAccessService.clearPasswordCache(keyName);
    }

    @Override
    public void clearAllPasswordCaches() {
        PrivateKeyAccessService.clearAllPasswordCaches();
    }
}
