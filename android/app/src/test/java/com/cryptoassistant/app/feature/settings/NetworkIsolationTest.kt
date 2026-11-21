package com.cryptoassistant.app.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「唯一的联网是检查更新」这条隐私承诺的门禁。
 *
 * 站点和设置页都写着这句话，但只有测试守得住：扫描 `src/main` 的全部源码，除
 * `UpdateCheck.kt` 外不允许出现任何网络 API；清单里也不允许出现 INTERNET 以外的网络权限。
 * 新增联网功能时必须先改白名单——这就是要的摩擦。
 */
class NetworkIsolationTest {

    private companion object {
        /** 允许联网的唯一文件（相对 `src/main/java`）。 */
        val ALLOWED_FILES = setOf("com/cryptoassistant/app/feature/settings/UpdateCheck.kt")

        /** 任何能发出网络请求的入口；命中即失败。 */
        val NETWORK_APIS = listOf(
            Regex("""openConnection"""),
            Regex("""HttpURLConnection"""),
            Regex("""\bHttpClient\b"""),
            Regex("""java\.net\.Socket"""),
            Regex("""\bnew Socket\s*\("""),
            Regex("""\bURL\s*\("""),
            Regex("""OkHttp|Retrofit|Volley|Fuel\b"""),
            // 不匹配裸 WebView 字样：无障碍服务会拿它当节点类名比较，那里不联网。
            Regex("""android\.webkit|WebViewClient|\bloadUrl\s*\("""),
        )

        const val EXPECTED_API =
            "https://api.github.com/repos/Pasokontukau/asymmetric-crypto-tool-release/releases/latest"
    }

    private fun mainDir(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return listOf(
            File(cwd, "src/main"),
            File(cwd, "app/src/main"),
            File(cwd.parentFile, "app/src/main"),
        ).firstOrNull { it.isDirectory }
            ?: error("Cannot locate src/main from ${cwd.absolutePath}")
    }

    @Test
    fun `no networking code outside UpdateCheck`() {
        val root = mainDir()
        val javaRoot = File(root, "java")
        assertTrue("missing ${javaRoot.path}", javaRoot.isDirectory)

        val findings = mutableListOf<String>()
        javaRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                val relative = file.relativeTo(javaRoot).invariantSeparatorsPath
                if (relative in ALLOWED_FILES) return@forEach
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    // 注释里提到这些名字不算代码（KDoc/行注释在仓库里很常见）。
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") ||
                        trimmed.startsWith("/*")
                    ) {
                        return@forEachIndexed
                    }
                    val code = line.substringBefore("//")
                    if (NETWORK_APIS.any { it.containsMatchIn(code) }) {
                        findings += "$relative:${index + 1}: ${line.trim()}"
                    }
                }
            }

        if (findings.isNotEmpty()) findings.forEach { println(it) }
        assertTrue(
            "联网代码只允许出现在 $ALLOWED_FILES，实际还出现在：\n" +
                findings.joinToString("\n"),
            findings.isEmpty(),
        )
    }

    @Test
    fun `the only networking permission is INTERNET`() {
        val manifest = File(mainDir(), "AndroidManifest.xml")
        assertTrue("missing ${manifest.path}", manifest.isFile)

        val networkPermissions = Regex("""uses-permission android:name="([^"]+)"""")
            .findAll(manifest.readText())
            .map { it.groupValues[1] }
            .filter { it.contains("INTERNET") || it.contains("NETWORK") || it.contains("WIFI") }
            .toList()

        assertEquals(listOf("android.permission.INTERNET"), networkPermissions)
    }

    @Test
    fun `the endpoint constant is pinned`() {
        assertEquals(
            "改地址等于改变隐私承诺，必须同时改站点说明与这条断言",
            EXPECTED_API,
            UpdateCheck.LATEST_RELEASE_API,
        )
    }
}
