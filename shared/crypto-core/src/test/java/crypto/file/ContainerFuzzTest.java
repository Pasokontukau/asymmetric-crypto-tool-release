package crypto.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * 容器格式的固定种子模糊测试（F-01）。
 *
 * <p>样本是合法的小容器，只做结构层变异（翻字节、截断、拼接）。判据：
 * <ul>
 *   <li>不能抛 Error / OutOfMemory / 数组越界这类“未预期”异常；</li>
 *   <li>要么成功解出**原始明文**，要么以可读的受检异常拒绝；</li>
 *   <li>不能卡死（总时长设上限）。</li>
 * </ul>
 * 固定种子保证可复现；失败时打印种子与迭代序号。</p>
 */
public class ContainerFuzzTest {

    private static final long SEED = 20261124L;
    private static final int ITERATIONS = 300;
    private static final int PAYLOAD_SIZE = 2048;
    private static final long TIME_BUDGET_MS = 120_000L;

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static byte[] payload() {
        byte[] data = new byte[PAYLOAD_SIZE];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31 + 7);
        }
        return data;
    }

    @Test
    public void mutatedContainersNeverCrashAndNeverYieldWrongPlaintext() throws Exception {
        KeyPair sender = keyPair();
        KeyPair receiver = keyPair();
        byte[] plain = payload();
        byte[] container = encrypt(plain, sender, receiver);

        Random random = new Random(SEED);
        long started = System.currentTimeMillis();
        int rejected = 0;
        int unexpectedlyAccepted = 0;

        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            if (System.currentTimeMillis() - started > TIME_BUDGET_MS) {
                break;
            }
            byte[] mutated = mutate(container, random);
            try {
                byte[] decrypted = decrypt(mutated, receiver);
                unexpectedlyAccepted++;
                assertEquals("第 " + iteration + " 次变异被接受，但明文不一致（种子 " + SEED + "）",
                        java.util.Arrays.toString(plain), java.util.Arrays.toString(decrypted));
            } catch (Exception expected) {
                assertReadableRejection(expected, iteration);
                rejected++;
            } catch (Error error) {
                throw new AssertionError("第 " + iteration + " 次变异抛出了 Error（种子 " + SEED + "）: "
                        + error.getClass().getName(), error);
            }
        }

        assertTrue("被拒绝次数应占绝大多数", rejected > ITERATIONS / 2);
        assertTrue("接受次数异常偏高: " + unexpectedlyAccepted, unexpectedlyAccepted <= 2);
    }

    /** 截断也属于模糊输入，单独遍历一批长度。 */
    @Test
    public void truncatedContainersAreRejected() throws Exception {
        KeyPair sender = keyPair();
        KeyPair receiver = keyPair();
        byte[] container = encrypt(payload(), sender, receiver);

        int step = Math.max(1, container.length / 40);
        for (int length = 0; length < container.length; length += step) {
            byte[] truncated = java.util.Arrays.copyOf(container, length);
            try {
                decrypt(truncated, receiver);
                throw new AssertionError("截断到 " + length + " 字节仍被接受");
            } catch (AssertionError failed) {
                throw failed;
            } catch (Exception expected) {
                assertReadableRejection(expected, length);
            }
        }
    }

    private static void assertReadableRejection(Exception exception, int index) {
        String type = exception.getClass().getName();
        assertTrue("第 " + index + " 次出现未预期异常类型: " + type,
                exception instanceof java.io.IOException
                        || exception instanceof java.security.GeneralSecurityException
                        || exception instanceof IllegalArgumentException
                        || exception instanceof java.io.EOFException
                        || exception instanceof IllegalStateException);
    }

    private static byte[] mutate(byte[] source, Random random) {
        int mode = random.nextInt(3);
        if (mode == 0) {
            byte[] copy = source.clone();
            int flips = 1 + random.nextInt(3);
            for (int i = 0; i < flips; i++) {
                int position = random.nextInt(copy.length);
                copy[position] ^= (byte) (1 << random.nextInt(8));
            }
            return copy;
        }
        if (mode == 1) {
            int length = random.nextInt(source.length);
            return java.util.Arrays.copyOf(source, length);
        }
        // 插入或删除一小段
        int at = random.nextInt(source.length);
        int span = 1 + random.nextInt(16);
        byte[] spliced = new byte[source.length + span];
        System.arraycopy(source, 0, spliced, 0, at);
        for (int i = 0; i < span; i++) {
            spliced[at + i] = (byte) random.nextInt(256);
        }
        System.arraycopy(source, at, spliced, at + span, source.length - at);
        return spliced;
    }

    private static byte[] encrypt(byte[] plain, KeyPair sender, KeyPair receiver) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        File source = File.createTempFile("fuzz-src", ".bin");
        try {
            try (OutputStream fileOut = new FileOutputStream(source)) {
                fileOut.write(plain);
            }
            try (InputStream signatureInput = new FileInputStream(source);
                 InputStream plaintextInput = new FileInputStream(source)) {
                FileCryptoEngine.encrypt(
                        signatureInput,
                        plaintextInput,
                        plain.length,
                        "fuzz.bin",
                        sender.getPrivate(),
                        receiver.getPublic(),
                        output,
                        null,
                        new AtomicBoolean(false));
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
        }
        return output.toByteArray();
    }

    private static byte[] decrypt(byte[] container, KeyPair receiver) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = new ByteArrayInputStream(container)) {
            FileCryptoEngine.decrypt(
                    input,
                    receiver.getPrivate(),
                    java.util.Collections.emptyList(),
                    output,
                    null,
                    new AtomicBoolean(false));
        }
        return output.toByteArray();
    }
}
