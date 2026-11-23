package com.cryptoassistant.app.data.log

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import com.cryptoassistant.app.BuildConfig
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用内诊断日志。
 *
 * 设计边界（与「不写日志」的隐私承诺不冲突）：
 * - 只记录**事件名、等级、结果、数量、耗时与错误类别**；
 * - 输入框诊断额外记录**结构元数据**：前台应用包名、控件类名/资源 id/窗口号、
 *   是否可见/有选区、长度档位、是否位于 WebView（见 [FieldFacts]），不记录任何文字；
 * - 绝不写入明文、密文、密钥、口令、剪贴板内容、联系人公钥、文件名、路径或完整 URI；
 * - 文件落在应用私有目录 `files/logs/app.log`，不发往任何服务器
 *   （应用唯一的联网是默认关闭的可选更新检查）；
 * - 超过 [MAX_BYTES] 自动轮转成 `app.log.1`，最多保留两份，避免无限增长；
 * - 等级存在 SharedPreferences，Application 启动时同步读取，避免第一条日志越过关闭状态。
 */
object AppLog {

    private const val PREFS_NAME = "diagnostics"
    private const val KEY_ENABLED = "logging_enabled"
    private const val KEY_LEVEL = "log_level"
    private const val FILE_NAME = "app.log"
    private const val ARCHIVE_NAME = "app.log.1"
    private const val MAX_BYTES = 1024L * 1024L
    private const val MAX_EVENT_CHARS = 80
    private const val MAX_DETAIL_CHARS = 300
    private const val MAX_CRASH_MESSAGE_CHARS = 300
    private const val MAX_CRASH_FRAMES = 40
    private const val MAX_CAUSE_FRAMES = 20
    private const val MAX_CAUSE_DEPTH = 3

    private val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)
    private val exportTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val entryStart = Regex("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} [EWIDVC] ")
    private val lock = Any()

    @Volatile
    private var directory: File? = null

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var appContext: Context? = null

    private val defaultLevelValue: LogLevel = LogLevel.defaultFor(BuildConfig.DEBUG)

    private val _level = MutableStateFlow(defaultLevelValue)
    val level: StateFlow<LogLevel> = _level.asStateFlow()

    val defaultLevel: LogLevel get() = defaultLevelValue

    /** 兼容旧调用；新代码应读取 [level]。 */
    val enabled: Boolean get() = _level.value.isEnabled

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        directory = File(app.filesDir, "logs")
        val store = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = store
        val resolved = resolveLevel(store)
        _level.value = resolved
        // 同步新旧两个键：新代码读 log_level，旧安装降级时仍能读 logging_enabled。
        store.edit()
            .putString(KEY_LEVEL, resolved.wireName)
            .putBoolean(KEY_ENABLED, resolved.isEnabled)
            .apply()
    }

    private fun resolveLevel(store: SharedPreferences): LogLevel {
        LogLevel.fromWire(store.getString(KEY_LEVEL, null))?.let { return it }
        if (store.contains(KEY_ENABLED)) {
            return if (store.getBoolean(KEY_ENABLED, true)) {
                defaultLevelValue
            } else {
                LogLevel.OFF
            }
        }
        return defaultLevelValue
    }

    /** 立即生效并持久化；关闭后连冷启动的 app.start 也不会写入。 */
    fun setLevel(newLevel: LogLevel) {
        val previous = _level.value
        _level.value = newLevel
        prefs?.edit()
            ?.putString(KEY_LEVEL, newLevel.wireName)
            ?.putBoolean(KEY_ENABLED, newLevel.isEnabled)
            ?.apply()
        if (previous != newLevel && newLevel != LogLevel.OFF) {
            // 新等级低于 INFO 时这条记录会被阈值过滤，UI 状态仍然会显示当前等级。
            write(
                LogLevel.INFO,
                "log.level",
                "from=${previous.wireName} to=${newLevel.wireName}",
            )
        }
    }

    /** 兼容旧的布尔开关；true 回到当前构建默认，false 表示 OFF。 */
    fun setEnabled(value: Boolean) {
        setLevel(if (value) defaultLevelValue else LogLevel.OFF)
    }

    fun isLoggable(eventLevel: LogLevel): Boolean = _level.value.allows(eventLevel)

    /** 版本/构建/SDK/设备/当前等级的上下文串；只含已批准的非敏感元数据。 */
    fun contextFields(): String = runtimeContext()

    fun info(event: String, detail: String? = null) = write(LogLevel.INFO, event, detail)

    fun debug(event: String, detail: String? = null) = write(LogLevel.DEBUG, event, detail)

    fun verbose(event: String, detail: String? = null) = write(LogLevel.VERBOSE, event, detail)

    fun warn(event: String, detail: String? = null) = write(LogLevel.WARN, event, detail)

    /**
     * 意外错误。异常只取类名，**不写 `Throwable.message`**，避免路径/文件名进入日志。
     * 需要更多信息时由调用方通过 [detail] 传安全的 `kind=...` 字段。
     */
    fun error(event: String, throwable: Throwable? = null, detail: String? = null) {
        if (!isLoggable(LogLevel.ERROR)) return
        val message = listOfNotNull(
            detail?.takeIf { it.isNotBlank() },
            throwable?.let { "class=${it.javaClass.simpleName}" },
        ).joinToString(" | ")
        write(LogLevel.ERROR, event, message.ifBlank { null })
    }

    /**
     * 记录未捕获异常：线程、异常类、脱敏消息与堆栈，供崩溃后导出排查。
     *
     * <p>堆栈只包含类名、方法名、文件名与行号；异常消息先经 [LogSanitizer]。
     * 等级为 OFF 时一条也不写。</p>
     */
    fun crash(thread: Thread, throwable: Throwable) {
        if (!isLoggable(LogLevel.ERROR)) return
        val dir = directory ?: return
        val block = runCatching { buildCrashBlock(thread, throwable) }.getOrNull() ?: return
        synchronized(lock) { appendBlock(dir, block) }
    }

    private fun buildCrashBlock(thread: Thread, throwable: Throwable): String = buildString {
        append(LocalDateTime.now().format(formatter))
        append(" E app.crash | thread=")
        append(thread.name)
        append('\n')
        append("    context=")
        append(runtimeContext())
        append('\n')
        append("    ")
        append(throwable.javaClass.name)
        appendCrashMessage(throwable.message)
        append('\n')
        throwable.stackTrace.take(MAX_CRASH_FRAMES).forEach { frame ->
            append("    at ").append(frame).append('\n')
        }
        var cause = throwable.cause
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            append("    Caused by: ")
            append(cause.javaClass.name)
            appendCrashMessage(cause.message)
            append('\n')
            cause.stackTrace.take(MAX_CAUSE_FRAMES).forEach { frame ->
                append("        at ").append(frame).append('\n')
            }
            cause = cause.cause
            depth++
        }
    }

    private fun StringBuilder.appendCrashMessage(message: String?) {
        val clean = LogSanitizer.sanitize(message, MAX_CRASH_MESSAGE_CHARS)
        if (!clean.isNullOrEmpty()) append(": ").append(clean)
    }

    /** 当前日志条数与占用空间（含轮转文件）。 */
    data class Stats(val lines: Int, val bytes: Long)

    fun stats(): Stats {
        synchronized(lock) {
            val files = existingFiles()
            val lines = files.sumOf { file ->
                runCatching { file.useLines { it.count() } }.getOrDefault(0)
            }
            return Stats(lines = lines, bytes = files.sumOf { it.length() })
        }
    }

    /**
     * 最近的日志条目，新的在前；每个条目是一组行（崩溃堆栈会占多行）。
     * 条目内保持原始顺序，避免预览把堆栈倒过来。
     */
    fun recentEntries(limit: Int = 30): List<List<String>> {
        if (limit <= 0) return emptyList()
        synchronized(lock) {
            val entries = ArrayDeque<List<String>>()
            var current = mutableListOf<String>()
            fun flushCurrent() {
                if (current.isEmpty()) return
                if (entries.size == limit) entries.removeFirst()
                entries.addLast(current)
                current = mutableListOf()
            }
            existingFiles().forEach { file ->
                runCatching {
                    file.useLines { lines ->
                        lines.forEach { line ->
                            if (line.isBlank()) return@forEach
                            if (entryStart.containsMatchIn(line)) flushCurrent()
                            current.add(line)
                        }
                    }
                }
            }
            flushCurrent()
            return entries.toList().asReversed()
        }
    }

    /** 导出用全文：header + 先旧（轮转）后新，按时间顺序排列。 */
    fun exportText(): String {
        val logs = readAll()
        val stats = stats()
        val header = buildString {
            appendLine("# CryptoAssistant Android diagnostic log")
            appendLine("# exportedAt=${LocalDateTime.now().format(exportTimeFormatter)}")
            appendLine("# ${runtimeContext()}${exportContext()}")
            appendLine("# lines=${stats.lines} bytes=${stats.bytes}")
            appendLine("# fields=pkg,cls,vid,win,edit,pwd,vis,sel,selLen,len,web,cmp,strategy,state")
        }
        return header + if (logs.isBlank()) "（暂无日志记录）\n" else logs
    }

    /** 读取原始正文（不含导出头）；兼容旧日志。 */
    fun readAll(): String {
        synchronized(lock) {
            return existingFiles().joinToString(separator = "") { file ->
                runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            existingFiles().forEach { runCatching { it.delete() } }
        }
    }

    private fun existingFiles(): List<File> {
        val dir = directory ?: return emptyList()
        return listOf(File(dir, ARCHIVE_NAME), File(dir, FILE_NAME)).filter { it.isFile }
    }

    private fun write(level: LogLevel, event: String, detail: String?) {
        if (!_level.value.allows(level)) return
        val dir = directory ?: return
        val cleanEvent = event.replace('\n', ' ').take(MAX_EVENT_CHARS)
        // 结构化 detail 由调用方保证安全；这里只压平换行并限制长度。
        // 不套用 URI/路径脱敏，避免把 MIME 通配符（如 */*、image/*）误伤。
        val cleanDetail = detail
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.trim()
            ?.take(MAX_DETAIL_CHARS)
            ?.takeIf { it.isNotEmpty() }
        val line = buildString {
            append(LocalDateTime.now().format(formatter))
            append(' ')
            append(level.shortName)
            append(' ')
            append(cleanEvent)
            if (cleanDetail != null) {
                append(" | ")
                append(cleanDetail)
            }
        }
        synchronized(lock) { appendBlock(dir, line + "\n") }
    }

    private fun appendBlock(dir: File, block: String) {
        runCatching {
            dir.mkdirs()
            val file = File(dir, FILE_NAME)
            if (file.length() >= MAX_BYTES) rotate(file)
            file.appendText(block, Charsets.UTF_8)
        }
    }

    private fun rotate(file: File) {
        val archive = File(file.parentFile ?: return, ARCHIVE_NAME)
        archive.delete()
        file.renameTo(archive)
    }

    /**
     * 只在导出头里补充的会话上下文：构建 UUID/编译时间、默认输入法、无障碍启用状态。
     *
     * <p>这些信息能区分“同一版本号的不同构建”和“输入法/服务状态”，但不含用户内容；
     * 放在导出头而不是每条日志，避免每条记录重复长串。读取失败时静默省略。</p>
     */
    private fun exportContext(): String {
        val app = appContext ?: return ""
        val ime = runCatching {
            Settings.Secure.getString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull()
            ?.trim()
            ?.replace(Regex("\\s+"), "_")
            ?.take(80)
            ?.takeIf { it.isNotEmpty() }
        val a11yEnabled = runCatching {
            val enabled = Settings.Secure.getString(
                app.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val prefix = app.packageName + "/"
            enabled.split(':').any { it.startsWith(prefix) }
        }.getOrNull()
        return buildString {
            append(" uuid=").append(BuildConfig.BUILD_UUID)
            append(" built=").append(BuildConfig.BUILD_TIME.replace(' ', 'T'))
            ime?.let { append(" ime=").append(it) }
            a11yEnabled?.let { append(" a11yEnabled=").append(it) }
        }
    }

    private fun runtimeContext(): String {
        val device = LogSanitizer.sanitize(
            "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            80,
        ).orEmpty().ifBlank { "unknown" }
        return "version=${BuildConfig.VERSION_NAME} " +
            "code=${BuildConfig.VERSION_CODE} " +
            "build=${BuildConfig.BUILD_TYPE} " +
            "sdk=${Build.VERSION.SDK_INT} " +
            "android=${Build.VERSION.RELEASE} " +
            "device=$device " +
            "level=${_level.value.wireName}"
    }
}
