package com.cryptoassistant.app.feature.files

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptoassistant.app.CryptoAssistantApplication
import com.cryptoassistant.app.data.keys.IdentityKey
import com.cryptoassistant.app.data.keys.PrivateKeyBackup
import crypto.file.FileCryptoEngine
import crypto.volume.VolumeCryptoEngine
import crypto.volume.VolumePartPlan
import java.io.File
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 批量文件队列的设备测试：用自家 FileProvider 造出真实可读的 content:// 密文，
 * 验证多选后按顺序解密、结果全部保留、失败文件不会阻断后续文件。
 */
@RunWith(AndroidJUnit4::class)
class BatchFileDecryptRoutingTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app: CryptoAssistantApplication
        get() = context.applicationContext as CryptoAssistantApplication

    private val taskRoot: File by lazy { File(context.filesDir, FileTaskStorage.DIRECTORY_NAME) }
    private val fixtureDirectory: File by lazy { File(taskRoot, "batch-test") }
    private var viewModel: FileTaskViewModel? = null

    @After
    fun cleanUp() {
        runCatching { viewModel?.reset() }
        FileTaskStorage.deleteRecursively(fixtureDirectory)
    }

    @Test
    fun twoEncryptedFilesAreDecryptedInSelectionOrder() {
        val identity = ensureIdentity()
        val privateKey = requireNotNull(app.keyRepository.currentPrivateKey())
        val publicKey = PrivateKeyBackup.decodePublicKey(identity.publicKeyBase64)
        val first = writeEncryptedFixture("first.aegix", "第一份明文.txt", "第一批内容", privateKey, publicKey)
        val second = writeEncryptedFixture("second.aegix", "第二份明文.txt", "第二批内容", privateKey, publicKey)

        val vm = FileTaskViewModel(app).also { viewModel = it }
        vm.applyInitialMode(FileTaskMode.DECRYPT)
        vm.selectSources(listOf(providerUri(first), providerUri(second)))
        val ready = awaitState(vm) { it.queue.size == 2 && it.status == FileTaskStatus.READY }
        assertEquals(2, ready.runnableCount)

        vm.start()
        val done = awaitState(vm) {
            it.status == FileTaskStatus.SUCCEEDED ||
                it.status == FileTaskStatus.PARTIAL ||
                it.status == FileTaskStatus.FAILED
        }

        assertEquals(FileTaskStatus.SUCCEEDED, done.status)
        assertEquals(2, done.successCount)
        assertEquals(
            listOf("第一份明文.txt", "第二份明文.txt"),
            done.results.map { it.displayName },
        )
        assertEquals(listOf("first.aegix", "second.aegix"), done.queue.map { it.source.displayName })
        done.results.forEach { result ->
            assertTrue("解密结果必须真实落盘", result.file.isFile)
            assertTrue(
                "输出必须落在 files/file-tasks/<uuid>/ 下",
                result.file.absolutePath.startsWith(taskRoot.absolutePath),
            )
        }

        vm.reset()
        done.results.forEach { result ->
            assertFalse("没有导出过的结果在 reset 时应当删除", result.file.exists())
        }
        viewModel = null
    }

    @Test
    fun brokenFileDoesNotStopTheFollowingFile() {
        val identity = ensureIdentity()
        val privateKey = requireNotNull(app.keyRepository.currentPrivateKey())
        val publicKey = PrivateKeyBackup.decodePublicKey(identity.publicKeyBase64)
        val valid = writeEncryptedFixture("valid.aegix", "有效结果.txt", "有效内容", privateKey, publicKey)
        val junk = File(fixtureDirectory, "photo.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        }

        val vm = FileTaskViewModel(app).also { viewModel = it }
        vm.applyInitialMode(FileTaskMode.DECRYPT)
        vm.selectSources(listOf(providerUri(junk), providerUri(valid)))
        awaitState(vm) { it.queue.size == 2 && it.status == FileTaskStatus.READY }

        vm.start()
        val done = awaitState(vm) {
            it.status == FileTaskStatus.SUCCEEDED ||
                it.status == FileTaskStatus.PARTIAL ||
                it.status == FileTaskStatus.FAILED
        }

        assertEquals(FileTaskStatus.PARTIAL, done.status)
        assertEquals(1, done.successCount)
        assertEquals(1, done.failureCount)
        val broken = done.queue.first { it.source.displayName == "photo.png" }
        assertNotNull("坏文件必须留下可读原因", broken.error)
        assertEquals(
            "误收的文件要明确说不是本工具格式",
            context.getString(FileTaskError.FILE_FORMAT.messageRes),
            broken.error,
        )
        val succeeded = done.queue.first { it.source.displayName == "valid.aegix" }
        assertNotNull("后面的有效文件仍应完成", succeeded.result)
        assertTrue(succeeded.result!!.file.isFile)
        assertEquals("有效结果.txt", succeeded.result!!.displayName)

        vm.reset()
        viewModel = null
    }

    @Test
    fun twoFilesAreEncryptedInOrderAndDecryptWithRecipientKey() {
        val identity = ensureIdentity()
        val senderPublic = PrivateKeyBackup.decodePublicKey(identity.publicKeyBase64)
        // 生成一把独立接收方密钥并加为联系人（2048 位足够快，且不依赖第二台设备）。
        val recipientPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        val recipient = runBlocking {
            app.keyRepository.addContact(
                "批量接收方",
                PrivateKeyBackup.encodePublicKey(recipientPair.public),
            ).getOrThrow()
        }
        try {
            val first = writePlainFixture("batch-one.txt", "第一份批量明文")
            val second = writePlainFixture("batch-two.txt", "第二份批量明文")

            val vm = FileTaskViewModel(app).also { viewModel = it }
            vm.applyInitialMode(FileTaskMode.ENCRYPT)
            vm.selectRecipient(recipient.id)
            vm.selectSources(listOf(providerUri(first), providerUri(second)))
            awaitState(vm) { it.queue.size == 2 && it.status == FileTaskStatus.READY }

            // 这是 1.3.0 的逐文件旧路径回归；D-2 默认打包由 P2-C 单独覆盖。
            vm.setPackageEnabled(false)
            vm.start()
            val done = awaitState(vm) {
                it.status == FileTaskStatus.SUCCEEDED ||
                    it.status == FileTaskStatus.PARTIAL ||
                    it.status == FileTaskStatus.FAILED
            }

            assertEquals(FileTaskStatus.SUCCEEDED, done.status)
            assertEquals(2, done.successCount)
            val names = done.results.map { it.displayName }
            assertEquals("批量密文不能重名", 2, names.toSet().size)
            names.forEach { name ->
                assertTrue(name.startsWith(FileTaskStorage.ENCRYPTED_NAME_PREFIX))
                assertTrue(name.endsWith(EncryptedFileFormat.EXTENSION))
            }

            val expected = listOf("第一份批量明文", "第二份批量明文")
            done.results.forEachIndexed { index, result ->
                val roundTrip = File(fixtureDirectory, "roundtrip-$index.bin")
                val decrypted = result.file.inputStream().use { input ->
                    roundTrip.outputStream().use { output ->
                        FileCryptoEngine.decrypt(
                            input,
                            recipientPair.private,
                            listOf(senderPublic),
                            output,
                            null,
                            null,
                        )
                    }
                }
                assertEquals("接收方应能解开批量密文", expected[index], roundTrip.readText())
                assertEquals(true, decrypted.isSignatureValid)
                assertEquals(
                    listOf("batch-one.txt", "batch-two.txt")[index],
                    decrypted.originalFileName,
                )
            }

            vm.reset()
            viewModel = null
        } finally {
            runBlocking { app.keyRepository.removeContact(recipient.id) }
        }
    }

    @Test
    fun twoFilesArePackagedIntoMultipleVolumesAndFirstPartDecryptsToZip() {
        val identity = ensureIdentity()
        val senderPublic = PrivateKeyBackup.decodePublicKey(identity.publicKeyBase64)
        val recipientPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        val recipient = runBlocking {
            app.keyRepository.addContact(
                "归档接收方",
                PrivateKeyBackup.encodePublicKey(recipientPair.public),
            ).getOrThrow()
        }
        try {
            val first = writePlainFixture("archive-one.txt", "归档第一份")
            val second = writePlainFixture("archive-two.txt", "归档第二份")

            val vm = FileTaskViewModel(app).also { viewModel = it }
            vm.applyInitialMode(FileTaskMode.ENCRYPT)
            vm.selectRecipient(recipient.id)
            vm.selectSources(listOf(providerUri(first), providerUri(second)))
            val ready = awaitState(vm) {
                it.queue.size == 2 && it.status == FileTaskStatus.READY
            }
            assertTrue("多选应默认允许打包", ready.canPackage)

            // 128 B 明文容量强制多卷，验证 1 个结果、多个独立 VOLU。
            vm.start(VolumePartPlan.HEADER_RESERVE_BYTES + 128L)
            val done = awaitState(vm) {
                it.status == FileTaskStatus.SUCCEEDED ||
                    it.status == FileTaskStatus.FAILED ||
                    it.status == FileTaskStatus.READY
            }

            assertEquals(FileTaskStatus.SUCCEEDED, done.status)
            assertEquals("打包成功不应把源队列标成逐项成功", 0, done.successCount)
            val result = requireNotNull(done.archiveResult) { "打包成功必须有 archiveResult" }
            assertTrue(result.isArchive)
            assertEquals(2, result.archiveFileCount)
            assertTrue("128 B 容量应产生多个分卷", result.volumeCount!! > 1)
            assertEquals(result.volumeCount, result.parts.size)
            assertEquals(result.parts.first(), result.file)
            assertEquals(result.parts.sumOf { it.length() }, result.bytes)
            result.parts.forEach { part ->
                assertTrue(part.isFile)
                assertTrue(part.name.endsWith(EncryptedFileFormat.EXTENSION))
            }

            // 每卷都是完整 VOLU；按顺序解出切片并拼回 ZIP，验证整包可还原。
            val zip = File(fixtureDirectory, "roundtrip-archive.zip")
            val volumeCount = requireNotNull(result.volumeCount)
            zip.outputStream().use { output ->
                result.parts.forEachIndexed { index, part ->
                    part.inputStream().use { input ->
                        val volume = VolumeCryptoEngine.decrypt(
                            input,
                            recipientPair.private,
                            listOf(senderPublic),
                            output,
                            null,
                            null,
                        )
                        assertEquals(index + 1, volume.partIndex)
                        assertEquals(volumeCount, volume.partCount)
                        assertEquals(true, volume.sourceIsArchive)
                    }
                }
            }
            ZipFile(zip).use { archive ->
                val names = archive.entries().asSequence().map { it.name }.toList()
                assertTrue(names.any { it.endsWith("archive-one.txt") })
                assertTrue(names.any { it.endsWith("archive-two.txt") })
            }

            vm.reset()
            result.parts.forEach { part ->
                assertFalse("reset 应删除全部分卷", part.exists())
            }
            viewModel = null
        } finally {
            runBlocking { app.keyRepository.removeContact(recipient.id) }
        }
    }

    // region helpers

    private fun writePlainFixture(name: String, content: String): File {
        fixtureDirectory.mkdirs()
        return File(fixtureDirectory, name).apply { writeText(content) }
    }

    private fun providerUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun writeEncryptedFixture(
        name: String,
        originalName: String,
        content: String,
        privateKey: PrivateKey,
        publicKey: PublicKey,
    ): File {
        fixtureDirectory.mkdirs()
        val target = File(fixtureDirectory, name)
        val plaintext = content.toByteArray()
        val signatureInput = plaintext.inputStream()
        val plaintextInput = plaintext.inputStream()
        target.outputStream().use { output ->
            FileCryptoEngine.encrypt(
                signatureInput,
                plaintextInput,
                plaintext.size.toLong(),
                originalName,
                privateKey,
                publicKey,
                output,
                null,
                null,
            )
        }
        signatureInput.close()
        plaintextInput.close()
        return target
    }

    private fun ensureIdentity(): IdentityKey {
        app.keyRepository.snapshot.value.identity?.let { return it }
        return runBlocking { app.keyRepository.createIdentity("批量导入测试").getOrThrow() }
    }

    private fun awaitState(
        viewModel: FileTaskViewModel,
        predicate: (FileTaskUiState) -> Boolean,
    ): FileTaskUiState = runBlocking {
        try {
            withTimeout(30_000) { viewModel.state.first(predicate) }
        } catch (error: Exception) {
            val current = viewModel.state.value
            throw AssertionError(
                "等待状态超时：status=${current.status} loading=${current.loading} " +
                    "queue=${current.queue.map { "${it.source.displayName}:${it.status}:${it.error}" }} " +
                    "error=${current.error}",
                error,
            )
        }
    }

    // endregion
}
