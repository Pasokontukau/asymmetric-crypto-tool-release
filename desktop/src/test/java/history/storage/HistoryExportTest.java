package history.storage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import core.util.ExportCrypto;
import history.model.HistoryRecord;
import history.model.HistoryType;
import history.model.RecordCategory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 历史导出：默认加密路径不落明文；明文格式仍按用户选择工作。 */
public class HistoryExportTest {

    private static final char[] PASSWORD = "history-export-pass".toCharArray();

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static HistoryRecord sampleRecord() {
        HistoryRecord record = new HistoryRecord(
                "文件加密",
                "C:\\Users\\me\\工资表 2026.xlsx",
                "C:\\Users\\me\\secure_file.aegix",
                "我的密钥",
                "张三",
                "C:\\Users\\me\\工资表 2026.xlsx",
                "C:\\Users\\me\\secure_file.aegix",
                "季度报表"
        );
        record.setType(HistoryType.ENCRYPT);
        record.setCategory(RecordCategory.BATCH);
        record.setSuccess(true);
        record.setResult("成功");
        return record;
    }

    @Test
    public void encryptedExportCarriesNoPlaintextNameOrPath() throws Exception {
        File target = temporaryFolder.newFile("history.aexp");

        HistoryExport.writeEncrypted(target, Collections.singletonList(sampleRecord()), PASSWORD);

        byte[] stored = Files.readAllBytes(target.toPath());
        assertTrue("应写入加密容器", ExportCrypto.isEncrypted(stored));
        String raw = new String(stored, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("工资表"));
        assertFalse(raw.contains("Users"));
        assertFalse(raw.contains("张三"));

        String decrypted = new String(ExportCrypto.decrypt(stored, PASSWORD), StandardCharsets.UTF_8);
        assertTrue("解密后仍应保留记录", decrypted.contains("工资表 2026.xlsx"));
    }

    @Test
    public void plaintextFormatsRemainOptInAndEscaped() {
        HistoryRecord record = sampleRecord();
        record.description = "含\"引号\"和<标签>";

        String csv = HistoryExport.toCsv(Collections.singletonList(record));
        assertTrue(csv.contains("\"含\"\"引号\"\"和<标签>\""));
        assertTrue(csv.contains("\"张三\""));

        String html = HistoryExport.toHtml(Collections.singletonList(record));
        assertTrue(html.contains("&quot;引号&quot;"));
        assertTrue(html.contains("&lt;标签&gt;"));
        assertFalse(html.contains("<标签>"));
    }
}
