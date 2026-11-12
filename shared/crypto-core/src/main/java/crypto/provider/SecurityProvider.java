package crypto.provider;

import core.util.LogManager;
import java.security.SecureRandom;

public class SecurityProvider {
    
    public static final SecureRandom STRONG_RANDOM;
    public static final SecureRandom REGULAR_RANDOM;
    private static final boolean strongRandomFallback;
    
    static {
        SecureRandom strong = null;
        SecureRandom regular = null;
        boolean fallback = false;
        try {
            strong = SecureRandom.getInstanceStrong();
            regular = new SecureRandom();
        } catch (Exception e) {
            fallback = true;
            LogManager.warning("无法获取强随机数生成器，尝试备选方案");
            LogManager.error("初始化随机数生成器失败", e);
            try {
                // 备选方案1：尝试 NativePRNGNonBlocking
                strong = SecureRandom.getInstance("NativePRNGNonBlocking");
                regular = SecureRandom.getInstance("SHA1PRNG");
            } catch (Exception ex) {
                LogManager.error("备选随机数生成器也失败，回退到默认 SecureRandom", ex);
                strong = new SecureRandom();
                regular = new SecureRandom();
            }
        }
        STRONG_RANDOM = strong;
        REGULAR_RANDOM = regular;
        strongRandomFallback = fallback;
    }
    
    private SecurityProvider() {
        // 禁止实例化
    }
    
    /**
     * 检查强随机数生成器是否可用（非回退模式）
     * @return true 表示强随机数生成器正常可用
     */
    public static boolean isStrongRandomAvailable() {
        return !strongRandomFallback;
    }
}
