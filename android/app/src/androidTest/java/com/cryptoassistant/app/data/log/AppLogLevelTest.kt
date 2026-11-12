package com.cryptoassistant.app.data.log

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptoassistant.app.BuildConfig
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AppLog 的等级、迁移、旧格式兼容、导出头、崩溃脱敏与轮转。
 *
 * 测试会改写应用的 `diagnostics` 偏好和 `files/logs`，因此前后都恢复默认等级并清空日志。
 */
@RunWith(AndroidJUnit4::class)
class AppLogLevelTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs: android.content.SharedPreferences
        get() = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    private val logDirectory: File
        get() = File(context.filesDir, "logs")

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        AppLog.init(context)
        AppLog.clear()
        AppLog.setLevel(LogLevel.VERBOSE)
    }

    @After
    fun tearDown() {
        AppLog.clear()
        prefs.edit().clear().commit()
        AppLog.init(context)
    }

    @Test
    fun levelFiltersNewWrites() {
        AppLog.setLevel(LogLevel.INFO)
        AppLog.clear()

        AppLog.debug("test.debug")
        AppLog.verbose("test.verbose")
        AppLog.info("test.info", "kind=ok")
        AppLog.warn("test.warn", "kind=expected")
        AppLog.error("test.error", IllegalStateException("secret message"), "kind=unknown")

        val text = AppLog.readAll()
        assertFalse(text.contains("test.debug"))
        assertFalse(text.contains("test.verbose"))
        assertTrue(text.contains("test.info"))
        assertTrue(text.contains("test.warn"))
        assertTrue(text.contains("test.error"))
        assertFalse("不能写 Throwable.message", text.contains("secret message"))
    }

    @Test
    fun offWritesNothingIncludingCrash() {
        AppLog.setLevel(LogLevel.OFF)
        AppLog.clear()

        AppLog.info("test.info")
        AppLog.crash(Thread.currentThread(), RuntimeException("secret message"))

        assertTrue(AppLog.readAll().isBlank())
    }

    @Test
    fun levelPersistsAcrossInit() {
        AppLog.setLevel(LogLevel.DEBUG)

        AppLog.init(context)

        assertEquals(LogLevel.DEBUG, AppLog.level.value)
        assertEquals("debug", prefs.getString("log_level", null))
    }

    @Test
    fun oldBooleanPreferenceMigrates() {
        prefs.edit().clear().putBoolean("logging_enabled", false).commit()
        AppLog.init(context)
        assertEquals(LogLevel.OFF, AppLog.level.value)
        assertEquals("off", prefs.getString("log_level", null))

        prefs.edit().clear().putBoolean("logging_enabled", true).commit()
        AppLog.init(context)
        assertEquals(LogLevel.defaultFor(BuildConfig.DEBUG), AppLog.level.value)
        assertEquals(
            LogLevel.defaultFor(BuildConfig.DEBUG).wireName,
            prefs.getString("log_level", null),
        )
    }

    @Test
    fun oldAndNewLogLinesAreGrouped() {
        AppLog.clear()
        logDirectory.mkdirs()
        File(logDirectory, "app.log").writeText(
            "10-24 12:00:00.000 I app.start | version=old\n" +
                "10-24 12:00:01.000 D test.debug | kind=ok\n" +
                "10-24 12:00:02.000 V test.verbose | kind=ok\n",
        )

        val entries = AppLog.recentEntries(10)

        assertEquals(3, entries.size)
        assertTrue(entries.last().first().contains("app.start"))
        assertTrue(entries.first().first().contains("test.verbose"))
    }

    @Test
    fun exportHeaderIncludesContext() {
        AppLog.setLevel(LogLevel.INFO)
        AppLog.clear()
        AppLog.info("test.export", "kind=ok")

        val text = AppLog.exportText()

        assertTrue(text.contains("# CryptoAssistant Android diagnostic log"))
        assertTrue(text.contains("version=${BuildConfig.VERSION_NAME}"))
        assertTrue(text.contains("level=info"))
        assertTrue(text.contains("test.export"))
        assertFalse("导出正文不能包含路径/文件名", text.contains("secret.docx"))
    }

    @Test
    fun structuredDetailsKeepMimeWildcards() {
        AppLog.setLevel(LogLevel.DEBUG)
        AppLog.clear()
        AppLog.debug("test.import", "mime=*/* stream=2")

        val text = AppLog.readAll()
        assertTrue(text.contains("mime=*/* stream=2"))
    }

    @Test
    fun crashBlockIsSanitizedAndContainsContext() {
        AppLog.setLevel(LogLevel.ERROR)
        AppLog.clear()

        AppLog.crash(
            Thread.currentThread(),
            IllegalStateException(
                "failed /sdcard/secret.docx content://provider/secret " + "A".repeat(80),
            ),
        )

        val text = AppLog.readAll()
        assertTrue(text.contains("app.crash"))
        assertTrue(text.contains("context=version="))
        assertFalse(text.contains("secret.docx"))
        assertFalse(text.contains("content://provider/secret"))
        assertTrue(text.contains("<path>") || text.contains("<uri>"))
    }

    @Test
    fun fullLogRotatesIntoArchive() {
        AppLog.setLevel(LogLevel.VERBOSE)
        AppLog.clear()
        logDirectory.mkdirs()
        File(logDirectory, "app.log").writeText("x".repeat(1024 * 1024))

        AppLog.info("test.rotate", "kind=ok")

        assertTrue(File(logDirectory, "app.log.1").isFile)
        assertTrue(File(logDirectory, "app.log").isFile)
    }
}
