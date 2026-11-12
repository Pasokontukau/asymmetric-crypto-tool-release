package core.util;

import core.util.LogManager;
import core.util.ResourceMonitor;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class OperationMonitor {

    // 操作类型枚举
    public enum OperationType {
        FILE_ENCRYPT,
        FILE_DECRYPT,
        KEY_GENERATION,
        SIGNATURE_CREATE,
        SIGNATURE_VERIFY,
        DATA_ENCRYPT,
        DATA_DECRYPT
    }

    // 操作监控记录类
    private static class OperationRecord {
        String operationId;
        OperationType type;
        String description;
        long startTime;
        long predictedTime;
        long lastCheckTime;
        long processedBytes;
        long totalBytes;
        double averageSpeed;
        Map<String, Object> contextData;
        boolean completed;

        OperationRecord(String operationId, OperationType type, String description, long totalBytes) {
            this.operationId = operationId;
            this.type = type;
            this.description = description;
            this.startTime = System.currentTimeMillis();
            this.lastCheckTime = this.startTime;
            this.processedBytes = 0;
            this.totalBytes = totalBytes;
            this.averageSpeed = 0;
            this.contextData = new ConcurrentHashMap<>();
            this.completed = false;
            // 初始预测时间（基于文件大小的线性预测）
            this.predictedTime = calculateInitialPredictedTime(type, totalBytes);
        }
    }

    // 保存所有操作记录
    private static final Map<String, OperationRecord> operationRecords = new ConcurrentHashMap<>();
    // 系统资源监控相关
    private static final Runtime RUNTIME = Runtime.getRuntime();
    private static final OperatingSystemMXBean OS_MX_BEAN = ManagementFactory.getOperatingSystemMXBean();
    private static final MemoryMXBean MEMORY_MX_BEAN = ManagementFactory.getMemoryMXBean();
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
    // 时间预测系数（毫秒/MB）
    private static final Map<OperationType, Double> TIME_COEFFICIENTS = new EnumMap<>(OperationType.class);
    // 超过预测时间的警告阈值（百分比）
    private static final double WARNING_THRESHOLD = 1.5; // 超过预测时间50%时警告
    // 严重警告阈值（百分比）
    private static final double SEVERE_THRESHOLD = 2.0; // 超过预测时间100%时严重警告
    // 最小检查间隔（毫秒）
    private static final long MIN_CHECK_INTERVAL = 1000;
    // 历史操作记录，用于智能预测
    private static final List<OperationRecord> historyRecords = Collections.synchronizedList(new ArrayList<>());
    // 历史记录最大数量
    private static final int MAX_HISTORY_RECORDS = 100;

    static {
        // 初始化时间预测系数
        TIME_COEFFICIENTS.put(OperationType.FILE_ENCRYPT, 20.0);    // 20ms/MB
        TIME_COEFFICIENTS.put(OperationType.FILE_DECRYPT, 15.0);    // 15ms/MB
        TIME_COEFFICIENTS.put(OperationType.KEY_GENERATION, 500.0); // 500ms固定
        TIME_COEFFICIENTS.put(OperationType.SIGNATURE_CREATE, 10.0); // 10ms/MB
        TIME_COEFFICIENTS.put(OperationType.SIGNATURE_VERIFY, 5.0);  // 5ms/MB
        TIME_COEFFICIENTS.put(OperationType.DATA_ENCRYPT, 25.0);     // 25ms/MB
        TIME_COEFFICIENTS.put(OperationType.DATA_DECRYPT, 20.0);     // 20ms/MB
    }

    /**
     * 开始监控一个新操作
     * @param type 操作类型
     * @param description 操作描述
     * @param totalBytes 总字节数（如果适用）
     * @return 操作ID，用于后续更新和结束监控
     */
    public static String startOperation(OperationType type, String description, long totalBytes) {
        String operationId = generateOperationId();
        OperationRecord record = new OperationRecord(operationId, type, description, totalBytes);
        operationRecords.put(operationId, record);
        
        // 记录操作开始日志
        LogManager.getLogger().info(buildLogEntry(record, "OPERATION_STARTED"));
        
        return operationId;
    }

    /**
     * 更新操作进度
     * @param operationId 操作ID
     * @param processedBytes 已处理字节数
     */
    public static void updateProgress(String operationId, long processedBytes) {
        OperationRecord record = operationRecords.get(operationId);
        if (record == null || record.completed) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        long elapsedTime = currentTime - record.startTime;
        long checkInterval = currentTime - record.lastCheckTime;
        
        // 保存之前的已处理字节数
        long previousProcessedBytes = record.processedBytes;
        
        // 更新已处理字节数
        record.processedBytes = processedBytes;
        
        // 计算当前速度和平均速度 - 即使间隔短也更新
        double currentSpeed = 0;
        if (checkInterval > 0 && processedBytes > previousProcessedBytes) {
            currentSpeed = (processedBytes - previousProcessedBytes) / Math.max(checkInterval / 1000.0, 0.001);
            if (record.averageSpeed == 0) {
                record.averageSpeed = currentSpeed;
            } else {
                // 加权平均，当前速度权重0.3，历史平均权重0.7
                record.averageSpeed = (currentSpeed * 0.3) + (record.averageSpeed * 0.7);
            }
        } else if (elapsedTime > 0 && processedBytes > 0) {
            // 如果间隔太短但有进度，使用总体平均速度
            currentSpeed = processedBytes / Math.max(elapsedTime / 1000.0, 0.001);
            if (record.averageSpeed == 0) {
                record.averageSpeed = currentSpeed;
            }
        }
        
        // 重新预测剩余时间（每次都更新）
        long remainingBytes = Math.max(0, record.totalBytes - processedBytes);
        long predictedRemainingTime = (long) (remainingBytes / Math.max(record.averageSpeed, 1));
        record.predictedTime = elapsedTime + predictedRemainingTime;
        
        // 检查是否超过预测时间（每次都检查）
        checkPredictionThreshold(record, currentTime, elapsedTime);
        
        // 更新检查时间
        record.lastCheckTime = currentTime;
    }

    /**
     * 添加操作上下文数据
     * @param operationId 操作ID
     * @param key 上下文键
     * @param value 上下文值
     */
    public static void addContextData(String operationId, String key, Object value) {
        OperationRecord record = operationRecords.get(operationId);
        if (record != null) {
            record.contextData.put(key, value);
        }
    }

    /**
     * 结束操作监控
     * @param operationId 操作ID
     * @param success 是否成功
     * @param message 附加信息
     */
    public static void endOperation(String operationId, boolean success, String message) {
        OperationRecord record = operationRecords.remove(operationId);
        if (record == null) {
            return;
        }

        record.completed = true;
        long endTime = System.currentTimeMillis();
        long actualTime = endTime - record.startTime;
        
        // 记录操作结束日志
        String status = success ? "OPERATION_COMPLETED_SUCCESS" : "OPERATION_COMPLETED_FAILED";
        String logEntry = buildLogEntry(record, status, actualTime, message);
        
        if (success) {
            LogManager.getLogger().info(logEntry);
        } else {
            LogManager.getLogger().severe(logEntry + " | " + getDetailedErrorInfo());
        }
        
        // 保存到历史记录
        addToHistory(record);
    }

    /**
     * 记录操作异常
     * @param operationId 操作ID
     * @param throwable 异常对象
     */
    public static void recordException(String operationId, Throwable throwable) {
        OperationRecord record = operationRecords.get(operationId);
        if (record == null) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        long elapsedTime = currentTime - record.startTime;
        
        // 只记录异常类型：getMessage()/堆栈可能带用户路径与原始文件名。
        String errorType = throwable.getClass().getName();
        String logEntry = buildLogEntry(record, "OPERATION_EXCEPTION", elapsedTime, "异常类型: " + errorType);
        LogManager.getLogger().severe(logEntry + " | " + getDetailedErrorInfo());
        
        // 结束操作
        endOperation(operationId, false, "异常: " + errorType);
    }

    /**
     * 计算初始预测时间
     * @param type 操作类型
     * @param totalBytes 总字节数
     * @return 预测时间（毫秒）
     */
    private static long calculateInitialPredictedTime(OperationType type, long totalBytes) {
        if (totalBytes <= 0) {
            return 1000; // 默认1秒
        }
        
        // 基于历史记录的智能预测
        long historyPredictedTime = calculateHistoryBasedPrediction(type, totalBytes);
        if (historyPredictedTime > 0) {
            return historyPredictedTime;
        }
        
        // 基于文件大小的线性预测
        double coefficient = TIME_COEFFICIENTS.getOrDefault(type, 10.0);
        // 转换为MB（确保至少0.001MB避免除以零或极小值）
        double totalMB = Math.max(totalBytes / (1024.0 * 1024.0), 0.001);
        return Math.max(100, (long) (totalMB * coefficient));
    }

    /**
     * 基于历史记录的智能预测
     * @param type 操作类型
     * @param totalBytes 总字节数
     * @return 预测时间（毫秒），如果没有足够历史记录返回0
     */
    private static long calculateHistoryBasedPrediction(OperationType type, long totalBytes) {
        if (historyRecords.isEmpty()) {
            return 0;
        }
        
        // 过滤同类型的历史记录
        List<OperationRecord> sameTypeRecords = new ArrayList<>();
        for (OperationRecord record : historyRecords) {
            if (record.type == type && record.totalBytes > 0) {
                sameTypeRecords.add(record);
            }
        }
        
        if (sameTypeRecords.size() < 5) {
            return 0;
        }
        
        // 计算平均速度
        double totalSpeed = 0;
        int validCount = 0;
        for (OperationRecord record : sameTypeRecords) {
            // 计算实际执行时间（基于预测完成时间，但需要记录实际完成时间）
            // 由于我们没有记录实际结束时间，这里使用预测时间作为近似值
            if (record.predictedTime > 0) {
                double speed = record.totalBytes / (double) record.predictedTime;
                totalSpeed += speed;
                validCount++;
            }
        }
        
        if (validCount == 0) {
            return 0;
        }
        
        double avgSpeed = totalSpeed / validCount;
        if (avgSpeed <= 0) {
            return 0;
        }
        
        return Math.max(100, (long) (totalBytes / avgSpeed));
    }

    /**
     * 检查是否超过预测时间阈值
     * @param record 操作记录
     * @param currentTime 当前时间
     * @param elapsedTime 已用时间
     */
    private static void checkPredictionThreshold(OperationRecord record, long currentTime, long elapsedTime) {
        if (record.predictedTime <= 0) {
            return;
        }
        
        double timeRatio = (double) elapsedTime / record.predictedTime;
        
        if (timeRatio >= SEVERE_THRESHOLD) {
            // 严重警告，记录详细信息
            String logEntry = buildLogEntry(record, "OPERATION_TIME_SEVERE_WARNING", elapsedTime, 
                "实际时间超过预测时间100%，可能存在异常");
            LogManager.getLogger().severe(logEntry + " | " + getDetailedResourceInfo());
        } else if (timeRatio >= WARNING_THRESHOLD) {
            // 警告，记录信息
            String logEntry = buildLogEntry(record, "OPERATION_TIME_WARNING", elapsedTime, 
                "实际时间超过预测时间50%，请关注");
            LogManager.getLogger().warning(logEntry + " | " + getResourceUsageInfo());
        }
    }

    /**
     * 生成操作ID
     * @return 唯一的操作ID
     */
    private static String generateOperationId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 构建日志条目
     * @param record 操作记录
     * @param status 操作状态
     * @return 日志字符串
     */
    private static String buildLogEntry(OperationRecord record, String status) {
        return buildLogEntry(record, status, 0, "");
    }

    /**
     * 构建日志条目
     * @param record 操作记录
     * @param status 操作状态
     * @param elapsedTime 已用时间
     * @param message 附加信息
     * @return 日志字符串
     */
    private static String buildLogEntry(OperationRecord record, String status, long elapsedTime, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("OperationMonitor: [");
        sb.append("ID: ").append(record.operationId).append(", ");
        sb.append("Type: ").append(record.type.name()).append(", ");
        sb.append("Description: ").append(record.description).append(", ");
        sb.append("Status: ").append(status).append(", ");
        sb.append("StartTime: ").append(new SimpleDateFormat("HH:mm:ss.SSS").format(new Date(record.startTime))).append(", ");
        
        if (elapsedTime > 0) {
            sb.append("ElapsedTime: ").append(elapsedTime).append("ms, ");
            sb.append("PredictedTime: ").append(record.predictedTime).append("ms, ");
            if (record.predictedTime > 0) {
                sb.append("TimeRatio: ").append(String.format("%.2f", (double) elapsedTime / record.predictedTime)).append(", ");
            }
        }
        
        sb.append("Processed: ").append(LogManager.formatBytes(record.processedBytes)).append("/").append(LogManager.formatBytes(record.totalBytes)).append(", ");
        sb.append("Speed: ").append(LogManager.formatBytes((long) record.averageSpeed)).append("/s, ");
        
        if (!record.contextData.isEmpty()) {
            sb.append("Context: ").append(record.contextData).append(", ");
        }
        
        if (!message.isEmpty()) {
            sb.append("Message: ").append(message).append(", ");
        }
        
        // 移除末尾的逗号和空格
        if (sb.charAt(sb.length() - 2) == ',' && sb.charAt(sb.length() - 1) == ' ') {
            sb.setLength(sb.length() - 2);
        }
        
        sb.append("]");
        return sb.toString();
    }

    /**
     * 添加到历史记录
     * @param record 操作记录
     */
    private static void addToHistory(OperationRecord record) {
        synchronized (historyRecords) {
            historyRecords.add(record);
            // 限制历史记录数量
            if (historyRecords.size() > MAX_HISTORY_RECORDS) {
                historyRecords.remove(0);
            }
        }
    }

    /**
     * 获取资源使用情况
     * @return 资源使用情况字符串
     */
    private static String getResourceUsageInfo() {
        return ResourceMonitor.getResourceUsageInfo();
    }

    /**
     * 获取详细资源信息
     * @return 详细资源信息字符串
     */
    private static String getDetailedResourceInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("DetailedResources: [");
        
        // 内存使用情况
        MemoryUsage heapMemoryUsage = MEMORY_MX_BEAN.getHeapMemoryUsage();
        MemoryUsage nonHeapMemoryUsage = MEMORY_MX_BEAN.getNonHeapMemoryUsage();
        
        sb.append("Heap: ").append(LogManager.formatBytes(heapMemoryUsage.getUsed())).append("/").append(LogManager.formatBytes(heapMemoryUsage.getMax())).append(", ");
        sb.append("NonHeap: ").append(LogManager.formatBytes(nonHeapMemoryUsage.getUsed())).append("/").append(LogManager.formatBytes(nonHeapMemoryUsage.getMax())).append(", ");
        sb.append("Free: ").append(LogManager.formatBytes(RUNTIME.freeMemory())).append(", ");
        
        // 线程使用情况
        sb.append("Threads: ").append(THREAD_MX_BEAN.getThreadCount()).append("/").append(THREAD_MX_BEAN.getPeakThreadCount()).append(", ");
        sb.append("DaemonThreads: ").append(THREAD_MX_BEAN.getDaemonThreadCount()).append(", ");
        
        // CPU负载（如果支持）
        if (OS_MX_BEAN instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsMxBean = (com.sun.management.OperatingSystemMXBean) OS_MX_BEAN;
            double cpuLoad = sunOsMxBean.getSystemCpuLoad();
            if (cpuLoad >= 0.0) {
                sb.append("CPULoad: ").append(String.format("%.1f", cpuLoad * 100)).append("%").append(", ");
            } else {
                sb.append("CPULoad: N/A").append(", ");
            }
        }
        
        // 移除末尾的逗号和空格
        if (sb.charAt(sb.length() - 2) == ',' && sb.charAt(sb.length() - 1) == ' ') {
            sb.setLength(sb.length() - 2);
        }
        
        sb.append("]");
        return sb.toString();
    }

    /**
     * 获取详细错误信息
     * @return 详细错误信息字符串
     */
    private static String getDetailedErrorInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("ErrorDetails: [");
        sb.append("SystemTime: ").append(new Date()).append(", ");
        sb.append("ResourceInfo: ").append(getDetailedResourceInfo());
        sb.append("]");
        return sb.toString();
    }
}