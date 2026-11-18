package securecomm.ui.worker;

import core.model.FileInfo;
import core.util.FileOperationErrors;
import core.util.FolderZipUtils;
import core.util.LogManager;
import crypto.key.PrivateKeyAccessService;
import java.io.File;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JProgressBar;
import javax.swing.SwingWorker;
import securecomm.service.BatchDecryptPreparation;
import securecomm.service.FileDecryptService;
import securecomm.service.FileTaskService;
import securecomm.service.MergeInspection;
import securecomm.service.MergeOutcome;
import securecomm.service.PartSetPromptText;
import securecomm.service.PartSetRouter;
import securecomm.service.ProgressSink;
import securecomm.service.HistoryEntryFactory;
import securecomm.service.OutputNaming;
import securecomm.ui.util.SecureTextProgressUpdate;
import securecomm.ui.util.SmoothProgressManager;

/**
 * 批量解密：逐文件走共享内核，单个失败不中断后续；进度与结果都交给 {@link DecryptBatchHost}。
 * 迁移自 {@code SecureTextPanel.DecryptFilesWorker}（DR-2 步骤 2.3），逻辑与文案保持不变。
 */
public class DecryptFilesWorker extends SwingWorker<List<FileInfo>, SecureTextProgressUpdate> {

    private final DecryptBatchHost host;
    private final JProgressBar progressBar;
    private final List<File> filesToDecrypt;
    private final String myKeyPairName;
    private final String otherKeySelection;
    private final long startTime;
    private final FileTaskService taskService;
    private final AtomicBoolean cancelled;

    private int decryptSuccessCount = 0;
    private int decryptFailCount = 0;
    private int signatureFailCount = 0;
    private String firstErrorMessage;
    private final List<FileInfo> outputFiles = new ArrayList<FileInfo>();
    private File outputDirectory;
    private final List<FileInfo> inputFilesRecord = new ArrayList<FileInfo>();
    private final List<FileInfo> outputFilesRecord = new ArrayList<FileInfo>();
    private SmoothProgressManager smoothProgress;
    /** 分卷不齐/拒绝时的说明；非空表示这批没有开始解密。 */
    private String blockedMessage;
    /** 齐套合并的卷数；0 表示这批没有走合并。 */
    private int mergedPartCount;
    /** 用户点了取消：停止后续文件，已完成的产物照常展示。 */
    private boolean cancelledRun;

    public DecryptFilesWorker(
            DecryptBatchHost host,
            JProgressBar progressBar,
            List<File> filesToDecrypt,
            String myKeyPairName,
            String otherKeySelection,
            long startTime,
            FileTaskService taskService,
            AtomicBoolean cancelled
    ) {
        this.host = host;
        this.progressBar = progressBar;
        this.filesToDecrypt = filesToDecrypt;
        this.myKeyPairName = myKeyPairName;
        this.otherKeySelection = otherKeySelection;
        this.startTime = startTime;
        this.taskService = taskService;
        this.cancelled = cancelled;
    }

    /** 单文件 .aegix 与单卷外部包统一走共享内核。 */
    private void decryptBinaryFile(
            File inputFile,
            PrivateKey privateKey,
            List<PublicKey> senderCandidates,
            File outputDir,
            int baseProgress,
            int progressRange
    ) throws Exception {
        FileDecryptService.Result result = FileDecryptService.decrypt(
                inputFile,
                privateKey,
                senderCandidates,
                outputDir,
                (phase, processed, total) -> {
                    int percent = total <= 0
                            ? 0
                            : (int) Math.min(100L, Math.max(0L, processed * 100L / total));
                    publish(new SecureTextProgressUpdate(
                            baseProgress + progressRange * percent / 100,
                            "正在解密: " + inputFile.getName()
                                    + " (" + LogManager.formatBytes(processed)
                                    + "/" + LogManager.formatBytes(total) + ")"));
                },
                (partNumber, partCount, processed, total) -> {
                    int percent = total <= 0
                            ? 0
                            : (int) Math.min(100L, Math.max(0L, processed * 100L / total));
                    publish(new SecureTextProgressUpdate(
                            baseProgress + progressRange * percent / 100,
                            "正在解密: " + inputFile.getName()
                                    + " [第 " + partNumber + "/" + partCount + " 卷, "
                                    + LogManager.formatBytes(processed)
                                    + "/" + LogManager.formatBytes(total) + "]"));
                },
                cancelled);

        File output = result.getOutput();
        long outputSize = result.isFolder()
                ? FolderZipUtils.calculateFolderSize(output)
                : output.length();

        FileInfo outFileInfo = new FileInfo(
                output.getName(),
                output.getAbsolutePath(),
                outputSize,
                "成功");
        outFileInfo.setOriginalFileName(result.getOriginalFileName());
        outFileInfo.setIsFolder(result.isFolder());
        outFileInfo.setSignatureValid(result.isSignatureValid());
        if (result.getVolumeCount() > 0) {
            outFileInfo.setIsSplit(true);
            outFileInfo.setVolumeCount(result.getVolumeCount());
        }
        if (result.getSha256Hash() != null) {
            outFileInfo.setSha256Hash(result.getSha256Hash());
        }

        FileInfo inFileInfo = new FileInfo(
                inputFile.getName(),
                inputFile.getAbsolutePath(),
                inputFile.length(),
                "成功");
        inFileInfo.setOriginalFileName(inputFile.getName());
        inFileInfo.setSignatureValid(result.isSignatureValid());
        if (result.getVolumeCount() > 0) {
            inFileInfo.setIsSplit(true);
            inFileInfo.setVolumeCount(result.getVolumeCount());
        }
        if (!result.isSignatureValid()) {
            inFileInfo.setErrorMessage("签名无效");
            signatureFailCount++;
        }

        inputFilesRecord.add(inFileInfo);
        outputFilesRecord.add(outFileInfo);
        outputFiles.add(outFileInfo);
        decryptSuccessCount++;
        publish(new SecureTextProgressUpdate(
                baseProgress + progressRange,
                "已解密: " + inputFile.getName()));
    }

    @Override
    protected List<FileInfo> doInBackground() throws Exception {
        smoothProgress = new SmoothProgressManager(progressBar);

        publish(new SecureTextProgressUpdate(3, "正在初始化..."));

        String outputDir = settings.manager.SettingsManager.getDefaultOutputDirectory();
        if (outputDir == null || outputDir.isEmpty()) {
            outputDirectory = filesToDecrypt.get(0).getParentFile();
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
        List<PublicKey> senderCandidates = host.resolveSenderCandidates(otherKeySelection);

        publish(new SecureTextProgressUpdate(20, "正在检查分卷..."));
        BatchDecryptPreparation preparation = taskService.routeBatchDecrypt(
                filesToDecrypt,
                myPrivateKey,
                senderCandidates,
                outputDirectory,
                new ProgressSink() {
                    @Override
                    public void stage(int percent, String messageKey, Object... args) {
                        publish(new SecureTextProgressUpdate(20 + percent * 75 / 100, messageKey));
                    }

                    @Override
                    public void done() {
                    }
                },
                cancelled);
        if (!preparation.canProceed()) {
            if (cancelRequested()) {
                cancelledRun = true;
                return Collections.emptyList();
            }
            String message = preparation.getErrorMessage();
            blockedMessage = message == null ? blockReason(preparation) : message;
            return Collections.emptyList();
        }
        if (preparation.getAction() == BatchDecryptPreparation.Action.MERGED) {
            return collectMergedOutput(preparation.getMerged());
        }

        int total = filesToDecrypt.size();
        for (int i = 0; i < total; i++) {
            File inputFile = filesToDecrypt.get(i);
            if (cancelRequested()) {
                cancelledRun = true;
                break;
            }
            final int fileBaseProgress = 20 + (i * 75 / total);
            final int fileProgressRange = Math.max(1, 75 / total);

            try {
                decryptBinaryFile(
                        inputFile,
                        myPrivateKey,
                        senderCandidates,
                        outputDirectory,
                        fileBaseProgress,
                        fileProgressRange);
            } catch (Exception error) {
                if (cancelRequested()) {
                    cancelledRun = true;
                    break;
                }
                LogManager.error("decrypt-file failed | bytes=" + inputFile.length(), error);
                decryptFailCount++;
                String errorMessage = FileOperationErrors.userMessage(error);
                if (firstErrorMessage == null) {
                    firstErrorMessage = errorMessage;
                }
                FileInfo inFileInfo = new FileInfo(
                        inputFile.getName(),
                        inputFile.getAbsolutePath(),
                        inputFile.length(),
                        "失败",
                        errorMessage,
                        null);
                inFileInfo.setOriginalFileName(inputFile.getName());
                inputFilesRecord.add(inFileInfo);
                publish(new SecureTextProgressUpdate(
                        fileBaseProgress + fileProgressRange,
                        "解密失败: " + inputFile.getName()));
            }

            int progress = 20 + (i + 1) * 75 / total;
            String fileName = OutputNaming.truncateForDisplay(inputFile.getName(), 20);
            publish(new SecureTextProgressUpdate(progress, "正在处理: " + fileName));
        }

        publish(new SecureTextProgressUpdate(100, "完成！"));
        return outputFiles;
    }

    /**
     * 齐套合并：合并过程已逐卷解密并写出原始文件，产物直接进结果列表，
     * 不再走 {@code decrypt}（对明文再解密会报“文件头不完整”）。
     */
    private List<FileInfo> collectMergedOutput(MergeOutcome outcome) {
        securecomm.service.MergedBatchRecords records =
                securecomm.service.MergedBatchRecords.of(outcome, filesToDecrypt);
        inputFilesRecord.addAll(records.getInputs());
        outputFilesRecord.add(records.getOutput());
        outputFiles.add(records.getOutput());
        mergedPartCount = records.getPartCount();
        if (!records.isSignatureValid()) {
            signatureFailCount++;
        }
        decryptSuccessCount++;
        publish(new SecureTextProgressUpdate(
                100, "已合并: " + records.getOutput().getOriginalFileName()));
        return outputFiles;
    }

    private boolean cancelRequested() {
        return cancelled != null && cancelled.get();
    }

    /** 分卷不齐/拒绝/混选时的一句话说明；中文文案只留在界面层。 */
    private static String blockReason(BatchDecryptPreparation preparation) {
        MergeInspection inspection = preparation.getInspection();
        if (inspection == null) {
            return PartSetPromptText.mixedSelection();
        }
        if (inspection.getRoute() == PartSetRouter.Route.NEED_MORE_PARTS) {
            return PartSetPromptText.missingParts(inspection.getDetail());
        }
        PartSetRouter.Reason reason = inspection.getReason();
        if (reason == PartSetRouter.Reason.MIXED_SETS) {
            return PartSetPromptText.mixedSets();
        }
        if (reason == PartSetRouter.Reason.DUPLICATE_VOLUMES) {
            return PartSetPromptText.duplicateVolumes();
        }
        if (reason == PartSetRouter.Reason.INVALID_VOLUME_NUMBER) {
            return PartSetPromptText.invalidVolumeNumber();
        }
        return PartSetPromptText.unmergeable();
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

            if (blockedMessage != null) {
                host.showResult(blockedMessage, true);
                progressBar.setString("批量解密未开始");
                return;
            }

            if (cancelledRun) {
                host.showOutputs(outputFiles);
                host.showResult("批量解密已取消", true);
                progressBar.setString("已取消");
                return;
            }

            host.showOutputs(outputFiles);

            progressBar.setString("批量解密完成！");

            String resultText = HistoryEntryFactory
                    .decryptBatchResult(decryptSuccessCount, decryptFailCount, signatureFailCount);
            if (mergedPartCount > 0) {
                resultText = "已合并 " + mergedPartCount + " 卷，" + resultText;
            }

            if (decryptSuccessCount == 0 && decryptFailCount > 0) {
                host.showResult("批量解密失败！"
                        + (firstErrorMessage == null ? "" : firstErrorMessage), true);
            } else {
                host.showResult("批量解密完成！" + resultText, decryptFailCount > 0);
            }

            String contactName = host.contactNameOrNull(otherKeySelection);

            host.recordHistory(
                    contactName,
                    resultText,
                    filesToDecrypt.size(),
                    inputFilesRecord,
                    outputFilesRecord,
                    myKeyPairName,
                    System.currentTimeMillis() - startTime);
        } catch (Exception ex) {
            host.showResult("批量解密失败：" + FileOperationErrors.userMessage(ex), true);
            progressBar.setString("批量解密失败！");
            LogManager.error("批量解密失败", ex);
        } finally {
            host.batchFinished();
        }
    }
}
