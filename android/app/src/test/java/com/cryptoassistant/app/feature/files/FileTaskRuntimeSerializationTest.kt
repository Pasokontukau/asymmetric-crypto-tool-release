package com.cryptoassistant.app.feature.files

import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 计划 D15：设置页“清除缓存”与文件任务启动必须严格串行。
 *
 * <p>要守住的两条不变量：</p>
 * 1. 清理的“检查 busy → 删除”进行期间，[FileTaskRuntime.beginOperation] 不能登记成功，
 *    任务启动只能挂起到删除结束（否则清理会删掉刚启动任务正在写的目录）；
 * 2. 已登记任务时清理必须整体拒绝，一个文件都不删。
 *
 * <p>[FileTaskRuntime] 是进程内单例，busy 计数与锁都是全局状态；每个用例结束后由
 * [tearDown] 放开闸门、归还计数，避免用例之间互相污染。</p>
 */
class FileTaskRuntimeSerializationTest {

    @get:Rule
    val temporary = TemporaryFolder()

    /** 删除停在闸门上：[clearEntered] 说明清理已持锁并已通过 busy 检查。 */
    private val clearEntered = CountDownLatch(1)
    private val releaseClear = CountDownLatch(1)

    /** 三段时序的统一记录，只有真正越过闸门的步骤才入列。 */
    private val order = Collections.synchronizedList(mutableListOf<String>())

    private val clearResult = AtomicReference<FileTaskCacheClearResult>()
    private val clearFailure = AtomicReference<Throwable>()
    private val beginFailure = AtomicReference<Throwable>()

    private var clearThread: Thread? = null
    private var beginThread: Thread? = null

    private val gatedDelete: (File) -> Boolean = { child ->
        order.add("clear-start")
        clearEntered.countDown()
        check(releaseClear.await(10, TimeUnit.SECONDS)) { "清理闸门未在超时前放行" }
        val deleted = FileTaskStorage.deleteRecursively(child)
        order.add("clear-end")
        deleted
    }

    @After
    fun tearDown() {
        releaseClear.countDown()
        clearThread?.join(TimeUnit.SECONDS.toMillis(10))
        beginThread?.join(TimeUnit.SECONDS.toMillis(10))
        // busy 计数是全局的：任何断言失败都不能把它留在非 0，否则后续用例全被误拒。
        while (FileTaskRuntime.busy.value) {
            FileTaskRuntime.endOperation()
        }
    }

    @Test
    fun `task start waits for an in-flight clear instead of registering mid-delete`() {
        val root = newRoot()
        startClear(root, gatedDelete)
        assertTrue("清理应进入删除阶段", clearEntered.await(10, TimeUnit.SECONDS))

        val beginDone = CountDownLatch(1)
        beginThread = Thread {
            runBlocking {
                runCatching { FileTaskRuntime.beginOperation() }
                    .onSuccess {
                        order.add("begin")
                        beginDone.countDown()
                    }
                    .onFailure {
                        beginFailure.set(it)
                        beginDone.countDown()
                    }
            }
        }.also { it.start() }

        // 清理还持着锁：任务启动必须一直等，不能登记成功、不能把 busy 置真。
        assertFalse(
            "清理持锁期间 beginOperation 不该返回",
            beginDone.await(300, TimeUnit.MILLISECONDS),
        )
        assertFalse(FileTaskRuntime.busy.value)

        releaseClear.countDown()
        assertTrue("清理结束后任务启动应完成", beginDone.await(10, TimeUnit.SECONDS))
        clearThread?.join(TimeUnit.SECONDS.toMillis(10))

        assertNull("任务启动不应失败", beginFailure.get())
        assertNull("清理不应失败", clearFailure.get())
        assertNotNull("清理应返回统计", clearResult.get())
        assertEquals(listOf("clear-start", "clear-end", "begin"), order.toList())
        assertEquals(1, clearResult.get().deletedDirectories)
        assertEquals(0, clearResult.get().failedEntries)
        assertFalse(File(root, "task").exists())
        assertTrue(FileTaskRuntime.busy.value)

        FileTaskRuntime.endOperation()
        assertFalse(FileTaskRuntime.busy.value)
    }

    @Test
    fun `clear is refused while an operation is registered and deletes nothing`() {
        val root = newRoot()
        runBlocking { FileTaskRuntime.beginOperation() }
        assertTrue(FileTaskRuntime.busy.value)

        val failure = runBlocking {
            runCatching { FileTaskRuntime.clearCache(root) }.exceptionOrNull()
        }

        assertTrue("清理必须被拒绝，实际为：$failure", failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("文件任务正在处理"))
        assertFalse(FileTaskRuntime.clearing.value)
        assertTrue(File(root, "task").isDirectory)
        assertTrue(File(File(root, "task"), "payload.bin").isFile)

        FileTaskRuntime.endOperation()
        assertFalse(FileTaskRuntime.busy.value)

        // 计数归还后同一条清理路径必须可用，说明拒绝只来自 busy，不是锁没放开。
        val result = runBlocking { FileTaskRuntime.clearCache(root) }
        assertEquals(0, result.failedEntries)
        assertEquals(1, result.deletedDirectories)
        assertFalse(File(root, "task").exists())
    }

    @Test
    fun `cancelled start while waiting for a clear leaves no dangling busy count`() {
        val root = newRoot()
        startClear(root, gatedDelete)
        assertTrue("清理应进入删除阶段", clearEntered.await(10, TimeUnit.SECONDS))

        runBlocking {
            val beginAttempted = CountDownLatch(1)
            val job = launch(Dispatchers.Default) {
                beginAttempted.countDown()
                FileTaskRuntime.beginOperation()
            }
            assertTrue(beginAttempted.await(5, TimeUnit.SECONDS))
            // 等锁期间被取消：没有登记过，就不能在别处补一次 endOperation。
            job.cancelAndJoin()
        }
        assertFalse(FileTaskRuntime.busy.value)

        releaseClear.countDown()
        clearThread?.join(TimeUnit.SECONDS.toMillis(10))
        assertNull("清理不应失败", clearFailure.get())
        assertFalse("取消的启动不能留下脏计数", FileTaskRuntime.busy.value)

        val result = runBlocking { FileTaskRuntime.clearCache(root) }
        assertEquals(0, result.failedEntries)
        assertFalse(File(root, "task").exists())
    }

    private fun newRoot(): File =
        temporary.newFolder(FileTaskStorage.DIRECTORY_NAME).also { root ->
            File(root, "task").apply {
                mkdirs()
                File(this, "payload.bin").writeBytes(ByteArray(8))
            }
        }

    private fun startClear(root: File, delete: (File) -> Boolean) {
        clearThread = Thread {
            runBlocking {
                runCatching { FileTaskRuntime.clearCache(root, delete = delete) }
                    .fold(clearResult::set, clearFailure::set)
            }
        }.also { it.start() }
    }
}
