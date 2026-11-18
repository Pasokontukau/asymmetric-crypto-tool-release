package securecomm.ui.worker;

import core.model.FileInfo;
import core.util.FileOperationErrors;
import core.util.FolderZipUtils;
import core.util.LogManager;
import crypto.file.FileCryptoEngine;
import crypto.key.PrivateKeyAccessService;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JProgressBar;
import javax.swing.SwingWorker;
import securecomm.service.FileTypeClassifier;
import securecomm.service.HistoryEntryFactory;
import securecomm.service.OutputNaming;
import securecomm.ui.util.AtomicFileOutput;
import securecomm.ui.util.EncryptedFileNaming;
import securecomm.ui.util.SecureTextProgressUpdate;
import securecomm.ui.util.SmoothProgressManager;
import securecomm.ui.util.TempFileManager;
import volumes.service.ExternalVolumeWriter;

/**
 * 批量加密：需要时先把多个文件打成临时 ZIP，再逐文件加密；超过阈值自动写外部加密分卷。
 * 迁移自 {@code SecureTextPanel.EncryptFilesWorker}（DR-2 步骤 2.4），逻辑、文案与进度保持不变。
 */
public class EncryptFilesWorker extends SwingWorker<List<FileInfo>, SecureTextProgressUpdate> {

    private final EncryptBatchHost host;
    private final JProgressBar progressBar;
    private final String myKeyPairName;
    private final String otherKeySelection;
    private final long startTime;
    private final boolean skipEncFiles;
    private final boolean packageFiles;
    private final String archiveRootName;
    private final int originalFileCount;

    private List<File> filesToEncrypt;
    private int successCount = 0;
    private int failCount = 0;
    private String firstErrorMessage;
    private final List<FileInfo> outputFiles = new ArrayList<FileInfo>();
    private File outputDirectory;
    private final List<FileInfo> inputFilesRecord = new ArrayList<FileInfo>();
    private final List<FileInfo> outputFilesRecord = new ArrayList<FileInfo>();
    private SmoothProgressManager smoothProgress;

    public EncryptFilesWorker(
            EncryptBatchHost host,
            JProgressBar progressBar,
            List<File> filesToEncrypt,
            String myKeyPairName,
            String otherKeySelection,
            long startTime,
            boolean skipEncFiles,
            boolean packageFiles,
            String archiveRootName
    ) {
        this.host = host;
        this.progressBar = progressBar;
        this.filesToEncrypt = filesToEncrypt;
        this.myKeyPairName = myKeyPairName;
        this.otherKeySelection = otherKeySelection;
        this.startTime = startTime;
        this.skipEncFiles = skipEncFiles;
        this.packageFiles = packageFiles;
        this.archiveRootName = archiveRootName;
        this.originalFileCount = filesToEncrypt.size();
    }

    @Override
    protected List<FileInfo> doInBackground() throws Exception {
        smoothProgress = new SmoothProgressManager(progressBar);

        publish(new SecureTextProgressUpdate(3, "正在初始化..."));

        String outputDir = settings.manager.SettingsManager.getDefaultOutputDirectory();
        if (outputDir == null || outputDir.isEmpty()) {
            outputDirectory = filesToEncrypt.get(0).getParentFile();
        } else {
            outputDirectory = new File(outputDir);
        }
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs();
        }

        PrivateKey myPrivateKey = PrivateKeyAccessService.getPrivateKey(myKeyPairName);
        if (myPrivateKey == null) {
            return null;
        }
        publish(new SecureTextProgressUpdate(20, "已加载私钥..."));

        publish(new SecureTextProgressUpdate(20, "正在加载公钥..."));
        PublicKey otherPublicKey = host.loadPublicKey(otherKeySelection);
        long volumeLimitBytes = settings.storage.SettingsStorage.getVolumeLimitBytes();

        File packagedZip = null;
        try {
            if (packageFiles) {
                List<File> members = new ArrayList<File>();
                for (File file : filesToEncrypt) {
                    if (skipEncFiles && FileTypeClassifier.isEncryptedFileName(file.getName())) {
                        continue;
                    }
                    members.add(file);
                }
                if (members.size() >= 2) {
                    File tempZip = File.createTempFile(
                            "aegix-archive-",
                            ".zip",
                            new File(System.getProperty("java.io.tmpdir")));
                    tempZip.deleteOnExit();
                    packagedZip = tempZip;
                    publish(new SecureTextProgressUpdate(10, "正在打包多个文件..."));
                    FolderZipUtils.zipFiles(members, tempZip, archiveRootName, null);
                    TempFileManager.registerArchiveZip(tempZip, archiveRootName + ".zip");
                    filesToEncrypt = Collections.singletonList(tempZip);
                }
            }

            int total = filesToEncrypt.size();
            for (int i = 0; i < total; i++) {
                File inputFile = filesToEncrypt.get(i);

                try {
                    if (skipEncFiles && FileTypeClassifier.isEncryptedFileName(inputFile.getName())) {
                        continue;
                    }

                    String originalFileName = inputFile.getName();
                    String archiveZipName = TempFileManager.getArchiveName(inputFile);
                    boolean isArchiveZip = archiveZipName != null;
                    boolean isFolderZip = TempFileManager.isFolderZip(inputFile);
                    String folderName = null;

                    if (isFolderZip) {
                        folderName = TempFileManager.resolveFolderName(inputFile);
                        originalFileName = folderName + ".folder";
                    } else if (isArchiveZip) {
                        originalFileName = archiveZipName;
                    }

                    // 与 Android 一致：首个文件不带序号，只有同一秒内重名才追加 _2、_3。
                    String baseName = OutputNaming.encryptedBaseName(java.time.LocalDateTime.now());

                    if (inputFile.length() > volumeLimitBytes) {
                        String archiveName = isFolderZip
                                ? (folderName + ".zip")
                                : (isArchiveZip ? archiveZipName : inputFile.getName());
                        boolean sourceIsZip = isFolderZip || isArchiveZip;
                        List<File> parts = ExternalVolumeWriter.write(
                                inputFile,
                                sourceIsZip,
                                archiveName,
                                isFolderZip,
                                null,
                                outputDirectory,
                                baseName,
                                myPrivateKey,
                                otherPublicKey,
                                volumeLimitBytes,
                                null
                        );
                        int partCount = parts.size();

                        FileInfo inFileInfo = new FileInfo(
                                isFolderZip
                                        ? (folderName + ".zip")
                                        : (isArchiveZip ? archiveZipName : inputFile.getName()),
                                inputFile.getAbsolutePath(),
                                inputFile.length(),
                                "成功"
                        );
                        inFileInfo.setOriginalFileName(isFolderZip
                                ? folderName
                                : (isArchiveZip ? archiveZipName : inputFile.getName()));
                        inFileInfo.setIsFolder(isFolderZip);
                        inFileInfo.setIsSplit(partCount > 1);
                        inFileInfo.setVolumeCount(partCount);
                        inputFilesRecord.add(inFileInfo);

                        for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
                            File part = parts.get(partIndex);
                            FileInfo outFileInfo = new FileInfo(
                                    part.getName(),
                                    part.getAbsolutePath(),
                                    part.length(),
                                    "成功"
                            );
                            outFileInfo.setOriginalFileName(archiveName);
                            outFileInfo.setSignatureValid(true);
                            outFileInfo.setIsSplit(partCount > 1);
                            outFileInfo.setVolumeCount(partCount);
                            outFileInfo.setCurrentVolume(partIndex + 1);
                            outputFilesRecord.add(outFileInfo);
                            outputFiles.add(outFileInfo);
                        }
                        successCount++;
                    } else {
                        String outputFileName = EncryptedFileNaming
                                .uniqueName(outputDirectory, baseName);
                        final String headerOriginalFileName = originalFileName;
                        File outputFile = AtomicFileOutput.write(
                                outputDirectory,
                                outputFileName,
                                encryptedOutput -> {
                                    try (InputStream signatureInput = new BufferedInputStream(
                                                 new FileInputStream(inputFile));
                                         InputStream plaintextInput = new BufferedInputStream(
                                                 new FileInputStream(inputFile))) {
                                        FileCryptoEngine.encrypt(
                                                signatureInput,
                                                plaintextInput,
                                                inputFile.length(),
                                                headerOriginalFileName,
                                                myPrivateKey,
                                                otherPublicKey,
                                                encryptedOutput,
                                                null,
                                                new AtomicBoolean(false)
                                        );
                                    }
                                });

                        FileInfo inFileInfo = new FileInfo(
                                isFolderZip
                                        ? (folderName + ".zip")
                                        : (isArchiveZip ? archiveZipName : inputFile.getName()),
                                inputFile.getAbsolutePath(),
                                inputFile.length(),
                                "成功"
                        );
                        inFileInfo.setOriginalFileName(isFolderZip
                                ? folderName
                                : (isArchiveZip ? archiveZipName : inputFile.getName()));
                        inFileInfo.setIsFolder(isFolderZip);

                        FileInfo outFileInfo = new FileInfo(
                                outputFile.getName(),
                                outputFile.getAbsolutePath(),
                                outputFile.length(),
                                "成功"
                        );
                        outFileInfo.setOriginalFileName(originalFileName);
                        outFileInfo.setSignatureValid(true);

                        inputFilesRecord.add(inFileInfo);
                        outputFilesRecord.add(outFileInfo);

                        outputFiles.add(outFileInfo);

                        successCount++;
                    }
                } catch (Exception e) {
                    LogManager.error("encrypt-file failed | bytes=" + inputFile.length(), e);
                    failCount++;
                    String errorMessage = FileOperationErrors.userMessage(e);
                    if (firstErrorMessage == null) {
                        firstErrorMessage = errorMessage;
                    }
                    FileInfo inFileInfo = new FileInfo(
                            inputFile.getName(),
                            inputFile.getAbsolutePath(),
                            inputFile.length(),
                            "失败",
                            errorMessage
                    );
                    inputFilesRecord.add(inFileInfo);
                }

                int baseProgress = 20;
                int progressPerFile = 75 / total;
                int progress = baseProgress + (i + 1) * progressPerFile;
                String fileName = OutputNaming.truncateForDisplay(inputFile.getName(), 20);
                publish(new SecureTextProgressUpdate(progress, "正在处理: " + fileName));
            }

            publish(new SecureTextProgressUpdate(100, "完成！"));
            return outputFiles;
        } finally {
            if (packagedZip != null) {
                TempFileManager.unregisterZipFile(packagedZip);
                FolderZipUtils.safeDelete(packagedZip);
            }
        }
    }

    @Override
    protected void process(List<SecureTextProgressUpdate> chunks) {
        for (SecureTextProgressUpdate update : chunks) {
            if (smoothProgress != null) {
                if (update.progress == 100) {
                    smoothProgress.setImmediateProgress(100, update.stepText);
                } else {
                    smoothProgress.setTargetProgress(update.progress, update.stepText);
                }
            } else {
                progressBar.setValue(update.progress);
                progressBar.setString(update.stepText);
            }
        }
    }

    @Override
    protected void done() {
        if (smoothProgress != null) {
            smoothProgress.stop();
        }
        try {
            get();

            host.showOutputs(outputFiles);

            progressBar.setString("批量加密完成！");

            String resultText = HistoryEntryFactory.encryptBatchResult(successCount, failCount);

            if (successCount == 0 && failCount > 0) {
                host.showResult("批量加密失败！"
                        + (firstErrorMessage == null ? "" : firstErrorMessage), true);
            } else {
                host.showResult("批量加密完成！" + resultText, failCount > 0);
            }

            String contactName = host.contactNameOrNull(otherKeySelection);

            host.recordHistory(
                    contactName,
                    resultText,
                    originalFileCount,
                    inputFilesRecord,
                    outputFilesRecord,
                    myKeyPairName,
                    System.currentTimeMillis() - startTime);
        } catch (Exception ex) {
            host.showResult("批量加密失败：" + FileOperationErrors.userMessage(ex), true);
            progressBar.setString("批量加密失败！");
            LogManager.error("批量加密失败", ex);
        } finally {
            host.afterBatch();
        }
    }
}
