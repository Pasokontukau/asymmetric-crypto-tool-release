# 当前单文件 `.aegix` 夹具

这些文件钉住**当前单文件容器**（不是 VOLU 外部分卷）的字节布局与解密结果，供共享内核、Android 单元测试与桌面端读取同一批字节。它们不是旧的 `.enc`/GCM1 格式。

- 只包含公开测试密钥与测试数据，不得用于真实数据。
- 单文件容器当前**没有魔数与版本字段**：第一项是签名长度，随后依次是签名、文件名 IV、加密文件名、RSA 加密的 AES 密钥、AES IV、密文长度与密文。因此“未来版本拒绝”只能在有版本字段的 VOLU 容器上验证（`ExternalPartFixtureTest` 会把版本改成 2 并断言 `UnsupportedVolumeVersionException`）。
- 夹具由共享内核 `FileCryptoEngine` 生成，生成入口是 `android/tools/desktop-bridge` 的 `generateSingleFileFixture`。
- 桌面端与 Android 端各有一份字节副本；更新时必须同步复制。

## 文件

| 文件 | 内容 |
|---|---|
| `secure_file_20261111_120000.aegix` | 单文件密文，明文是 UTF-8 文本，原始名为 `季度 报告.txt` |
| `single-file-fixture.properties` | 测试密钥、明文 Base64/大小/SHA-256、密文大小/SHA-256、签名长度（RSA 4096 → 512 字节）、原始名 |

## 重新生成

在仓库根目录执行（需要 JDK 17）：

```bash
cd android
export JAVA_HOME="$HOME/.jdks/corretto-17"
./gradlew -Dorg.gradle.jvmargs="-Xmx2048m -Dfile.encoding=GBK" \
  :tools:desktop-bridge:generateSingleFileFixture
```

然后把 `shared/crypto-core/src/test/resources/single/` 下的两个产物复制到：

- `desktop/src/test/resources/single/`
- `android/app/src/test/resources/single/`

每次重新生成都会换一套公开测试密钥。两端测试必须同时跑：

```bash
cd android && ./gradlew :crypto-core:test :app:testDebugUnitTest
cd ../desktop && mvn -B test
```

夹具的字节布局见 [`docs/加密文件格式规范.md`](../../../../../../docs/加密文件格式规范.md)。
