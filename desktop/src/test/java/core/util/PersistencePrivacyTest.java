package core.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 落盘面的静态隐私守卫。
 *
 * <p>桌面 UI 很大，临时 ZIP 和历史迁移都嵌在 Swing/存储类里，单测难以直接驱动；
 * 这里锁定住“临时名固定前缀”和“迁移后删旧明文”的调用形状，避免以后又用原始
 * 文件夹名做临时前缀，或迁移成功却把 {@code history.json} 留在数据目录。</p>
 */
public class PersistencePrivacyTest {

    @Test
    public void folderTempZipsUseFixedPrefixes() throws IOException {
        String ui = uiSources();
        String worker = source("src/main/java/securecomm/ui/worker/AddFilesWorker.java");
        String service = source("src/main/java/securecomm/service/FileDecryptService.java");

        assertFalse("临时 ZIP 前缀不能拼接原文件夹名",
                ui.contains("createTempFile(folder.getName()"));
        assertFalse("解密临时 ZIP 前缀不能拼接原始文件夹名",
                ui.contains("folderName + \"_\""));
        assertTrue("文件夹加密临时 ZIP 应使用固定前缀",
                worker.contains("\"aegix-folder-\""));
        assertTrue("文件夹解密使用固定前缀的暂存目录",
                service.contains("\"aegix-unzip-\""));
        assertTrue("文件夹解密先解到暂存目录再搬到目标目录",
                service.contains("FolderZipUtils.unzipFolder(zip, staging"));
    }

    @Test
    public void decryptTempFilesUseFixedPrefixes() throws IOException {
        String service = source("src/main/java/securecomm/service/FileDecryptService.java");

        assertTrue(service.contains("\"aegix-decrypt-\""));
        assertTrue(service.contains("\"volu-decrypt-\""));
    }

    @Test
    public void historyExportDefaultsToEncryptedContainer() throws IOException {
        String panel = source("src/main/java/history/ui/HistoryPanel.java");

        assertTrue("历史导出默认项应是加密格式",
                panel.contains("\"加密格式（推荐）\""));
        assertTrue("历史导出应通过 HistoryExport 写加密容器",
                source("src/main/java/history/repository/HistoryRepository.java")
                        .contains("HistoryExport.writeEncrypted"));
        assertTrue("历史页应调用仓库的加密导出",
                panel.contains("historyRepository.writeEncrypted"));
        assertTrue("明文导出前必须二次确认",
                panel.contains("明文导出提醒"));
    }

    @Test
    public void settingsExportDefaultsToEncryptedContainer() throws IOException {
        String panel = source("src/main/java/settings/ui/SettingsPanel.java");

        assertTrue("设置导出默认文件名应是 .aexp",
                panel.contains("settings.aexp"));
        assertTrue("设置导出应弹密码框",
                panel.contains("ExportPasswordDialog.ask"));
        assertTrue("设置导出应调用加密导出",
                panel.contains("exportSettings(exportFile, password)"));
    }

    @Test
    public void desktopUserFileOutputUsesAegixOnly() throws IOException {
        String writer = source("src/main/java/volumes/service/ExternalVolumeWriter.java");
        String ui = uiSources();
        String worker = source("src/main/java/securecomm/ui/worker/EncryptFilesWorker.java");

        assertTrue("分卷/单文件输出版本应统一 .aegix",
                writer.contains("EXTENSION = \".aegix\""));
        assertTrue("单文件加密输出走 .aegix 命名入口",
                worker.contains("EncryptedFileNaming"));
        assertTrue(".aegix 后缀定义在判型类里",
                source("src/main/java/securecomm/service/FileTypeClassifier.java")
                        .contains("ENCRYPTED_EXTENSION = \".aegix\""));
        assertTrue("命名类仍委托 EncryptedFileNaming",
                source("src/main/java/securecomm/service/OutputNaming.java")
                        .contains("EncryptedFileNaming.uniqueName"));
        assertFalse("桌面用户文件不能再生成 .enc",
                ui.contains("\".enc\""));
        assertFalse("桌面分卷文件名不能再使用 .enc",
                writer.contains("\".enc\""));
    }

    @Test
    public void smallTextFilesGoThroughAegixContainer() throws IOException {
        String panel = source("src/main/java/securecomm/ui/SecureTextPanel.java");
        String ui = uiSources();

        assertFalse("小 .txt 不能再被读进文本区包成 Base64",
                ui.contains("作为文本加载到输入区"));
        assertFalse("小 .txt 不能再走旧的文本加密分支",
                ui.contains("MAX_PLAINTEXT_BYTES"));
        assertFalse("小 .txt 不能再写 Base64 文本产物",
                ui.contains("decodeBase64(content)"));
        assertTrue("文件（含 .txt）一律进文件列表",
                panel.contains("addFilesToFileList(files);"));
    }

    @Test
    public void historyLegacyPlaintextIsDeletedWithoutReading() throws IOException {
        String storage = source("src/main/java/history/storage/HistoryStorage.java");

        assertFalse("旧历史不再迁移", storage.contains("migrateOldHistory"));
        assertTrue("旧明文 history.json 启动时直接删除",
                storage.contains("Files.deleteIfExists(oldPath)"));
        assertTrue("历史写入只能使用加密后的字节",
                storage.contains("Files.write(historyPath, encryptedData)"));
        assertFalse("历史存储不能写明文 JSON",
                storage.contains("Files.write(historyPath, json"));
        assertTrue("旧明文 .dat 不能被当作历史解析",
                storage.contains("不是当前加密格式"));
    }

    private static String source(String relative) throws IOException {
        File file = new File(projectRoot(), relative);
        if (!file.isFile()) {
            throw new IOException("找不到源码: " + relative + "（cwd=" + projectRoot() + "）");
        }
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * 整个 UI 层（面板 + 所有 Worker）的源码拼接。
     * 用“整层扫描”代替“逐个文件 contains”：Worker 再搬家时禁令不必跟着改。
     */
    private static String uiSources() throws IOException {
        StringBuilder text = new StringBuilder();
        File root = new File(projectRoot(), "src/main/java/securecomm/ui");
        java.util.List<java.nio.file.Path> paths = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> walk = Files.walk(root.toPath())) {
            walk.filter(path -> path.toString().endsWith(".java")).forEach(paths::add);
        }
        java.util.Collections.sort(paths);
        for (java.nio.file.Path path : paths) {
            text.append(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
                    .append('\n');
        }
        return text.toString();
    }

    private static File projectRoot() throws IOException {
        File cwd = new File(System.getProperty("user.dir", "."));
        for (File candidate : new File[]{cwd, new File(cwd, "desktop")}) {
            if (new File(candidate, "src/main/java").isDirectory()) {
                return candidate;
            }
        }
        throw new IOException("找不到 desktop 源码根（cwd=" + cwd + "）");
    }
}
