package app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/**
 * 网络路径的集成测试：本地起 HTTP 服务当作 Releases API，验证界面看到的三态契约
 * （有新版本 / 已是最新 / 检查失败），以及请求头与失败归类。不访问 GitHub。
 */
public class UpdateCheckHttpTest {

    private HttpServer server;

    @After
    public void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private String serve(int status, String body, AtomicReference<String> headers)
            throws Exception {
        HttpServer http = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/latest", exchange -> {
            if (headers != null) {
                headers.set(
                        valueOrEmpty(exchange.getRequestHeaders().getFirst("User-Agent")) + "|"
                                + valueOrEmpty(exchange.getRequestHeaders().getFirst("Accept")));
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        http.start();
        server = http;
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/latest";
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    @Test
    public void newerTagOverHttpBecomesNewerWithPlainVersion() throws Exception {
        AtomicReference<String> headers = new AtomicReference<>("");
        String url = serve(200, "{\"tag_name\":\"v9.9.9\",\"name\":\"9.9.9\"}", headers);

        UpdateCheck.Result result = UpdateCheck.check("1.4.1", url);

        assertEquals(UpdateCheck.Status.NEWER, result.status);
        assertEquals("9.9.9", result.version);
        assertEquals(
                "请求必须带 GitHub 要求的 UA 与 Accept",
                "CryptoAssistant|application/vnd.github+json",
                headers.get());
    }

    @Test
    public void sameOrOlderTagBecomesUpToDateWithLocalVersion() throws Exception {
        String same = serve(200, "{\"tag_name\":\"v1.4.1\"}", null);
        UpdateCheck.Result result = UpdateCheck.check("1.4.1", same);
        assertEquals(UpdateCheck.Status.UP_TO_DATE, result.status);
        assertEquals("1.4.1", result.version);

        String older = serve(200, "{\"tag_name\":\"1.4.0\"}", null);
        assertEquals(UpdateCheck.Status.UP_TO_DATE, UpdateCheck.check("1.4.1", older).status);
    }

    @Test
    public void non200ResponseIsAFailureNotAnException() throws Exception {
        String notFound = serve(404, "{\"message\":\"Not Found\"}", null);
        assertEquals(UpdateCheck.Status.FAILED, UpdateCheck.check("1.4.1", notFound).status);

        String serverError = serve(500, "boom", null);
        assertEquals(UpdateCheck.Status.FAILED, UpdateCheck.check("1.4.1", serverError).status);
    }

    @Test
    public void bodyWithoutAUsableTagIsAFailure() throws Exception {
        String notJson = serve(200, "<html>rate limited</html>", null);
        assertEquals(UpdateCheck.Status.FAILED, UpdateCheck.check("1.4.1", notJson).status);

        String noTag = serve(200, "{\"message\":\"API rate limit exceeded\"}", null);
        assertEquals(UpdateCheck.Status.FAILED, UpdateCheck.check("1.4.1", noTag).status);

        String emptyTag = serve(200, "{\"tag_name\":\"\"}", null);
        assertEquals(UpdateCheck.Status.FAILED, UpdateCheck.check("1.4.1", emptyTag).status);
    }

    @Test
    public void unreachableServerIsAFailure() throws Exception {
        HttpServer closed = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        closed.start();
        int port = closed.getAddress().getPort();
        closed.stop(0);

        UpdateCheck.Result result = UpdateCheck.check("1.4.1", "http://127.0.0.1:" + port + "/latest");
        assertEquals(UpdateCheck.Status.FAILED, result.status);
        assertEquals("", result.version);
    }

    @Test
    public void shippedEndpointIsStillThePublicReleasesApi() {
        assertTrue(UpdateCheck.LATEST_RELEASE_API.startsWith("https://api.github.com/"));
        assertTrue(UpdateCheck.LATEST_RELEASE_API.endsWith("/releases/latest"));
    }
}
