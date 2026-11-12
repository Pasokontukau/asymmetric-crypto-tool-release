package core.util;

import core.util.LogManager;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;

public class ResourceMonitor {

    private static final Runtime RUNTIME = Runtime.getRuntime();
    private static final OperatingSystemMXBean OS_MX_BEAN = ManagementFactory.getOperatingSystemMXBean();
    private static final MemoryMXBean MEMORY_MX_BEAN = ManagementFactory.getMemoryMXBean();
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();

    /**
     * 获取系统信息
     * @return 系统信息字符串
     */
    public static String getSystemInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("OS: ").append(OS_MX_BEAN.getName()).append(" ")
          .append(OS_MX_BEAN.getVersion()).append(" ")
          .append(System.getProperty("os.arch")).append(", ");
        sb.append("AvailableProcessors: ").append(OS_MX_BEAN.getAvailableProcessors()).append(", ");
        sb.append("CPUUsage: ").append(getCpuUsage()).append(", ");
        sb.append("JavaVersion: ").append(System.getProperty("java.version")).append(", ");
        sb.append("JavaVendor: ").append(System.getProperty("java.vendor")).append(", ");
        sb.append("JavaHome: ").append(System.getProperty("java.home")).append(", ");
        sb.append("JvmName: ").append(System.getProperty("java.vm.name")).append(", ");
        sb.append("JvmVersion: ").append(System.getProperty("java.vm.version")).append(", ");
        sb.append("User: ").append(System.getProperty("user.name")).append(", ");
        sb.append("WorkingDir: ").append(System.getProperty("user.dir"));
        return sb.toString();
    }

    /**
     * 获取CPU使用率
     * @return CPU使用率百分比字符串
     */
    public static String getCpuUsage() {
        if (OS_MX_BEAN instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsMxBean = (com.sun.management.OperatingSystemMXBean) OS_MX_BEAN;
            double cpuLoad = sunOsMxBean.getSystemCpuLoad();
            // getSystemCpuLoad()返回-1.0表示不可用，需要处理
            if (cpuLoad >= 0.0) {
                return String.format("%.1f%%", cpuLoad * 100);
            } else {
                return "N/A";
            }
        }
        return "N/A";
    }

    /**
     * 获取系统资源使用情况
     * @return 资源使用情况字符串
     */
    public static String getResourceUsageInfo() {
        StringBuilder sb = new StringBuilder();

        // 内存使用情况 - 详细分区信息
        MemoryUsage heapMemoryUsage = MEMORY_MX_BEAN.getHeapMemoryUsage();
        MemoryUsage nonHeapMemoryUsage = MEMORY_MX_BEAN.getNonHeapMemoryUsage();

        sb.append("Heap: ")
          .append(LogManager.formatBytes(heapMemoryUsage.getUsed())).append("/")
          .append(LogManager.formatBytes(heapMemoryUsage.getMax())).append("(")
          .append(heapMemoryUsage.getMax() > 0 ? String.format("%.1f%%", (double) heapMemoryUsage.getUsed() / heapMemoryUsage.getMax() * 100) : "N/A").append("), ");
        sb.append("NonHeap: ")
          .append(LogManager.formatBytes(nonHeapMemoryUsage.getUsed())).append("/")
          .append(LogManager.formatBytes(nonHeapMemoryUsage.getMax())).append("(")
          .append(nonHeapMemoryUsage.getMax() > 0 ? String.format("%.1f%%", (double) nonHeapMemoryUsage.getUsed() / nonHeapMemoryUsage.getMax() * 100) : "N/A").append("), ");
        sb.append("Free: ").append(LogManager.formatBytes(RUNTIME.freeMemory())).append(", ");
        sb.append("Total: ").append(LogManager.formatBytes(RUNTIME.totalMemory())).append(", ");
        sb.append("Max: ").append(LogManager.formatBytes(RUNTIME.maxMemory())).append(", ");

        // 线程使用情况
        sb.append("Threads: ").append(THREAD_MX_BEAN.getThreadCount())
          .append("/").append(THREAD_MX_BEAN.getPeakThreadCount())
          .append("/").append(THREAD_MX_BEAN.getDaemonThreadCount()).append("(daemon), ");

        // GC信息
        sb.append("GC: ").append(getGcInfo()).append(", ");

        // CPU使用率
        sb.append("CPU: ").append(getCpuUsage());

        return sb.toString();
    }

    /**
     * 获取GC信息
     * @return GC信息字符串
     */
    public static String getGcInfo() {
        StringBuilder sb = new StringBuilder();

        // 获取所有GC收集器信息
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        for (int i = 0; i < gcBeans.size(); i++) {
            GarbageCollectorMXBean gcBean = gcBeans.get(i);
            if (i > 0) sb.append(" | ");
            sb.append(gcBean.getName()).append(": ")
              .append(gcBean.getCollectionCount()).append("次(")
              .append(LogManager.formatMilliseconds(gcBean.getCollectionTime())).append(")");
        }

        if (gcBeans.isEmpty()) {
            sb.append("N/A");
        }

        return sb.toString();
    }

    /**
     * 获取当前使用的内存
     * @return 当前使用的内存字节数
     */
    public static long getUsedMemory() {
        return RUNTIME.totalMemory() - RUNTIME.freeMemory();
    }

    /**
     * 获取最大可用内存
     * @return 最大可用内存字节数
     */
    public static long getMaxMemory() {
        return RUNTIME.maxMemory();
    }

    /**
     * 获取当前线程数
     * @return 当前线程数
     */
    public static int getThreadCount() {
        return THREAD_MX_BEAN.getThreadCount();
    }
}
