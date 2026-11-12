import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import crypto.file.FileCryptoEngine;
import crypto.file.FileDecryptResult;
import crypto.key.KeyUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.Collections;
import java.util.Properties;
import org.junit.Test;
import securecomm.ui.util.EncryptedFileNaming;

/**
 * 桌面端读共享的单文件 {@code .aegix} 固定夹具。
 *
 * <p>字节副本位于 {@code desktop/src/test/resources/single/}，来源见共享目录的 README。
 * 这里验证桌面 Maven 依赖的共享内核版本、资源复制与后缀常量没有漂移；线格式断言在
 * 共享内核 {@code crypto.file.SingleFileFixtureTest}。</p>
 */
public class SingleFileFixtureTest {

    private static final String PROPERTIES = "/single/single-file-fixture.properties";

    @Test
    public void decryptsTheSharedSingleFileFixture() throws Exception {
        Properties properties = properties();
        byte[] plaintext = Base64.getDecoder().decode(properties.getProperty("plain.base64"));
        byte[] cipher = fixture(properties.getProperty("cipher.file"));

        assertTrue("夹具必须是桌面端识别的 .aegix",
                properties.getProperty("cipher.file").endsWith(EncryptedFileNaming.EXTENSION));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FileDecryptResult result = FileCryptoEngine.decrypt(
                new ByteArrayInputStream(cipher),
                KeyUtils.getPrivateKeyFromBytes(Base64.getDecoder().decode(
                        properties.getProperty("recipient.private.pkcs8.b64"))),
                Collections.singletonList(KeyUtils.getPublicKeyFromBytes(
                        Base64.getDecoder().decode(
                                properties.getProperty("sender.public.x509.b64")))),
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
    public void pinnedHeaderKeepsTheSignatureLengthFirst() throws Exception {
        Properties properties = properties();
        byte[] cipher = fixture(properties.getProperty("cipher.file"));
        int expectedSignature = Integer.parseInt(
                properties.getProperty("header.signature.bytes"));

        assertEquals(properties.getProperty("cipher.size"), String.valueOf(cipher.length));
        assertEquals("单文件容器第一项是签名长度，没有魔数/版本字段",
                expectedSignature, readInt(cipher, 0));
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

    private static byte[] fixture(String name) throws Exception {
        InputStream input = SingleFileFixtureTest.class.getResourceAsStream("/single/" + name);
        assertNotNull("缺少夹具 /single/" + name, input);
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
}
