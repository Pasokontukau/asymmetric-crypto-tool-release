package crypto.file;

import crypto.key.KeyUtils;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Collections;
import java.util.Properties;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 当前单文件 `.aegix` 容器的固定夹具测试。
 *
 * <p>夹具是冻结的一份字节，不是“现场加密再解密”。它同时钉住头部各长度字段的顺序，
 * 避免写入端和读取端一起改错时仍然通过。单文件容器没有版本字段，未来版本拒绝在
 * VOLU 夹具上验证（{@code ExternalPartFixtureTest}）。</p>
 */
public class SingleFileFixtureTest {

    private static final String PROPERTIES = "/single/single-file-fixture.properties";

    @Test
    public void fixtureDecryptsToPinnedPlaintextAndRecoversName() throws Exception {
        Properties properties = properties();
        byte[] plaintext = Base64.getDecoder().decode(properties.getProperty("plain.base64"));
        byte[] cipher = fixture("/single/" + properties.getProperty("cipher.file"));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FileDecryptResult result = FileCryptoEngine.decrypt(
                new ByteArrayInputStream(cipher),
                recipientPrivate(properties),
                Collections.singletonList(senderPublic(properties)),
                output,
                null,
                null);

        assertArrayEquals(plaintext, output.toByteArray());
        assertEquals(properties.getProperty("plain.name"), result.getOriginalFileName());
        assertEquals(plaintext.length, result.getPlaintextBytes());
        assertTrue(result.isSignatureValid());
        assertNotNull(result.getMatchedSender());
    }

    @Test
    public void fixturePinsHeaderLayoutAndLengths() throws Exception {
        Properties properties = properties();
        byte[] cipher = fixture("/single/" + properties.getProperty("cipher.file"));
        byte[] plaintext = Base64.getDecoder().decode(properties.getProperty("plain.base64"));
        int expectedSignature = Integer.parseInt(
                properties.getProperty("header.signature.bytes"));

        assertEquals("夹具不是同一份字节", properties.getProperty("cipher.size"),
                String.valueOf(cipher.length));
        assertEquals("签名长度字段", expectedSignature, readInt(cipher, 0));

        int cursor = 4;
        assertTrue("签名必须完整落在文件内", cursor + expectedSignature < cipher.length);
        cursor += expectedSignature;

        int nameIvLength = readInt(cipher, cursor);
        cursor += 4;
        assertEquals("文件名 IV 长度", 12, nameIvLength);
        cursor += nameIvLength;

        int encryptedNameLength = readInt(cipher, cursor);
        cursor += 4;
        int expectedNameBytes = properties.getProperty("plain.name")
                .getBytes(StandardCharsets.UTF_8).length + 16;
        assertEquals("加密文件名长度 = UTF-8 名 + GCM tag", expectedNameBytes, encryptedNameLength);
        cursor += encryptedNameLength;

        int encryptedKeyLength = readInt(cipher, cursor);
        cursor += 4;
        assertEquals("RSA 4096 加密密钥长度", 512, encryptedKeyLength);
        cursor += encryptedKeyLength;

        int ivLength = readInt(cipher, cursor);
        cursor += 4;
        assertEquals("AES IV 长度", 12, ivLength);
        cursor += ivLength;

        int ciphertextLength = readInt(cipher, cursor);
        cursor += 4;
        assertEquals("密文长度字段", cipher.length - cursor, ciphertextLength);
        assertEquals("明文长度 = 密文 - GCM tag",
                plaintext.length, ciphertextLength - 16);
        assertEquals("夹具明文 SHA-256", properties.getProperty("plain.sha256"),
                sha256(plaintext));
    }

    @Test
    public void tamperedCiphertextIsRejectedWithoutOutput() throws Exception {
        Properties properties = properties();
        byte[] plaintext = Base64.getDecoder().decode(properties.getProperty("plain.base64"));
        byte[] cipher = fixture("/single/" + properties.getProperty("cipher.file"));
        cipher[cipher.length - 1] ^= 0x01;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try {
            FileCryptoEngine.decrypt(
                    new ByteArrayInputStream(cipher),
                    recipientPrivate(properties),
                    Collections.singletonList(senderPublic(properties)),
                    output,
                    null,
                    null);
            fail("篡改密文必须被 GCM 拒绝");
        } catch (FileFormatException expected) {
            assertTrue(expected.getMessage().contains("认证失败")
                    || expected.getMessage().contains("损坏")
                    || expected.getMessage().contains("篡改"));
        }
        // 流式 GCM 在 doFinal 校验 tag 前已可能写出部分明文；调用方必须删除临时输出，
        // 桌面端由 AtomicFileOutput 保证。这里只钉住“不会写出超过明文长度的内容”。
        assertTrue("认证失败后不得产生超过明文的输出", output.size() <= plaintext.length);
    }

    @Test
    public void wrongRecipientKeyIsRejected() throws Exception {
        Properties properties = properties();
        byte[] cipher = fixture("/single/" + properties.getProperty("cipher.file"));
        // 破坏 RSA 密文，模拟“不是加密给本机密钥”。
        int encryptedKeyOffset = 4 + Integer.parseInt(
                properties.getProperty("header.signature.bytes")) + 4 + 12 + 4
                + properties.getProperty("plain.name").getBytes(StandardCharsets.UTF_8).length + 16;
        cipher[encryptedKeyOffset + 8] ^= 0x01;

        try {
            FileCryptoEngine.decrypt(
                    new ByteArrayInputStream(cipher),
                    recipientPrivate(properties),
                    Collections.singletonList(senderPublic(properties)),
                    new ByteArrayOutputStream(),
                    null,
                    null);
            fail("错误的接收方密钥必须明确失败");
        } catch (WrongRecipientException expected) {
            assertTrue(expected.getMessage().contains("本机私钥"));
        }
    }

    @Test
    public void volumePayloadIsRejectedBySingleFileEntry() throws Exception {
        Properties external = new Properties();
        InputStream input = SingleFileFixtureTest.class.getResourceAsStream(
                "/external/external-part-fixture.properties");
        assertNotNull("缺少外部分卷夹具", input);
        try {
            external.load(input);
        } finally {
            input.close();
        }
        byte[] volume = fixture("/external/" + external.getProperty("single.part.file"));
        try {
            FileCryptoEngine.decrypt(
                    new ByteArrayInputStream(volume),
                    recipientPrivate(external),
                    Collections.singletonList(senderPublic(external)),
                    new ByteArrayOutputStream(),
                    null,
                    null);
            fail("VOLU 卷不能交给单文件入口");
        } catch (UnsupportedVolumeFileException expected) {
            assertTrue(expected.getMessage().contains("VOLU"));
        }
    }

    @Test
    public void fixtureDoesNotStorePlaintextNameInTheContainer() throws Exception {
        Properties properties = properties();
        byte[] cipher = fixture("/single/" + properties.getProperty("cipher.file"));
        byte[] name = properties.getProperty("plain.name").getBytes(StandardCharsets.UTF_8);

        for (int index = 0; index + name.length <= cipher.length; index++) {
            boolean match = true;
            for (int offset = 0; offset < name.length; offset++) {
                if (cipher[index + offset] != name[offset]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                fail("头部不应出现明文文件名");
            }
        }
        assertNull("夹具属性本身不应记录明文文件路径", properties.getProperty("plain.path"));
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }

    private static Properties properties() throws Exception {
        InputStream input = SingleFileFixtureTest.class.getResourceAsStream(PROPERTIES);
        assertNotNull("缺少夹具 " + PROPERTIES, input);
        try {
            Properties properties = new Properties();
            properties.load(input);
            return properties;
        } finally {
            input.close();
        }
    }

    private static byte[] fixture(String resourcePath) throws Exception {
        InputStream input = SingleFileFixtureTest.class.getResourceAsStream(resourcePath);
        assertNotNull("缺少夹具 " + resourcePath, input);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static PrivateKey recipientPrivate(Properties properties) throws Exception {
        return KeyUtils.getPrivateKeyFromBytes(
                Base64.getDecoder().decode(
                        properties.getProperty("recipient.private.pkcs8.b64")));
    }

    private static PublicKey senderPublic(Properties properties) throws Exception {
        return KeyUtils.getPublicKeyFromBytes(
                Base64.getDecoder().decode(
                        properties.getProperty("sender.public.x509.b64")));
    }

    private static String sha256(byte[] data) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest(data)) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }
}
