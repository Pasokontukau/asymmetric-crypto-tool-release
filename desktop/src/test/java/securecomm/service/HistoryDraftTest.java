package securecomm.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** 历史草稿只组装字段，不读写存储；文案适配器对空键保持安全。 */
public class HistoryDraftTest {

    @Test
    public void fileDraftKeepsSplitFields() {
        HistoryDraft draft = HistoryDraft.file("加密文件", "my-key", "张三",
                "/in/a.txt", "/out/secure_file_1.aegix_vol001", true, 3, 1);

        assertEquals("加密文件", draft.operationType);
        assertEquals("my-key", draft.keypairName);
        assertEquals("/in/a.txt", draft.fileInputPath);
        assertEquals("/out/secure_file_1.aegix_vol001", draft.fileOutputPath);
        assertEquals(Boolean.TRUE, draft.isSplit);
        assertEquals(Integer.valueOf(3), draft.totalVolumes);
        assertEquals(Integer.valueOf(1), draft.currentVolume);
        assertNull(draft.inputText);
    }

    @Test
    public void textDraftHasNoFileFields() {
        HistoryDraft draft = HistoryDraft.text("加密文本", "输入", "输出", "my-key", null);

        assertEquals("输入", draft.inputText);
        assertEquals("输出", draft.outputText);
        assertNull(draft.fileInputPath);
        assertNull(draft.isSplit);
    }

    @Test
    public void messageSinkToleratesNullKey() {
        assertEquals("", new DefaultMessageSink().get(null));
    }
}
