package securecomm.service;

import java.io.File;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次单文件加密的输入：文件名、密钥、输出目录与分卷阈值都是显式参数，
 * 不读设置、不碰 Swing，便于直接写测试。
 */
public final class EncryptRequest {

    public final File source;
    public final File outputDirectory;
    public final PrivateKey signingKey;
    public final PublicKey recipientKey;
    public final long volumeLimitBytes;
    /** 容器里记录的原文件名；为空时用 source 的文件名。 */
    public final String originalFileName;
    public final boolean sourceIsZip;
    public final boolean sourceIsFolder;
    public final Integer sourceFileCount;
    public final AtomicBoolean cancelled;
    public final ProgressSink progress;

    private EncryptRequest(
            File source,
            File outputDirectory,
            PrivateKey signingKey,
            PublicKey recipientKey,
            long volumeLimitBytes,
            String originalFileName,
            boolean sourceIsZip,
            boolean sourceIsFolder,
            Integer sourceFileCount,
            AtomicBoolean cancelled,
            ProgressSink progress
    ) {
        this.source = source;
        this.outputDirectory = outputDirectory;
        this.signingKey = signingKey;
        this.recipientKey = recipientKey;
        this.volumeLimitBytes = volumeLimitBytes;
        this.originalFileName = originalFileName;
        this.sourceIsZip = sourceIsZip;
        this.sourceIsFolder = sourceIsFolder;
        this.sourceFileCount = sourceFileCount;
        this.cancelled = cancelled;
        this.progress = progress;
    }

    /** 普通文件：按大小决定单文件容器还是外部加密分卷。 */
    public static EncryptRequest singleFile(
            File source,
            File outputDirectory,
            PrivateKey signingKey,
            PublicKey recipientKey,
            long volumeLimitBytes
    ) {
        return new EncryptRequest(source, outputDirectory, signingKey, recipientKey, volumeLimitBytes,
                null, false, false, null, null, null);
    }

    public EncryptRequest withOriginalFileName(String name) {
        return new EncryptRequest(source, outputDirectory, signingKey, recipientKey, volumeLimitBytes,
                name, sourceIsZip, sourceIsFolder, sourceFileCount, cancelled, progress);
    }

    public EncryptRequest withSourceFlags(boolean isZip, boolean isFolder, Integer fileCount) {
        return new EncryptRequest(source, outputDirectory, signingKey, recipientKey, volumeLimitBytes,
                originalFileName, isZip, isFolder, fileCount, cancelled, progress);
    }

    public EncryptRequest withCancellation(AtomicBoolean flag) {
        return new EncryptRequest(source, outputDirectory, signingKey, recipientKey, volumeLimitBytes,
                originalFileName, sourceIsZip, sourceIsFolder, sourceFileCount, flag, progress);
    }

    public EncryptRequest withProgress(ProgressSink sink) {
        return new EncryptRequest(source, outputDirectory, signingKey, recipientKey, volumeLimitBytes,
                originalFileName, sourceIsZip, sourceIsFolder, sourceFileCount, cancelled, sink);
    }
}
