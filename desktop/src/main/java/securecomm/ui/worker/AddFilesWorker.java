package securecomm.ui.worker;

import core.util.FileOperationErrors;
import core.util.FolderZipUtils;
import core.util.LogManager;
import core.util.TempFileRegistry;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JProgressBar;
import javax.swing.SwingWorker;
import securecomm.ui.util.SmoothProgressManager;
import securecomm.ui.util.TempFileManager;

/**
 * 添加文件：普通文件直接加入；文件夹先打成临时 ZIP 再作为单个条目加入。
 * 迁移自 {@code SecureTextPanel.AddFilesWorker}（DR-2 步骤 2.5），进度文案与行为保持不变。
 */
public class AddFilesWorker extends SwingWorker<List<Object>, Object[]> {

    private final AddFilesHost host;
    private final JProgressBar progressBar;
    private final List<File> normalFiles;
    private final List<File> foldersToZip;
    private SmoothProgressManager smoothProgress;

    public AddFilesWorker(
            AddFilesHost host,
            JProgressBar progressBar,
            List<File> normalFiles,
            List<File> foldersToZip
    ) {
        this.host = host;
        this.progressBar = progressBar;
        this.normalFiles = normalFiles;
        this.foldersToZip = foldersToZip;
    }

    @Override
    protected List<Object> doInBackground() throws Exception {
        smoothProgress = new SmoothProgressManager(progressBar);
        List<Object> processedFiles = new ArrayList<Object>();
        int totalFiles = normalFiles.size() + foldersToZip.size();
        int currentFileIndex = 0;

        // 1. 普通文件（可能已经在列表里）
        for (File file : normalFiles) {
            currentFileIndex++;
            final int finalCurrentFileIndex = currentFileIndex;
            final int finalTotalFiles = totalFiles;
            final String fileName = file.getName();
            publish(new Object[]{"start_file", fileName, finalCurrentFileIndex, finalTotalFiles});
            try {
                if (!host.isAlreadySelected(file)) {
                    processedFiles.add(file);
                    publish(new Object[]{"complete_file", fileName, finalCurrentFileIndex, finalTotalFiles,
                            finalCurrentFileIndex * 100 / finalTotalFiles});
                }
            } catch (Exception e) {
                LogManager.error("add-file failed | bytes=" + file.length(), e);
                publish(new Object[]{"error", fileName, e.getMessage()});
            }
        }

        // 2. 需要压缩的文件夹
        for (int i = 0; i < foldersToZip.size(); i++) {
            currentFileIndex++;
            final int finalCurrentFileIndex = currentFileIndex;
            final int finalTotalFiles = totalFiles;
            File folder = foldersToZip.get(i);
            final String folderName = folder.getName();
            publish(new Object[]{"start_folder", folderName, finalCurrentFileIndex, finalTotalFiles});
            File tempZipFile = null;
            try {
                tempZipFile = TempFileManager.createTempZip("aegix-folder-");
                tempZipFile.deleteOnExit();
                FolderZipUtils.zipFolder(folder, tempZipFile, (current, total, item) -> {
                    int folderProgress = (total > 0) ? (current * 100 / total) : 0;
                    int overallStep = (finalCurrentFileIndex - 1) * 100 / finalTotalFiles;
                    int fileProgress = folderProgress / finalTotalFiles;
                    publish(new Object[]{"progress_folder", folderName, finalCurrentFileIndex, finalTotalFiles,
                            overallStep + fileProgress, item, current, total});
                });
                TempFileManager.registerFolderZip(tempZipFile, folderName);
                processedFiles.add(tempZipFile);
                tempZipFile = null;
                publish(new Object[]{"complete_file", folderName + ".zip", finalCurrentFileIndex, finalTotalFiles,
                        finalCurrentFileIndex * 100 / finalTotalFiles});
            } catch (Exception e) {
                if (tempZipFile != null) {
                    TempFileRegistry.delete(tempZipFile);
                }
                LogManager.error("add-folder failed", e);
                publish(new Object[]{"error", folder.getName(), FileOperationErrors.userMessage(e)});
            }
        }
        return processedFiles;
    }

    @Override
    protected void process(List<Object[]> chunks) {
        for (Object[] chunk : chunks) {
            String type = (String) chunk[0];
            switch (type) {
                case "start_file": {
                    String fileName = (String) chunk[1];
                    int idx = (Integer) chunk[2];
                    int total = (Integer) chunk[3];
                    smoothProgress.setTargetProgress((idx - 1) * 100 / total,
                            "正在处理文件 [" + idx + "/" + total + "]: " + fileName);
                    host.showStatus("正在进行分卷处理中...", false);
                    break;
                }
                case "start_folder": {
                    String folderName = (String) chunk[1];
                    int idx = (Integer) chunk[2];
                    int total = (Integer) chunk[3];
                    smoothProgress.setTargetProgress((idx - 1) * 100 / total,
                            "正在处理文件夹 [" + idx + "/" + total + "]: " + folderName);
                    host.showStatus("正在进行分卷处理中...", false);
                    break;
                }
                case "progress_folder": {
                    String folderName = (String) chunk[1];
                    int idx = (Integer) chunk[2];
                    int total = (Integer) chunk[3];
                    int progress = (Integer) chunk[4];
                    String itemName = (String) chunk[5];
                    smoothProgress.setTargetProgress(progress,
                            "正在处理文件夹项目 [" + idx + "/" + total + "]: " + folderName + " - " + itemName);
                    break;
                }
                case "complete_file": {
                    String completeFile = (String) chunk[1];
                    int idx = (Integer) chunk[2];
                    int total = (Integer) chunk[3];
                    int progress = (Integer) chunk[4];
                    smoothProgress.setTargetProgress(progress,
                            "文件处理完成 [" + idx + "/" + total + "]: " + completeFile);
                    break;
                }
                case "error": {
                    String errFile = (String) chunk[1];
                    String errMsg = (String) chunk[2];
                    host.showStatus("文件处理失败: " + errFile + " - " + errMsg, true);
                    break;
                }
                default:
                    break;
            }
        }
    }

    @Override
    protected void done() {
        if (smoothProgress != null) {
            smoothProgress.stop();
        }
        try {
            List<Object> processedFiles = get();
            int addedCount = 0;
            for (Object processedObj : processedFiles) {
                if (processedObj instanceof File) {
                    File file = (File) processedObj;
                    if (!host.isAlreadySelected(file)) {
                        host.addSelectedFile(file);
                        addedCount++;
                    }
                }
            }
            host.finishAdd(addedCount);
        } catch (Exception e) {
            LogManager.error("文件处理失败", e);
            host.showStatus("文件处理失败: " + FileOperationErrors.userMessage(e), true);
        }
    }
}
