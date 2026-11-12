package com.cryptoassistant.app.feature.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptoassistant.app.CryptoAssistantApplication
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.keys.IdentityKey
import com.cryptoassistant.app.data.keys.PrivateKeyBackup
import com.cryptoassistant.app.feature.open.IncomingParseResult
import com.cryptoassistant.app.feature.open.parseIncomingFiles
import crypto.file.FileCryptoEngine
import java.io.File
import java.util.ArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「用其他应用打开」的接收链路。
 *
 * <p>设备测试与目标应用同 UID，所以能用**自家 FileProvider** 造出一个真实可读的
 * `content://`——这是没有微信/文件管理器时唯一能跑通真实 URI 读写的路径；
 * 微信侧只给 octet-stream、以及它是否在 path 里保留 `.aegix`，仍属真机人工验收项。</p>
 */
@RunWith(AndroidJUnit4::class)
class IncomingFileRoutingTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app: CryptoAssistantApplication
        get() = context.applicationContext as CryptoAssistantApplication

    private val taskRoot: File by lazy { File(context.filesDir, FileTaskStorage.DIRECTORY_NAME) }
    private val fixtureDirectory: File by lazy { File(taskRoot, "import-test") }

    @After
    fun cleanUp() {
        FileTaskStorage.deleteRecursively(fixtureDirectory)
    }

    @Test
    fun viewIntentWithOwnProviderUriIsAccepted() {
        val file = writeEncryptedFixture("sample.aegix")
        val uri = providerUri(file)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val result = parseIncomingFiles(context, intent)
        assertTrue("content:// VIEW 应当被接受", result is IncomingParseResult.Accepted)
        val parsed = result as IncomingParseResult.Accepted

        assertEquals(listOf(uri), parsed.request.uris)
        assertEquals(Intent.ACTION_VIEW, parsed.request.action)
        assertTrue("同 UID 的 provider 应当可读", parsed.request.readGranted)
        assertFalse("没有持久化位时不要谎报", parsed.request.persistable)
        assertTrue(parsed.diagnostic.contains("act=VIEW"))
        assertTrue(parsed.diagnostic.contains("n=1"))
        assertFalse("诊断日志不能包含文件名", parsed.diagnostic.contains("sample.aegix"))
        assertFalse("诊断日志不能包含绝对路径", parsed.diagnostic.contains(file.absolutePath))
    }

    @Test
    fun rejectsUrisWithoutContentSchemeOrFileUri() {
        val view = parseIncomingFiles(
            context,
            Intent(Intent.ACTION_VIEW).setData(Uri.parse("file:///sdcard/Download/x.enc")),
        )
        assertTrue(view is IncomingParseResult.Rejected)
        assertEquals("not_content", (view as IncomingParseResult.Rejected).reason)

        val sendWithoutStream = parseIncomingFiles(
            context,
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "只是文字，没有文件"),
        )
        assertTrue(sendWithoutStream is IncomingParseResult.Rejected)
        val rejected = sendWithoutStream as IncomingParseResult.Rejected
        assertEquals("no_uri", rejected.reason)
        assertTrue(rejected.message.contains("没有包含文件"))
    }

    @Test
    fun sendMultipleAcceptsAllContentUrisInOrder() {
        val first = providerUri(writePlainFixture("share-one.txt"))
        val second = providerUri(writePlainFixture("share-two.txt"))
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(listOf(first, second)))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val result = parseIncomingFiles(context, intent)
        assertTrue(result is IncomingParseResult.Accepted)
        val parsed = result as IncomingParseResult.Accepted

        assertEquals(listOf(first, second), parsed.request.uris)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, parsed.request.action)
        assertTrue(parsed.diagnostic.contains("act=SEND_MULTIPLE"))
        assertTrue(parsed.diagnostic.contains("stream=2"))
    }

    @Test
    fun tooManyContentUrisAreRejectedBeforePermissionChecks() {
        val uris = (1..FileImportLimits.MAX_FILES + 1)
            .map { Uri.parse("content://example.test/item/$it") }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }

        val result = parseIncomingFiles(context, intent)
        assertTrue(result is IncomingParseResult.Rejected)
        val rejected = result as IncomingParseResult.Rejected
        assertEquals("too_many", rejected.reason)
        assertTrue(rejected.diagnostic.contains("n=${FileImportLimits.MAX_FILES + 1}"))
    }

    @Test
    fun importedEncryptedFileRunsThroughExistingStateMachine() {
        val uri = providerUri(writeEncryptedFixture("self.aegix"))
        val viewModel = FileTaskViewModel(app)

        viewModel.adoptImportedFile(uri)
        val ready = awaitState(viewModel) { it.status == FileTaskStatus.READY || it.status == FileTaskStatus.FAILED }
        assertEquals("导入后应当直接停在 READY，等用户确认", FileTaskStatus.READY, ready.status)
        assertEquals(FileTaskMode.DECRYPT, ready.mode)
        assertEquals("self.aegix", ready.source?.displayName)

        viewModel.start()
        val done = awaitState(viewModel) {
            it.status == FileTaskStatus.SUCCEEDED || it.status == FileTaskStatus.FAILED
        }

        assertEquals("导入的文件应当能被现有解密流程解开", FileTaskStatus.SUCCEEDED, done.status)
        val result = requireNotNull(done.result) { "成功时必须有结果" }
        assertEquals("导入说明.txt", result.displayName)
        assertEquals(true, result.signatureValid)
        assertTrue(result.file.isFile)
        assertTrue(
            "输出必须落在 files/file-tasks/<uuid>/ 下",
            result.file.absolutePath.startsWith(taskRoot.absolutePath),
        )

        viewModel.reset()
        assertFalse("没有导出过的结果在 reset 时应当删除", result.file.exists())
    }

    @Test
    fun importedFileThatIsNotOurFormatFailsWithoutPlaintext() {
        val junk = File(fixtureDirectory, "photo.png").apply { parentFile?.mkdirs() }
        junk.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val viewModel = FileTaskViewModel(app)

        viewModel.adoptImportedFile(providerUri(junk))
        awaitState(viewModel) { it.status == FileTaskStatus.READY || it.status == FileTaskStatus.FAILED }
        viewModel.start()

        val done = awaitState(viewModel) {
            it.status == FileTaskStatus.SUCCEEDED || it.status == FileTaskStatus.FAILED
        }
        assertEquals(FileTaskStatus.FAILED, done.status)
        assertNull("失败时不能留下任何结果文件", done.result)
        assertNotNull("要有可读的错误文案", done.error)
        assertEquals(
            "误收的文件要明确说不是本工具格式",
            context.getString(FileTaskError.FILE_FORMAT.messageRes),
            done.error,
        )
    }

    @Test
    fun sendMultipleImportsIntoEncryptMode() {
        val first = providerUri(writePlainFixture("send-one.txt"))
        val second = providerUri(writePlainFixture("send-two.txt"))
        val viewModel = FileTaskViewModel(app)

        assertEquals(
            ImportAdoptionResult.ACCEPTED,
            viewModel.adoptImportedFiles(
                uris = listOf(first, second),
                mode = FileTaskMode.ENCRYPT,
            ),
        )
        val ready = awaitState(viewModel) {
            it.status == FileTaskStatus.READY || it.status == FileTaskStatus.FAILED
        }

        assertEquals(FileTaskStatus.READY, ready.status)
        assertEquals(FileTaskMode.ENCRYPT, ready.mode)
        assertEquals(listOf("send-one.txt", "send-two.txt"), ready.queue.map { it.source.displayName })
        assertEquals(
            "应提示已接收并等待加密",
            context.getString(R.string.files_received_encrypt, 2),
            ready.notice,
        )
        viewModel.reset()
    }

    @Test
    fun secondImportWhileLoadingIsRejectedAsBusy() {
        val first = providerUri(writePlainFixture("busy-one.txt"))
        val second = providerUri(writePlainFixture("busy-two.txt"))
        val viewModel = FileTaskViewModel(app)

        assertEquals(
            ImportAdoptionResult.ACCEPTED,
            viewModel.adoptImportedFiles(listOf(first), FileTaskMode.ENCRYPT),
        )
        assertEquals(
            ImportAdoptionResult.BUSY,
            viewModel.adoptImportedFiles(listOf(second), FileTaskMode.ENCRYPT),
        )

        val ready = awaitState(viewModel) {
            it.status == FileTaskStatus.READY || it.status == FileTaskStatus.FAILED
        }
        assertEquals(listOf("busy-one.txt"), ready.queue.map { it.source.displayName })
        assertEquals(
            "忙碌时的拒收提示应使用统一资源",
            context.getString(R.string.files_busy_notice),
            ready.notice,
        )
        viewModel.reset()
    }

    @Test
    fun tooManyFilesAreRejectedByViewModelBeforeLoading() {
        val viewModel = FileTaskViewModel(app)
        val uris = (1..FileImportLimits.MAX_FILES + 1)
            .map { Uri.parse("content://example.test/item/$it") }

        assertEquals(
            ImportAdoptionResult.TOO_MANY,
            viewModel.adoptImportedFiles(uris = uris, mode = FileTaskMode.ENCRYPT),
        )
        assertTrue(viewModel.state.value.notice.orEmpty().contains("200"))
    }

    /**
     * 手工验收种子：把一份可解密的 .aegix 放进 `files/file-tasks/demo/`，并留下导出租约，
     * 使应用冷启动时的 [FileTaskStorage.cleanupStale] 不会把它删掉，方便用 adb 复现
     * 「从其他应用打开」那条系统入口（测试专用，不进产品代码）。
     */
    @Test
    fun seedDemoEncryptedFileForManualOpen() {
        val demoDirectory = File(taskRoot, "demo").apply { mkdirs() }
        val target = File(demoDirectory, "payload.aegix")
        val plaintext = "这是从「用其他应用打开」进入并被解密出来的明文。\n".toByteArray()
        val identity = ensureIdentity()
        val privateKey = requireNotNull(app.keyRepository.currentPrivateKey())
        val publicKey = PrivateKeyBackup.decodePublicKey(identity.publicKeyBase64)
        val signatureInput = plaintext.inputStream()
        val plaintextInput = plaintext.inputStream()
        target.outputStream().use { output ->
            FileCryptoEngine.encrypt(
                signatureInput,
                plaintextInput,
                plaintext.size.toLong(),
                "演示说明.txt",
                privateKey,
                publicKey,
                output,
                null,
                null,
            )
        }
        signatureInput.close()
        plaintextInput.close()
        FileTaskStorage.markExported(demoDirectory)
        val uri = providerUri(target)
        println("SEED_URI=$uri")
    }

    // region helpers

    private fun providerUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun writePlainFixture(name: String): File {
        fixtureDirectory.mkdirs()
        return File(fixtureDirectory, name).apply { writeText("分享导入测试内容") }
    }

    private fun writeEncryptedFixture(name: String): File {
        val identity = ensureIdentity()
        val privateKey = requireNotNull(app.keyRepository.currentPrivateKey()) { "缺少本机私钥" }
        val publicKey = PrivateKeyBackup.decodePublicKey(identity.publicKeyBase64)
        val plaintext = "从微信文件卡片打开的解密密文内容\n".toByteArray()

        fixtureDirectory.mkdirs()
        val target = File(fixtureDirectory, name)
        val signatureInput = plaintext.inputStream()
        val plaintextInput = plaintext.inputStream()
        target.outputStream().use { output ->
            FileCryptoEngine.encrypt(
                signatureInput,
                plaintextInput,
                plaintext.size.toLong(),
                "导入说明.txt",
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
        return runBlocking { app.keyRepository.createIdentity("导入测试").getOrThrow() }
    }

    private fun awaitState(
        viewModel: FileTaskViewModel,
        predicate: (FileTaskUiState) -> Boolean,
    ): FileTaskUiState = runBlocking {
        withTimeout(60_000) { viewModel.state.first(predicate) }
    }

    // endregion
}
