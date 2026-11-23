package core.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * ZIP 解压在 Windows 文件系统边界上的行为（D-11 / C-03）。
 *
 * <p>判据只有一条：**不能越界写，也不能静默丢数据**。拒绝时抛可读的 IOException 是允许的；
 * 悄悄少解一个文件、或覆盖掉先解出来的同名文件，都不允许。</p>
 */
public class ZipExtractionEdgeTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File zipOf(Map<String, byte[]> entries) throws IOException {
        File zip = folder.newFile("case-" + System.nanoTime() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return zip;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> entries(String... pairs) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], bytes(pairs[i + 1]));
        }
        return map;
    }

    private String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void caseOnlyCollisionDoesNotLoseAFile() throws Exception {
        File dest = folder.newFolder("case-collision");
        File zip = zipOf(entries("A.txt", "UPPER", "a.txt", "lower"));

        FolderZipUtils.unzipFolder(zip, dest, null);

        File[] files = dest.listFiles();
        assertNotNull(files);
        assertEquals("两个仅大小写不同的条目必须都落地", 2, files.length);
        boolean sawUpper = false;
        boolean sawLower = false;
        for (File file : files) {
            String content = read(file);
            sawUpper |= "UPPER".equals(content);
            sawLower |= "lower".equals(content);
        }
        assertTrue("大写版本丢失", sawUpper);
        assertTrue("小写版本丢失", sawLower);
    }

    @Test
    public void existingFileIsNotOverwritten() throws Exception {
        File dest = folder.newFolder("no-overwrite");
        File existing = new File(dest, "keep.txt");
        Files.write(existing.toPath(), bytes("original"));
        File zip = zipOf(entries("keep.txt", "from-zip"));

        FolderZipUtils.unzipFolder(zip, dest, null);

        assertEquals("已有文件不能被覆盖", "original", read(existing));
        assertTrue("新条目应以 _2 落地", new File(dest, "keep_2.txt").isFile());
    }

    @Test
    public void parentTraversalIsRejectedAndWritesNothingOutside() throws Exception {
        File dest = folder.newFolder("traversal");
        File zip = zipOf(entries("../escape.txt", "pwned"));

        try {
            FolderZipUtils.unzipFolder(zip, dest, null);
            fail("包含 .. 的条目必须被拒绝");
        } catch (IOException expected) {
            assertFalse(new File(dest.getParentFile(), "escape.txt").exists());
        }
    }

    @Test
    public void absoluteAndDrivePathsAreRejected() throws Exception {
        for (String name : new String[] {"/abs.txt", "C:/evil.txt", "dir\\x.txt"}) {
            File dest = folder.newFolder("abs-" + Math.abs(name.hashCode()));
            File zip = zipOf(entries(name, "x"));
            try {
                FolderZipUtils.unzipFolder(zip, dest, null);
                fail("应拒绝条目: " + name);
            } catch (IOException expected) {
                // 期望：拒绝。
            }
        }
    }

    @Test
    public void controlCharactersAreRejected() throws Exception {
        File dest = folder.newFolder("control");
        File zip = zipOf(entries("bad\u0001name.txt", "x"));
        try {
            FolderZipUtils.unzipFolder(zip, dest, null);
            fail("含控制字符的条目必须被拒绝");
        } catch (IOException expected) {
            // 期望：拒绝。
        }
    }

    /**
     * Windows 会悄悄去掉结尾的点和空格，"a." 与 "a" 实际是同一个文件。
     * 两个条目并存时会静默合并，因此必须直接拒绝。
     */
    @Test
    public void trailingDotOrSpaceIsRejected() throws Exception {
        for (String name : new String[] {"dot.", "space ", "dir./inner.txt"}) {
            File dest = folder.newFolder("tail-" + Math.abs(name.hashCode()));
            File zip = zipOf(entries(name, "tail"));
            try {
                FolderZipUtils.unzipFolder(zip, dest, null);
                fail("条目 " + name + " 以点/空格结尾，必须被拒绝");
            } catch (IOException expected) {
                File[] produced = dest.listFiles();
                assertNotNull(produced);
                assertEquals("拒绝前不应已经写出文件: " + name, 0, produced.length);
            }
        }
    }

    @Test
    public void windowsDeviceNamesAreRejected() throws Exception {
        for (String name : new String[] {"CON.txt", "nul", "PRN.tar.gz", "COM1", "LPT9.log", "sub/AUX.txt"}) {
            File dest = folder.newFolder("reserved-" + Math.abs(name.hashCode()));
            File zip = zipOf(entries(name, "x"));
            try {
                FolderZipUtils.unzipFolder(zip, dest, null);
                fail("Windows 保留名必须被拒绝: " + name);
            } catch (IOException expected) {
                assertTrue("错误信息应说明是保留名: " + expected.getMessage(),
                        expected.getMessage().contains("保留名"));
            }
        }
    }

    /**
     * 长路径：Java 在部分 Windows 配置下能写超过 MAX_PATH 的路径，也可能直接报错。
     * 两种情况都可接受，但必须给出 IOException 而不是崩溃，且不能写到目标目录之外。
     */
    @Test
    public void deepPathEitherSucceedsOrFailsCleanly() throws Exception {
        // 29 段 × 10 字符 + 文件名 ≈ 300 字符，确保真的超过 Windows MAX_PATH(260)，
        // 且仍在 MAX_SEGMENTS(32) 与 MAX_PATH_BYTES(1024) 之内。
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 29; i++) {
            name.append("segment").append(i < 10 ? "0" : "").append(i).append('/');
        }
        name.append("deep.txt");

        File dest = folder.newFolder("deep");
        File zip = zipOf(entries(name.toString(), "deep"));
        try {
            FolderZipUtils.unzipFolder(zip, dest, null);
            // 成功：内容必须在目标目录内。
            File parent = dest;
            String[] parts = name.toString().split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                parent = new File(parent, parts[i]);
            }
            assertTrue(new File(parent, "deep.txt").isFile());
        } catch (IOException expected) {
            // 失败：可接受，但路径必须仍指向目标目录内。
        }
    }

    /**
     * S-16：内容损坏但结构还能解析的 ZIP（STORED + CRC 不符）必须被拒绝，
     * 而不是“解压成功”后给用户一堆坏文件。
     */
    @Test
    public void corruptedStoredEntryIsRejected() throws Exception {
        byte[] data = "hello-corrupted-payload".getBytes(StandardCharsets.UTF_8);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);

        File zip = folder.newFile("stored.zip");
        try (java.util.zip.ZipOutputStream out =
                     new java.util.zip.ZipOutputStream(new FileOutputStream(zip))) {
            java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry("payload.bin");
            entry.setMethod(java.util.zip.ZipEntry.STORED);
            entry.setSize(data.length);
            entry.setCrc(crc.getValue());
            out.putNextEntry(entry);
            out.write(data);
            out.closeEntry();
        }

        byte[] bytes = Files.readAllBytes(zip.toPath());
        int needle = indexOf(bytes, data);
        assertTrue("测试数据没找到", needle >= 0);
        bytes[needle + 3] ^= 0x7F;
        Files.write(zip.toPath(), bytes);

        File dest = folder.newFolder("corrupted");
        try {
            FolderZipUtils.unzipFolder(zip, dest, null);
            fail("CRC 不符的条目必须被拒绝");
        } catch (IOException expected) {
            assertTrue("错误信息应提到校验/CRC: " + expected.getMessage(),
                    expected.getMessage().contains("CRC") || expected.getMessage().contains("校验"));
        }
        File[] produced = dest.listFiles();
        assertNotNull(produced);
        assertEquals("失败时不能留下半成品", 0, produced.length);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    @Test
    public void entryCountLimitIsEnforced() throws Exception {
        Map<String, byte[]> many = new LinkedHashMap<>();
        for (int i = 0; i <= FolderZipUtils.MAX_ENTRIES; i++) {
            many.put("f" + i + ".txt", new byte[] {1});
        }
        File zip = zipOf(many);
        File dest = folder.newFolder("too-many");
        try {
            FolderZipUtils.unzipFolder(zip, dest, null);
            fail("超过条目上限必须被拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("条目"));
        }
    }
}
