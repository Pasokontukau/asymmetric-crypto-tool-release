# 桌面端（Swing）

Java 17 + Swing 的桌面程序，Maven 构建。自 **2026-11-05** 起重新启用；Android 是事实标准，桌面端按[对齐规划](../docs/desktop/桌面端对齐安卓端规划.md)补齐文件格式、分卷与互操作。
>
> **2026-11-08 便携包阶段 1 已实施**：构建目标升到 Java 17；程序根目录、数据/输出目录、
> `messages.properties` 与重启命令已脱离 `user.dir`。
> **2026-11-09 阶段 2 已实施**：`package-portable.bat` 已产出 jlink + jpackage 便携 ZIP；
> 中文路径、非管理员已在本机矩阵覆盖；**无 Java 干净机验证已暂缓（2026-11-10）**；设置页重启冒烟已于 2026-11-10 在真实主窗口验过。
> **2026-11-10 行为对齐与真实 Swing 验收**：密钥备份、文件夹名、多文件归档、恶意 ZIP 拒绝 8/8；
> 修复文件夹解密双层目录，批量单文件序号与 Android 对齐；便携 ZIP 已按本轮代码重建。
> **2026-11-11 异常路径与重建**：路径过长、磁盘不足、输出目录只读、目标文件占用四条真实 Swing
> 验收通过；可用 `verify-error-paths.bat` 重跑；便携 ZIP 已按这批代码重建。
> **2026-11-11 便携冒烟与真实文件锁**：从最终 ZIP 解压启动真实 exe，主窗口、字号保存、数据目录
> 在 exe 旁与 DPAPI 均通过；`locked-file` 改用外部进程真实独占文件（非注入）。`verify.sh --build`
> 已接入五场景；产物与边界见[便携包规划 §15.10](../docs/desktop/桌面端便携包与运行时规划.md)。

> **对齐方向：Android 是事实标准，桌面端向它对齐。** 线格式、分卷、行为差异一律在 `desktop/` 侧修；不要为了适配桌面端去改 `android/`（除非 Android 本身有明确 bug 且用户同意）。

## 环境

| 项 | 要求 |
|---|---|
| JDK | **17**（构建 + 运行；`pom.xml` 使用 `maven.compiler.release=17`，不再承诺 Java 8） |
| Maven | 3.x，需在 `PATH` 中或用 `MAVEN_HOME` 指向 |
| 平台 | Windows 优先（DPAPI 相关类依赖 JNA 调用 Windows 接口） |

## 构建与运行

```bat
:: 仓库根的一键启动（推荐）
..\start-desktop.bat        :: app.jar 不存在或比源码旧则先构建，再启动
..\start-desktop.bat build  :: 强制重建后启动
..\start-desktop.bat run    :: 只启动现有产物，不检查新旧

cd desktop
build.bat     :: mvn clean package -DskipTests，成功后把 fat jar 复制为 app.jar
run.bat       :: 运行 app.jar；没有 app.jar 时退回 target\classes
run_debug.bat :: 调试启动（同样优先 app.jar）
```

### 便携包（阶段 2）

在 JDK 17 + Maven 环境下执行：

```bat
cd desktop
package-portable.bat
```

脚本会发布 `:crypto-core` 到 Maven Local、构建 fat jar、用 jlink 生成精简 JDK 17 运行时、
用 jpackage 生成 app-image，并输出：

```text
target\portable\CryptoAssistant-<version>-win64.zip
target\portable\CryptoAssistant-<version>-win64.zip.sha256
```

解压整个 `CryptoAssistant/` 后双击 `CryptoAssistant.exe`；不需要系统安装 Java。
包内 `runtime/` 是随包运行时，`app/` 是 fat jar，`data/` 与 `output/` 默认在 exe 旁创建。
这是未签名的便携包，SmartScreen 可能提示未知发布者；不做自动更新或旧数据迁移。

### 安装器

需要 JDK 17、Maven 3 与 **WiX Toolset 3**（jpackage 生成 EXE 安装器依赖它）：

```bat
cd desktop
package-installer.bat
```

产物：

```text
target\installer\CryptoAssistant-<version>.exe
target\installer\CryptoAssistant-<version>.exe.sha256
target\installer\README-installer.txt
```

安装器是 per-machine（需要管理员权限），默认装到 `C:\Program Files\CryptoAssistant`，
可创建开始菜单与桌面快捷方式。程序目录不可写，运行期数据回退到
`%APPDATA%\CryptoAssistant\data`，卸载不会删除该目录。

WiX 探测顺序：`%LOCALAPPDATA%\CryptoAssistant\build-tools\wix3`（便携二进制）、
`Program Files (x86)\WiX Toolset v3.14\bin`、v3.11、PATH 上的 `candle.exe`。

### 异常路径回归

在 Windows + JDK 17 + Maven 环境执行：

```bat
cd desktop
.\verify-error-paths.bat
```

脚本会构建当前 fat jar、编译 `tools/e2e/ErrorPathDriver.java`，再用真实主窗口依次跑：

| 模式 | 失败提示 |
|---|---|
| `long` | 文件名或路径过长 |
| `disk-full` | 磁盘空间不足 |
| `readonly` | 没有写入权限 |
| `file-in-use` | 目标文件正在被其他程序使用（注入移动前冲突） |
| `locked-file` | 目标文件正在被其他程序使用（外部进程真实锁） |

`readonly` 场景用 `icacls` 对测试输出目录临时拒绝当前用户写权限，跑完立即移除；
`disk-full` 与 `file-in-use` 用 `-Daegix.test.failWrite=...` 注入，不写真实用户目录；
`locked-file` 不注入，由另一个 PowerShell 进程以 `FileShare.None` 独占源文件，产生真实的
Windows 共享冲突。五个场景都要求红色提示且输出目录无 `.aegix`/`.tmp`；任一失败脚本返回非 0。
测试产物在 `target/e2e-error-paths/`，不会放进便携包。脚本不再执行 `mvn clean`，不会删掉
`target/portable` 里的便携 ZIP。

`tools/agent/verify.sh --build` 在 Windows 上会自动执行本脚本；只改文件写入/落盘代码时，
至少跑一遍 `verify.sh --build`，不要只跑 JVM 单测。

### 便携包冒烟

`verify-portable-smoke.ps1` 从 `target/portable/CryptoAssistant-<version>-win64.zip` 解压到
`%TEMP%`，启动真实 `CryptoAssistant.exe`，等真实主窗口出现后保存一次字号，并断言：

- `app.home` 与 `data` 都在 exe 旁，启动工作目录下没有 `data`；
- `data/settings.properties` 确实写入新字号；
- 随包运行时的 `WindowsDPAPI.isAvailable()` 为 true。

```powershell
cd desktop
powershell -NoProfile -ExecutionPolicy Bypass -File verify-portable-smoke.ps1
```

不要在仓库目录里直接运行解压后的 exe：本工作区是 Low 完整性级别，Low 进程不能写 `%TEMP%`，
JNA/DPAPI 会假失败（[踩坑 P-31](../docs/agent/pitfalls.md)）。脚本已固定解压到 `%TEMP%`。

`build.bat` / `run.bat` 支持传入 `nopause`，由根启动脚本使用以避免中途停顿；
`build.bat` 的 Maven 查找顺序为 `MAVEN_HOME` → `%USERPROFILE%\.trae-cn\tools\maven\latest\bin\mvn.cmd` → PATH。
根启动脚本固定从 `desktop/` 启动，开发时默认数据目录是 `desktop/data`；如果曾在设置里改过 `data.dir`，
实际位置以设置页和日志为准。便携包用 `-Dapp.home=$BINDIR` 把程序根目录传给 JVM，因此从任意工作目录
启动时，数据/输出仍落在程序目录旁。

> **2026-11-06 启动修复**：`AppMain` 曾设置 `sun.font.fontmanager=sun.font.SunFontManager`
> （抽象类），FlatLaf 初始化时 `FontManagerFactory` 会抛 `InstantiationException`，应用直接退出。
> 已移除该覆盖；当时 JDK 8/17 均可启动。2026-11-09 起桌面改用 Java 17 字节码，当前产物必须用 JDK 17 运行。排查记录见 [pitfalls P-25](../docs/agent/pitfalls.md)。
>
> **控制台编码（2026-11-12 起统一 UTF-8）**：批处理用 `chcp 65001`，JVM 以
> `-Dfile.encoding=UTF-8` 启动（含 jpackage 生成的启动器），日志由 `LogManager` 显式按 UTF-8 写。
> 控制台中文乱码但用 UTF-8 打开日志正常时，是显示层编码问题，不是日志损坏，见
> [pitfalls P-26](../docs/agent/pitfalls.md)。

等价的手工命令：

```bash
# monorepo 首次构建：先把共享内核发到本机 Maven 仓库
cd ../android
./gradlew :crypto-core:publishToMavenLocal
cd ../desktop
mvn clean package -DskipTests
java -jar target/asymmetric-encryption-tool-*-jar-with-dependencies.jar
```

monorepo 的内核源码在 `..\shared\crypto-core`，Gradle 根在 `..\android`；`build.bat` 检测到
`..\android\gradlew.bat` 时会自动执行上面的内核发布。独立桌面仓库没有这两个目录时跳过，
改为从远端 Maven 仓库解析同一固定版本。

构建产物 `target/`、`app.jar`、`bin/`、`lib/` 都是本地产物，已在 `.gitignore` 中，不要提交。

## 接收外部加密分卷

桌面没有独立的「分卷合并」标签页（该页已于 2026-11-12 删除）；多卷包统一走「安全通信」文件页的批量解密：

1. 选择接收方密钥对（会按安全等级提示密码）；
2. 在文件页添加同一套 `.aegix` 分卷（如 `_vol001.aegix`、`_vol002.aegix`）；
3. 文件页会先检查齐套；缺卷/混包/重卷会直接说明原因；
4. 齐套后按元数据输出原文件（单文件分卷）或 ZIP（文件夹/多文件分卷）到默认输出目录或手选的目录。

检查只读取每卷 VOLU 元数据；合并过程中可取消，失败或取消不会留下半成品 ZIP。
真实 Swing 手工矩阵已跑 **24/24**（2/3 卷、缺卷、重卷、混包、中途取消、只读输出目录）；
真实 Swing 点选、密码框、路径过长、磁盘不足、只读输出目录与文件占用已在 2026-11-10/11 验过，
可用 `verify-error-paths.bat` 重跑；真实超大文件仍未实测。

## 文件页加密与大文件分卷

“安全通信”文件页的批量加密对普通文件走共享内核 `FileCryptoEngine`；超过当前分卷大小时：

- 普通文件直接按偏移切片，不套 ZIP；接收方合并后直接还原原文件；
- 文件夹打包后的临时 ZIP 直接切片，合并后仍是 ZIP；
- 分卷大小在「设置 → 文件处理」里选，五档 **50 MB / 100 MB / 500 MB / 1 GB / 2 GB**，默认 **500 MB**，单文件与文件夹共用；
- 同一页的“多个文件默认打包为一个加密包”默认开启；文件页会按此预选，本次仍可取消。只对 2 个及以上普通文件生效，文件夹仍各自打包；
- 开始加密前会显示“将生成 N 卷”；多文件打包的卷数是保守估算，预计超过 20 卷时提示增大分卷大小或减少文件；
- 输出名为 `secure_file_<ts>_<setCode>_volNNN.aegix`；每卷 ≤ 2 GB、最多 99 卷；
- 接收方需在文件页的批量解密里选齐全部卷，再合并还原；
- Swing 手工点选与四条异常路径已实测；真实超大文件仍未实测，自动化覆盖小体积切片、合并回读与预览卷数一致。

## 文件页解密

「安全通信」文件页的二进制解密已切到共享内核：

- 单文件 `.aegix` 走 `crypto.file.FileCryptoEngine`；
- 当前外部分卷（VOLU）走 `crypto.volume.VolumeCryptoEngine`；单卷外部包输出 ZIP，不自动解包；
- 多卷外部包不能单独解密，文件页会提示整组一起批量解密；
- 输出先写临时文件，失败或取消不会留下半成品。

`build.bat` 检测到 `JAVA_HOME` 仍是 JDK 8 时会自动改用 `%USERPROFILE%\.jdks\corretto-17`；
该目录不存在时直接报错并提示设置 JDK 17，不会退回到 JDK 8 构建。

## 目录

| 路径 | 内容 |
|---|---|
| `src/main/java/app/` | 入口 `AppMain`、主窗口、主题与字体 |
| `src/main/java/core/` | 存储、日志、通用 UI 组件、校验与监控 |
| `src/main/java/crypto/key/` | 桌面平台密钥访问（`PrivateKeyAccessService`）；加解密内核在 `shared/crypto-core`，以固定版本 artifact 引入 |
| `src/main/java/securecomm/`、`contacts/`、`history/`、`keymanagement/`、`settings/` | 各功能面板与其存储 |
| `src/main/java/platform/windows/` | DPAPI 本地密钥保护 |
| `src/main/java/stegano/`、`volumes/` | 隐写与分卷容器；独立「分卷合并」页已删除，合并入口在文件页批量解密 |
| `src/main/resources/messages.properties` | 中文资源；`Messages` 从 classpath 按 UTF-8 加载 `/messages*.properties` |
| `packaging/` | exe4j 工程与图标（旧打包方式，不参与 Maven 构建） |
| `legacy/` | 旧的手工 fat-jar 流程，仅在复现历史构建时使用 |

## 数据与配置

- 数据目录默认是**程序根目录下的 `data/`**：开发时从 `desktop/` 启动即 `desktop/data`；便携包则是 exe 所在目录旁的 `data/`。内含 `keypairs/`、`contacts/`、`config/`、`settings.properties` 与 `logs/`。
- 常规设置（安全级别、分卷大小、多文件默认打包、数据目录等）写在**程序根目录的 `config/settings.properties`**，随便携包移动；不再写 Java Preferences。程序目录不可写时回退到 `%APPDATA%\CryptoAssistant\settings.properties`。
- 程序根目录优先取 `-Dapp.home`，其次取 `jpackage.app-path` 的父目录，开发环境回退到 `user.dir`。
- 程序目录不可写，或已配置的数据目录不可写时，回退到 `%APPDATA%\CryptoAssistant\data`（其它平台为 `~/.local/share/CryptoAssistant/data`），并在启动时弹窗提示；日志和设置页显示实际位置。
- 可在应用设置里改数据目录（写入 `config/settings.properties` 的 `data.dir`）；相对路径按程序根目录解析，改完重启后生效。
- `data/` 含本机密钥与联系人，**已在 `.gitignore` 中**，发布或提交前确认没有被加入索引。

## 已知限制

- 已有内核回归测试（`SharedCoreInteropTest`）与外部卷合并测试（`ExternalPartMergerTest`）：文字、单文件、当前外部分卷夹具、GCM1 私钥，以及齐套/缺卷/混包/重卷/篡改/取消（含中途）/单卷；旧 VOLU 样本已删除，旧格式不再作为可读路径。另有分卷提示文案（完整/缺卷/混包/取消）测试；`mvn test` 会运行。
- 桌面 Maven 依赖 `com.cryptoassistant:crypto-core:${crypto-core.version}:desktop`，不再复制源码；内核自带可移植的 no-op `LogManager`/`OperationMonitor` 默认类，`desktop` classifier 会排除它们，桌面自己的实现负责真实日志与监控。版本号在 `pom.xml` 与 `shared/crypto-core/build.gradle.kts` 两处同步。
- 本地构建依赖 `publishToMavenLocal`；新机器或 CI 必须先发布内核，或改为配置远端 Maven 仓库。D-C 修正了共享内核 P4 的 1-based 卷号不一致，属于 Android 自身 bug，不是为桌面改线格式。
- 不迁移旧版本数据目录：便携包首次运行会在程序目录旁新建 `data/`；按既定方向不背旧数据兼容。旧 exe4j 流程仍留在 `packaging/`，但不再作为分发方式。
- 阶段 2 已产出 `jlink`/`jpackage` 便携 ZIP；中文路径、非管理员、设置文件持久化、只读程序目录回退与设置页重启已在本机验过；**无 Java 干净机验证已暂缓（2026-11-10）**；安装器已于 2026-11-11 产出（`package-installer.bat`），并已实际安装（2026-11-12 另一台电脑、2026-11-22 本机复核安装/覆盖/卸载），见[便携包规划](../docs/desktop/桌面端便携包与运行时规划.md) §15.6–§15.9。
- 常规设置已改为 `config/settings.properties`，启动与改设置不再写注册表；`KeychainStorage` 的密码缓存仍使用 `java.util.prefs.Preferences`（DPAPI 加密），本次未迁移。只读程序目录 + `TEMP` 指向不可写位置时，JNA/FlatLaf 原生库临时解压曾报 `AccessDenied`；已于 2026-11-11 修复并复验（§15.8）。
- 文字、单文件、单卷 VOLU 与 GCM1 私钥已与 Android 同布局；外部加密分卷的**内核合并**（`ExternalPartMerger`）与**文件页批量解密入口**均已实现，缺卷/混包/重卷/取消会提示并清理临时文件。真实 Swing 手工矩阵 **24/24**，真实 Swing 点选与真实密码框另于 2026-11-10 验过（8/8）；路径过长、磁盘不足、输出目录只读与文件占用于 2026-11-11 验过（见 `verify-error-paths.bat`）；真实超大文件仍未实测。
- 单文件 `.aegix` 与当前单卷/多卷外部包已在文件页接入共享内核（`FileDecryptService`），服务层测试覆盖；真实 Swing 点选已于 2026-11-10 验过。旧 `VolumeManager`、旧 VOLU 模型与旧分卷写入已删除；桌面已对超过当前分卷大小的文件写当前外部分卷；分卷大小可在「设置 → 文件处理」选五档（默认 500 MB）。
- 仓库根 `jdk-25/` 已于 2026-11-05 删除；构建统一使用 JDK 17。

## 相关文档

- 加密内核与线格式：[`docs/desktop/protocol.md`](../docs/desktop/protocol.md)
- 桌面端对齐 Android 的范围与阶段：[`docs/desktop/桌面端对齐安卓端规划.md`](../docs/desktop/桌面端对齐安卓端规划.md)
- 桌面端解耦批次计划（DR-1…DR-4 已完成，剩所有者手点项）：[`docs/desktop/桌面端解耦批次计划.md`](../docs/desktop/桌面端解耦批次计划.md)
- 历史计划与设计：[`docs/archive/desktop/`](../docs/archive/desktop/)
- 重启桌面端的路线与决策：[`docs/repo-publication-plan.md`](../docs/repo-publication-plan.md)
