package securecomm.service;

import java.io.File;

/**
 * 分卷合并结果；字段与 {@code ExternalPartMerger.MergeResult} 对应。
 * 合并过程会逐卷解密并还原原始文件（文件夹来源还原为 ZIP），产物已是最终文件，
 * 不再走 {@code decrypt}；{@link #isSignatureValid()} 是逐卷验签的结果。
 */
public final class MergeOutcome {

    private final boolean success;
    private final File output;
    private final int partCount;
    private final long archiveSize;
    private final boolean signatureValid;
    private final String archiveName;
    private final String errorMessage;

    private MergeOutcome(boolean success, File output, int partCount, long archiveSize,
                         boolean signatureValid, String archiveName, String errorMessage) {
        this.success = success;
        this.output = output;
        this.partCount = partCount;
        this.archiveSize = archiveSize;
        this.signatureValid = signatureValid;
        this.archiveName = archiveName;
        this.errorMessage = errorMessage;
    }

    public static MergeOutcome success(File output, int partCount, long archiveSize,
                                       boolean signatureValid, String archiveName) {
        return new MergeOutcome(true, output, partCount, archiveSize, signatureValid, archiveName, null);
    }

    public static MergeOutcome failure(String errorMessage) {
        return new MergeOutcome(false, null, 0, 0L, false, null, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public File getOutput() {
        return output;
    }

    public int getPartCount() {
        return partCount;
    }

    public long getArchiveSize() {
        return archiveSize;
    }

    public boolean isSignatureValid() {
        return signatureValid;
    }

    public String getArchiveName() {
        return archiveName;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
