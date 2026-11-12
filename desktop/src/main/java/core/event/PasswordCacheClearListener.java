package core.event;














public interface PasswordCacheClearListener {
    /**
     * 当需要清除特定密钥对的密码缓存时调用
     * @param keyPairName 密钥对名称
     */
    void onClearPasswordCache(String keyPairName);
    
    /**
     * 当需要清除所有密码缓存时调用
     */
    void onClearAllPasswordCaches();
}
