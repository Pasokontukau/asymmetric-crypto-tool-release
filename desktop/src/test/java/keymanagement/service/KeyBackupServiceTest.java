package keymanagement.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import core.storage.StorageManager;
import crypto.key.KeyPairManager;
import java.io.File;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import keymanagement.model.KeyPairInfo;
import keymanagement.storage.KeyPairStorage;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 桌面端与 Android 的 aegix-key-bundle 互操作与防篡改校验。 */
public class KeyBackupServiceTest {

    private static final char[] PASSWORD = "backup-pass-123".toCharArray();
    private static KeyPair keyPair;
    private static KeyPair otherKeyPair;

    @BeforeClass
    public static void createKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        otherKeyPair = generator.generateKeyPair();
    }

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
    public void exportsAndroidBundleAndImportsSamePrivateKey() throws Exception {
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        KeyPairInfo info = new KeyPairInfo(
                "backup_key", Instant.now(), "RSA", 2048, publicKey, "backup_key.pem");

        String bundle = KeyBackupService.exportBundle(info, keyPair.getPrivate(), PASSWORD);

        assertTrue("必须是当前完整备份格式", bundle.contains("format=aegix-key-bundle"));
        KeyBackupService.Preview preview = KeyBackupService.inspect(bundle);
        assertEquals("backup_key", preview.getName());
        assertEquals(2048, preview.getKeySize());
        assertEquals(publicKey, preview.getPublicKey());

        KeyPairInfo imported = KeyBackupService.importBundle(bundle, "恢复密钥", PASSWORD);
        assertEquals("恢复密钥", imported.getName());
        assertEquals(publicKey, imported.getPublicKey());
        assertTrue(new File(StorageManager.getKeypairsDir(),
                imported.getName() + ".pub").isFile());
        assertTrue(new File(StorageManager.getKeypairsDir(),
                imported.getName() + ".pem").isFile());
        assertTrue(new File(StorageManager.getKeypairsDir(),
                imported.getName() + ".salt").isFile());

        byte[] encrypted = KeyPairStorage.loadEncryptedPrivateKey(imported.getName());
        byte[] plain = KeyPairManager.decryptPrivateKey(encrypted, PASSWORD);
        assertArrayEquals("导入后的私钥应与导出前一致",
                keyPair.getPrivate().getEncoded(), plain);
    }

    @Test(expected = Exception.class)
    public void rejectsWrongBackupPassword() throws Exception {
        String bundle = newBundle("wrong_password");
        KeyBackupService.importBundle(bundle, "wrong_password", "not-the-password".toCharArray());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTamperedPublicKey() throws Exception {
        String otherPublic = Base64.getEncoder().encodeToString(
                otherKeyPair.getPublic().getEncoded());
        String bundle = newBundle("tampered");
        String tampered = bundle.replaceFirst("publicKey=.*", "publicKey=" + otherPublic);

        KeyBackupService.importBundle(tampered, "tampered", PASSWORD);
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsBinaryBackupFile() throws Exception {
        File file = temporaryFolder.newFile("binary.txt");
        Files.write(file.toPath(), new byte[]{1, 2, 0, 4});
        KeyBackupService.readText(file);
    }

    private static String newBundle(String name) throws Exception {
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        KeyPairInfo info = new KeyPairInfo(
                name, Instant.now(), "RSA", 2048, publicKey, name + ".pem");
        return KeyBackupService.exportBundle(info, keyPair.getPrivate(), PASSWORD);
    }
}
