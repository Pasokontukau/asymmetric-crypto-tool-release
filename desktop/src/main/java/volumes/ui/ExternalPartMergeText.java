package volumes.ui;

import core.util.LogManager;
import crypto.file.FileCancelledException;
import java.util.List;
import volumes.service.ExternalPartMerger;

/**
 * 外部加密分卷合并的界面文案。
 *
 * <p>只把 {@link ExternalPartMerger} 的状态翻译成用户能照着做的提示；
 * 不读文件、不写文件，便于在无 Swing 环境的单元测试里验证。</p>
 */
public final class ExternalPartMergeText {

    private ExternalPartMergeText() {
    }

    /** 检查结果的一句话说明。 */
    public static String inspection(ExternalPartMerger.Inspection inspection) {
        if (inspection == null) {
            return "尚未检查分卷。";
        }
        ExternalPartMerger.Status status = inspection.getStatus();
        switch (status) {
            case COMPLETE: {
                int partCount = inspection.getOrderedParts().isEmpty()
                        ? inspection.getParts().size()
                        : inspection.getOrderedParts().size();
                long archiveSize = 0L;
                if (!inspection.getOrderedParts().isEmpty()) {
                    Long size = inspection.getOrderedParts().get(0).getInfo().getArchiveSize();
                    if (size != null) {
                        archiveSize = size;
                    }
                }
                String sizeText = archiveSize > 0
                        ? "，整包 " + LogManager.formatBytes(archiveSize)
                        : "";
                return "齐套：共 " + partCount + " 卷" + sizeText + "，可以合并。";
            }
            case INCOMPLETE: {
                List<Integer> missing = inspection.getMissingIndexes();
                int received = inspection.getParts().size();
                int total = received + missing.size();
                return "缺卷：" + formatMissing(missing)
                        + "（已收 " + received + "/" + total + "）。请补齐后再合并。";
            }
            case MIXED:
                return "混包：选中的文件不属于同一个分卷包。请只保留同一套分卷后重试。";
            case DUPLICATE:
                return "重卷：同一个卷号被选择了两次。请移除重复文件后重试。";
            case INVALID_INDEX:
                return "卷号越界：" + inspection.getDetail() + "。请重新选择完整分卷。";
            case SINGLE_PART:
                return "这是单卷包（partCount=1），不需要合并；请直接用普通解密。";
            case NOT_A_SET:
            default:
                return "不是外部分卷集合：" + inspection.getDetail();
        }
    }

    /** 检查失败的提示；与合并失败区分，避免用户以为已经写了输出。 */
    public static String checkFailure(Throwable error) {
        if (isCancelled(error)) {
            return "已取消：没有写入任何文件。";
        }
        String message = error == null ? null : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "未知错误";
        }
        return "检查失败：" + message;
    }

    /** 合并失败的提示；取消单独说明，避免用户以为文件损坏。 */
    public static String failure(Throwable error) {
        if (isCancelled(error)) {
            return "已取消：没有保留半成品。";
        }
        if (error instanceof ExternalPartMerger.ExternalMergeException) {
            ExternalPartMerger.ExternalMergeException mergeError =
                    (ExternalPartMerger.ExternalMergeException) error;
            ExternalPartMerger.Status status = mergeError.getStatus();
            if (status == ExternalPartMerger.Status.INCOMPLETE) {
                return "缺卷：" + mergeError.getMessage();
            }
            if (status == ExternalPartMerger.Status.MIXED) {
                return "混包：" + mergeError.getMessage();
            }
            if (status == ExternalPartMerger.Status.DUPLICATE) {
                return "重卷：" + mergeError.getMessage();
            }
            if (status == ExternalPartMerger.Status.INVALID_INDEX) {
                return "卷号越界：" + mergeError.getMessage();
            }
        }
        String message = error == null ? null : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "未知错误";
        }
        return "合并失败：" + message;
    }

    /** 取消在异常链里被包装成 ExternalMergeException，需要沿 cause 查找。 */
    public static boolean isCancelled(Throwable error) {
        Throwable current = error;
        int guard = 0;
        while (current != null && guard < 16) {
            if (current instanceof FileCancelledException) {
                return true;
            }
            if ("已取消".equals(current.getMessage())) {
                return true;
            }
            current = current.getCause();
            guard++;
        }
        return false;
    }

    private static String formatMissing(List<Integer> missing) {
        if (missing == null || missing.isEmpty()) {
            return "缺失卷号未知";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < missing.size(); index++) {
            if (index > 0) {
                builder.append("、");
            }
            builder.append("第 ").append(missing.get(index)).append(" 卷");
        }
        return builder.toString();
    }
}
