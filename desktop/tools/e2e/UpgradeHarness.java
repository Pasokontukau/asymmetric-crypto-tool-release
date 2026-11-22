import contacts.model.Contact;
import contacts.storage.ContactStorage;
import core.storage.StorageManager;
import crypto.file.FileCryptoEngine;
import crypto.file.FileDecryptResult;
import crypto.key.KeyPairManager;
import crypto.key.KeyUtils;
import history.storage.HistoryStorage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import keymanagement.model.KeyPairInfo;
import keymanagement.storage.KeyPairStorage;
import settings.storage.SettingsStorage;

/** 安装器/便携包升级验收夹具：在应用真实数据目录里播种，再用另一份 jar 验证与解密。 */
public final class UpgradeHarness {

    private static final String KEY = "upgrade-key";
    private static final String PASSWORD = "upgrade-pass-123";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: UpgradeHarness <seed|verify> <workdir>");
        }
        Path base = Paths.get(args[1]).toAbsolutePath();
        Files.createDirectories(base);
        if ("seed".equals(args[0])) {
            seed(base);
        } else if ("verify".equals(args[0])) {
            verify(base);
        } else {
            throw new IllegalArgumentException("unknown mode: " + args[0]);
        }
    }

    private static void seed(Path base) throws Exception {
        KeyPair keys = KeyPairManager.generateKeyPair(4096);
        byte[] encrypted = KeyPairManager.encryptPrivateKey(
                keys.getPrivate().getEncoded(), PASSWORD.toCharArray());
        String publicKey = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        File pem = new File(StorageManager.getKeypairsDir(), KEY + ".pem");
        KeyPairStorage.saveKeyPair(KEY,
                new KeyPairInfo(KEY, Instant.now(), "RSA", 4096, publicKey, pem.getAbsolutePath()),
                encrypted);
        KeyPairStorage.setDefaultKeyPair(KEY);
        ContactStorage.saveContact(
                new Contact("upgrade-contact", publicKey, "RSA", "upgrade-fingerprint", KEY));
        HistoryStorage.addHistoryRecord("文件加密", "upgrade-acceptance", "seeded",
                KEY, "upgrade-contact", null, null, "升级验收夹具");
        SettingsStorage.setPasswordCacheTimeout(15);

        byte[] plain = "升级验收明文 upgrade acceptance 1.4.1 -> 1.4.2\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(base.resolve("plain.txt"), plain);
        try (InputStream signature = new ByteArrayInputStream(plain);
             InputStream input = new ByteArrayInputStream(plain);
             OutputStream output = Files.newOutputStream(base.resolve("old-pre-upgrade.aegix"))) {
            FileCryptoEngine.encrypt(signature, input, plain.length, "plain.txt",
                    keys.getPrivate(), keys.getPublic(), output, null, new AtomicBoolean(false));
        }

        System.out.println("SEED PASS keypairs=" + KeyPairStorage.loadAllKeyPairs().size()
                + " contacts=" + ContactStorage.loadAllContacts().size()
                + " history=" + HistoryStorage.loadHistory().size()
                + " timeout=" + SettingsStorage.getPasswordCacheTimeout()
                + " dataDir=" + StorageManager.getDataDir()
                + " plainSha=" + sha256(plain));
    }

    private static void verify(Path base) throws Exception {
        byte[] encrypted = KeyPairStorage.loadEncryptedPrivateKey(KEY);
        if (encrypted == null || encrypted.length == 0) {
            fail("找不到加密私钥文件");
        }
        boolean passwordOk = KeyPairManager.verifyPassword(encrypted, PASSWORD.toCharArray());
        byte[] plainPrivate = KeyPairManager.decryptPrivateKey(encrypted, PASSWORD.toCharArray());
        PrivateKey privateKey = KeyUtils.getPrivateKeyFromBytes(plainPrivate);
        Arrays.fill(plainPrivate, (byte) 0);

        String publicKeyBase64 = null;
        List<KeyPairInfo> keyPairs = KeyPairStorage.loadAllKeyPairs();
        for (KeyPairInfo info : keyPairs) {
            if (KEY.equals(info.getName())) {
                publicKeyBase64 = info.getPublicKey();
            }
        }
        if (publicKeyBase64 == null) {
            fail("密钥对元数据丢失");
        }
        PublicKey publicKey = KeyUtils.getPublicKeyFromBytes(Base64.getDecoder().decode(publicKeyBase64));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FileDecryptResult result;
        try (InputStream input = Files.newInputStream(base.resolve("old-pre-upgrade.aegix"))) {
            result = FileCryptoEngine.decrypt(input, privateKey,
                    Collections.singletonList(publicKey), output, null, new AtomicBoolean(false));
        }
        byte[] plain = output.toByteArray();
        byte[] expected = Files.readAllBytes(base.resolve("plain.txt"));
        boolean sameBytes = Arrays.equals(plain, expected);
        boolean hasContact = false;
        for (Contact contact : ContactStorage.loadAllContacts()) {
            if ("upgrade-contact".equals(contact.getName())) {
                hasContact = true;
            }
        }
        int history = HistoryStorage.loadHistory().size();
        int timeout = SettingsStorage.getPasswordCacheTimeout();

        String summary = "VERIFY " + (passwordOk && sameBytes && hasContact && history > 0 && result.isSignatureValid()
                && timeout == 15 ? "PASS" : "FAIL")
                + " keypairs=" + keyPairs.size()
                + " contacts=" + (hasContact ? 1 : 0)
                + " history=" + history
                + " timeout=" + timeout
                + " decryptedBytes=" + plain.length
                + " signatureValid=" + result.isSignatureValid()
                + " sameBytes=" + sameBytes
                + " originalName=" + result.getOriginalFileName()
                + " dataDir=" + StorageManager.getDataDir();
        System.out.println(summary);
        if (summary.startsWith("VERIFY FAIL")) {
            System.exit(3);
        }
    }

    private static void fail(String message) {
        System.out.println("VERIFY FAIL " + message);
        System.exit(3);
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
