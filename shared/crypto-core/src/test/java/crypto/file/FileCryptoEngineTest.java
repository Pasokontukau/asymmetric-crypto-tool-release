package crypto.file;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import crypto.config.CryptoConfig;
import crypto.key.KeyUtils;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/**
 * 单文件流式引擎测试。
 *
 * <p>桌面互操作用 {@code tools/desktop-bridge} 编译出的桌面 crypto 源码作为对端，
 * 通过子进程写入/读取同一份 {@code .enc}，避免与 crypto-core 的同名类冲突。</p>
 */
public class FileCryptoEngineTest {

    private static final String FIXTURE_RESOURCE = "/interop/desktop-fixture.properties";

    @Test
    public void roundTrip_preservesBytesAndFileName() throws Exception {
        KeyPair sender = keyPair();
        KeyPair receiver = keyPair();
        byte[] plaintext = randomBytes(3 * 1024 * 1024 + 123);

        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        FileCryptoEngine.encrypt(
                new ByteArrayInputStream(plaintext),
                new ByteArrayInputStream(plaintext),
                plaintext.length,
                "报告 2026.bin",
                sender.getPrivate(),
                receiver.getPublic(),
                encrypted,
                null,
                new AtomicBoolean(false));

        List<FileProgressListener.Phase> phases = new ArrayList<FileProgressListener.Phase>();
        final AtomicLong decryptProgress = new AtomicLong();
        ByteArrayOutputStream decrypted = new ByteArrayOutputStream();
        FileDecryptResult result = FileCryptoEngine.decrypt(
                new ByteArrayInputStream(encrypted.toByteArray()),
                receiver.getPrivate(),
                Arrays.asList(sender.getPublic()),
                decrypted,
                (phase, processed, total) -> {
                    phases.add(phase);
                    decryptProgress.set(processed);
                },
                new AtomicBoolean(false));

        assertArrayEquals(plaintext, decrypted.toByteArray());
        assertEquals("报告 2026.bin", result.getOriginalFileName());
        assertTrue(result.isSignatureValid());
        assertNotNull(result.getMatchedSender());
        assertEquals(plaintext.length, result.getPlaintextBytes());
        assertFalse(phases.isEmpty());
        assertTrue(phases.stream().allMatch(
                phase -> phase == FileProgressListener.Phase.DECRYPTING));
        assertEquals(
                result.getPlaintextBytes() + CryptoConfig.GCM_TAG_LENGTH,
                decryptProgress.get());
    }

    @Test
    public void originalFileName_neverAppearsInCiphertextBytes() throws Exception {
        KeyPair sender = keyPair();
        KeyPair receiver = keyPair();
        byte[] plaintext = randomBytes(64 * 1024);
        String originalName = "工资表 2026.xlsx";
        byte[] needle = originalName.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        FileCryptoEngine.encrypt(
                new ByteArrayInputStream(plaintext),
                new ByteArrayInputStream(plaintext),
                plaintext.length,
                originalName,
                sender.getPrivate(),
                receiver.getPublic(),
                encrypted,
                null,
                new AtomicBoolean(false));

        assertFalse(
                "原始文件名只能出现在加密文件名里，不能落进明文头",
                contains(encrypted.toByteArray(), needle));
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    @Test
    public void progress_reportsBothEncryptionPhases() throws Exception {
        KeyPair sender = keyPair();
        KeyPair receiver = keyPair();
        byte[] plaintext = randomBytes(2 * 1024 * 1024 + 7);
        List<FileProgressListener.Phase> phases = new ArrayList<FileProgressListener.Phase>();
        final AtomicLong last = new AtomicLong(-1);
        final boolean[] monotonic = {true};
        final FileProgressListener.Phase[] lastPhase = {null};

        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        FileCryptoEngine.encrypt(
                new ByteArrayInputStream(plaintext),
                new ByteArrayInputStream(plaintext),
                plaintext.length,
                "a.bin",
                sender.getPrivate(),
                receiver.getPublic(),
                encrypted,
                (phase, processed, total) -> {
                    phases.add(phase);
                    // 第二遍加密会从 0 重新开始，只在同一阶段内要求进度单调。
                    if (phase != lastPhase[0]) {
                        lastPhase[0] = phase;
                        last.set(-1);
                    }
                    if (processed < last.get()) monotonic[0] = false;
                    last.set(processed);
                },
                new AtomicBoolean(false));

        assertTrue(phases.contains(FileProgressListener.Phase.SIGNING));
        assertTrue(phases.contains(FileProgressListener.Phase.ENCRYPTING));
        assertTrue("进度不应回退", monotonic[0]);
    }

    /**
     * 96 MB 文件在 128 MB 堆下跑完整流程。Android Conscrypt 的 AES/GCM 会在 update
     * 阶段缓存整段密文，这个测试会直接 OOM；BouncyCastle 的流式 GCM 必须保持通过。
     */
    @Test
    public void largeFile_streamsWithBoundedMemory() throws Exception {
        KeyPair sender = keyPair();
        KeyPair receiver = keyPair();
        File input = File.createTempFile("large-input", ".bin");
        File encrypted = File.createTempFile("large-enc", ".enc");
        File decrypted = File.createTempFile("large-out", ".bin");
        long size = 96L * 1024 * 1024 + 17;
        try {
            writePattern(input, size);
            try (InputStream signatureInput = new FileInputStream(input);
                 InputStream plaintextInput = new FileInputStream(input);
                 OutputStream output = new FileOutputStream(encrypted)) {
                FileCryptoEngine.encrypt(
                        signatureInput,
                        plaintextInput,
                        size,
                        "large.bin",
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
                        Arrays.asList(sender.getPublic()),
                        output,
                        null,
                        new AtomicBoolean(false));
            }

            assertEquals(size, decrypted.length());
            assertEquals(sha256(input), sha256(decrypted));
            assertTrue(result.isSignatureValid());
            assertEquals("large.bin", result.getOriginalFileName());
        } finally {
            Files.deleteIfExists(input.toPath());
            Files.deleteIfExists(encrypted.toPath());
            Files.deleteIfExists(decrypted.toPath());
        }
    }

    @Test
    public void emptyFile_roundTrips() throws Exception {
        KeyPair sender = keyPair();
        KeyPair receiver = keyPair();
        byte[] plaintext = new byte[0];

        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        FileCryptoEngine.encrypt(
                new ByteArrayInputStream(plaintext),
                new ByteArrayInputStream(plaintext),
                0,
                "empty.bin",
                sender.getPrivate(),
                receiver.getPublic(),
                encrypted,
                null,
                new AtomicBoolean(false));

        ByteArrayOutputStream decrypted = new ByteArrayOutputStream();
        FileDecryptResult result = FileCryptoEngine.decrypt(
                new ByteArrayInputStream(encrypted.toByteArray()),
                receiver.getPrivate(),
                Arrays.asList(sender.getPublic()),
                decrypted,
                null,
                new AtomicBoolean(false));

        assertEquals(0, decrypted.size());
        assertEquals("empty.bin", result.getOriginalFileName());
        assertTrue(result.isSignatureValid());
    }

    @Test
    public void tamperedCiphertext_isRejected() throws Exception {
        KeyPair sender = keyPair();
        KeyPair receiver = keyPair();
        byte[] plaintext = randomBytes(64 * 1024);
        byte[] encrypted = encryptToBytes(plaintext, sender, receiver);

        byte[] tampered = encrypted.clone();
        tampered[tampered.length - 1] ^= 0x01;

        try {
            FileCryptoEngine.decrypt(
                    new ByteArrayInputStream(tampered),
                    receiver.getPrivate(),
                    Arrays.asList(sender.getPublic()),
                    new ByteArrayOutputStream(),
                    null,
                    new AtomicBoolean(false));
            fail("篡改后的密文不应通过 GCM 认证");
        } catch (FileFormatException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("认证失败"));
        }
    }

    @Test
    public void wrongRecipient_isRejected() throws Exception {
        KeyPair sender = keyPair();
        KeyPair receiver = keyPair();
        KeyPair other = keyPair();
        byte[] plaintext = randomBytes(32 * 1024);
        byte[] encrypted = encryptToBytes(plaintext, sender, receiver);

        try {
            FileCryptoEngine.decrypt(
                    new ByteArrayInputStream(encrypted),
                    other.getPrivate(),
                    Arrays.asList(sender.getPublic()),
                    new ByteArrayOutputStream(),
                    null,
                    new AtomicBoolean(false));
            fail("错误私钥不应能解开文件密钥");
        } catch (WrongRecipientException expected) {
            // 预期
        }
    }

    @Test
    public void unknownSender_keepsPlaintextButFlagsSignature() throws Exception {
        KeyPair sender = keyPair();
        KeyPair receiver = keyPair();
        KeyPair stranger = keyPair();
        byte[] plaintext = randomBytes(128 * 1024);
        byte[] encrypted = encryptToBytes(plaintext, sender, receiver);

        ByteArrayOutputStream decrypted = new ByteArrayOutputStream();
        FileDecryptResult result = FileCryptoEngine.decrypt(
                new ByteArrayInputStream(encrypted),
                receiver.getPrivate(),
                Arrays.asList(stranger.getPublic()),
                decrypted,
                null,
                new AtomicBoolean(false));

        assertArrayEquals(plaintext, decrypted.toByteArray());
        assertFalse(result.isSignatureValid());
        assertEquals(null, result.getMatchedSender());
    }

    @Test
    public void volumeMagic_isRejectedExplicitly() throws Exception {
        byte[] magicOnly = new byte[] {0x56, 0x4F, 0x4C, 0x55, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0};
        try {
            FileCryptoEngine.decrypt(
                    new ByteArrayInputStream(magicOnly),
                    keyPair().getPrivate(),
                    new ArrayList<PublicKey>(),
                    new ByteArrayOutputStream(),
                    null,
                    new AtomicBoolean(false));
            fail("VOLU 文件不应被当成单文件解析");
        } catch (UnsupportedVolumeFileException expected) {
            // 预期
        }
    }

    @Test
    public void truncatedHeader_isRejected() throws Exception {
        try {
            FileCryptoEngine.decrypt(
                    new ByteArrayInputStream(new byte[] {0, 0, 2, 0, 1, 2}),
                    keyPair().getPrivate(),
                    new ArrayList<PublicKey>(),
                    new ByteArrayOutputStream(),
                    null,
                    new AtomicBoolean(false));
            fail("截断文件不应被接受");
        } catch (FileFormatException expected) {
            // 预期
        }
    }

    @Test
    public void cancellation_stopsBeforeWriting() throws Exception {
        KeyPair sender = keyPair();
        KeyPair receiver = keyPair();
        byte[] plaintext = randomBytes(1024 * 1024);
        AtomicBoolean cancelled = new AtomicBoolean(true);
        try {
            FileCryptoEngine.encrypt(
                    new ByteArrayInputStream(plaintext),
                    new ByteArrayInputStream(plaintext),
                    plaintext.length,
                    "cancel.bin",
                    sender.getPrivate(),
                    receiver.getPublic(),
                    new ByteArrayOutputStream(),
                    null,
                    cancelled);
            fail("取消后不应继续加密");
        } catch (FileCancelledException expected) {
            // 预期
        }
    }

    @Test
    public void desktopToAndroid_decryptsSingleFile() throws Exception {
        Properties fixture = loadFixture();
        PrivateKey receiverPrivate = privateKey(fixture, "receiver.private.pkcs8.b64");
        PublicKey senderPublic = publicKey(fixture, "sender.public.x509.b64");

        byte[] plaintext = randomBytes(1024 * 1024 + 321);
        Path root = Files.createTempDirectory("file-interop-desktop");
        Path input = root.resolve("桌面文件.bin");
        Path encrypted = root.resolve("desktop.enc");
        Files.write(input, plaintext);

        ProcessResult encrypt = runBridge(
                "encrypt-file", fixturePath().toString(),
                input.toAbsolutePath().toString(),
                encrypted.toAbsolutePath().toString());
        assertEquals(encrypt.output, 0, encrypt.exitCode);

        ByteArrayOutputStream decrypted = new ByteArrayOutputStream();
        FileDecryptResult result = FileCryptoEngine.decrypt(
                new FileInputStream(encrypted.toFile()),
                receiverPrivate,
                Arrays.asList(senderPublic),
                decrypted,
                null,
                new AtomicBoolean(false));

        assertArrayEquals(plaintext, decrypted.toByteArray());
        assertTrue(result.isSignatureValid());
        assertEquals("桌面文件.bin", result.getOriginalFileName());
    }

    @Test
    public void androidToDesktop_encryptsSingleFile() throws Exception {
        Properties fixture = loadFixture();
        PrivateKey senderPrivate = privateKey(fixture, "sender.private.pkcs8.b64");
        PublicKey receiverPublic = publicKey(fixture, "receiver.public.x509.b64");

        byte[] plaintext = randomBytes(1024 * 1024 + 77);
        Path root = Files.createTempDirectory("file-interop-android");
        Path encrypted = root.resolve("android.enc");
        Path decrypted = root.resolve("desktop-out.bin");

        FileCryptoEngine.encrypt(
                new ByteArrayInputStream(plaintext),
                new ByteArrayInputStream(plaintext),
                plaintext.length,
                "android-file.bin",
                senderPrivate,
                receiverPublic,
                new java.io.FileOutputStream(encrypted.toFile()),
                null,
                new AtomicBoolean(false));

        ProcessResult result = runBridge(
                "decrypt-file", fixturePath().toString(),
                encrypted.toAbsolutePath().toString(),
                decrypted.toAbsolutePath().toString());
        assertEquals(result.output, 0, result.exitCode);
        assertTrue(result.output, result.output.contains("SIGNATURE_VALID=true"));
        assertEquals(
                "ORIGINAL_NAME_B64=" + Base64.getEncoder().encodeToString(
                        "android-file.bin".getBytes(StandardCharsets.UTF_8)),
                findLine(result.output, "ORIGINAL_NAME_B64="));
        assertArrayEquals(plaintext, Files.readAllBytes(decrypted));
    }

    private static byte[] encryptToBytes(byte[] plaintext, KeyPair sender, KeyPair receiver)
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FileCryptoEngine.encrypt(
                new ByteArrayInputStream(plaintext),
                new ByteArrayInputStream(plaintext),
                plaintext.length,
                "file.bin",
                sender.getPrivate(),
                receiver.getPublic(),
                output,
                null,
                new AtomicBoolean(false));
        return output.toByteArray();
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static void writePattern(java.io.File file, long size) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        Random random = new Random(7);
        long remaining = size;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            while (remaining > 0) {
                int chunk = (int) Math.min(buffer.length, remaining);
                random.nextBytes(buffer);
                out.write(buffer, 0, chunk);
                remaining -= chunk;
            }
        }
    }

    private static String sha256(java.io.File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder value = new StringBuilder();
        for (byte b : digest.digest()) {
            value.append(String.format("%02x", b));
        }
        return value.toString();
    }

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(20261023).nextBytes(data);
        return data;
    }

    private static Properties loadFixture() throws Exception {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(fixturePath())) {
            properties.load(in);
        }
        return properties;
    }

    private static Path fixturePath() throws Exception {
        java.net.URL url = FileCryptoEngineTest.class.getResource(FIXTURE_RESOURCE);
        assertNotNull("缺少互操作夹具：" + FIXTURE_RESOURCE, url);
        return Paths.get(url.toURI());
    }

    private static PrivateKey privateKey(Properties properties, String name) throws Exception {
        return KeyUtils.getPrivateKeyFromBytes(
                Base64.getDecoder().decode(properties.getProperty(name)));
    }

    private static PublicKey publicKey(Properties properties, String name) throws Exception {
        return KeyUtils.getPublicKeyFromBytes(
                Base64.getDecoder().decode(properties.getProperty(name)));
    }

    private static String findLine(String output, String prefix) {
        for (String line : output.split("\\R")) {
            if (line.startsWith(prefix)) return line;
        }
        return "";
    }

    private static ProcessResult runBridge(String... args) throws Exception {
        String jarPath = System.getProperty("desktop.bridge.jar");
        assertNotNull("缺少 desktop.bridge.jar 系统属性", jarPath);
        File jar = new File(jarPath);
        assertTrue("desktop-bridge jar 不存在：" + jar, jar.isFile());

        String javaBinary = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<String>();
        command.add(javaBinary);
        command.add("-cp");
        command.add(jar.getAbsolutePath());
        command.add("interop.InteropCli");
        java.util.Collections.addAll(command, args);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        try (InputStream in = process.getInputStream()) {
            while ((read = in.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
        }
        int exitCode = process.waitFor();
        return new ProcessResult(
                exitCode,
                new String(buffer.toByteArray(), StandardCharsets.UTF_8));
    }

    private static final class ProcessResult {
        final int exitCode;
        final String output;

        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
