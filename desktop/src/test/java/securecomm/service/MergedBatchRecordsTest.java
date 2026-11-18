package securecomm.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import core.model.FileInfo;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 合并结果进入结果卡与历史时的记录规则：每卷一条输入、产物一条输出。
 * 这些计数此前埋在 SwingWorker 里，没有任何测试守着。
 */
public class MergedBatchRecordsTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void recordsEveryVolumeOnceAndOneProduct() throws Exception {
        List<File> parts = volumes(3);
        File product = file("Photos.zip", 64);

        MergedBatchRecords records = MergedBatchRecords.of(
                MergeOutcome.success(product, 3, 64L, true, "Photos.zip"), parts);

        assertEquals("每个选中卷一条输入记录", 3, records.getInputs().size());
        assertEquals(3, records.getPartCount());
        assertTrue(records.isSignatureValid());

        FileInfo output = records.getOutput();
        assertEquals("Photos.zip", output.getFileName());
        assertEquals("Photos.zip", output.getOriginalFileName());
        assertEquals(Long.valueOf(64L), output.getFileSize());
        assertEquals(Integer.valueOf(3), output.getVolumeCount());
        assertTrue(output.getIsSplit());
        assertTrue(output.getSignatureValid());

        for (int index = 0; index < parts.size(); index++) {
            FileInfo input = records.getInputs().get(index);
            assertEquals(parts.get(index).getName(), input.getFileName());
            assertEquals(parts.get(index).getAbsolutePath(), input.getFilePath());
            assertNull("验证通过时不该有错误标注", input.getErrorMessage());
            assertTrue(input.getSignatureValid());
        }
    }

    @Test
    public void invalidSignatureMarksEveryVolume() throws Exception {
        List<File> parts = volumes(2);
        File product = file("report.zip", 16);

        MergedBatchRecords records = MergedBatchRecords.of(
                MergeOutcome.success(product, 2, 16L, false, "report.zip"), parts);

        assertFalse(records.isSignatureValid());
        assertFalse(records.getOutput().getSignatureValid());
        for (FileInfo input : records.getInputs()) {
            assertEquals("签名无效", input.getErrorMessage());
            assertFalse(input.getSignatureValid());
        }
    }

    @Test
    public void keepsProductWhenSelectionListIsEmpty() throws Exception {
        File product = file("empty.zip", 8);

        MergedBatchRecords records = MergedBatchRecords.of(
                MergeOutcome.success(product, 1, 8L, true, null),
                Collections.<File>emptyList());

        assertTrue("没有卷记录也不该丢产物", records.getInputs().isEmpty());
        assertEquals("empty.zip", records.getOutput().getFileName());
        assertEquals("归档名为空时退回文件名", "empty.zip",
                records.getOutput().getOriginalFileName());
    }

    private List<File> volumes(int count) throws Exception {
        List<File> parts = new ArrayList<File>();
        for (int index = 1; index <= count; index++) {
            parts.add(file(String.format("secure_file_20261107_120000_2f7c1a_vol%03d.aegix",
                    index), 32));
        }
        return parts;
    }

    private File file(String name, int bytes) throws Exception {
        File file = temporaryFolder.newFile(name);
        Files.write(file.toPath(), new byte[bytes]);
        return file;
    }
}
