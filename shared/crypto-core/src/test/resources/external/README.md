# 当前外部分卷夹具

这些文件是**当前外部分卷格式**（每卷一个单卷 VOLU）的固定样本，用于共享内核与桌面端的跨端读取测试。它们不是旧的“一个文件里装 N 段”的内部 VOLU。

- 只用于测试密钥与测试数据，不得用于真实数据。
- 夹具由共享内核 `VolumePartWriter` 生成；生成工具在 `android/tools/desktop-bridge`。
- 桌面端测试资源 `desktop/src/test/resources/external/` 是同一批字节的副本；更新后必须同步复制。
- 属性文件里的私钥是公开的测试密钥，不要替换成真实密钥。

## 文件

| 文件 | 内容 |
|---|---|
| `Photos.zip` | 多卷样本的原始 ZIP（3 个文件，未压缩） |
| `secure_file_20261107_120000_2f7c1a_vol001.aegix` 等 3 个 | 多卷样本，每卷独立解密后拼接 |
| `folder.zip` | 单卷文件夹样本的原始 ZIP |
| `secure_file_20261107_120000.aegix` | 单卷文件夹样本，保留文件夹语义 |
| `external-part-fixture.properties` | 测试密钥、整包名/大小/SHA-256、卷号、每卷明文大小 |

## 重新生成

在仓库根目录执行（需要 JDK 17）：

```bash
cd android
export JAVA_HOME="$HOME/.jdks/corretto-17"
./gradlew -Dorg.gradle.jvmargs="-Xmx2048m -Dfile.encoding=GBK" \
  :tools:desktop-bridge:generateExternalPartFixture
```

然后把本目录下除 `README.md` 外的文件复制到 `desktop/src/test/resources/external/`。两端测试必须同时跑：

```bash
./gradlew :crypto-core:test
cd ../desktop && mvn -B test
```

夹具的字节布局见 [`docs/加密文件格式规范.md`](../../../../../../docs/加密文件格式规范.md)。
