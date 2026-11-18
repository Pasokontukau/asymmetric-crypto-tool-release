package history.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import core.storage.StorageManager;
import core.util.ExportCrypto;
import history.model.HistoryRecord;
import history.model.HistoryType;
import history.model.RecordCategory;
import history.storage.HistoryStorage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import platform.windows.LocalCryptoKeyManager;

/** 历史仓库：查询、收藏、备注、删除、清空与导出都经同一入口，存储格式不变。 */
public class HistoryRepositoryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final HistoryRepository repository = new HistoryRepository();
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
    public void queryAndMutationsGoThroughRepository() throws Exception {
        Assume.assumeTrue("本机本地加密不可用，无法验证历史仓库",
                LocalCryptoKeyManager.isKeyAvailable());

        HistoryRecord first = record("文件加密", "张三", "第一份报表");
        HistoryRecord second = record("文件解密", "李四", "第二份报表");
        assertTrue(HistoryStorage.addHistory(first));
        assertTrue(HistoryStorage.addHistory(second));

        assertEquals(2, repository.loadAll().size());
        assertEquals(2, repository.contacts().size());
        assertEquals(1, repository.search("第一份").size());
        assertEquals(1, repository.byContact("李四").size());
        assertEquals(1, repository.query("报表", "张三", false).size());
        assertTrue(repository.starred().isEmpty());

        repository.setStarred(first.getId(), true);
        assertEquals(1, repository.starred().size());
        assertEquals("收藏与联系人取交集", 1, repository.query(null, "张三", true).size());
        assertTrue("联系人不同时收藏结果应为空",
                repository.query(null, "李四", true).isEmpty());

        repository.addNotes(first.getId(), "已核对");
        HistoryRecord reloaded = find("已核对");
        assertNotNull("备注应写回同一条记录", reloaded);
        assertEquals(first.getId(), reloaded.getId());

        repository.delete(second.getId());
        assertEquals(1, repository.loadAll().size());

        repository.clear();
        assertTrue(repository.loadAll().isEmpty());
    }

    @Test
    public void updatesFilePathsThroughRepository() throws Exception {
        Assume.assumeTrue("本机本地加密不可用，无法验证历史路径迁移",
                LocalCryptoKeyManager.isKeyAvailable());

        HistoryRecord record = record("文件加密", "张三", "搬迁报表");
        assertTrue(HistoryStorage.addHistory(record));

        int updated = repository.updateFilePaths("C:\\", "D:\\");
        assertTrue("输入/输出路径都应被改写，实际 " + updated, updated >= 2);

        HistoryRecord reloaded = repository.loadAll().get(0);
        assertTrue(reloaded.getFileInputPath().startsWith("D:\\"));
        assertTrue(reloaded.getFileOutputPath().startsWith("D:\\"));
    }

    @Test
    public void plainAndEncryptedExportUseSameRecords() throws Exception {
        HistoryRecord record = record("文件加密", "张三", "季度报表");
        List<HistoryRecord> records = java.util.Collections.singletonList(record);

        File json = temporaryFolder.newFile("history.json");
        repository.writePlain(json, records, HistoryRepository.Format.JSON);
        String jsonText = new String(Files.readAllBytes(json.toPath()), StandardCharsets.UTF_8);
        assertTrue(jsonText.contains("季度报表"));

        File csv = temporaryFolder.newFile("history.csv");
        repository.writePlain(csv, records, HistoryRepository.Format.CSV);
        String csvText = new String(Files.readAllBytes(csv.toPath()), StandardCharsets.UTF_8);
        assertTrue(csvText.contains("ID,时间,类型"));

        File html = temporaryFolder.newFile("history.html");
        repository.writePlain(html, records, HistoryRepository.Format.HTML);
        String htmlText = new String(Files.readAllBytes(html.toPath()), StandardCharsets.UTF_8);
        assertTrue(htmlText.contains("<table>"));

        File encrypted = temporaryFolder.newFile("history.aexp");
        repository.writeEncrypted(encrypted, records, "repo-pass-123".toCharArray());
        byte[] stored = Files.readAllBytes(encrypted.toPath());
        assertTrue("加密导出必须是加密容器", ExportCrypto.isEncrypted(stored));
        String raw = new String(stored, StandardCharsets.ISO_8859_1);
        assertFalse("加密导出不能出现明文路径", raw.contains("季度报表"));
    }

    private HistoryRecord find(String notes) {
        for (HistoryRecord record : repository.loadAll()) {
            if (notes.equals(record.getNotes())) {
                return record;
            }
        }
        return null;
    }

    private static HistoryRecord record(String operation, String contact, String description) {
        HistoryRecord record = new HistoryRecord(
                operation,
                "C:\\input\\" + description + ".txt",
                "C:\\output\\" + description + ".aegix",
                "测试密钥",
                contact,
                "C:\\input\\" + description + ".txt",
                "C:\\output\\" + description + ".aegix",
                description);
        record.setType(HistoryType.ENCRYPT);
        record.setCategory(RecordCategory.BATCH);
        record.setSuccess(true);
        record.setResult("成功");
        return record;
    }
}
