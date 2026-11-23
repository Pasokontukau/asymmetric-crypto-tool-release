package crypto.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 子 JVM 探针（G-01）：在受限堆下跑 100 MB 文件的完整加密/解密。
 *
 * <p>之所以用独立进程：同一个测试 JVM 的堆由 Gradle 配置决定，无法在用例内部收紧；
 * 而“能不能在 96 MB 堆里处理 100 MB 文件”正是流式实现的核心证据。</p>
 */
public final class MemoryProbe {

    private MemoryProbe() {
    }

    public static void main(String[] args) throws Exception {
        long size = 100L * 1024 * 1024 + 7;
        File input = File.createTempFile("mem-in", ".bin");
        File encrypted = File.createTempFile("mem-enc", ".aegix");
        File decrypted = File.createTempFile("mem-out", ".bin");
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair sender = generator.generateKeyPair();
            KeyPair receiver = generator.generateKeyPair();

            writePattern(input, size);

            try (InputStream signatureInput = new FileInputStream(input);
                 InputStream plaintextInput = new FileInputStream(input);
                 OutputStream output = new FileOutputStream(encrypted)) {
                FileCryptoEngine.encrypt(
                        signatureInput,
                        plaintextInput,
                        size,
                        "big.bin",
                        sender.getPrivate(),
                        receiver.getPublic(),
                        output,
                        null,
                        new AtomicBoolean(false));
            }

            FileDecryptResult result;
            try (InputStream inputStream = new FileInputStream(encrypted);
                 OutputStream output = new FileOutputStream(decrypted)) {
                result = FileCryptoEngine.decrypt(
                        inputStream,
                        receiver.getPrivate(),
                        java.util.Collections.singletonList(sender.getPublic()),
                        output,
                        null,
                        new AtomicBoolean(false));
            }

            if (!result.isSignatureValid()) {
                throw new IllegalStateException("签名应可被识别");
            }
            if (decrypted.length() != size || input.length() != size) {
                throw new IllegalStateException("长度不一致: in=" + input.length()
                        + " out=" + decrypted.length() + " expected=" + size);
            }
            System.out.println("MEMORY_PROBE_OK heap=" + Runtime.getRuntime().maxMemory()
                    + " size=" + size);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            input.delete();
            //noinspection ResultOfMethodCallIgnored
            encrypted.delete();
            //noinspection ResultOfMethodCallIgnored
            decrypted.delete();
        }
    }

    private static void writePattern(File file, long size) throws Exception {
        byte[] chunk = new byte[64 * 1024];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = (byte) (i * 17 + 5);
        }
        try (OutputStream output = new FileOutputStream(file)) {
            long written = 0;
            while (written < size) {
                int length = (int) Math.min(chunk.length, size - written);
                output.write(chunk, 0, length);
                written += length;
            }
        }
    }
}
