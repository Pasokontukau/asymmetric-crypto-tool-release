package securecomm.service;

/**
 * 一条待写入历史的不可变草稿。
 * 只承载“记什么”，不关心存储位置与序列化格式（格式仍由 {@code HistoryStorage} 决定）。
 */
public final class HistoryDraft {

    public final String operationType;
    public final String inputText;
    public final String outputText;
    public final String keypairName;
    public final String contactName;
    public final String fileInputPath;
    public final String fileOutputPath;
    public final String comment;
    public final Boolean isSplit;
    public final Integer totalVolumes;
    public final Integer currentVolume;

    private HistoryDraft(
            String operationType,
            String inputText,
            String outputText,
            String keypairName,
            String contactName,
            String fileInputPath,
            String fileOutputPath,
            String comment,
            Boolean isSplit,
            Integer totalVolumes,
            Integer currentVolume
    ) {
        this.operationType = operationType;
        this.inputText = inputText;
        this.outputText = outputText;
        this.keypairName = keypairName;
        this.contactName = contactName;
        this.fileInputPath = fileInputPath;
        this.fileOutputPath = fileOutputPath;
        this.comment = comment;
        this.isSplit = isSplit;
        this.totalVolumes = totalVolumes;
        this.currentVolume = currentVolume;
    }

    /** 文本加解密：只有一段输入/输出文字。 */
    public static HistoryDraft text(String operationType, String inputText, String outputText,
                                    String keypairName, String contactName) {
        return new HistoryDraft(operationType, inputText, outputText, keypairName, contactName,
                null, null, null, null, null, null);
    }

    /** 文件操作：输入/输出是路径；分卷时带上卷信息。 */
    public static HistoryDraft file(String operationType, String keypairName, String contactName,
                                    String inputPath, String outputPath,
                                    Boolean isSplit, Integer totalVolumes, Integer currentVolume) {
        return new HistoryDraft(operationType, null, null, keypairName, contactName,
                inputPath, outputPath, null, isSplit, totalVolumes, currentVolume);
    }
}
