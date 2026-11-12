package core.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * 桌面日志格式与隐私边界。
 *
 * <p>主消息只保留调用方写入的操作、计数与异常类别；路径、URI、PEM/长 Base64
 * 会被替换为占位符。异常只记录类型和堆栈，<b>不记录 {@link Throwable#getMessage()}</b>，
 * 因为文件异常常把绝对路径或原始文件名带进消息里。</p>
 *
 * <p>堆栈由 {@link StackTraceElement} 逐帧生成，不使用
 * {@code printStackTrace()}，避免其首行再次输出异常消息。</p>
 */
public final class LogFormatter extends Formatter {

    private static final int MAX_STACK_FRAMES = 10;
    private static final int MAX_CAUSE_DEPTH = 3;

    private final SimpleDateFormat timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    public synchronized String format(LogRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append(timestamp.format(new Date(record.getMillis())));
        sb.append(" [").append(record.getLevel().getName()).append("] [");
        sb.append(record.getLoggerName()).append("] [");
        sb.append(record.getSourceClassName()).append(".").append(record.getSourceMethodName()).append("()");
        sb.append("] [").append(Thread.currentThread().getName()).append("] [");
        sb.append(Thread.currentThread().getId()).append("] [");
        sb.append(LogManager.formatBytes(ResourceMonitor.getUsedMemory())).append("] ");
        sb.append(sanitize(record.getMessage()));
        sb.append('\n');

        Throwable throwable = record.getThrown();
        if (throwable != null) {
            appendThrowable(sb, throwable, "", 0);
        }
        return sb.toString();
    }

    private static void appendThrowable(StringBuilder sb, Throwable throwable, String prefix, int depth) {
        sb.append("  [Exception] ");
        if (prefix.isEmpty()) {
            sb.append("Type: ");
        } else {
            sb.append(prefix);
        }
        sb.append(throwable.getClass().getName()).append('\n');
        StackTraceElement[] frames = throwable.getStackTrace();
        int shown = Math.min(frames.length, MAX_STACK_FRAMES);
        for (int i = 0; i < shown; i++) {
            sb.append("    ").append(prefix).append("at ").append(frames[i]).append('\n');
        }
        if (frames.length > shown) {
            sb.append("    ").append(prefix).append("... 还有 ")
                    .append(frames.length - shown).append(" 行堆栈跟踪\n");
        }

        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable && depth < MAX_CAUSE_DEPTH) {
            appendThrowable(sb, cause, prefix + "Caused by: ", depth + 1);
        }
    }

    /**
     * 日志正文脱敏。调用方仍应只写操作/计数；这里兜底处理路径、URI 与密钥样式内容。
     */
    static String sanitize(String message) {
        if (message == null) return "";
        String filtered = message
                .replaceAll("(?i)password([\\s]*=[:\\s]*)[^\\s,]+", "password$1[REDACTED]")
                .replaceAll("(?i)secret([\\s]*=[:\\s]*)[^\\s,]+", "secret$1[REDACTED]")
                .replaceAll("(?i)private([\\s]*=[:\\s]*)[^\\s,]+", "private$1[REDACTED]")
                .replaceAll("(?i)key([\\s]*=[:\\s]*)[^\\s,]+", "key$1[REDACTED]")
                .replaceAll("(?i)token([\\s]*=[:\\s]*)[^\\s,]+", "token$1[REDACTED]")
                .replaceAll("-----BEGIN [^-]+-----[\\s\\S]*?-----END [^-]+-----", "<redacted>")
                .replaceAll("(?i)\\b(?:content|file|https?)://\\S+", "<uri>")
                .replaceAll("(?i)\\b[A-Za-z]:\\\\[^\\s]+", "<path>")
                .replaceAll("(?<![\\w:])/(?:[^\\s/]+/)+[^\\s]*", "<path>")
                .replaceAll("\\b[A-Za-z0-9+/]{64,}={0,2}\\b", "<redacted>")
                .replaceAll("\\s+", " ")
                .trim();
        return filtered;
    }
}
