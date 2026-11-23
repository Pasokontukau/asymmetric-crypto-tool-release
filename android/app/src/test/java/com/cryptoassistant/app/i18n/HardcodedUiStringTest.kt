package com.cryptoassistant.app.i18n

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI 硬编码中文的“棘轮”检查。
 *
 * <p>G0 基线 145 条；G1 设置壳降到 128；G2 主流程降到 44；G3 悬浮球/胶囊/引导/悬浮球设置
 * 降到 6；G4 工具书外壳迁完后为 0。任何新增 UI 硬编码都会让数字变大并失败。</p>
 *
 * <p>扫描 UI 常见形态：`Text("中文")`、`text = "中文"`、`contentDescription = "中文"`、
 * `Toast.makeText(..., "中文")`，以及 `SectionTitle("中文")`/`InfoBullet("中文")`/`StatusPill("中文")`
 * 这类包装组件。工具书正文是内容数据，不在此扫描；注释与日志不在范围。</p>
 */
class HardcodedUiStringTest {

    private companion object {
        /** 2026-11-03 G4 基线：UI 文案已全部资源化。 */
        const val EXPECTED_MAX = 0
    }

    private val patterns = listOf(
        Regex("""Text\(\s*"[^"]*\p{IsHan}"""),
        Regex("""text\s*=\s*"[^"]*\p{IsHan}"""),
        Regex("""contentDescription\s*=\s*"[^"]*\p{IsHan}"""),
        Regex("""Toast\.makeText\([^\n]*"[^"]*\p{IsHan}"""),
        Regex("""(SectionTitle|InfoBullet|StatusPill)\(\s*"[^"]*\p{IsHan}"""),
    )

    private fun uiSourceRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, "src/main/java"),
            File(cwd, "app/src/main/java"),
            File(cwd.parentFile, "app/src/main/java"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Cannot locate src/main/java from ${cwd.absolutePath}")
    }

    @Test
    fun `hardcoded chinese ui strings do not exceed the migration baseline`() {
        val root = uiSourceRoot()
        val findings = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "GuideContent.kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, raw ->
                    val trimmed = raw.trim()
                    if (trimmed.startsWith("//") ||
                        trimmed.startsWith("*") ||
                        trimmed.startsWith("/*")
                    ) {
                        return@forEachIndexed
                    }
                    // 去掉行尾注释，避免把示例/说明算进去。
                    val code = raw.substringBefore("//")
                    if (patterns.any { it.containsMatchIn(code) }) {
                        findings += "${file.relativeTo(root)}:${index + 1}: ${raw.trim()}"
                    }
                }
            }

        if (findings.isNotEmpty()) {
            println("Hardcoded UI strings (${findings.size}):")
            findings.take(40).forEach { println(it) }
            if (findings.size > 40) println("... ${findings.size - 40} more")
        }
        assertTrue(
            "Hardcoded UI Chinese grew to ${findings.size} (baseline $EXPECTED_MAX). " +
                "Migrate to string resources and lower EXPECTED_MAX per phase.",
            findings.size <= EXPECTED_MAX,
        )
    }
}
