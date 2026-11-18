package securecomm.service;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 结果文案必须与内联实现逐字一致，否则历史记录与结果标签会漂移。 */
public class HistoryEntryFactoryTest {

    @Test
    public void encryptResultWording() {
        assertEquals("成功 3", HistoryEntryFactory.encryptBatchResult(3, 0));
        assertEquals("失败 2", HistoryEntryFactory.encryptBatchResult(0, 2));
        assertEquals("成功 3，失败 2", HistoryEntryFactory.encryptBatchResult(3, 2));
        assertEquals("成功 0", HistoryEntryFactory.encryptBatchResult(0, 0));
    }

    @Test
    public void decryptResultWording() {
        assertEquals("", HistoryEntryFactory.decryptBatchResult(0, 0, 0));
        assertEquals("成功 2", HistoryEntryFactory.decryptBatchResult(2, 0, 0));
        assertEquals("失败 2", HistoryEntryFactory.decryptBatchResult(0, 2, 0));
        assertEquals("验证失败 1", HistoryEntryFactory.decryptBatchResult(0, 0, 1));
        assertEquals("失败 2，验证失败 1", HistoryEntryFactory.decryptBatchResult(0, 2, 1));
        assertEquals("成功 2，失败 1", HistoryEntryFactory.decryptBatchResult(2, 1, 0));
        assertEquals("成功 2，验证失败 1", HistoryEntryFactory.decryptBatchResult(2, 0, 1));
        assertEquals("成功 2，失败 1，验证失败 1",
                HistoryEntryFactory.decryptBatchResult(2, 1, 1));
    }

    @Test
    public void batchSummaryMentionsCount() {
        assertEquals("批量加密 3 个文件", HistoryEntryFactory.batchSummary("批量加密", 3));
        assertEquals("批量解密 12 个文件", HistoryEntryFactory.batchSummary("批量解密", 12));
    }
}
