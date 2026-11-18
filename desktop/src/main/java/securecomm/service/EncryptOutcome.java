package securecomm.service;

import java.io.File;
import java.util.Collections;
import java.util.List;

/** 单文件加密的结果：成功时给出产物（可能多卷），失败时只带一句可展示的原因。 */
public final class EncryptOutcome {

    private final boolean success;
    private final List<File> outputs;
    private final String originalFileName;
    private final int volumeCount;
    private final String errorMessage;

    private EncryptOutcome(boolean success, List<File> outputs, String originalFileName,
                           int volumeCount, String errorMessage) {
        this.success = success;
        this.outputs = outputs == null ? Collections.<File>emptyList() : outputs;
        this.originalFileName = originalFileName;
        this.volumeCount = volumeCount;
        this.errorMessage = errorMessage;
    }

    public static EncryptOutcome success(List<File> outputs, String originalFileName) {
        List<File> copy = new java.util.ArrayList<File>(outputs);
        return new EncryptOutcome(true, copy, originalFileName, copy.size(), null);
    }

    public static EncryptOutcome failure(String errorMessage) {
        return new EncryptOutcome(false, null, null, 0, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public List<File> getOutputs() {
        return Collections.unmodifiableList(outputs);
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

    public String getErrorMessage() {
        return errorMessage;
    }
}
