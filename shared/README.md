# shared/ —— 两端共享的上游

`crypto-core/` 是 Android 与桌面端**唯一**的加密内核源码，不属于任何一端。
它保留桌面端原始包名（`crypto.*`、`core.*`）与线格式，产物是 Java 8 字节码。

## 谁怎么用它

| 使用方 | 接入方式 |
|---|---|
| Android | `android/settings.gradle.kts` 把 `:crypto-core` 的 `projectDir` 重映射到这里；`app` 用 `implementation(project(":crypto-core"))` |
| 桌面端 | `desktop/pom.xml` 依赖 `com.cryptoassistant:crypto-core:1.4.0:desktop`；不复制源码、不引用 `../android` |
| 互操作测试 | `android/tools/desktop-bridge` 依赖 `:crypto-core`，把内核类嵌入 bridge jar |

## 常用命令

```bash
# Android + 内核测试（Gradle 根仍在 android/）
cd android
./gradlew -Dorg.gradle.jvmargs="-Xmx2048m -Dfile.encoding=GBK" :crypto-core:test

# 发布给桌面端（本机 Maven 仓库）
./gradlew -Dorg.gradle.jvmargs="-Xmx2048m -Dfile.encoding=GBK" :crypto-core:publishToMavenLocal

# 桌面端加载新内核后重新打包
cd ../desktop && mvn -B clean package
```

## 平台差异

`core/util/LogManager.java` 与 `core/util/OperationMonitor.java` 在这里是可移植的 no-op 默认实现，
Android 直接使用。桌面端有自己的真实实现（落盘日志、MXBean），因此 `desktop` classifier
会排除这两个默认类；不要把它换回默认 jar，否则桌面日志会静默失效。

## 注意

- 改线格式必须同时跑 `:crypto-core:test` 与 `desktop` 的 `mvn test`；夹具在
  `crypto-core/src/test/resources/`，两端共用。
- 桌面端依赖固定版本；改完内核先 `publishToMavenLocal`，否则桌面会继续用旧 artifact。
- 当前 `shared/` 还不是独立 Gradle 构建：monorepo 里由 `android/` 的 Gradle 根承载。
  拆成三个仓库时，需要给 `shared/` 补自己的 wrapper/settings，并把 artifact 发布到远端仓库。
