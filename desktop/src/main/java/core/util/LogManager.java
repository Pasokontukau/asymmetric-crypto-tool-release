package core.util;

import core.storage.StorageManager;
import core.util.ResourceMonitor;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LogManager {
    
    private static Logger logger;
    private static FileHandler fileHandler;
    private static String logDirectory;
    private static String logFileName;
    
    public static void init() {
        try {
            String dataDir = StorageManager.getDataDir();
            logDirectory = dataDir + File.separator + "logs";
            File logDir = new File(logDirectory);
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
            String timeStamp = dateFormat.format(new Date());
            logFileName = "app_log_" + timeStamp + ".log";
            String logFilePath = logDirectory + File.separator + logFileName;
            
            logger = Logger.getLogger("AsymmetricEncryptionTool");
            logger.setLevel(Level.ALL);
            
            Handler[] handlers = logger.getHandlers();
            if (handlers.length > 0) {
                logger.removeHandler(handlers[0]);
            }
            
            LogFormatter formatter = new LogFormatter();
            
            fileHandler = new FileHandler(logFilePath, true);
            fileHandler.setLevel(Level.ALL);
            fileHandler.setFormatter(formatter);
            logger.addHandler(fileHandler);
            
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setLevel(Level.INFO);
            consoleHandler.setFormatter(formatter);
            logger.addHandler(consoleHandler);
            
            logger.info("日志管理器初始化成功");
            logger.info("系统信息：" + ResourceMonitor.getSystemInfo());
            logger.info("初始资源使用情况：" + ResourceMonitor.getResourceUsageInfo());
            
            // 注册 JVM 关闭钩子，确保日志被正确刷新
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                close();
            }, "LogManager-Shutdown"));
            
        } catch (Exception e) {
            System.err.println("日志管理器初始化失败: " + e.getClass().getName());
        }
    }
    
    public static String formatMilliseconds(long millis) {
        if (millis < 1000) {
            return millis + "ms";
        } else if (millis < 60000) {
            return String.format("%.1fs", millis / 1000.0);
        } else {
            long minutes = millis / 60000;
            long seconds = (millis % 60000) / 1000;
            return minutes + "m" + seconds + "s";
        }
    }
    
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
    
    public static Logger getLogger() {
        if (logger == null) {
            init();
        }
        return logger;
    }
    
    public static String getLogDirectory() {
        return logDirectory;
    }
    
    public static String getLogFileName() {
        return logFileName;
    }
    
    public static void close() {
        if (fileHandler != null) {
            fileHandler.close();
        }
    }
    
    public static void info(String message) {
        getLogger().info(message);
    }
    
    public static void infoWithResource(String message) {
        getLogger().info(message + " | " + ResourceMonitor.getResourceUsageInfo());
    }
    
    public static void warning(String message) {
        getLogger().warning(message);
    }
    
    public static void warning(String message, Throwable throwable) {
        getLogger().log(Level.WARNING, message, throwable);
    }
    
    public static void warningWithResource(String message) {
        getLogger().warning(message + " | " + ResourceMonitor.getResourceUsageInfo());
    }
    
    public static void warningWithResource(String message, Throwable throwable) {
        getLogger().log(Level.WARNING, message + " | " + ResourceMonitor.getResourceUsageInfo(), throwable);
    }
    
    public static void error(String message) {
        getLogger().severe(message);
    }
    
    public static void errorWithResource(String message) {
        getLogger().severe(message + " | " + ResourceMonitor.getResourceUsageInfo());
    }
    
    public static void error(String message, Throwable throwable) {
        getLogger().log(Level.SEVERE, message, throwable);
    }
    
    public static void errorWithResource(String message, Throwable throwable) {
        getLogger().log(Level.SEVERE, message + " | " + ResourceMonitor.getResourceUsageInfo(), throwable);
    }
    
    public static void debug(String message) {
        getLogger().fine(message);
    }
    
    public static void debugWithResource(String message) {
        getLogger().fine(message + " | " + ResourceMonitor.getResourceUsageInfo());
    }
    
    public static void logCryptoOperation(String operationType, String keyName, long dataSize, boolean success, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("CryptoOperation: [Type: ").append(operationType)
          .append(", Key: ").append(keyName)
          .append(", DataSize: ").append(formatBytes(dataSize))
          .append(", Success: ").append(success)
          .append(", Message: ").append(message)
          .append(", Timestamp: ").append(new Date())
          .append(", Resources: ").append(ResourceMonitor.getResourceUsageInfo())
          .append("]");
        
        if (success) {
            getLogger().info(sb.toString());
        } else {
            getLogger().severe(sb.toString());
        }
    }
    
}
