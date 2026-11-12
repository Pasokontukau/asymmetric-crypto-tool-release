# 桌面端当前外部分卷夹具

本目录是 `shared/crypto-core/src/test/resources/external/` 的副本，供桌面 Maven 测试独立运行。

- 只用于测试密钥与测试数据，不得用于真实数据。
- 来源与重新生成方法见共享目录的 [`README.md`](../../../../../shared/crypto-core/src/test/resources/external/README.md)。
- 共享内核更新夹具后，必须同步复制到这里；不要只改一端。
- 桌面测试读取同一批字节，验证 `ExternalPartMerger` 能合并当前外部分卷、`FileDecryptService` 能处理单卷文件夹包。
