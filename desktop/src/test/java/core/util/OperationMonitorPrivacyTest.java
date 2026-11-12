package core.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 操作监控的异常日志只允许出现异常类型。
 *
 * <p>{@code recordException} 当前没有调用方，但它仍是公开 API；如果以后有人接上，
 * {@code getMessage()} 或堆栈会原样带出用户路径和原始文件名。这里直接捕获 JUL
 * 原始消息，而不是只看格式化后的文件。</p>
 */
public class OperationMonitorPrivacyTest {

    @Test
    public void recordExceptionLogsTypeButNotMessageOrPath() {
        String originalName = "工资表 2026.xlsx";
        String rawMessage = "C:\\Users\\me\\" + originalName + " (拒绝访问)";

        Logger logger = LogManager.getLogger();
        List<String> messages = new ArrayList<String>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(String.valueOf(record.getMessage()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);

        try {
            String operationId = OperationMonitor.startOperation(
                    OperationMonitor.OperationType.FILE_ENCRYPT, "文件加密", 1024);
            OperationMonitor.recordException(operationId, new IOException(rawMessage));
        } finally {
            logger.removeHandler(handler);
        }

        String joined = String.join("\n", messages);
        assertFalse("异常消息不能把原文件名写进日志", joined.contains(originalName));
        assertFalse("异常消息不能把绝对路径写进日志", joined.contains("C:\\Users"));
        assertTrue("仍应保留异常类型便于排查", joined.contains("java.io.IOException"));
    }
}
