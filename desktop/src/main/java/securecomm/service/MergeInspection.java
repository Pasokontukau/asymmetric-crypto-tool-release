package securecomm.service;

import volumes.service.ExternalPartMerger;

/**
 * 分卷检查结果（DR-3 步骤 3.3）：把 {@link ExternalPartMerger.Status} 与
 * {@link PartSetRouter} 的动作/原因包在一起，界面只按 {@code route} 分支，不自己映射状态。
 *
 * <p>只读元数据，不合并、不写盘；失败用 {@link #getErrorMessage()} 表达。
 * 路由与原因由状态现算，不另存一份，避免两处真值。</p>
 */
public final class MergeInspection {

    private final boolean success;
    private final ExternalPartMerger.Status status;
    private final String detail;
    private final String errorMessage;

    private MergeInspection(boolean success, ExternalPartMerger.Status status,
                            String detail, String errorMessage) {
        this.success = success;
        this.status = status;
        this.detail = detail;
        this.errorMessage = errorMessage;
    }

    /** 检查完成（读取元数据成功）；是否齐套看 {@link #getRoute()}。 */
    public static MergeInspection checked(ExternalPartMerger.Status status, String detail) {
        return new MergeInspection(true, status, detail, null);
    }

    /** 检查本身失败（私钥缺失、元数据不可读等）。 */
    public static MergeInspection failure(String message) {
        return new MergeInspection(false, null, null, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public ExternalPartMerger.Status getStatus() {
        return status;
    }

    /** 下一步动作；检查失败时为 {@code null}。 */
    public PartSetRouter.Route getRoute() {
        return success ? PartSetRouter.route(status) : null;
    }

    /** 需要提示的原因；检查失败时为 {@code null}。 */
    public PartSetRouter.Reason getReason() {
        return success ? PartSetRouter.reason(status) : null;
    }

    /** 合并器给出的说明，可直接展示或作为补选提示。 */
    public String getDetail() {
        return detail;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isMergeReady() {
        return success && getRoute() == PartSetRouter.Route.MERGE;
    }

    /** 缺卷或拒绝时需要用户重新选择。 */
    public boolean requiresReselection() {
        return success && PartSetRouter.requiresReselection(status);
    }
}
