package platform.windows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 内部密钥文件名迁移：旧 {@code local_key.enc} 只读取一次，之后只用
 * {@code local_key.bin}，旧文件随即删除，避免桌面数据目录里再出现 {@code .enc}。
 */
public class LocalCryptoKeyManagerTest {

    private String originalDataDir;
    private Path tempDir;

    @Before
    public void setUp() throws Exception {
        originalDataDir = core.storage.StorageManager.getDataDir();
        tempDir = Files.createTempDirectory("aegix-key-" + UUID.randomUUID());
        core.storage.StorageManager.useDataDirForTests(tempDir.toString());
        LocalCryptoKeyManager.resetCacheForTests();
    }

    @After
    public void tearDown() throws Exception {
        LocalCryptoKeyManager.resetCacheForTests();
        core.storage.StorageManager.useDataDirForTests(originalDataDir);
        deleteRecursively(tempDir);
    }

    @Test
    public void legacyKeyFileIsMigratedOnceAndRemoved() throws Exception {
        SecretKey created = LocalCryptoKeyManager.getOrCreateLocalKey();
        byte[] expected = created.getEncoded();

        Path newKey = tempDir.resolve("local_key.bin");
        Path legacyKey = tempDir.resolve("local_key.enc");
        assertTrue("新建的密钥应落在 local_key.bin", Files.exists(newKey));

        // 模拟旧版本留下的文件名。
        Files.move(newKey, legacyKey);
        LocalCryptoKeyManager.resetCacheForTests();

        SecretKey migrated = LocalCryptoKeyManager.getOrCreateLocalKey();

        assertArrayEquals("迁移后密钥必须与旧密钥一致", expected, migrated.getEncoded());
        assertTrue("迁移后应写入 local_key.bin", Files.exists(newKey));
        assertFalse("迁移后旧 local_key.enc 必须删除", Files.exists(legacyKey));

        // 第二次启动只读新文件，不应再依赖旧名。
        LocalCryptoKeyManager.resetCacheForTests();
        SecretKey reloaded = LocalCryptoKeyManager.getOrCreateLocalKey();
        assertArrayEquals(expected, reloaded.getEncoded());
    }

    @Test
    public void newDataDirOnlyCreatesBinKey() throws Exception {
        LocalCryptoKeyManager.getOrCreateLocalKey();

        assertTrue(Files.exists(tempDir.resolve("local_key.bin")));
        assertFalse(Files.exists(tempDir.resolve("local_key.enc")));
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        Files.walk(dir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // 清理失败不影响断言
                    }
                });
    }
}
