package app;

/**
 * 读取构建产物的版本号。
 *
 * 版本号的唯一来源是 `pom.xml` 的 `revision`，打包时由 maven-assembly-plugin 写进
 * jar 清单的 Implementation-Version；这里只读清单，不在代码里重复写死，避免「关于」页
 * 显示的版本和实际安装包不一致（此前写死过 1.0.0）。
 *
 * 源码提交同理：打包脚本用 `-Dgit.commit=...` 把它写进清单的 Implementation-Build。
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

    /**
     * 构建这份 jar 的源码提交（清单里的 Implementation-Build）。
     *
     * 不用 {@code Package#getImplementationBuild}：JDK 的 Package 没有这个方法，
     * 只有 Version/Vendor/Title 三个 Implementation 字段，所以直接读清单。
     * 清单不存在（直接跑 class 文件）时返回「未知」。
     */
    public static String build() {
        String commit = manifestValue("Implementation-Build");
        if (commit == null || commit.trim().isEmpty()) {
            return "未知";
        }
        return commit.trim();
    }

    /** 从本类所在 jar 的 MANIFEST.MF 读一个字段；读不到返回 null。 */
    private static String manifestValue(String key) {
        try (java.io.InputStream in = AppVersion.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (in == null) {
                return null;
            }
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder value = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(" ")) {  // 清单按 72 字节折行，续行以空格开头
                    if (value != null) {
                        value.append(line, 1, line.length());
                    }
                    continue;
                }
                if (value != null) {
                    return value.toString();  // 上一个字段结束
                }
                if (line.regionMatches(true, 0, key + ":", 0, key.length() + 1)) {
                    value = new StringBuilder(line.substring(key.length() + 1).trim());
                }
            }
            return value == null ? null : value.toString();
        } catch (java.io.IOException ex) {
            return null;
        }
    }
}
