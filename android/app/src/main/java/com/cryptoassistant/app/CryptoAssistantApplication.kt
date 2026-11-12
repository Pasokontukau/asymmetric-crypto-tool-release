package com.cryptoassistant.app

import android.app.Application
import com.cryptoassistant.app.data.SettingsRepository
import com.cryptoassistant.app.data.keys.KeyRepository
import com.cryptoassistant.app.data.log.AppLog
import com.cryptoassistant.app.feature.files.ArchiveWorkspace
import com.cryptoassistant.app.feature.files.FileTaskStorage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CryptoAssistantApplication : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var keyRepository: KeyRepository
        private set

    /** 应用级协程作用域：不依赖任何 Activity/Service 存活（磁贴、启动迁移等短任务用它）。 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        installCrashLogger()
        settingsRepository = SettingsRepository(this)
        keyRepository = KeyRepository(this)
        // 老装机升级后把「悬浮球工作模式」一次性落盘，避免模式随系统无障碍状态漂移。
        appScope.launch { settingsRepository.ensureBallWorkMode() }
        // 上次进程被杀时可能留下未完成的文件任务；启动时整体清理。
        val cleaned = FileTaskStorage.cleanupStale(File(filesDir, FileTaskStorage.DIRECTORY_NAME))
        val archiveCleaned = ArchiveWorkspace.cleanup(filesDir)
        AppLog.debug("app.cleanup", "dirs=$cleaned archive=$archiveCleaned")
        AppLog.info("app.start", AppLog.contextFields())
    }

    /** 把未捕获异常的堆栈写进日志，再交还给系统原本的崩溃处理（仍然会弹系统崩溃页）。 */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { AppLog.crash(thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
