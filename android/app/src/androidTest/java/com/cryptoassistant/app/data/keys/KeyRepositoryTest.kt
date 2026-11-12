package com.cryptoassistant.app.data.keys

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var repository: KeyRepository

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "keys-test-" + System.nanoTime())
        repository = KeyRepository(context, rootDirectory = directory)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun identityLifecycleBackupAndContacts() = runBlocking {
        assertNull(repository.snapshot.value.identity)

        val created = repository.createIdentity("测试身份").getOrThrow()
        assertEquals(4096, created.keySize)
        // 桌面端指纹是 SHA-256 前 16 字节的十六进制表示
        assertEquals(32, created.fingerprint.length)
        assertNotNull(repository.currentPrivateKey())
        assertNull(repository.error.value)

        val password = "backup-password".toCharArray()
        val backup = repository.exportIdentityBackup(password.copyOf()).getOrThrow()
        assertTrue("完整备份要带上公钥", backup.contains("publicKey="))
        repository.deleteIdentity().getOrThrow()
        assertNull(repository.snapshot.value.identity)

        val restored = repository.importIdentity("恢复", backup, password.copyOf()).getOrThrow()
        assertEquals(created.fingerprint, restored.fingerprint)
        assertEquals(created.publicKeyBase64, restored.publicKeyBase64)
        // 完整备份必须带回原始创建时间；最近导入是本机事件。
        assertEquals(created.createdAt, restored.createdAt)
        assertNotNull(restored.lastImportedAt)
        assertNull(repository.error.value)

        // 自己的公钥不能被添加为联系人
        assertTrue(repository.addContact("自己", created.publicKeyBase64).isFailure)

        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val otherKey = Base64.getEncoder().encodeToString(other.public.encoded)
        val contact = repository.addContact("小明", otherKey).getOrThrow()
        assertEquals(1, repository.snapshot.value.contacts.size)
        assertTrue(repository.addContact("重复公钥", otherKey).isFailure)

        repository.removeContact(contact.id).getOrThrow()
        assertTrue(repository.snapshot.value.contacts.isEmpty())

        // 旧版纯 GCM1 没有 createdAt：导入后显示未知，而不是伪造导入时间。
        val legacyBackup = PrivateKeyBackup.seal(
            requireNotNull(repository.currentPrivateKey()),
            password.copyOf(),
        )
        repository.deleteIdentity().getOrThrow()
        val legacy = repository.importIdentity(
            "旧备份",
            legacyBackup,
            password.copyOf(),
        ).getOrThrow()
        assertEquals(IdentityKey.UNKNOWN_CREATED_AT, legacy.createdAt)
        assertNotNull(legacy.lastImportedAt)

        password.fill('\u0000')
    }
}
