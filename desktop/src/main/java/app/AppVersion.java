package app;

/**
 * 读取构建产物的版本号。
 *
 * 版本号的唯一来源是 `pom.xml` 的 `revision`，打包时由 maven-assembly-plugin 写进
 * jar 清单的 Implementation-Version；这里只读清单，不在代码里重复写死，避免「关于」页
 * 显示的版本和实际安装包不一致（此前写死过 1.0.0）。
 *
 * 直接以 class 文件运行（没有清单）或清单缺失时返回「未知」，不猜测版本号。
 */
public final class AppVersion {

    private AppVersion() {
    }

    public static String get() {
        Package pkg = AppVersion.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        if (version == null || version.trim().isEmpty()) {
            return "未知";
        }
        return version.trim();
    }
}
