package contacts.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import contacts.model.Contact;
import contacts.storage.ContactStorage;
import core.storage.StorageManager;
import crypto.key.KeyPairManager;
import crypto.key.KeyUtils;
import java.io.File;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import keymanagement.model.KeyPairInfo;
import keymanagement.storage.KeyPairStorage;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 联系人添加前的 Android 对齐校验。 */
public class ContactValidatorTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private String originalDataDir;

    @Before
    public void useTemporaryDataDirectory() throws Exception {
        originalDataDir = StorageManager.getDataDir();
        File data = temporaryFolder.newFolder("data");
        new File(data, "keypairs").mkdirs();
        new File(data, "contacts").mkdirs();
        new File(data, "config").mkdirs();
        StorageManager.useDataDirForTests(data.getAbsolutePath());
    }

    @After
    public void restoreDataDirectory() {
        StorageManager.useDataDirForTests(originalDataDir);
    }

    @Test
    public void rejectsOwnPublicKey() throws Exception {
        KeyPair own = generate(2048);
        saveOwnKey("own", own);

        try {
            ContactValidator.validateForAdd(publicKey(own));
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage().contains("自己"));
            return;
        }
        throw new AssertionError("自己的公钥必须被拒绝");
    }

    @Test
    public void rejectsDuplicateContactFingerprint() throws Exception {
        KeyPair other = generate(2048);
        String publicKey = publicKey(other);
        ContactStorage.saveContact(new Contact(
                "bob",
                publicKey,
                "RSA",
                KeyUtils.generateKeyFingerprint(publicKey),
                null));

        try {
            ContactValidator.validateForAdd(publicKey);
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage().contains("bob"));
            return;
        }
        throw new AssertionError("重复联系人指纹必须被拒绝");
    }

    @Test
    public void rejectsRsaKeySmallerThan2048Bits() throws Exception {
        KeyPair weak = generate(1024);
        try {
            ContactValidator.validateForAdd(publicKey(weak));
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage().contains("2048"));
            return;
        }
        throw new AssertionError("1024 位公钥必须被拒绝");
    }

    @Test
    public void acceptsValidContactAndGroupsFingerprint() throws Exception {
        KeyPair other = generate(2048);
        String fingerprint = ContactValidator.validateForAdd(publicKey(other));

        assertEquals(KeyUtils.generateKeyFingerprint(publicKey(other)), fingerprint);
        assertEquals("abcd efgh ijkl", ContactValidator.formatFingerprint("abcdefghijkl"));
    }

    private static KeyPair generate(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static String publicKey(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    private static void saveOwnKey(String name, KeyPair keyPair) throws Exception {
        byte[] encrypted = KeyPairManager.encryptPrivateKey(
                keyPair.getPrivate().getEncoded(), "test-pass-123".toCharArray());
        KeyPairInfo info = new KeyPairInfo(
                name, Instant.now(), "RSA", 2048, publicKey(keyPair), name + ".pem");
        KeyPairStorage.saveKeyPair(name, info, encrypted);
    }
}
