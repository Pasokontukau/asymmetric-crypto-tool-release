package core.util;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 桌面日志的隐私边界：主消息里的路径/密钥被替换，异常只留类型和堆栈，
 * 不输出可能带用户文件名的异常消息。
 */
public class LogFormatterPrivacyTest {

    private static LogRecord record(String message, Throwable throwable) {
        LogRecord record = new LogRecord(Level.SEVERE, message);
        record.setLoggerName("test");
        record.setSourceClassName("test.Panel");
        record.setSourceMethodName("run");
        record.setThrown(throwable);
        return record;
    }

    @Test
    public void exceptionMessageWithOriginalFileNameIsNotLogged() {
        String originalName = "工资表 2026.xlsx";
        String text = new LogFormatter().format(record(
                "encrypt-file failed | bytes=1024",
                new IOException("C:\\Users\\me\\" + originalName + " (拒绝访问)")));

        assertFalse("异常消息不能把原文件名写进日志", text.contains(originalName));
        assertFalse("异常消息不能把绝对路径写进日志", text.contains("C:\\Users"));
        assertTrue(text.contains("java.io.IOException"));
        assertTrue(text.contains("at "));
    }

    @Test
    public void causeMessageIsNotLogged() {
        String originalName = "季度汇报.docx";
        IOException cause = new IOException("/home/me/" + originalName);
        IOException error = new IOException("保存失败", cause);

        String text = new LogFormatter().format(record("save-file failed", error));

        assertFalse(text.contains(originalName));
        assertFalse(text.contains("/home/me"));
        assertTrue(text.contains("Caused by: java.io.IOException"));
    }

    @Test
    public void mainMessagePathsAndSecretsAreRedacted() {
        String text = new LogFormatter().sanitize(
                "打开 C:\\Users\\me\\secret.docx 失败 password=abc123");

        assertFalse(text.contains("secret.docx"));
        assertFalse(text.contains("C:\\Users"));
        assertFalse(text.contains("abc123"));
        assertTrue(text.contains("<path>"));
        assertTrue(text.contains("password=[REDACTED]"));
    }

    @Test
    public void safeCountersAndSizesRemainReadable() {
        String message = "encrypt-file failed | bytes=1024 parts=2 mode=ENCRYPT";

        assertTrue(new LogFormatter().sanitize(message).contains(message));
    }
}
