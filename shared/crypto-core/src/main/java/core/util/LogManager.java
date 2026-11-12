package core.util;

/**
 * 共享内核的可移植日志适配器（默认实现）。
 *
 * <p>桌面端自有实现会初始化 {@code java.util.logging.FileHandler}、写入文件并依赖
 * {@code StorageManager} / {@code ResourceMonitor}；这些不适合放进纯 JVM 内核，
 * 也可能把运行信息落盘。共享内核只保留加密代码实际调用的 API，默认不输出任何内容。</p>
 *
 * <p>Android 直接使用本类；桌面端的 {@code desktop} classifier 会排除本类，
 * 改用自己的真实日志实现。协议实现本身不依赖日志行为。</p>
 */
public final class LogManager {

    private LogManager() {
    }

    /** 与桌面端一致：把字节数格式化为可读字符串（不涉及敏感数据）。 */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    // 以下方法保留桌面端调用点所需的签名，但在 Android 上不写日志、
    // 不落盘、不记录明文/密钥/密文。

    public static void info(String message) {
        // no-op
    }

    public static void infoWithResource(String message) {
        // no-op
    }

    public static void debug(String message) {
        // no-op
    }

    public static void warning(String message) {
        // no-op
    }

    public static void warning(String message, Throwable throwable) {
        // no-op
    }

    public static void error(String message) {
        // no-op
    }

    public static void error(String message, Throwable throwable) {
        // no-op
    }
}
