package com.cryptoassistant.app.feature.settings

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络路径的集成测试。
 *
 * 本地起一个只回答一次的 HTTP 服务（`java.net.ServerSocket`，不引入任何测试依赖），把它当作
 * Releases API，验证**界面真正看到的三态契约**：200 + 新 tag → 有新版本、200 + 同版本 →
 * 已是最新、非 200 / 非 JSON / 缺字段 / 连不上 → 一律 Failed。不走 GitHub，也不需要协程调度
 * （用同步的 `checkBlocking`）。
 *
 * 这里不用 `com.sun.net.httpserver`：AGP 的单元测试编译类路径只给 android.jar 与 JDK 的常规
 * 类，`com.sun.*` 解析不到。
 */
class UpdateCheckHttpTest {

    private var server: TestServer? = null

    @After
    fun stopServer() {
        server?.close()
        server = null
    }

    /** 起一个只服务一个连接的 HTTP/1.1 应答器，记录它收到的 `User-Agent|Accept`。 */
    private fun serve(status: Int, body: String): TestServer =
        TestServer(status, body).also {
            server = it
            it.start()
        }

    @Test
    fun `a newer tag over http becomes Newer with the plain version`() {
        val test = serve(200, """{"tag_name":"v9.9.9","name":"9.9.9"}""")

        assertEquals(
            UpdateCheck.Outcome.Newer("9.9.9"),
            UpdateCheck.checkBlocking("1.4.1", test.url),
        )
        assertEquals(
            "请求必须带 GitHub 要求的 UA 与 Accept",
            "CryptoAssistant|application/vnd.github+json",
            test.headers.get(),
        )
    }

    @Test
    fun `the same or an older tag becomes UpToDate with the local version`() {
        val same = serve(200, """{"tag_name":"v1.4.1"}""")
        assertEquals(
            UpdateCheck.Outcome.UpToDate("1.4.1"),
            UpdateCheck.checkBlocking("1.4.1", same.url),
        )
    }

    @Test
    fun `a non 200 response is a failure, not an exception`() {
        val notFound = serve(404, """{"message":"Not Found"}""")
        assertEquals(UpdateCheck.Outcome.Failed, UpdateCheck.checkBlocking("1.4.1", notFound.url))

        val serverError = serve(500, "boom")
        assertEquals(UpdateCheck.Outcome.Failed, UpdateCheck.checkBlocking("1.4.1", serverError.url))
    }

    @Test
    fun `a body that is not json or has no tag is a failure`() {
        val notJson = serve(200, "<html>rate limited</html>")
        assertEquals(UpdateCheck.Outcome.Failed, UpdateCheck.checkBlocking("1.4.1", notJson.url))

        val noTag = serve(200, """{"message":"API rate limit exceeded"}""")
        assertEquals(UpdateCheck.Outcome.Failed, UpdateCheck.checkBlocking("1.4.1", noTag.url))

        val emptyTag = serve(200, """{"tag_name":""}""")
        assertEquals(UpdateCheck.Outcome.Failed, UpdateCheck.checkBlocking("1.4.1", emptyTag.url))
    }

    @Test
    fun `an unreachable server is a failure`() {
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = closed.localPort
        closed.close()

        assertEquals(
            UpdateCheck.Outcome.Failed,
            UpdateCheck.checkBlocking("1.4.1", "http://127.0.0.1:$port/latest"),
        )
    }

    @Test
    fun `the shipped endpoint is still the public releases api`() {
        assertTrue(
            "线上地址不该被改成别的主机：${UpdateCheck.LATEST_RELEASE_API}",
            UpdateCheck.LATEST_RELEASE_API.startsWith("https://api.github.com/"),
        )
        assertTrue(UpdateCheck.LATEST_RELEASE_API.endsWith("/releases/latest"))
    }

    private class TestServer(private val status: Int, private val body: String) : Closeable {

        private val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val headers = AtomicReference("")

        val url: String = "http://127.0.0.1:${socket.localPort}/latest"

        private val thread = Thread {
            runCatching { serveOnce() }
        }.apply {
            isDaemon = true
            name = "update-check-test-server"
        }

        fun start() {
            thread.start()
        }

        private fun serveOnce() {
            socket.accept().use { client ->
                val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                var userAgent = ""
                var accept = ""
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val header = line.substringBefore(':').trim().lowercase()
                    when (header) {
                        "user-agent" -> userAgent = line.substringAfter(':').trim()
                        "accept" -> accept = line.substringAfter(':').trim()
                    }
                }
                headers.set("$userAgent|$accept")

                val payload = body.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 $status ${reason(status)}\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${payload.size}\r\n" +
                    "Connection: close\r\n\r\n"
                client.getOutputStream().apply {
                    write(head.toByteArray(Charsets.ISO_8859_1))
                    write(payload)
                    flush()
                }
            }
        }

        private fun reason(code: Int): String = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "Status"
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }
}
