package securecomm.service;

import core.model.FileInfo;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 齐套合并后的结果与历史记录组装（DR-3 步骤 3.4）。
 *
 * <p>规则集中在这里：“每个选中卷一条输入记录、产物一条输出记录”，卷数与验签状态
 * 跟着合并结果走；签名无效时逐卷标注，与普通解密路径的口径一致。
 * Worker 只负责把记录放进列表和历史，因此这些计数不必再去界面里点。</p>
 */
public final class MergedBatchRecords {

    private final List<FileInfo> inputs;
    private final FileInfo output;
    private final int partCount;
    private final boolean signatureValid;

    private MergedBatchRecords(List<FileInfo> inputs, FileInfo output,
                               int partCount, boolean signatureValid) {
        this.inputs = Collections.unmodifiableList(inputs);
        this.output = output;
        this.partCount = partCount;
        this.signatureValid = signatureValid;
    }

    /**
     * @param outcome 合并结果；产物是最终文件，不再解密
     * @param parts   用户选中的全部分卷（顺序不重要，只用于记输入）
     */
    public static MergedBatchRecords of(MergeOutcome outcome, List<File> parts) {
        File product = outcome.getOutput();
        String label = outcome.getArchiveName() == null
                ? product.getName() : outcome.getArchiveName();

        FileInfo output = new FileInfo(
                product.getName(), product.getAbsolutePath(), product.length(), "成功");
        output.setOriginalFileName(label);
        output.setSignatureValid(outcome.isSignatureValid());
        output.setIsSplit(true);
        output.setVolumeCount(outcome.getPartCount());

        List<FileInfo> inputs = new ArrayList<FileInfo>();
        List<File> selected = parts == null ? Collections.<File>emptyList() : parts;
        for (File part : selected) {
            FileInfo input = new FileInfo(
                    part.getName(), part.getAbsolutePath(), part.length(), "成功");
            input.setOriginalFileName(part.getName());
            input.setSignatureValid(outcome.isSignatureValid());
            input.setIsSplit(true);
            input.setVolumeCount(outcome.getPartCount());
            if (!outcome.isSignatureValid()) {
                input.setErrorMessage("签名无效");
            }
            inputs.add(input);
        }
        return new MergedBatchRecords(
                inputs, output, outcome.getPartCount(), outcome.isSignatureValid());
    }

    public List<FileInfo> getInputs() {
        return inputs;
    }

    public FileInfo getOutput() {
        return output;
    }

    /** 合并涉及的卷数，历史文案“已合并 N 卷”用它。 */
    public int getPartCount() {
        return partCount;
    }

    /** 逐卷验签结果；无效时输入记录已逐条标注。 */
    public boolean isSignatureValid() {
        return signatureValid;
    }
}
