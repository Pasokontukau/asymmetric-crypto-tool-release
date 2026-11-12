package core.util;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 桌面日志调用点的静态守卫。
 *
 * <p>单测无法启动 Swing 面板去触发每一条失败分支，所以这里直接检查源码：
 * 日志调用里不能出现文件名/路径变量，也不能拼 {@code getMessage()}。真正的
 * 异常消息脱敏由 {@link LogFormatterPrivacyTest} 覆盖。</p>
 */
public class LogCallsitePrivacyTest {

    private static final Pattern SUSPICIOUS = Pattern.compile(
            "\\b(?:file|inputFile|outputFile|selectedFile|fileOrFolder|folder|target|path|"
                    + "originalPath|child|source|part|zipFile|tempFile)\\s*\\.\\s*"
                    + "(?:getName|getAbsolutePath|getPath|toAbsolutePath)\\s*\\("
                    + "|\\+\\s*(?:file|inputFile|outputFile|selectedFile|fileOrFolder|folder|target|path|"
                    + "originalPath|child|source|part|zipFile|tempFile)\\b"
                    + "(?!\\s*\\.\\s*(?:length|size|exists|isFile|isDirectory)\\s*\\()"
                    + "|\\.getMessage\\s*\\(");

    @Test
    public void logCallsitesDoNotInterpolateFileNamesPathsOrExceptionMessages() throws IOException {
        List<String> failures = new ArrayList<String>();
        for (Path source : javaSources()) {
            String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
            for (String call : callBodies(text, "LogManager.")) {
                if (SUSPICIOUS.matcher(call).find()) {
                    failures.add(source + ": " + call.replace('\n', ' '));
                }
            }
        }
        assertTrue("日志调用点不能拼接文件名/路径/异常消息:\n" + String.join("\n", failures),
                failures.isEmpty());
    }

    @Test
    public void consoleFallbacksDoNotInterpolateFileNamesPathsOrExceptionMessages() throws IOException {
        List<String> failures = new ArrayList<String>();
        for (Path source : javaSources()) {
            String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
            for (String token : new String[]{"System.err.", "System.out."}) {
                for (String call : callBodies(text, token)) {
                    if (SUSPICIOUS.matcher(call).find()) {
                        failures.add(source + ": " + call.replace('\n', ' '));
                    }
                }
            }
        }
        assertTrue("控制台兜底也不能拼文件名/路径/异常消息:\n" + String.join("\n", failures),
                failures.isEmpty());
    }

    @Test
    public void unsafePathLoggingApiIsGone() {
        for (Method method : LogManager.class.getDeclaredMethods()) {
            assertNotEquals("LogManager 不应再提供带路径参数的日志 API",
                    "logFileOperation", method.getName());
        }
    }

    private static List<Path> javaSources() throws IOException {
        Path root = Paths.get("src/main/java");
        if (!Files.isDirectory(root)) {
            root = Paths.get("desktop/src/main/java");
        }
        if (!Files.isDirectory(root)) {
            throw new IOException("找不到桌面源码目录: " + root.toAbsolutePath());
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(path -> path.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
    }

    /** 从每个调用起点截到配对的右括号，字符串字面量里的括号不计数。 */
    private static List<String> callBodies(String text, String token) {
        List<String> calls = new ArrayList<String>();
        int from = 0;
        while (true) {
            int start = text.indexOf(token, from);
            if (start < 0) return calls;
            int open = text.indexOf('(', start);
            if (open < 0) return calls;
            int depth = 0;
            char quote = 0;
            boolean escaped = false;
            boolean closed = false;
            for (int i = open; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quote != 0) {
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        calls.add(text.substring(start, i + 1));
                        from = i + 1;
                        closed = true;
                        break;
                    }
                }
            }
            if (!closed) return calls;
        }
    }
}
