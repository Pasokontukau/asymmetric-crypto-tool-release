package com.cryptoassistant.app.feature.files

import android.content.Context
import com.cryptoassistant.app.data.log.AppLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 文件任务与设置页之间的进程内协调器。
 *
 * <p>文件页可以因为导航的 `saveState`/`restoreState` 继续存活，设置页不能通过自己的
 * ViewModel 判断“有没有人在写临时文件”。这里只保存不落盘的进程内状态：</p>
 * - [busy]：正在执行文件队列或保存结果；设置页清理必须拒绝；
 * - [cacheCleared]：设置页完成清理后通知所有存活的文件页 ViewModel 重置；
 * - [clearing]：清理本身正在进行，防止重复点击。
 *
 * <p>清理与任务启动共用 [clearMutex]（计划 D15）：[beginOperation] 的登记与 [clearCache]
 * 的“检查 busy → 删除”严格串行，不存在“清理开始后任务刚好登记成功”的窗口。清理持锁期间
 * 新的任务启动会挂起到删除结束后再登记，不报错、不丢任务。</p>
 *
 * <p>清理使用 [NonCancellable] 完成，避免用户旋转/离开设置页时只删一半；即使 UI
 * 协程被取消，事件也会在删除结束后发出，文件页不会继续指向已删结果。</p>
 */
object FileTaskRuntime {

    private val operationLock = Any()
    private var activeOperations = 0

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _clearing = MutableStateFlow(false)
    val clearing: StateFlow<Boolean> = _clearing.asStateFlow()

    private val _cacheCleared = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 只在内存里广播“临时结果已被清理”；新创建的 ViewModel 本来就是空状态。 */
    val cacheCleared: SharedFlow<Unit> = _cacheCleared.asSharedFlow()

    private val clearMutex = Mutex()

    /**
     * 登记一次进行中的文件任务（队列执行或保存结果），与 [endOperation] 成对。
     *
     * <p>与 [clearCache] 共享 [clearMutex]，所以登记只可能发生在两次清理之间：
     * 清理持锁时调用会挂起到删除结束后再登记；登记成功后调用清理则会被 busy 检查
     * 拒绝。锁只覆盖登记那一瞬间，不覆盖任务本身的执行时长。</p>
     *
     * <p>必须在协程里调用；配对的 [endOperation] 只能在本函数正常返回之后调用。
     * 协程在等锁时被取消，本函数抛出取消异常且没有登记过，此时绝不能再补一次 end，
     * 否则会把其他任务的计数还掉。</p>
     */
    suspend fun beginOperation() = clearMutex.withLock {
        synchronized(operationLock) {
            activeOperations++
            _busy.value = true
        }
    }

    fun endOperation() {
        synchronized(operationLock) {
            activeOperations = (activeOperations - 1).coerceAtLeast(0)
            _busy.value = activeOperations > 0
        }
    }

    suspend fun inspectCache(context: Context): FileTaskCacheUsage = withContext(Dispatchers.IO) {
        FileTaskStorage.inspect(cacheRoot(context))
    }

    /**
     * 删除 `files/file-tasks` 的全部直接子项（含活跃租约），并通知文件页重置。
     * 只删这一个目录；密钥、日志、联系人和用户保存到别处的文件不在范围内。
     */
    suspend fun clearCache(context: Context): FileTaskCacheClearResult =
        clearCache(cacheRoot(context))

    /**
     * [clearCache] 的可测试入口：[root] 直接给目录，[delete] 沿用 [FileTaskStorage.clearAll]
     * 的既有注入点，在测试里控制删除时序或注入失败。与 Context 版本走同一条
     * [clearMutex] 路径，不是另一套行为。
     */
    internal suspend fun clearCache(
        root: File,
        delete: (File) -> Boolean = { FileTaskStorage.deleteRecursively(it) },
    ): FileTaskCacheClearResult = clearMutex.withLock {
        // 与 beginOperation 同一把锁：检查通过到删除结束之间，不可能再登记新的任务。
        check(!_busy.value) { "文件任务正在处理或保存，完成或取消后再清理" }
        _clearing.value = true
        try {
            val result = withContext(NonCancellable + Dispatchers.IO) {
                val cleared = FileTaskStorage.clearAll(root, delete = delete)
                _cacheCleared.tryEmit(Unit)
                cleared
            }
            AppLog.info(
                "app.cache",
                "op=clear dirs=${result.deletedDirectories} files=${result.deletedFiles} " +
                    "bytes=${result.freedBytes} failed=${result.failedEntries}",
            )
            result
        } finally {
            _clearing.value = false
        }
    }

    private fun cacheRoot(context: Context): File =
        File(context.applicationContext.filesDir, FileTaskStorage.DIRECTORY_NAME)
}
