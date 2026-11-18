package securecomm.service;

import volumes.service.ExternalPartMerger;

/**
 * 外部分卷集合的路由判定（解耦 DR-1）。
 *
 * <p>只把 {@link ExternalPartMerger.Status} 映射成"接下来做什么"，是纯函数：
 * 不读文件、不弹窗、不拼中文提示。提示文案由上层根据 {@link Reason} 选择，
 * 避免业务层直接写死界面文本。</p>
 */
public final class PartSetRouter {

    /** 上层应采取的动作。 */
    public enum Route {
        /** 齐套，合并成一个 ZIP。 */
        MERGE,
        /** 按单个 {@code .aegix} 走普通解密。 */
        DECRYPT_SINGLE,
        /** 缺卷，需要用户补选。 */
        NEED_MORE_PARTS,
        /** 拒绝：混包、重卷或卷号非法。 */
        REJECT
    }

    /** 需要提示的原因；{@link #NONE} 表示无需提示。 */
    public enum Reason {
        NONE,
        MISSING_PARTS,
        MIXED_SETS,
        DUPLICATE_VOLUMES,
        INVALID_VOLUME_NUMBER
    }

    private PartSetRouter() {
    }

    /** 判定动作。 */
    public static Route route(ExternalPartMerger.Status status) {
        if (status == null) {
            return Route.REJECT;
        }
        switch (status) {
            case COMPLETE:
                return Route.MERGE;
            case SINGLE_PART:
            case NOT_A_SET:
                return Route.DECRYPT_SINGLE;
            case INCOMPLETE:
                return Route.NEED_MORE_PARTS;
            case MIXED:
            case DUPLICATE:
            case INVALID_INDEX:
            default:
                return Route.REJECT;
        }
    }

    /** 对应的提示原因。 */
    public static Reason reason(ExternalPartMerger.Status status) {
        if (status == null) {
            return Reason.INVALID_VOLUME_NUMBER;
        }
        switch (status) {
            case INCOMPLETE:
                return Reason.MISSING_PARTS;
            case MIXED:
                return Reason.MIXED_SETS;
            case DUPLICATE:
                return Reason.DUPLICATE_VOLUMES;
            case INVALID_INDEX:
                return Reason.INVALID_VOLUME_NUMBER;
            default:
                return Reason.NONE;
        }
    }

    /** 是否需要用户重新选择（补选或换一组）。 */
    public static boolean requiresReselection(ExternalPartMerger.Status status) {
        Route route = route(status);
        return route == Route.NEED_MORE_PARTS || route == Route.REJECT;
    }
}
