package core.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import org.junit.Test;

/** 密码加密导出容器：可逆、无明文、密码错误/篡改必须失败。 */
public class ExportCryptoTest {

    private static final char[] PASSWORD = "correct horse battery".toCharArray();

    @Test
    public void roundTripsChineseText() throws Exception {
        String text = "文件名: 工资表 2026.xlsx\n路径: C:\\Users\\me\\文档\n联系人: 张三";
        byte[] plaintext = text.getBytes(StandardCharsets.UTF_8);

        byte[] encrypted = ExportCrypto.encrypt(plaintext, PASSWORD);

        assertTrue("导出文件应带 AEXP 头", ExportCrypto.isEncrypted(encrypted));
        String raw = new String(encrypted, StandardCharsets.ISO_8859_1);
        assertFalse("加密结果不能出现原文件名", raw.contains("工资表"));
        assertFalse("加密结果不能出现用户路径", raw.contains("Users"));
        assertFalse("加密结果不能出现联系人", raw.contains("张三"));

        assertArrayEquals(plaintext, ExportCrypto.decrypt(encrypted, PASSWORD));
    }

    @Test
    public void wrongPasswordFails() throws Exception {
        byte[] encrypted = ExportCrypto.encrypt("secret".getBytes(StandardCharsets.UTF_8), PASSWORD);
        try {
            ExportCrypto.decrypt(encrypted, "wrong password".toCharArray());
            fail("错误密码不应解密成功");
        } catch (GeneralSecurityException expected) {
            assertTrue(expected.getMessage().contains("密码错误"));
        }
    }

    @Test
    public void tamperedCiphertextFails() throws Exception {
        byte[] encrypted = ExportCrypto.encrypt("secret".getBytes(StandardCharsets.UTF_8), PASSWORD);
        encrypted[encrypted.length - 1] ^= 0x01;
        try {
            ExportCrypto.decrypt(encrypted, PASSWORD);
            fail("篡改后的导出文件不应解密成功");
        } catch (GeneralSecurityException expected) {
            assertTrue(expected.getMessage().contains("损坏"));
        }
    }

    @Test
    public void shortPasswordIsRejected() throws Exception {
        try {
            ExportCrypto.encrypt("secret".getBytes(StandardCharsets.UTF_8), "short".toCharArray());
            fail("少于 8 位的密码不应被接受");
        } catch (GeneralSecurityException expected) {
            assertTrue(expected.getMessage().contains("8"));
        }
    }

    @Test
    public void plainBytesAreNotMistakenForEncryptedExport() {
        assertFalse(ExportCrypto.isEncrypted("{}".getBytes(StandardCharsets.UTF_8)));
        assertFalse(ExportCrypto.isEncrypted(null));
        assertFalse(ExportCrypto.isEncrypted(new byte[0]));
    }
}
