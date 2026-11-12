package com.cryptoassistant.app.feature.files

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptoassistant.app.CryptoAssistantApplication
import com.cryptoassistant.app.data.keys.IdentityKey
import com.cryptoassistant.app.data.keys.KeyBundle
import com.cryptoassistant.app.data.keys.KeyRepository
import com.cryptoassistant.app.data.keys.PrivateKeyBackup
import crypto.key.KeyUtils
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 当前外部分卷的设备路由测试：用共享内核生成的固定夹具、独立的 KeyRepository
 * 和真实 FileProvider content URI，走完整的选文件 → 解密/合并 → 结果落盘路径。
 */
@RunWith(AndroidJUnit4::class)
class VolumeFileRoutingTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext: Context = InstrumentationRegistry.getInstrumentation().context
    private val app: CryptoAssistantApplication
        get() = context.applicationContext as CryptoAssistantApplication

    private val taskRoot: File by lazy { File(context.filesDir, FileTaskStorage.DIRECTORY_NAME) }
    private val fixtureDirectory: File by lazy { File(taskRoot, "external-test") }
    private var viewModel: FileTaskViewModel? = null
    private var repositoryDirectory: File? = null

    @After
    fun cleanUp() {
        runCatching { viewModel?.reset() }
        FileTaskStorage.deleteRecursively(fixtureDirectory)
        repositoryDirectory?.let { FileTaskStorage.deleteRecursively(it) }
        viewModel = null
        repositoryDirectory = null
    }

    @Test
    fun threePartSetMergesBackToOriginalZip() = runBlocking {
        val properties = loadProperties()
        val vm = createViewModel(properties)
        val names = properties.getProperty("multi.part.files").split(",")
        val sources = names.map { copyFixture(it) }
        val original = copyFixture(properties.getProperty("multi.archive.file")).readBytes()

        vm.applyInitialMode(FileTaskMode.DECRYPT)
        vm.selectSources(sources.map(::providerUri))
        val ready = awaitState(vm) {
            it.externalPartSet?.status == crypto.volume.VolumeSetPlan.Status.COMPLETE
        }
        assertEquals(3, ready.externalPartSet?.partCount)

        vm.start()
        val done = awaitTerminal(vm)
        assertEquals(FileTaskStatus.SUCCEEDED, done.status)
        val result = requireNotNull(done.result)
        assertEquals(properties.getProperty("multi.archive.name"), result.displayName)
        assertEquals(3, result.mergedPartCount)
        assertEquals(true, result.signatureValid)
        assertEquals(true, result.contentHashVerified)
        assertArrayEquals(original, result.file.readBytes())
        assertTrue(result.file.absolutePath.startsWith(taskRoot.absolutePath))
    }

    @Test
    fun singlePartFolderPackageLandsAsZipWithoutExtracting() = runBlocking {
        val properties = loadProperties()
        val vm = createViewModel(properties)
        val source = copyFixture(properties.getProperty("single.part.file"))
        val original = copyFixture(properties.getProperty("single.archive.file")).readBytes()

        vm.applyInitialMode(FileTaskMode.DECRYPT)
        vm.selectSources(listOf(providerUri(source)))
        awaitState(vm) { it.queue.size == 1 && it.status == FileTaskStatus.READY }
        vm.start()
        val done = awaitTerminal(vm)

        assertEquals(FileTaskStatus.SUCCEEDED, done.status)
        val result = requireNotNull(done.result)
        assertEquals(properties.getProperty("single.archive.name"), result.displayName)
        assertEquals(true, result.sourceIsFolder)
        assertEquals(true, result.isArchive)
        assertArrayEquals(original, result.file.readBytes())
        assertFalse("不应在输出现场重建目录", File(result.file.parentFile, "docs").exists())

        val entries = mutableSetOf<String>()
        ZipInputStream(result.file.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries += entry.name
                entry = zip.nextEntry
            }
        }
        assertEquals(setOf("docs/readme.txt", "data/values.bin"), entries)
    }

    @Test
    fun tamperedCiphertextFailsAndLeavesNoPlaintext() = runBlocking {
        val properties = loadProperties()
        val vm = createViewModel(properties)
        val names = properties.getProperty("multi.part.files").split(",")
        val sources = names.map { copyFixture(it) }
        val first = sources.first()
        val bytes = first.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        first.writeBytes(bytes)

        vm.applyInitialMode(FileTaskMode.DECRYPT)
        vm.selectSources(sources.map(::providerUri))
        awaitState(vm) {
            it.externalPartSet?.status == crypto.volume.VolumeSetPlan.Status.COMPLETE
        }
        vm.start()
        val done = awaitTerminal(vm)

        assertEquals(FileTaskStatus.FAILED, done.status)
        assertNull(done.result)
        val item = done.queue.single()
        assertNotNull(item.error)
        assertEquals(
            "篡改分卷应给出分卷格式错误",
            context.getString(FileTaskError.VOLUME_FORMAT.messageRes),
            item.error,
        )
    }

    @Test
    fun unsupportedVolumeVersionIsRejectedWithReadableMessage() = runBlocking {
        val properties = loadProperties()
        val vm = createViewModel(properties)
        val source = copyFixture(properties.getProperty("multi.part.files").split(",").first())
        val bytes = source.readBytes()
        bytes[4] = 0
        bytes[5] = 0
        bytes[6] = 0
        bytes[7] = 2
        source.writeBytes(bytes)

        vm.applyInitialMode(FileTaskMode.DECRYPT)
        vm.selectSources(listOf(providerUri(source)))
        awaitState(vm) { it.queue.size == 1 && it.status == FileTaskStatus.READY }
        vm.start()
        val done = awaitTerminal(vm)

        assertEquals(FileTaskStatus.FAILED, done.status)
        assertNull(done.result)
        val error = requireNotNull(done.queue.single().error)
        assertEquals(
            "错误应使用统一的分卷版本文案",
            context.getString(FileTaskError.VOLUME_VERSION.messageRes),
            error,
        )
    }

    // region helpers

    private suspend fun createViewModel(properties: Properties): FileTaskViewModel {
        val root = File(context.cacheDir, "external-test-keys-${UUID.randomUUID()}")
        repositoryDirectory = root
        val repository = KeyRepository(context, rootDirectory = root)
        val password = "external-test-password".toCharArray()
        val privateKey = KeyUtils.getPrivateKeyFromBytes(
            Base64.getDecoder().decode(
                properties.getProperty("recipient.private.pkcs8.b64"),
            ),
        )
        val publicKey = PrivateKeyBackup.derivePublicKey(privateKey)
        val identity = IdentityKey(
            displayName = "external-receiver",
            publicKeyBase64 = PrivateKeyBackup.encodePublicKey(publicKey),
            fingerprint = PrivateKeyBackup.fingerprint(publicKey),
            keySize = PrivateKeyBackup.keySize(publicKey),
            createdAt = 1L,
        )
        val bundle = KeyBundle.encode(identity, PrivateKeyBackup.seal(privateKey, password))
        repository.importIdentity("external-receiver", bundle, password).getOrThrow()
        repository.addContact(
            "external-sender",
            properties.getProperty("sender.public.x509.b64"),
        ).getOrThrow()
        return FileTaskViewModel(app, repository).also { viewModel = it }
    }

    private fun loadProperties(): Properties {
        val properties = Properties()
        testContext.assets.open("external/external-part-fixture.properties").use {
            properties.load(it)
        }
        return properties
    }

    private fun copyFixture(name: String): File {
        fixtureDirectory.mkdirs()
        val target = File(fixtureDirectory, name)
        testContext.assets.open("external/$name").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun providerUri(file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )

    private suspend fun awaitState(
        vm: FileTaskViewModel,
        predicate: (FileTaskUiState) -> Boolean,
    ): FileTaskUiState = withTimeout(20_000) { vm.state.first(predicate) }

    private suspend fun awaitTerminal(vm: FileTaskViewModel): FileTaskUiState = awaitState(vm) {
        it.status == FileTaskStatus.SUCCEEDED ||
            it.status == FileTaskStatus.PARTIAL ||
            it.status == FileTaskStatus.FAILED
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    // endregion
}
