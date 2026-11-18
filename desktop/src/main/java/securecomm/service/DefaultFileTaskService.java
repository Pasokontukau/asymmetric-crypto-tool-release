package securecomm.service;

import core.util.FileOperationErrors;
import core.util.LogManager;
import crypto.file.FileCryptoEngine;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import volumes.service.ExternalPartMerger;
import volumes.service.ExternalVolumeWriter;

/**
 * 默认实现：只做编排，密码学与写盘仍由共享内核和现有服务完成，行为与面板里的调用序列一致。
 * 命名规则沿用 DR-1 的 {@link OutputNaming} 与 {@code EncryptedFileNaming}。
 */
public class DefaultFileTaskService implements FileTaskService {

    @Override
    public EncryptOutcome encrypt(EncryptRequest request) {
        if (request == null || request.source == null || !request.source.isFile()) {
            return EncryptOutcome.failure(FileOperationErrors.userMessage(
                    new java.io.IOException("输入文件不存在或不是普通文件")));
        }
        if (request.outputDirectory == null) {
            return EncryptOutcome.failure("输出目录不能为空");
        }
        String originalFileName = request.originalFileName == null
                ? request.source.getName() : request.originalFileName;
        ProgressSink progress = request.progress;
        if (progress != null) {
            progress.stage(3, "正在初始化...");
        }
        try {
            File outputDirectory = request.outputDirectory;
            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs();
            }
            String baseName = OutputNaming.encryptedBaseName(LocalDateTime.now());

            if (request.volumeLimitBytes > 0 && request.source.length() > request.volumeLimitBytes) {
                List<File> parts = ExternalVolumeWriter.write(
                        request.source,
                        request.sourceIsZip,
                        originalFileName,
                        request.sourceIsFolder,
                        request.sourceFileCount,
                        outputDirectory,
                        baseName,
                        request.signingKey,
                        request.recipientKey,
                        request.volumeLimitBytes,
                        request.cancelled);
                if (progress != null) {
                    progress.stage(100, "完成！");
                }
                return EncryptOutcome.success(parts, originalFileName);
            }

            final String headerOriginalFileName = originalFileName;
            final File source = request.source;
            String outputFileName = securecomm.ui.util.EncryptedFileNaming
                    .uniqueName(outputDirectory, baseName);
            File output = securecomm.ui.util.AtomicFileOutput.write(
                    outputDirectory,
                    outputFileName,
                    encryptedOutput -> {
                        try (InputStream signatureInput = new BufferedInputStream(
                                     new FileInputStream(source));
                             InputStream plaintextInput = new BufferedInputStream(
                                     new FileInputStream(source))) {
                            FileCryptoEngine.encrypt(
                                    signatureInput,
                                    plaintextInput,
                                    source.length(),
                                    headerOriginalFileName,
                                    request.signingKey,
                                    request.recipientKey,
                                    encryptedOutput,
                                    null,
                                    request.cancelled == null
                                            ? new java.util.concurrent.atomic.AtomicBoolean(false)
                                            : request.cancelled);
                        }
                    });
            if (progress != null) {
                progress.stage(100, "完成！");
            }
            return EncryptOutcome.success(java.util.Collections.singletonList(output), originalFileName);
        } catch (Exception error) {
            LogManager.error("encrypt-file failed | bytes="
                    + (request.source == null ? -1 : request.source.length()), error);
            return EncryptOutcome.failure(FileOperationErrors.userMessage(error));
        }
    }

    @Override
    public DecryptOutcome decrypt(DecryptRequest request) {
        if (request == null || request.source == null || !request.source.isFile()) {
            return DecryptOutcome.failure(FileOperationErrors.userMessage(
                    new java.io.IOException("输入文件不存在或不是普通文件")));
        }
        if (request.privateKey == null) {
            return DecryptOutcome.failure("私钥不能为空");
        }
        ProgressSink progress = request.progress;
        if (progress != null) {
            progress.stage(3, "正在初始化...");
        }
        try {
            FileDecryptService.Result result = FileDecryptService.decrypt(
                    request.source,
                    request.privateKey,
                    request.senderCandidates,
                    request.outputDirectory,
                    null,
                    null,
                    request.cancelled);
            if (progress != null) {
                progress.stage(100, "已解密: " + request.source.getName());
            }
            return DecryptOutcome.success(
                    result.getOutput(),
                    result.isFolder(),
                    result.isSignatureValid(),
                    result.getOriginalFileName(),
                    result.getVolumeCount(),
                    result.getSha256Hash());
        } catch (FileDecryptService.ExternalPartsException missing) {
            return DecryptOutcome.missingParts(missing.getPartCount(),
                    PartSetPromptText.missingCount(missing.getPartCount()));
        } catch (Exception error) {
            LogManager.error("decrypt-file failed | bytes=" + request.source.length(), error);
            return DecryptOutcome.failure(FileOperationErrors.userMessage(error));
        }
    }

    @Override
    public BatchDecryptPreparation routeBatchDecrypt(List<File> selection, java.security.PrivateKey privateKey,
                                                       java.util.List<java.security.PublicKey> senderCandidates,
                                                       File outputDirectory, ProgressSink progress,
                                                       java.util.concurrent.atomic.AtomicBoolean cancelled) {
        java.util.List<File> files = selection == null
                ? java.util.Collections.<File>emptyList() : selection;
        if (files.isEmpty()) {
            return BatchDecryptPreparation.blocked(null, "请先选择文件");
        }
        java.util.List<File> parts = new java.util.ArrayList<File>();
        for (File file : files) {
            if (FileTypeClassifier.classify(file) == FileTypeClassifier.Kind.EXTERNAL_PART) {
                parts.add(file);
            }
        }
        if (parts.isEmpty()) {
            return BatchDecryptPreparation.decrypt();
        }
        if (parts.size() != files.size()) {
            return BatchDecryptPreparation.blocked(null, null);
        }
        MergeInspection inspection = inspectParts(parts, privateKey);
        if (!inspection.isSuccess()) {
            return BatchDecryptPreparation.blocked(inspection, inspection.getErrorMessage());
        }
        if (inspection.getRoute() == PartSetRouter.Route.MERGE) {
            MergeOutcome merged = mergeParts(
                    MergeRequest.of(parts, privateKey, senderCandidates, outputDirectory)
                            .withProgress(progress)
                            .withCancellation(cancelled));
            if (!merged.isSuccess()) {
                return BatchDecryptPreparation.blocked(inspection, merged.getErrorMessage());
            }
            return BatchDecryptPreparation.merged(merged, inspection);
        }
        if (inspection.getRoute() == PartSetRouter.Route.DECRYPT_SINGLE) {
            return BatchDecryptPreparation.decrypt();
        }
        return BatchDecryptPreparation.blocked(inspection, null);
    }

    @Override
    public MergeInspection inspectParts(List<File> parts, java.security.PrivateKey privateKey) {
        if (privateKey == null) {
            return MergeInspection.failure("私钥不能为空");
        }
        try {
            ExternalPartMerger.Inspection inspection = ExternalPartMerger.inspect(parts, privateKey);
            return MergeInspection.checked(inspection.getStatus(), inspection.getDetail());
        } catch (Exception error) {
            LogManager.error("inspect-parts failed | parts=" + (parts == null ? 0 : parts.size()), error);
            return MergeInspection.failure(FileOperationErrors.userMessage(error));
        }
    }

    @Override
    public MergeOutcome mergeParts(MergeRequest request) {
        if (request == null || request.parts.isEmpty()) {
            return MergeOutcome.failure(PartSetPromptText.noPartsSelected());
        }
        if (request.privateKey == null) {
            return MergeOutcome.failure("私钥不能为空");
        }
        final ProgressSink progress = request.progress;
        if (progress != null) {
            progress.stage(3, "正在检查分卷...");
        }
        final java.util.concurrent.atomic.AtomicBoolean progressWarned =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        ExternalPartMerger.ProgressListener listener = progress == null
                ? null
                : (partNumber, partCount, doneBytes, totalBytes) -> reportMergeProgress(
                        progress, progressWarned, partNumber, partCount, doneBytes, totalBytes);
        try {
            ExternalPartMerger.MergeResult result = ExternalPartMerger.merge(
                    request.parts,
                    request.privateKey,
                    request.senderCandidates,
                    request.outputDirectory,
                    request.temporaryName,
                    listener,
                    request.cancelled);
            if (progress != null) {
                progress.stage(100, "已完成合并");
            }
            return MergeOutcome.success(
                    result.getOutput(),
                    result.getPartCount(),
                    result.getArchiveSize(),
                    result.isSignatureValid(),
                    result.getArchiveName());
        } catch (Exception error) {
            LogManager.error("merge-parts failed | parts=" + request.parts.size(), error);
            return MergeOutcome.failure(FileOperationErrors.userMessage(error));
        }
    }

    /**
     * 合并进度百分比，始终落在 5–95：优先按字节，{@code totalBytes} 为 0 时退回按卷数，
     * 两者都不可用时停在 5。越界的完成量（负数或超过总量）夹在边界内，不抛错。
     */
    static int mergePercent(int partNumber, int partCount, long doneBytes, long totalBytes) {
        if (totalBytes > 0) {
            return clampPercent(5.0 + 90.0 * Math.max(0L, doneBytes) / totalBytes);
        }
        if (partCount > 0) {
            return clampPercent(5.0 + 90.0 * Math.max(0, partNumber) / partCount);
        }
        return 5;
    }

    private static int clampPercent(double percent) {
        return (int) Math.max(5L, Math.min(95L, Math.round(percent)));
    }

    /** 进度展示失败不能影响合并结果：只记一次警告，后续事件直接忽略。 */
    private static void reportMergeProgress(ProgressSink progress,
                                            java.util.concurrent.atomic.AtomicBoolean warned,
                                            int partNumber, int partCount,
                                            long doneBytes, long totalBytes) {
        try {
            progress.stage(
                    mergePercent(partNumber, partCount, doneBytes, totalBytes),
                    "正在合并分卷 " + partNumber + "/" + partCount);
        } catch (RuntimeException error) {
            if (!warned.getAndSet(true)) {
                LogManager.warning("分卷合并进度回调失败");
            }
        }
    }
}
