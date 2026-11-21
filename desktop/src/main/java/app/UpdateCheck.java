package app;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 应用内版本检查。
 *
 * <p>语义（与 Android 的 `UpdateCheck` 一致）：只有用户打开「允许检查更新」后才会联网；每次
 * 检查向 GitHub 的公开 Releases API 发一次匿名 GET，只读 {@code tag_name}，不带任何本机
 * 信息；发现新版本只提示，不下载、不安装。</p>
 *
 * <p>解析与比较是纯函数，便于单测；{@link #check(String)} 是阻塞调用，必须放到后台线程
 * （设置页用 SwingWorker）。任何网络或解析问题都归为 {@link Status#FAILED}，不抛给界面。</p>
 */
public final class UpdateCheck {

    public static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/Pasokontukau/asymmetric-crypto-tool-release/releases/latest";

    private static final int TIMEOUT_MS = 8000;

    public enum Status { UP_TO_DATE, NEWER, FAILED }

    /** 检查结果；{@link #version} 在 UP_TO_DATE 时是本地版本，在 NEWER 时是远端版本。 */
    public static final class Result {
        public final Status status;
        public final String version;

        private Result(Status status, String version) {
            this.status = status;
            this.version = version;
        }

        static Result upToDate(String local) {
            return new Result(Status.UP_TO_DATE, local);
        }

        static Result newer(String remote) {
            return new Result(Status.NEWER, remote);
        }

        static Result failed() {
            return new Result(Status.FAILED, "");
        }
    }

    private UpdateCheck() {
    }

    /** 从 Releases API 的 JSON 里取 tag；缺字段、格式不对或空串返回 null。 */
    public static String parseTag(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("tag_name") || !root.get("tag_name").isJsonPrimitive()) {
                return null;
            }
            String tag = root.get("tag_name").getAsString().trim();
            return tag.isEmpty() ? null : tag;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** {@code v1.4.2} 比 {@code 1.4.1} 新；缺的段按 0 补齐，非数字后缀（如 {@code -beta}）忽略。 */
    public static boolean isNewer(String latest, String current) {
        return compare(latest, current) > 0;
    }

    public static int compare(String a, String b) {
        int[] partsA = numericParts(a);
        int[] partsB = numericParts(b);
        int length = Math.max(partsA.length, partsB.length);
        for (int index = 0; index < length; index++) {
            int diff = Integer.compare(partAt(partsA, index), partAt(partsB, index));
            if (diff != 0) {
                return diff;
            }
        }
        return 0;
    }

    private static int[] numericParts(String version) {
        String trimmed = version == null ? "" : version.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1);
        }
        String[] segments = trimmed.split("\\.", -1);
        int[] parts = new int[segments.length];
        for (int index = 0; index < segments.length; index++) {
            parts[index] = leadingNumber(segments[index]);
        }
        return parts;
    }

    private static int partAt(int[] parts, int index) {
        return index < parts.length ? parts[index] : 0;
    }

    private static int leadingNumber(String segment) {
        int end = 0;
        while (end < segment.length() && Character.isDigit(segment.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(segment.substring(0, end));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 阻塞检查；调用方负责放到后台线程。 */
    public static Result check(String currentVersion) {
        return check(currentVersion, LATEST_RELEASE_API);
    }

    /**
     * 同一个检查，但地址可注入：包内可见，仅供测试用本地 HTTP 服务覆盖网络路径。
     *
     * <p>这是整个应用里唯一会联网的代码，{@code NetworkIsolationTest} 扫描 {@code src/main/java}
     * 保证别处没有网络 API。</p>
     */
    static Result check(String currentVersion, String apiUrl) {
        String tag;
        try {
            tag = fetchLatestTag(apiUrl);
        } catch (Exception ex) {
            return Result.failed();
        }
        if (tag == null) {
            return Result.failed();
        }
        if (isNewer(tag, currentVersion)) {
            String remote = tag;
            if (remote.startsWith("v") || remote.startsWith("V")) {
                remote = remote.substring(1);
            }
            return Result.newer(remote);
        }
        return Result.upToDate(currentVersion);
    }

    private static String fetchLatestTag(String apiUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "CryptoAssistant");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            try (InputStream in = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
                return parseTag(body.toString());
            }
        } finally {
            connection.disconnect();
        }
    }
}
