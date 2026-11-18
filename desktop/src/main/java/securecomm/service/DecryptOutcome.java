package securecomm.service;

import java.io.File;

/** 解密结果：字段与 {@code FileDecryptService.Result} 一一对应，界面只读这里。 */
public final class DecryptOutcome {

    private final boolean success;
    private final File output;
    private final boolean folder;
    private final boolean signatureValid;
    private final String originalFileName;
    private final int volumeCount;
    private final String sha256Hash;
    private final boolean missingParts;
    private final int partCount;
    private final String errorMessage;

    private DecryptOutcome(boolean success, File output, boolean folder, boolean signatureValid,
                           String originalFileName, int volumeCount, String sha256Hash,
                           boolean missingParts, int partCount, String errorMessage) {
        this.success = success;
        this.output = output;
        this.folder = folder;
        this.signatureValid = signatureValid;
        this.originalFileName = originalFileName;
        this.volumeCount = volumeCount;
        this.sha256Hash = sha256Hash;
        this.missingParts = missingParts;
        this.partCount = partCount;
        this.errorMessage = errorMessage;
    }

    public static DecryptOutcome success(File output, boolean folder, boolean signatureValid,
                                         String originalFileName, int volumeCount, String sha256Hash) {
        return new DecryptOutcome(true, output, folder, signatureValid, originalFileName,
                volumeCount, sha256Hash, false, 0, null);
    }

    /** 分卷不齐：包是完整的，只是缺卷，调用方应提示“补选”。 */
    public static DecryptOutcome missingParts(int partCount, String errorMessage) {
        return new DecryptOutcome(false, null, false, false, null, 0, null, true, partCount, errorMessage);
    }

    public static DecryptOutcome failure(String errorMessage) {
        return new DecryptOutcome(false, null, false, false, null, 0, null, false, 0, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public File getOutput() {
        return output;
    }

    public boolean isFolder() {
        return folder;
    }

    public boolean isSignatureValid() {
        return signatureValid;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public boolean isSplit() {
        return volumeCount > 1;
    }

    public int getVolumeCount() {
        return volumeCount;
    }

    public String getSha256Hash() {
        return sha256Hash;
    }

    public boolean isMissingParts() {
        return missingParts;
    }

    public int getPartCount() {
        return partCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
