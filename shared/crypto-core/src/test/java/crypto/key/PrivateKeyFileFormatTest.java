package crypto.key;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import crypto.config.CryptoConfig;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import org.junit.Test;

/**
 * 私钥文件格式（K-12）：正常往返 + 一切“外部文件可能长这样”的畸形输入。
 *
 * <p>私钥文件是用户会自己拷贝、网盘同步、手工编辑的文件，必须假设它会被截断、被改动、
 * 被换成别的东西。这里断言的核心只有一条：**任何畸形输入都不能被当成“口令正确”**，
 * 且不能返回半截明文。</p>
 */
public class PrivateKeyFileFormatTest {

    private static final char[] PASSWORD = "Str0ng!Passphrase-2026".toCharArray();

    private static byte[] payload() {
        byte[] data = new byte[64];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 7 + 3);
        }
        return data;
    }

    @Test
    public void roundTripRestoresPlaintext() throws Exception {
        byte[] encrypted = KeyPairManager.encryptPrivateKey(payload(), PASSWORD.clone());
        assertArrayEquals(payload(), KeyPairManager.decryptPrivateKey(encrypted, PASSWORD.clone()));
        assertTrue(KeyPairManager.verifyPassword(encrypted, PASSWORD.clone()));
    }

    @Test
    public void newFormatStartsWithVersionMagic() throws Exception {
        byte[] encrypted = KeyPairManager.encryptPrivateKey(payload(), PASSWORD.clone());
        assertTrue("新格式必须以 GCM1 开头，旧 CBC 分支才会被绕开",
                encrypted.length >= 4
                        && encrypted[0] == 'G'
                        && encrypted[1] == 'C'
                        && encrypted[2] == 'M'
                        && encrypted[3] == '1');
        assertTrue("长度必须覆盖 magic+salt+iv+tag",
                encrypted.length > 4 + CryptoConfig.SALT_LENGTH + CryptoConfig.GCM_IV_LENGTH
                        + CryptoConfig.GCM_TAG_LENGTH);
    }

    @Test
    public void encryptionIsRandomised() throws Exception {
        byte[] first = KeyPairManager.encryptPrivateKey(payload(), PASSWORD.clone());
        byte[] second = KeyPairManager.encryptPrivateKey(payload(), PASSWORD.clone());
        assertFalse("salt/iv 每次都随机，同一明文两次密文不能相同", Arrays.equals(first, second));
        assertArrayEquals(payload(), KeyPairManager.decryptPrivateKey(second, PASSWORD.clone()));
    }

    @Test
    public void wrongPasswordFailsVerification() throws Exception {
        byte[] encrypted = KeyPairManager.encryptPrivateKey(payload(), PASSWORD.clone());
        assertFalse(KeyPairManager.verifyPassword(encrypted, "wrong-password".toCharArray()));
        try {
            KeyPairManager.decryptPrivateKey(encrypted, "wrong-password".toCharArray());
            fail("错误口令必须抛异常");
        } catch (AEADBadTagException expected) {
            // GCM 完整性校验失败：正确行为。
        } catch (Exception other) {
            // 不同 JDK 可能包装成 BadPadding/GeneralSecurityException，只要不是返回明文.
        }
    }

    @Test
    public void everyTruncationIsRejected() throws Exception {
        byte[] encrypted = KeyPairManager.encryptPrivateKey(payload(), PASSWORD.clone());
        for (int length = 1; length < encrypted.length; length++) {
            byte[] truncated = Arrays.copyOf(encrypted, length);
            try {
                byte[] plain = KeyPairManager.decryptPrivateKey(truncated, PASSWORD.clone());
                fail("截断到 " + length + " 字节竟被接受，返回了 " + plain.length + " 字节");
            } catch (Exception expected) {
                // 期望：一律拒绝。
            }
            assertFalse("截断到 " + length + " 字节时 verifyPassword 必须为 false",
                    KeyPairManager.verifyPassword(truncated, PASSWORD.clone()));
        }
    }

    @Test
    public void anyCiphertextBitFlipIsRejected() throws Exception {
        byte[] encrypted = KeyPairManager.encryptPrivateKey(payload(), PASSWORD.clone());
        int[] positions = {4, 4 + CryptoConfig.SALT_LENGTH,
                4 + CryptoConfig.SALT_LENGTH + CryptoConfig.GCM_IV_LENGTH,
                encrypted.length / 2, encrypted.length - 1};
        for (int position : positions) {
            byte[] tampered = encrypted.clone();
            tampered[position] ^= 0x01;
            assertFalse("第 " + position + " 字节被改动后 verifyPassword 必须为 false",
                    KeyPairManager.verifyPassword(tampered, PASSWORD.clone()));
            try {
                KeyPairManager.decryptPrivateKey(tampered, PASSWORD.clone());
                fail("第 " + position + " 字节被改动后仍被解密");
            } catch (Exception expected) {
                // 期望：拒绝。
            }
        }
    }

    @Test
    public void headerOnlyDataIsRejected() throws Exception {
        int headerOnly = 4 + CryptoConfig.SALT_LENGTH + CryptoConfig.GCM_IV_LENGTH;
        byte[] header = Arrays.copyOf(KeyPairManager.encryptPrivateKey(payload(), PASSWORD.clone()), headerOnly);
        try {
            KeyPairManager.decryptPrivateKey(header, PASSWORD.clone());
            fail("只有头部、没有密文与 tag 的数据必须被拒绝");
        } catch (Exception expected) {
            // 期望：拒绝。
        }
    }

    @Test
    public void tooShortDataIsRejected() {
        for (int length : new int[] {0, 1, 31}) {
            try {
                KeyPairManager.decryptPrivateKey(new byte[length], PASSWORD.clone());
                fail("长度 " + length + " 的数据必须被拒绝");
            } catch (Exception expected) {
                // 期望：拒绝。
            }
        }
    }

    @Test
    public void emptyOrNullInputsAreRejected() throws Exception {
        byte[] encrypted = KeyPairManager.encryptPrivateKey(payload(), PASSWORD.clone());

        try {
            KeyPairManager.decryptPrivateKey(encrypted, new char[0]);
            fail("空口令必须被拒绝");
        } catch (Exception expected) {
            // 期望：拒绝。
        }
        try {
            KeyPairManager.decryptPrivateKey(null, PASSWORD.clone());
            fail("null 数据必须被拒绝");
        } catch (Exception expected) {
            // 期望：拒绝。
        }
        try {
            KeyPairManager.encryptPrivateKey(payload(), null);
            fail("null 口令必须被拒绝");
        } catch (Exception expected) {
            // 期望：拒绝。
        }
    }

    @Test
    public void randomBytesShapedLikeHeaderNeverVerify() {
        // 伪造“GCM1 + 随机字节”，模拟被替换/损坏的私钥文件。
        for (int round = 0; round < 50; round++) {
            byte[] forged = new byte[4 + 16 + 12 + 48];
            forged[0] = 'G';
            forged[1] = 'C';
            forged[2] = 'M';
            forged[3] = '1';
            for (int i = 4; i < forged.length; i++) {
                forged[i] = (byte) ((i * 31 + round * 17) & 0xFF);
            }
            assertFalse("伪造头部 + 随机密文不得通过口令校验",
                    KeyPairManager.verifyPassword(forged, PASSWORD.clone()));
        }
    }

    @Test
    public void legacyCbcFormatWithoutMagicIsStillReadablePath() {
        // 旧格式 = 无 magic；首字节改成 'X' 后必须走旧分支，且绝不会返回“校验通过”。
        byte[] legacy = new byte[64];
        for (int i = 0; i < legacy.length; i++) {
            legacy[i] = (byte) (i * 13 + 5);
        }
        assertNotEquals('G', legacy[0]);
        assertFalse(KeyPairManager.verifyPassword(legacy, PASSWORD.clone()));
        assertFalse(KeyPairManager.verifyPassword(legacy, new String(PASSWORD).toUpperCase().toCharArray()));
    }

    @Test
    public void nonAsciiPasswordRoundTrips() throws Exception {
        char[] password = "中文口令-Passw0rd!".toCharArray();
        byte[] encrypted = KeyPairManager.encryptPrivateKey(payload(), password.clone());
        assertArrayEquals(payload(), KeyPairManager.decryptPrivateKey(encrypted, password.clone()));
        assertTrue(KeyPairManager.verifyPassword(encrypted, password.clone()));
    }

    @Test
    public void passwordIsNotEmbeddedInCiphertext() throws Exception {
        byte[] encrypted = KeyPairManager.encryptPrivateKey(payload(), PASSWORD.clone());
        byte[] needle = new String(PASSWORD).getBytes(StandardCharsets.UTF_8);
        assertTrue("密文中不应出现口令原文",
                indexOf(encrypted, needle) < 0);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
