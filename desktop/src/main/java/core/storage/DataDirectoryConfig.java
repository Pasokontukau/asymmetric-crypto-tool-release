package core.storage;

import settings.storage.SettingsStorage;

public class DataDirectoryConfig {

    private static String dataDir;

    private DataDirectoryConfig() {
    }

    /**
     * 获取当前数据目录的绝对路径。
     *
     * <p>用户显式配置的路径优先；相对路径按程序根目录解析；未配置时使用
     * 程序根目录下的 {@code data}。这样便携包从任意工作目录启动都不会漂移。</p>
     */
    public static String get() {
        if (dataDir == null) {
            dataDir = resolve(SettingsStorage.getDataDir());
        }
        return dataDir;
    }

    /**
     * 由 SettingsStorage 在数据目录变更时调用，更新缓存。
     */
    public static void update(String newDir) {
        dataDir = resolve(newDir);
    }

    private static String resolve(String configured) {
        if (configured == null || configured.trim().isEmpty()) {
            return AppPaths.defaultDataDir().toString();
        }
        return AppPaths.resolvePath(configured).toString();
    }
}
