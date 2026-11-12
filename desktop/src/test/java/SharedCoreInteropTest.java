import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import core.model.SignedAndEncryptedData;
import crypto.file.FileCryptoEngine;
import crypto.file.FileDecryptResult;
import crypto.key.KeyPairManager;
import crypto.key.KeyUtils;
import crypto.message.SecureMessage;
import crypto.volume.VolumeCryptoEngine;
import crypto.volume.VolumeDecryptResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * 桌面端依赖 shared/crypto-core 固定版本后的最小回归。
 *
 * <p>这些测试不修改 android/，只验证桌面 Maven 能消费同一份内核源码：文字签名加密、
 * 单文件流式引擎、当前外部分卷夹具读取、GCM1 私钥格式。分卷夹具来自
 * 桌面本地复制的互操作夹具（来源见 desktop/src/test/resources/external/README.md）。</p>
 */
public class SharedCoreInteropTest {

    @Test
    public void textRoundTripSignsAndVerifies() throws Exception {
        KeyPair sender = KeyPairManager.generateKeyPair(4096);
        KeyPair receiver = KeyPairManager.generateKeyPair(4096);

        SignedAndEncryptedData encrypted = SecureMessage.signAndEncryptText(
                "桌面端共享内核测试",
                sender.getPrivate(),
                receiver.getPublic());

        SecureMessage.DecryptAndVerifyResult result = SecureMessage.decryptAndVerify(
                encrypted, sender.getPublic(), receiver.getPrivate());

        assertEquals("桌面端共享内核测试", result.getPlaintextAsString());
        assertTrue(result.isSignatureValid());
    }

    @Test
    public void singleFileRoundTripPreservesNameAndSignature() throws Exception {
        KeyPair sender = KeyPairManager.generateKeyPair(4096);
        KeyPair receiver = KeyPairManager.generateKeyPair(4096);
        byte[] plaintext = new byte[4096];
        new Random(7).nextBytes(plaintext);

        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        FileCryptoEngine.encrypt(
                new ByteArrayInputStream(plaintext),
                new ByteArrayInputStream(plaintext),
                plaintext.length,
                "共享内核.bin",
                sender.getPrivate(),
                receiver.getPublic(),
                encrypted,
                null,
                new AtomicBoolean(false));

        ByteArrayOutputStream decrypted = new ByteArrayOutputStream();
        FileDecryptResult result = FileCryptoEngine.decrypt(
                new ByteArrayInputStream(encrypted.toByteArray()),
                receiver.getPrivate(),
                Collections.singletonList(sender.getPublic()),
                decrypted,
                null,
                new AtomicBoolean(false));

        assertArrayEquals(plaintext, decrypted.toByteArray());
        assertEquals("共享内核.bin", result.getOriginalFileName());
        assertTrue(result.isSignatureValid());
    }

    @Test
    public void readsCurrentExternalPartFixture() throws Exception {
        Properties properties = fixtureProperties();
        PrivateKey recipient = KeyUtils.getPrivateKeyFromBytes(
                Base64.getDecoder().decode(
                        properties.getProperty("recipient.private.pkcs8.b64")));
        PublicKey sender = KeyUtils.getPublicKeyFromBytes(
                Base64.getDecoder().decode(
                        properties.getProperty("sender.public.x509.b64")));
        String firstPart = properties.getProperty("multi.part.files").split(",")[0];

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        VolumeDecryptResult result = VolumeCryptoEngine.decrypt(
                new ByteArrayInputStream(fixture(firstPart)),
                recipient,
                Collections.singletonList(sender),
                output,
                null,
                new AtomicBoolean(false));

        assertEquals(1, result.getVolumeCount());
        assertEquals(1, result.getPartIndex());
        assertEquals(3, result.getPartCount());
        assertEquals(904L, result.getPlaintextBytes());
        assertTrue(result.isSignatureValid());
        assertTrue(result.isContentHashVerified());
    }

    @Test
    public void gcm1PrivateKeyRoundTrip() throws Exception {
        KeyPair keyPair = KeyPairManager.generateKeyPair(4096);
        char[] password = "shared-core-test".toCharArray();

        byte[] encrypted = KeyPairManager.encryptPrivateKey(
                keyPair.getPrivate().getEncoded(), password);

        assertArrayEquals(
                new byte[] {'G', 'C', 'M', '1'},
                Arrays.copyOf(encrypted, 4));
        assertArrayEquals(
                keyPair.getPrivate().getEncoded(),
                KeyPairManager.decryptPrivateKey(encrypted, password));
    }

    private static Properties fixtureProperties() throws Exception {
        try (InputStream input = SharedCoreInteropTest.class.getResourceAsStream(
                "/external/external-part-fixture.properties")) {
            if (input == null) {
                throw new IllegalStateException(
                        "缺少 /external/external-part-fixture.properties");
            }
            Properties properties = new Properties();
            properties.load(input);
            return properties;
        }
    }

    private static byte[] fixture(String name) throws Exception {
        try (InputStream input = SharedCoreInteropTest.class.getResourceAsStream("/external/" + name)) {
            if (input == null) {
                throw new IllegalStateException("缺少夹具 /external/" + name);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest(data)) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }
}
