package com.cryptoassistant.app.feature.settings

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用内版本检查。
 *
 * 语义（与桌面端 `app.UpdateCheck` 一致）：只有用户打开「允许检查更新」后才会联网；每次检查
 * 向 GitHub 的公开 Releases API 发一次匿名 GET，只读 `tag_name`，不带任何本机信息、不带
 * 账号；发现新版本只提示，不下载、不安装。
 *
 * 这是整个应用里**唯一**会联网的代码：`NetworkIsolationTest` 会扫描 `src/main`，除本文件
 * 外不允许出现任何网络 API。
 */
object UpdateCheck {

    const val LATEST_RELEASE_API =
        "https://api.github.com/repos/Pasokontukau/asymmetric-crypto-tool-release/releases/latest"

    private const val TIMEOUT_MS = 8000

    /** 检查结果；[version] 在 [UpToDate] 时是本地版本，在 [Newer] 时是远端版本。 */
    sealed interface Outcome {
        data class UpToDate(val version: String) : Outcome
        data class Newer(val version: String) : Outcome
        object Failed : Outcome
    }

    /** 只认一个字段，不为一次检查引入 JSON 依赖；字段缺失或空串视为无法解析。 */
    private val TAG_PATTERN = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")

    fun parseTag(json: String): String? =
        TAG_PATTERN.find(json)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** `v1.4.2` 比 `1.4.1` 新；缺的段按 0 补齐，非数字后缀（如 `-beta`）忽略。 */
    fun isNewer(latest: String, current: String): Boolean = compare(latest, current) > 0

    fun compare(a: String, b: String): Int {
        val partsA = numericParts(a)
        val partsB = numericParts(b)
        for (index in 0 until maxOf(partsA.size, partsB.size)) {
            val diff = partsA.getOrElse(index) { 0 }.compareTo(partsB.getOrElse(index) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private fun numericParts(version: String): List<Int> =
        version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .split('.')
            .map { segment -> segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /** 联网一次并给出结论；任何网络/解析问题都归为 [Outcome.Failed]，不抛给界面。 */
    suspend fun check(currentVersion: String): Outcome =
        withContext(Dispatchers.IO) { checkBlocking(currentVersion, LATEST_RELEASE_API) }

    /**
     * 同步实现，[apiUrl] 可注入以便用本地 HTTP 服务测网络路径；只能在后台线程调用
     * （Android 主线程联网会抛 `NetworkOnMainThreadException`）。
     */
    internal fun checkBlocking(
        currentVersion: String,
        apiUrl: String = LATEST_RELEASE_API,
    ): Outcome {
        val tag = runCatching { fetchLatestTag(apiUrl) }.getOrNull() ?: return Outcome.Failed
        return if (isNewer(tag, currentVersion)) {
            Outcome.Newer(tag.removePrefix("v").removePrefix("V"))
        } else {
            Outcome.UpToDate(currentVersion)
        }
    }

    private fun fetchLatestTag(apiUrl: String): String? {
        val connection = URL(apiUrl).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "CryptoAssistant")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { parseTag(it.readText()) }
            }
        } finally {
            connection.disconnect()
        }
    }
}
