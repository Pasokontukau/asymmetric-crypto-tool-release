package crypto.volume;

import crypto.file.FileCancelledException;
import crypto.file.WrongRecipientException;
import crypto.key.KeyUtils;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * VOLU 只读引擎的错误路径与进度测试。
 *
 * <p>成功路径由 {@link ExternalPartFixtureTest} 用固定夹具覆盖；这里复用同一批
 * 当前格式字节，只验证错接收方、版本、篡改、截断、取消和未知发送者。</p>
 */
public class VolumeCryptoEngineTest {

    private static final String PROPERTIES = "/external/external-part-fixture.properties";

    @Test
    public void unknownSenderStillDecryptsButSignatureIsInvalid() throws Exception {
        Properties properties = properties();
        byte[] encrypted = currentPart(properties);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        VolumeDecryptResult result = VolumeCryptoEngine.decrypt(
                new ByteArrayInputStream(encrypted),
                recipientPrivate(properties),
                Collections.<PublicKey>emptyList(),
                output,
                null,
                null
        );
        assertFalse(result.isSignatureValid());
        assertTrue(result.isContentHashVerified());
        assertEquals(904L, result.getPlaintextBytes());
        assertEquals(3, result.getPartCount());
    }

    @Test
    public void progressReportsTheCurrentPartOnly() throws Exception {
        Properties properties = properties();
        final List<int[]> parts = new ArrayList<int[]>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        VolumeCryptoEngine.decrypt(
                new ByteArrayInputStream(currentPart(properties)),
                recipientPrivate(properties),
                Collections.singletonList(senderPublic(properties)),
                output,
                (volumeIndex, volumeCount, processed, total) -> parts.add(
                        new int[]{volumeIndex, volumeCount}
                ),
                null
        );
        assertFalse(parts.isEmpty());
        assertEquals(1, parts.get(0)[0]);
        assertEquals(1, parts.get(parts.size() - 1)[0]);
        assertEquals(1, parts.get(parts.size() - 1)[1]);
    }

    @Test
    public void wrongRecipientIsRejected() throws Exception {
        Properties properties = properties();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair other = generator.generateKeyPair();
        try {
            decrypt(currentPart(properties), other.getPrivate(), properties, false);
            fail("错误接收方应当被拒绝");
        } catch (WrongRecipientException expected) {
            // 预期
        }
    }

    @Test
    public void unsupportedVersionIsRejectedExplicitly() throws Exception {
        Properties properties = properties();
        byte[] encrypted = currentPart(properties);
        writeInt(encrypted, 4, 2);
        try {
            decrypt(encrypted, recipientPrivate(properties), properties, false);
            fail("不支持的 VOLU 版本应当被拒绝");
        } catch (UnsupportedVolumeVersionException expected) {
            assertEquals(2, expected.getVersion());
        }
    }

    @Test
    public void tamperedPartIsRejectedByGcm() throws Exception {
        Properties properties = properties();
        byte[] encrypted = currentPart(properties);
        encrypted[encrypted.length - 1] ^= 0x01;
        try {
            decrypt(encrypted, recipientPrivate(properties), properties, false);
            fail("篡改分卷应当被拒绝");
        } catch (VolumeFormatException expected) {
            // 预期：单卷 GCM 失败
        }
    }

    @Test
    public void truncatedContainerIsRejected() throws Exception {
        Properties properties = properties();
        byte[] encrypted = currentPart(properties);
        byte[] truncated = new byte[encrypted.length - 8];
        System.arraycopy(encrypted, 0, truncated, 0, truncated.length);
        try {
            decrypt(truncated, recipientPrivate(properties), properties, false);
            fail("截断分卷应当被拒绝");
        } catch (VolumeFormatException expected) {
            // 预期：读取失败或长度不符
        }
    }

    @Test
    public void cancellationStopsBeforeReturningResult() throws Exception {
        Properties properties = properties();
        try {
            decrypt(currentPart(properties), recipientPrivate(properties), properties, true);
            fail("取消后不应返回结果");
        } catch (FileCancelledException expected) {
            // 预期
        }
    }

    // region helpers

    private static VolumeDecryptResult decrypt(
            byte[] encrypted,
            PrivateKey privateKey,
            Properties properties,
            boolean cancelled
    ) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        return VolumeCryptoEngine.decrypt(
                new ByteArrayInputStream(encrypted),
                privateKey,
                Collections.singletonList(senderPublic(properties)),
                output,
                null,
                new AtomicBoolean(cancelled)
        );
    }

    private static byte[] currentPart(Properties properties) throws Exception {
        String name = properties.getProperty("multi.part.files").split(",")[0];
        return fixture(name);
    }

    private static Properties properties() throws Exception {
        InputStream input = VolumeCryptoEngineTest.class.getResourceAsStream(PROPERTIES);
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
        InputStream input = VolumeCryptoEngineTest.class.getResourceAsStream(
                "/external/" + name);
        assertNotNull("缺少夹具 /external/" + name, input);
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

    private static void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    // endregion
}
