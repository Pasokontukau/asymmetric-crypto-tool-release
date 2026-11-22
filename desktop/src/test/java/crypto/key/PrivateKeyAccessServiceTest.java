package crypto.key;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import settings.storage.SettingsStorage;

/** 密码缓存超时必须是设置页的值，而不是写死的模式常量。 */
public class PrivateKeyAccessServiceTest {

    @Test
    public void cacheTtlFollowsConfiguredMinutes() {
        assertEquals(5L * 60 * 1000, PrivateKeyAccessService.cacheTtlMillis(5));
        assertEquals(15L * 60 * 1000, PrivateKeyAccessService.cacheTtlMillis(15));
        assertEquals(30L * 60 * 1000, PrivateKeyAccessService.cacheTtlMillis(30));
        assertEquals(60L * 60 * 1000, PrivateKeyAccessService.cacheTtlMillis(60));
    }

    @Test
    public void neverTimeoutDoesNotExpireInThisProcess() {
        assertEquals(Long.MAX_VALUE,
                PrivateKeyAccessService.cacheTtlMillis(SettingsStorage.TIMEOUT_NEVER));
    }

    @Test
    public void invalidTimeoutFallsBackToThirtyMinutes() {
        assertEquals(30L * 60 * 1000, PrivateKeyAccessService.cacheTtlMillis(0));
        assertEquals(30L * 60 * 1000, PrivateKeyAccessService.cacheTtlMillis(-2));
    }
}
