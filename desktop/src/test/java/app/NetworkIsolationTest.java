package app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * 「唯一的联网是检查更新」这条承诺的门禁。
 *
 * <p>站点与设置页都这么写，只有测试守得住：扫描 {@code src/main/java}，除
 * {@code app/UpdateCheck.java} 外不允许出现任何网络 API。新增联网功能必须先改白名单。</p>
 */
public class NetworkIsolationTest {

    /** 允许联网的唯一文件（相对 {@code src/main/java}）。 */
    private static final Set<String> ALLOWED_FILES = new HashSet<>(
            Arrays.asList("app/UpdateCheck.java"));

    /** 任何能发出网络请求的入口；命中即失败。 */
    private static final List<Pattern> NETWORK_APIS = Arrays.asList(
            Pattern.compile("openConnection"),
            Pattern.compile("HttpURLConnection"),
            Pattern.compile("\\bHttpClient\\b"),
            Pattern.compile("java\\.net\\.Socket"),
            Pattern.compile("\\bnew Socket\\s*\\("),
            Pattern.compile("\\bURL\\s*\\("),
            Pattern.compile("OkHttp|Retrofit|Volley|Fuel\\b"),
            Pattern.compile("\\bURI\\.create|\\bopenStream\\s*\\("));

    private static final String EXPECTED_API =
            "https://api.github.com/repos/Pasokontukau/asymmetric-crypto-tool-release/releases/latest";

    private static File sourceRoot() {
        File cwd = new File(System.getProperty("user.dir", "."));
        List<File> candidates = Arrays.asList(
                new File(cwd, "src/main/java"),
                new File(cwd, "desktop/src/main/java"),
                new File(cwd.getParentFile(), "desktop/src/main/java"));
        for (File candidate : candidates) {
            if (candidate.isDirectory()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate src/main/java from " + cwd.getAbsolutePath());
    }

    private static List<File> javaFiles(File root) {
        try (Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
            return paths.filter(Files::isRegularFile)
                    .map(java.nio.file.Path::toFile)
                    .filter(file -> file.getName().endsWith(".java"))
                    .collect(Collectors.toList());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Test
    public void noNetworkingCodeOutsideUpdateCheck() throws Exception {
        File root = sourceRoot();
        List<String> findings = new ArrayList<>();
        for (File file : javaFiles(root)) {
            String relative = root.toPath().relativize(file.toPath()).toString().replace('\\', '/');
            if (ALLOWED_FILES.contains(relative)) {
                continue;
            }
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String code = lines.get(index).split("//", 2)[0];
                for (Pattern pattern : NETWORK_APIS) {
                    if (pattern.matcher(code).find()) {
                        findings.add(relative + ":" + (index + 1) + ": " + lines.get(index).trim());
                        break;
                    }
                }
            }
        }
        findings.forEach(System.out::println);
        assertTrue(
                "联网代码只允许出现在 " + ALLOWED_FILES + "，实际还出现在：\n"
                        + String.join("\n", findings),
                findings.isEmpty());
    }

    @Test
    public void theEndpointConstantIsPinned() {
        assertEquals(
                "改地址等于改变隐私承诺，必须同时改站点说明与这条断言",
                EXPECTED_API,
                UpdateCheck.LATEST_RELEASE_API);
    }
}
