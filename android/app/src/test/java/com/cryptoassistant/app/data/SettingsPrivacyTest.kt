package com.cryptoassistant.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 设置面的落地审计。
 *
 * Android 没有桌面端那种“历史记录”模块；设置只走 DataStore 的固定偏好键。
 * 这里锁定三件事，避免以后把文件名、路径或历史内容塞进设置：
 * 1. DataStore 名称固定为 `settings`；
 * 2. 偏好键必须与清单完全一致，不能悄悄新增“file/path/history”类键；
 * 3. 数据层不存在历史仓库实现。
 */
class SettingsPrivacyTest {

    private val source: String by lazy {
        val candidates = listOf(
            File("src/main/java/com/cryptoassistant/app/data/SettingsRepository.kt"),
            File("app/src/main/java/com/cryptoassistant/app/data/SettingsRepository.kt"),
        )
        candidates.firstOrNull(File::isFile)?.readText()
            ?: error("找不到 SettingsRepository.kt（cwd=${File(".").absolutePath}）")
    }

    private val keys: Set<String> by lazy {
        Regex("""(?:string|boolean|float|int|long)PreferencesKey\("([^"]+)"\)""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun `data store name stays settings`() {
        assertTrue(
            source.contains("""preferencesDataStore(name = "settings")"""),
        )
    }

    @Test
    fun `settings keys are the fixed non-sensitive allowlist`() {
        val expected = setOf(
            "theme_mode",
            "dynamic_color",
            "accent_palette",
            "amoled_dark",
            "ball_enabled",
            "ball_x_fraction",
            "ball_y_fraction",
            "ball_size",
            "ball_opacity",
            "last_contact_id",
            "file_quick_pipeline",
            "archive_volume_size",
            "package_multiple_files",
            "security_lock",
            "app_lock",
            "lock_timeout_minutes",
            "ball_work_mode",
            "a11y_consent_at",
            "a11y_consent_version",
            "last_field_io",
        )
        assertEquals("新增设置键前先确认它不含文件名或路径", expected, keys)
    }

    @Test
    fun `settings do not carry file names paths or history content`() {
        listOf(
            "fileInputPath",
            "fileOutputPath",
            "sourceName",
            "originalName",
            "history",
            "clipboardText",
            "inputText",
            "outputText",
        ).forEach { token ->
            assertFalse("设置键/字段不能包含 $token", source.contains(token))
        }
    }

    @Test
    fun `android has no plaintext history repository`() {
        val dataDir = listOf(
            File("src/main/java/com/cryptoassistant/app/data"),
            File("app/src/main/java/com/cryptoassistant/app/data"),
        ).firstOrNull(File::isDirectory)
            ?: error("找不到 Android data 目录")

        val historyFiles = dataDir.walkTopDown()
            .filter { it.isFile }
            .filter { it.name.contains("History", ignoreCase = true) }
            .toList()

        assertTrue("Android 不应有历史仓库实现: $historyFiles", historyFiles.isEmpty())
    }
}
