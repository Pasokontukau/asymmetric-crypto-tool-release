package com.cryptoassistant.app.feature.files

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件名隐私的静态守卫。
 *
 * <p>分享/通知/任务日志都依赖 Android 框架对象，纯 JVM 很难真实启动；这里锁定
 * 调用点的形状：日志不能拼文件名/路径，分享意图只能用 [shareLabel]，通知只能用
 * 固定且无格式占位符的资源文案。真实加密结果的命名由 [FileTaskStorageNamingTest]、
 * [ShareLabelTest] 和共享内核测试覆盖。</p>
 */
class FileNamePrivacyTest {

    private val forbiddenLogExpression = Regex(
        "\\.(?:displayName|sourceName|originalFileName|absolutePath|path)\\b" +
            "|\\b(?:file|inputFile|outputFile|selectedFile|folder|archive|part|zipFile|tempFile|source)" +
            "\\s*\\.\\s*name\\b" +
            "|\\.getName\\s*\\(|\\.getAbsolutePath\\s*\\(",
    )

    @Test
    fun `task logs never interpolate file names or paths`() {
        val files = sourceFile("src/main/java/com/cryptoassistant/app/feature/files")
            .listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty()
        assertTrue("找不到文件任务源码", files.isNotEmpty())

        val failures = files.flatMap { file ->
            logCalls(file.readText())
                .filter { forbiddenLogExpression.containsMatchIn(it) }
                .map { "${file.name}: ${it.replace('\n', ' ')}" }
        }

        assertTrue("任务日志不能拼文件名/路径:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun `share intents are wired to the sanitized label`() {
        val source = sourceFile("src/main/java/com/cryptoassistant/app/feature/files/FileShare.kt")
            .readText()

        assertTrue(source.contains("putExtra(Intent.EXTRA_TITLE, label.title)"))
        assertTrue(source.contains("ClipData.newRawUri(label.clipLabel"))
        assertFalse(source.contains("EXTRA_TITLE, result.displayName"))
        assertFalse(source.contains("newRawUri(result.displayName"))
    }

    @Test
    fun `overlay notification uses fixed text without file placeholders`() {
        val service = sourceFile(
            "src/main/java/com/cryptoassistant/app/overlay/OverlayService.kt",
        ).readText()
        assertTrue(service.contains("setContentTitle(getString(R.string.overlay_notification_title))"))
        assertTrue(service.contains("setContentText(getString(R.string.overlay_notification_text))"))

        for (locale in listOf("values", "values-en")) {
            for (name in listOf("overlay_notification_title", "overlay_notification_text")) {
                val text = stringResource(locale, name)
                assertNotNull("缺少 $locale/$name", text)
                assertFalse("$locale/$name 不能带格式占位符", text!!.contains("%"))
                assertFalse("$locale/$name 不能包含加密文件名", text.contains(".aegix"))
                assertFalse("$locale/$name 不能包含脱敏名前缀", text.contains("secure_file"))
            }
        }
    }

    private fun stringResource(locale: String, name: String): String? {
        val xml = sourceFile("src/main/res/$locale/strings.xml").readText()
        return Regex(
            "<string name=\"$name\"[^>]*>(.*?)</string>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(xml)?.groupValues?.get(1)
    }

    private fun sourceFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, relative),
            File(cwd, "app/$relative"),
            File(cwd.parentFile, "app/$relative"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Cannot locate $relative from ${cwd.absolutePath}")
    }

    /** 从每个 {@code AppLog.} 起点截到配对右括号；字符串字面量里的括号不计数。 */
    private fun logCalls(text: String): List<String> {
        val calls = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = text.indexOf("AppLog.", from)
            if (start < 0) break
            val open = text.indexOf('(', start)
            if (open < 0) break
            var depth = 0
            var quote: Char? = null
            var escaped = false
            var end = -1
            for (index in open until text.length) {
                val c = text[index]
                if (quote != null) {
                    if (escaped) {
                        escaped = false
                    } else if (c == '\\') {
                        escaped = true
                    } else if (c == quote) {
                        quote = null
                    }
                } else if (c == '"' || c == '\'') {
                    quote = c
                } else if (c == '(') {
                    depth++
                } else if (c == ')') {
                    depth--
                    if (depth == 0) {
                        end = index
                        break
                    }
                }
            }
            if (end < 0) break
            calls += text.substring(start, end + 1)
            from = end + 1
        }
        return calls
    }
}
