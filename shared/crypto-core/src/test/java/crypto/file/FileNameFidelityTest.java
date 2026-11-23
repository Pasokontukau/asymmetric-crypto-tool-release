package crypto.file;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * X-05：文件名在容器里的保真度。
 *
 * <p>两端共用同一个内核，文件名以 UTF-8 写进文件头再读出；这里锁定四类最容易出错的输入：
 * 中英混排、emoji（代理对）、NFC/NFD 两种等价但字节不同的组合形式、以及接近 255 的长名。
 * 任何“读回来变了但看起来一样”的情况（例如被 NFC 归一化）都会让跨端解出的文件名对不上。</p>
 */
public class FileNameFidelityTest {

    private static final String[] NAMES = {
            "普通文件-测试.txt",
            "emoji-😀🔐-file.bin",
            "é-composed.txt",                 // U+00E9
            "e\u0301-decomposed.txt",         // e + U+0301，字节与上面不同
            "a b  c.txt",                     // 连续空格
            "資料-日本語-한국어.dat",
            repeat('x', 200) + ".txt",        // 长名（未到 255，跨端都合法）
    };

    private static String repeat(char c, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(c);
        }
        return builder.toString();
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    @Test
    public void everyEdgeNameSurvivesRoundTrip() throws Exception {
        KeyPair sender = keyPair();
        KeyPair receiver = keyPair();
        byte[] plain = "payload".getBytes(StandardCharsets.UTF_8);

        for (String name : NAMES) {
            byte[] container = encrypt(plain, name, sender, receiver);
            FileDecryptResult result = decrypt(container, receiver);
            assertEquals("文件名在往返后被改变", name, result.getOriginalFileName());
        }
    }

    @Test
    public void nfcAndNfdNamesStayDistinct() throws Exception {
        KeyPair sender = keyPair();
        KeyPair receiver = keyPair();
        byte[] plain = new byte[] {1, 2, 3};

        String composed = "caf\u00e9.txt";
        String decomposed = "cafe\u0301.txt";
        assertNotEquals("两个名字在 Java 字符串层面就必须不同", composed, decomposed);

        String first = decrypt(encrypt(plain, composed, sender, receiver), receiver).getOriginalFileName();
        String second = decrypt(encrypt(plain, decomposed, sender, receiver), receiver).getOriginalFileName();

        assertEquals(composed, first);
        assertEquals(decomposed, second);
        assertNotEquals("NFC/NFD 不能被归一化成同一个名字", first, second);
        assertArrayEquals(composed.getBytes(StandardCharsets.UTF_8),
                first.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void ciphertextContainsNoPlainName() throws Exception {
        KeyPair sender = keyPair();
        KeyPair receiver = keyPair();
        String name = "机密文件名.txt";
        byte[] container = encrypt("x".getBytes(StandardCharsets.UTF_8), name, sender, receiver);
        assertTrue("文件头应加密文件名，密文中不应出现明文名",
                indexOf(container, name.getBytes(StandardCharsets.UTF_8)) < 0);
    }

    private static byte[] encrypt(byte[] plain, String name, KeyPair sender, KeyPair receiver)
            throws Exception {
        File source = File.createTempFile("name-src", ".bin");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
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
                        name,
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

    private static FileDecryptResult decrypt(byte[] container, KeyPair receiver) throws Exception {
        try (InputStream input = new ByteArrayInputStream(container);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            return FileCryptoEngine.decrypt(
                    input,
                    receiver.getPrivate(),
                    java.util.Collections.singletonList(receiver.getPublic()),
                    output,
                    null,
                    new AtomicBoolean(false));
        }
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
