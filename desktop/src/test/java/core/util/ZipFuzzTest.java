package core.util;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Random;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * F-02：ZIP 结构层模糊测试（固定种子）。
 *
 * <p>输入是合法 ZIP 的字节级变异，交给 {@link FolderZipUtils#unzipFolder}。判据：
 * 不崩溃、不卡死、只抛受检异常；**任何情况下都不能写到目标目录之外**。
 * 目标目录内留下半成品是允许的（真实调用方会清理），越界写入不可以。</p>
 */
public class ZipFuzzTest {

    private static final long SEED = 20261124L;
    private static final int ITERATIONS = 200;
    private static final long TIME_BUDGET_MS = 90_000L;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File buildZip() throws Exception {
        File source = folder.newFolder("source");
        File nested = new File(source, "sub");
        assertTrue(nested.mkdirs());
        Files.write(new File(source, "a.txt").toPath(), "alpha".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(nested, "b.txt").toPath(), "beta".getBytes(StandardCharsets.UTF_8));
        File zip = new File(folder.getRoot(), "sample.zip");
        FolderZipUtils.zipFolder(source, zip, null);
        assertTrue(zip.isFile());
        return zip;
    }

    private static byte[] mutate(byte[] source, Random random) {
        byte[] copy;
        switch (random.nextInt(3)) {
            case 0:
                copy = source.clone();
                int flips = 1 + random.nextInt(4);
                for (int i = 0; i < flips; i++) {
                    copy[random.nextInt(copy.length)] ^= (byte) (1 << random.nextInt(8));
                }
                return copy;
            case 1:
                return java.util.Arrays.copyOf(source, random.nextInt(source.length));
            default:
                int at = random.nextInt(source.length);
                int span = 1 + random.nextInt(32);
                copy = new byte[source.length + span];
                System.arraycopy(source, 0, copy, 0, at);
                for (int i = 0; i < span; i++) {
                    copy[at + i] = (byte) random.nextInt(256);
                }
                System.arraycopy(source, at, copy, at + span, source.length - at);
                return copy;
        }
    }

    @Test
    public void mutatedZipsNeverEscapeDestinationOrCrash() throws Exception {
        byte[] original = Files.readAllBytes(buildZip().toPath());
        Random random = new Random(SEED);
        File sandbox = folder.newFolder("sandbox");
        long started = System.currentTimeMillis();
        int rejected = 0;
        int accepted = 0;

        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            if (System.currentTimeMillis() - started > TIME_BUDGET_MS) {
                break;
            }
            byte[] mutated = mutate(original, random);
            File zip = new File(sandbox, "mutated-" + iteration + ".zip");
            Files.write(zip.toPath(), mutated);
            File dest = new File(sandbox, "out-" + iteration);
            try {
                FolderZipUtils.unzipFolder(zip, dest, null);
                accepted++;
                // 被接受也不能写坏：同名的文件内容必须与原始一致。
                assertContentIfPresent(dest, "a.txt", "alpha");
                assertContentIfPresent(new File(dest, "sub"), "b.txt", "beta");
            } catch (IOException | IllegalArgumentException | IllegalStateException expected) {
                rejected++;
            } catch (RuntimeException unexpected) {
                throw new AssertionError("第 " + iteration + " 次变异抛出了未预期异常（种子 "
                        + SEED + "）: " + unexpected.getClass().getName(), unexpected);
            } catch (Error error) {
                throw new AssertionError("第 " + iteration + " 次变异抛出了 Error（种子 "
                        + SEED + "）: " + error.getClass().getName(), error);
            }

            // 越界检查：sandbox 下只允许出现 mutated-*.zip 与 out-* 目录。
            File[] entries = sandbox.listFiles();
            assertNotNull(entries);
            for (File entry : entries) {
                String name = entry.getName();
                assertTrue("出现越界文件: " + name,
                        name.startsWith("mutated-") || name.startsWith("out-"));
            }
        }

        assertTrue("应至少拒绝一部分变异（rejected=" + rejected + "）", rejected > 0);
        // 校验 CRC 后，只有结构仍然完全有效的变异才会被接受，比例应当很低。
        assertTrue("损坏包被接受的比例过高（accepted=" + accepted + "）",
                accepted <= ITERATIONS / 5);
    }

    private static void assertContentIfPresent(File directory, String name, String expected)
            throws IOException {
        File file = new File(directory, name);
        if (!file.isFile()) {
            return;
        }
        String actual = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        org.junit.Assert.assertEquals("解压出的内容被损坏但被当成成功: " + name, expected, actual);
    }
}
