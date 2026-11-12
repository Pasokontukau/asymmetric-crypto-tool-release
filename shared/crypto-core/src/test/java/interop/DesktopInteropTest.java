package interop;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import core.model.SignedAndEncryptedData;
import crypto.key.KeyPairManager;
import crypto.key.KeyUtils;
import crypto.message.SecureMessage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * 桌面端 ↔ crypto-core 互操作测试。
 *
 * <p>桌面端一侧由 {@code android/tools/desktop-bridge} 编译自仓库根目录
 * {@code src/main/java} 的加密核心源文件（仅日志/监控替换为桩实现），
 * 通过子进程调用，避免与 crypto-core 的同名类冲突。</p>
 */
public class DesktopInteropTest {

    private static final String FIXTURE_RESOURCE = "/interop/desktop-fixture.properties";
    private static Properties fixture;
    private static Path fixtureFile;

    @BeforeClass
    public static void loadFixture() throws Exception {
        java.net.URL url = DesktopInteropTest.class.getResource(FIXTURE_RESOURCE);
        assertNotNull("缺少互操作夹具：" + FIXTURE_RESOURCE, url);
        fixtureFile = Paths.get(url.toURI());
        fixture = new Properties();
        try (InputStream in = Files.newInputStream(fixtureFile)) {
            fixture.load(in);
        }
        assertEquals("1", fixture.getProperty("format.version"));
    }

    @Test
    public void desktopToAndroid_decryptsAndVerifiesSignature() throws Exception {
        SignedAndEncryptedData data = SignedAndEncryptedData.fromBytes(
                decode(fixture.getProperty("desktop.signedEncrypted.b64")));

        SecureMessage.DecryptAndVerifyResult result = SecureMessage.decryptAndVerify(
                data,
                publicKey("sender.public.x509.b64"),
                privateKey("receiver.private.pkcs8.b64"));

        assertTrue("桌面端消息在 crypto-core 中验签失败", result.isSignatureValid());
        assertArrayEquals(expectedPlaintext(), result.getPlaintext());
        assertEquals(expectedPlaintextText(), result.getPlaintextAsString());
    }

    @Test
    public void androidToDesktop_encryptsDecryptsAndVerifiesSignature() throws Exception {
        String plaintext = "Android → 桌面端 互操作测试：RSA-OAEP + AES-256-GCM + SHA256withRSA ✓ 2026-10-20";
        SignedAndEncryptedData data = SecureMessage.signAndEncryptText(
                plaintext,
                privateKey("sender.private.pkcs8.b64"),
                publicKey("receiver.public.x509.b64"));

        Path ciphertextFile = Files.createTempFile("android-to-desktop", ".b64");
        Files.write(ciphertextFile, encode(data.toBytes()).getBytes(StandardCharsets.US_ASCII));

        ProcessResult result = runBridge(
                "decrypt-with-fixture", fixtureFile.toAbsolutePath().toString(),
                ciphertextFile.toAbsolutePath().toString());

        assertEquals("桌面端无法解密 Android 消息：\n" + result.output, 0, result.exitCode);
        assertTrue(result.output, result.output.contains("SIGNATURE_VALID=true"));
        assertTrue(result.output.contains("PLAINTEXT_B64=" + encode(plaintext.getBytes(StandardCharsets.UTF_8))));
        Files.deleteIfExists(ciphertextFile);
    }

    @Test
    public void privateKeyFile_isReadableInBothDirections() throws Exception {
        byte[] expected = decode(fixture.getProperty("receiver.private.pkcs8.b64"));
        char[] password = fixture.getProperty("privateKey.password").toCharArray();

        // 桌面端写出的私钥文件 → crypto-core 读取
        byte[] fromDesktop = KeyPairManager.decryptPrivateKey(
                decode(fixture.getProperty("receiver.privateKey.encryptedFile.b64")),
                password);
        assertArrayEquals(expected, fromDesktop);
        assertTrue(KeyPairManager.verifyPassword(
                decode(fixture.getProperty("receiver.privateKey.encryptedFile.b64")), password));

        // crypto-core 写出的私钥文件 → 桌面端读取
        byte[] fromAndroid = KeyPairManager.encryptPrivateKey(expected, password);
        Path file = Files.createTempFile("android-private-key", ".b64");
        Files.write(file, encode(fromAndroid).getBytes(StandardCharsets.US_ASCII));

        ProcessResult result = runBridge(
                "decrypt-key", file.toAbsolutePath().toString(), new String(password));

        assertEquals(result.output, 0, result.exitCode);
        assertTrue(result.output, result.output.contains("PASSWORD_VERIFIED=true"));
        assertTrue(result.output, result.output.contains("PRIVATE_KEY_B64=" + encode(expected)));

        java.util.Arrays.fill(fromDesktop, (byte) 0);
        java.util.Arrays.fill(password, '\0');
        Files.deleteIfExists(file);
    }

    @Test
    public void tamperedSignatureIsNotTreatedAsTrusted() throws Exception {
        byte[] original = decode(fixture.getProperty("desktop.signedEncrypted.b64"));
        byte[] tampered = original.clone();
        // [签名长度(4)][签名...]：翻转第一个签名字节
        tampered[4] = (byte) (tampered[4] ^ 0x01);

        SignedAndEncryptedData data = SignedAndEncryptedData.fromBytes(tampered);
        SecureMessage.DecryptAndVerifyResult result = SecureMessage.decryptAndVerify(
                data,
                publicKey("sender.public.x509.b64"),
                privateKey("receiver.private.pkcs8.b64"));

        assertFalse("签名被篡改却仍判定有效", result.isSignatureValid());
        assertArrayEquals(expectedPlaintext(), result.getPlaintext());
    }

    @Test
    public void tamperedCiphertextFailsAuthentication() throws Exception {
        byte[] original = decode(fixture.getProperty("desktop.signedEncrypted.b64"));
        byte[] tampered = original.clone();
        tampered[tampered.length - 1] = (byte) (tampered[tampered.length - 1] ^ 0x01);

        try {
            byte[] plaintext = crypto.cipher.Decryptor.decryptData(
                    SignedAndEncryptedData.fromBytes(tampered).getEncryptedData(),
                    privateKey("receiver.private.pkcs8.b64"));
            fail("篡改后的密文不应通过 GCM 认证，实际得到 " + plaintext.length + " 字节");
        } catch (Exception expected) {
            // GCM 认证失败符合预期
        }
    }

    @Test
    public void wrongPrivateKeyCannotDecrypt() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair other = generator.generateKeyPair();

        SignedAndEncryptedData data = SignedAndEncryptedData.fromBytes(
                decode(fixture.getProperty("desktop.signedEncrypted.b64")));
        try {
            crypto.cipher.Decryptor.decryptData(data.getEncryptedData(), other.getPrivate());
            fail("错误私钥不应能解密桌面端密文");
        } catch (Exception expected) {
            // RSA-OAEP 解包或 GCM 校验失败
        }
    }

    @Test
    public void truncatedMessageIsRejected() {
        byte[] original = decode(fixture.getProperty("desktop.signedEncrypted.b64"));
        byte[] truncated = java.util.Arrays.copyOf(original, 12);
        try {
            SignedAndEncryptedData.fromBytes(truncated);
            fail("截断消息不应被解析");
        } catch (IllegalArgumentException expected) {
            // 边界校验生效
        }
    }

    private static byte[] expectedPlaintext() {
        return decode(fixture.getProperty("plaintext.b64"));
    }

    private static String expectedPlaintextText() {
        return new String(expectedPlaintext(), StandardCharsets.UTF_8);
    }

    private static PublicKey publicKey(String property) throws Exception {
        return KeyUtils.getPublicKeyFromBytes(decode(fixture.getProperty(property)));
    }

    private static PrivateKey privateKey(String property) throws Exception {
        return KeyUtils.getPrivateKeyFromBytes(decode(fixture.getProperty(property)));
    }

    private static byte[] decode(String value) {
        return Base64.getDecoder().decode(value);
    }

    private static String encode(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static ProcessResult runBridge(String... args) throws Exception {
        String jarPath = System.getProperty("desktop.bridge.jar");
        assertNotNull("缺少 desktop.bridge.jar 系统属性", jarPath);
        File jar = new File(jarPath);
        assertTrue("desktop-bridge jar 不存在：" + jar, jar.isFile());

        String javaBinary = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
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
        return new ProcessResult(exitCode, new String(buffer.toByteArray(), StandardCharsets.UTF_8));
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
