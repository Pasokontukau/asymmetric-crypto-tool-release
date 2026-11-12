package core.util;

import java.util.UUID;

/**
 * 共享内核的可移植操作监控适配器（默认实现）。
 *
 * <p>桌面端自有实现依赖 {@code java.lang.management} MXBean 与日志文件；Android 不提供这些
 * MXBean，且保留操作上下文会增加敏感数据驻留时间。此实现只维持加密代码所需的调用契约，
 * 不保存任何操作记录、上下文或异常信息。桌面端通过 {@code desktop} classifier
 * 排除本类并改用自己的实现。</p>
 */
public final class OperationMonitor {

    public enum OperationType {
        FILE_ENCRYPT,
        FILE_DECRYPT,
        KEY_GENERATION,
        SIGNATURE_CREATE,
        SIGNATURE_VERIFY,
        DATA_ENCRYPT,
        DATA_DECRYPT
    }

    private OperationMonitor() {
    }

    public static String startOperation(OperationType type, String description, long totalBytes) {
        return UUID.randomUUID().toString();
    }

    public static void updateProgress(String operationId, long processedBytes) {
        // no-op：不保存进度或数据量
    }

    public static void addContextData(String operationId, String key, Object value) {
        // no-op：不记录密钥算法、长度等上下文
    }

    public static void endOperation(String operationId, boolean success, String message) {
        // no-op
    }

    public static void recordException(String operationId, Throwable throwable) {
        // no-op：异常由调用方继续抛出，不在此记录堆栈
    }
}
