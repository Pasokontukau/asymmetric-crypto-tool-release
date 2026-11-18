package securecomm.service;

import crypto.key.KeyUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Properties;

/**
 * 外部分卷固定夹具的读取入口（测试专用）。
 *
 * <p>夹具由共享内核 {@code VolumePartWriter} 生成，桌面只消费同一批字节。
 * 这里只做“属性读取 + 复制到临时目录 + 解出测试密钥”，不构造任何分卷。</p>
 */
public final class ExternalPartFixtures {

    public static final String PROPERTIES = "/external/external-part-fixture.properties";

    private ExternalPartFixtures() {
    }

    public static Properties properties() throws Exception {
        try (InputStream input = ExternalPartFixtures.class.getResourceAsStream(PROPERTIES)) {
            if (input == null) {
                throw new IllegalStateException("缺少夹具 " + PROPERTIES);
            }
            Properties properties = new Properties();
            properties.load(input);
            return properties;
        }
    }

    /** 把 {@code /external/<name>} 复制到 {@code directory} 下并返回副本。 */
    public static File copy(File directory, String name) throws Exception {
        File output = new File(directory, name);
        try (InputStream input = ExternalPartFixtures.class.getResourceAsStream(
                "/external/" + name)) {
            if (input == null) {
                throw new IllegalStateException("缺少夹具 /external/" + name);
            }
            try (FileOutputStream out = new FileOutputStream(output)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
            }
        }
        return output;
    }

    /** 夹具里的接收方私钥（测试密钥，不用于生产）。 */
    public static PrivateKey recipientPrivate(Properties properties) throws Exception {
        return KeyUtils.getPrivateKeyFromBytes(
                Base64.getDecoder().decode(
                        properties.getProperty("recipient.private.pkcs8.b64")));
    }

    /** 夹具里的发送方公钥，用于验签。 */
    public static PublicKey senderPublic(Properties properties) throws Exception {
        return KeyUtils.getPublicKeyFromBytes(
                Base64.getDecoder().decode(
                        properties.getProperty("sender.public.x509.b64")));
    }
}
