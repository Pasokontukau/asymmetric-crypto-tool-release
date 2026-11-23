package securecomm.ui.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * G-03：两个（或多个）实例同时写同一个输出目录/同名文件。
 *
 * <p>要求：最终文件必须是**某个写入者的完整内容**，不能是两边交错的半截；
 * 临时文件不能残留；失败的一方必须抛异常而不是静默当作成功。</p>
 */
public class AtomicFileOutputConcurrencyTest {

    private static final int WRITERS = 4;
    private static final int SIZE = 1024 * 1024;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static byte[] pattern(int seed) {
        byte[] data = new byte[SIZE];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (seed * 37 + i);
        }
        return data;
    }

    @Test
    public void concurrentWritersProduceOneCompleteFileAndNoTemporaries() throws Exception {
        File directory = folder.newFolder("shared-output");
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(WRITERS);
        AtomicInteger successes = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();
        List<byte[]> payloads = new ArrayList<>();

        for (int index = 0; index < WRITERS; index++) {
            byte[] payload = pattern(index + 1);
            payloads.add(payload);
            Thread thread = new Thread(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    AtomicFileOutput.write(directory, "out.bin", (OutputStream output) -> {
                        // 分块写，给其它线程插队的机会。
                        for (int offset = 0; offset < payload.length; offset += 64 * 1024) {
                            output.write(payload, offset, Math.min(64 * 1024, payload.length - offset));
                            Thread.yield();
                        }
                    });
                    successes.incrementAndGet();
                } catch (Throwable failure) {
                    synchronized (failures) {
                        failures.add(failure);
                    }
                } finally {
                    done.countDown();
                }
            }, "writer-" + index);
            thread.start();
        }

        start.countDown();
        assertTrue("并发写入超时", done.await(60, TimeUnit.SECONDS));

        File target = new File(directory, "out.bin");
        assertTrue("最终文件必须存在", target.isFile());
        byte[] actual = Files.readAllBytes(target.toPath());
        assertEquals("最终文件长度不对", SIZE, actual.length);

        boolean matchesOneWriter = false;
        for (byte[] payload : payloads) {
            if (java.util.Arrays.equals(payload, actual)) {
                matchesOneWriter = true;
                break;
            }
        }
        assertTrue("最终内容是交错/半截数据：既不属于任何一个写入者", matchesOneWriter);

        assertTrue("至少要有一个写入成功", successes.get() >= 1);
        assertEquals("成功数 + 失败数应等于写入者数量",
                WRITERS, successes.get() + failures.size());
        for (Throwable failure : failures) {
            assertNotNull("失败必须带原因", failure.getMessage());
        }

        File[] leftovers = directory.listFiles((dir, name) -> name.startsWith("aegix-write-"));
        assertNotNull(leftovers);
        assertEquals("临时文件必须清理干净", 0, leftovers.length);
    }
}
