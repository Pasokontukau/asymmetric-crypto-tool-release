package core.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import history.model.HistoryRecord;
import history.storage.HistoryStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import platform.windows.LocalCryptoKeyManager;
import platform.windows.LocalDataEncryptor;

/**
 * 桌面历史只认当前加密格式。
 *
 * <p>旧版 {@code history.json} 不再读取、不再迁移，启动时直接删除；旧明文
 * {@code history.dat} 也不会被当作历史解析。当前唯一历史文件是 AES-GCM 加密的
 * {@code history.dat}。</p>
 */
public class HistoryStorageLegacyCleanupTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private String originalDataDir;

    @Before
    public void useTemporaryDataDir() throws Exception {
        originalDataDir = StorageManager.getDataDir();
        StorageManager.useDataDirForTests(temporaryFolder.getRoot().getAbsolutePath());
    }

    @After
    public void restoreDataDir() {
        try {
            LocalCryptoKeyManager.resetLocalKey();
        } catch (Exception ignored) {
            // 测试清理失败不要覆盖断言结果。
        }
        StorageManager.useDataDirForTests(originalDataDir);
    }

    @Test
    public void legacyPlaintextIsDeletedAndNotMigrated() throws Exception {
        Assume.assumeTrue("本机本地加密不可用，无法验证历史清理",
                LocalCryptoKeyManager.isKeyAvailable());

        Path legacy = Paths.get(StorageManager.getDataDir(), "history.json");
        Files.write(legacy, "[{\"description\":\"旧记录\"}]".getBytes(StandardCharsets.UTF_8));

        List<HistoryRecord> loaded = HistoryStorage.loadHistory();

        assertEquals("旧明文不再导入", 0, loaded.size());
        assertFalse("旧明文必须删除", Files.exists(legacy));
        assertFalse("不能为了旧明文生成新的加密历史",
                Files.exists(Paths.get(StorageManager.getDataDir(), "history.dat")));
    }

    @Test
    public void encryptedHistoryStillLoadsAndLeftoverPlaintextIsDeleted() throws Exception {
        Assume.assumeTrue("本机本地加密不可用，无法验证加密历史",
                LocalCryptoKeyManager.isKeyAvailable());

        String json = "[{\"description\":\"文件加密\",\"success\":true}]";
        Path encrypted = Paths.get(StorageManager.getDataDir(), "history.dat");
        Files.write(encrypted, LocalDataEncryptor.encryptHistoryData(json));

        Path legacy = Paths.get(StorageManager.getDataDir(), "history.json");
        Files.write(legacy, json.getBytes(StandardCharsets.UTF_8));

        List<HistoryRecord> loaded = HistoryStorage.loadHistory();

        assertEquals(1, loaded.size());
        assertTrue("加密历史仍应可读", Files.exists(encrypted));
        assertFalse("旧明文必须清掉", Files.exists(legacy));
    }

    @Test
    public void plaintextHistoryDatIsRejected() throws Exception {
        Assume.assumeTrue("本机本地加密不可用，无法验证历史格式判定",
                LocalCryptoKeyManager.isKeyAvailable());

        Path history = Paths.get(StorageManager.getDataDir(), "history.dat");
        Files.write(history, "[{\"description\":\"旧明文\"}]".getBytes(StandardCharsets.UTF_8));

        assertEquals("旧明文 .dat 不再解析", 0, HistoryStorage.loadHistory().size());
        assertTrue("损坏/旧格式文件保留给用户自行处理", Files.exists(history));
    }
}
