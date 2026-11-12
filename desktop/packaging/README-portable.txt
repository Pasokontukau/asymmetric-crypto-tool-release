CryptoAssistant 便携版
=====================

1. 解压整个 CryptoAssistant 文件夹到任意位置（不要只复制 exe）。
2. 双击 CryptoAssistant.exe 启动；不需要安装 Java。
3. 数据、日志和输出默认保存在 exe 同级的 data/ 与 output/ 目录；常规设置保存在
   config/settings.properties，随便携包移动。
   如果程序目录不可写，或原设置的数据目录不可写，会回退到 %APPDATA%\CryptoAssistant，
   并在启动时弹窗提示；设置页和启动日志会写明实际路径。
4. 这是未签名的内部验收包。Windows SmartScreen 可能提示“未知发布者”，
   请确认文件来源后再选择“更多信息 → 仍要运行”。
5. 校验下载完整性：用 SHA-256 对比压缩包旁的 .sha256 文件。

注意：
- 不要删除 runtime/ 或 app/ 目录，否则程序无法启动。
- 本包不包含旧版本数据迁移；首次运行会在程序目录旁新建 data/。
- 不要把这个目录放进需要管理员权限才能写入的 Program Files；
  如果放了，程序会回退到用户目录。
