package securecomm.service;

/**
 * 批量解密前的分流结果（DR-3 步骤 3.4）。
 *
 * <p>只有三种出口：按原选择逐个解密、合并产物已就绪（合并即逐卷解密，不再二次解密）、
 * 或者先让用户补选/重选。界面按 {@link #getAction()} 分支，中文提示按
 * {@link #getInspection()} 的 route/reason 选择。</p>
 */
public final class BatchDecryptPreparation {

    /** 下一步动作。 */
    public enum Action {
        /** 按原选择逐个解密（普通 .aegix 或其它文件）。 */
        DECRYPT,
        /** 分卷齐套，合并产物已就绪，不再解密。 */
        MERGED,
        /** 缺卷/重卷/混包/混选，或检查、合并失败，不开始。 */
        BLOCKED
    }

    private final Action action;
    private final MergeOutcome merged;
    private final MergeInspection inspection;
    private final String errorMessage;

    private BatchDecryptPreparation(Action action, MergeOutcome merged,
                                    MergeInspection inspection, String errorMessage) {
        this.action = action;
        this.merged = merged;
        this.inspection = inspection;
        this.errorMessage = errorMessage;
    }

    /** 普通路径：没有分卷参与，按原选择逐个解密。 */
    public static BatchDecryptPreparation decrypt() {
        return new BatchDecryptPreparation(Action.DECRYPT, null, null, null);
    }

    /** 齐套：合并已经写出原始文件，产物直接进结果列表。 */
    public static BatchDecryptPreparation merged(MergeOutcome outcome, MergeInspection inspection) {
        return new BatchDecryptPreparation(Action.MERGED, outcome, inspection, null);
    }

    /** 不进入解密：缺卷、混包、重卷、卷号非法、混选，或检查/合并本身失败。 */
    public static BatchDecryptPreparation blocked(MergeInspection inspection,
                                                  String errorMessage) {
        return new BatchDecryptPreparation(Action.BLOCKED, null, inspection, errorMessage);
    }

    public Action getAction() {
        return action;
    }

    /** 是否可以继续（解密或直接采用合并产物）。 */
    public boolean canProceed() {
        return action != Action.BLOCKED;
    }

    /** 合并结果；只有 {@link Action#MERGED} 时非空，产物与卷数都从这里取。 */
    public MergeOutcome getMerged() {
        return merged;
    }

    /** 检查结果；没有分卷参与（含混选）时为 {@code null}。 */
    public MergeInspection getInspection() {
        return inspection;
    }

    /** 检查或合并失败的原始说明；需要界面自己选文案时为 {@code null}。 */
    public String getErrorMessage() {
        return errorMessage;
    }
}
