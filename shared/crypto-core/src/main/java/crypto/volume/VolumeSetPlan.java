package crypto.volume;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 外部加密分卷（多文件打包后的 N 个 {@code .aegix}）的集合装配与校验。
 *
 * <p>接收侧只信卷内元数据，不信文件名与选择顺序：同一集合的每卷必须共享
 * {@code setId / partCount / archiveSha256 / archiveName / archiveSize}，
 * 且 {@code partIndex} 互不重复、覆盖 {@code 1..partCount}。</p>
 *
 * <p>本类只做判定与排序，不读文件、不写 ZIP；调用方在 {@link Status#COMPLETE}
 * 时才允许按 {@link Result#getOrderedParts()} 逐卷解密合并。</p>
 */
public final class VolumeSetPlan {

    /** 装配结果。 */
    public enum Status {
        /** 不是多卷集合：单卷包、旧 VOLU、或元数据不完整。 */
        NOT_A_SET,
        /** 全部卷到齐，可按 {@link Result#getOrderedParts()} 合并。 */
        COMPLETE,
        /** 同一集合但缺卷；{@link Result#getMissingIndexes()} 给出缺失序号。 */
        INCOMPLETE,
        /** 混入了别的集合（setId / 整包哈希 / 卷数 / 包名 / 大小 不一致）。 */
        MIXED,
        /** 同一序号出现两次。 */
        DUPLICATE,
        /** 序号越界（小于 1 或大于 partCount）。 */
        INVALID_INDEX
    }

    /** 装配结果，不可变。 */
    public static final class Result {
        private final Status status;
        private final List<VolumeInfo> orderedParts;
        private final List<Integer> missingIndexes;
        private final String detail;

        private Result(Status status, List<VolumeInfo> orderedParts,
                       List<Integer> missingIndexes, String detail) {
            this.status = status;
            this.orderedParts = Collections.unmodifiableList(orderedParts);
            this.missingIndexes = Collections.unmodifiableList(missingIndexes);
            this.detail = detail;
        }

        public Status getStatus() {
            return status;
        }

        /** 仅在 {@link Status#COMPLETE} 时非空，按 partIndex 升序。 */
        public List<VolumeInfo> getOrderedParts() {
            return orderedParts;
        }

        /** 仅在 {@link Status#INCOMPLETE} 时非空，升序。 */
        public List<Integer> getMissingIndexes() {
            return missingIndexes;
        }

        /** 面向日志/诊断的简短说明，不含密钥或文件名以外的隐私。 */
        public String getDetail() {
            return detail;
        }

        public boolean isComplete() {
            return status == Status.COMPLETE;
        }
    }

    private VolumeSetPlan() {
    }

    /** 该卷是否声明自己属于一个多卷集合（旧单卷与旧 VOLU 返回 false）。 */
    public static boolean isMultiPart(VolumeInfo info) {
        if (info == null) {
            return false;
        }
        Integer count = info.getPartCount();
        Integer index = info.getPartIndex();
        String setId = info.getSetId();
        return count != null && count > 1
                && index != null
                && setId != null && !setId.trim().isEmpty();
    }

    /**
     * 装配一组已经解析出元数据的卷。
     *
     * @param infos 解析成功的卷；顺序不重要，允许为空
     * @return 判定结果；只有 {@link Status#COMPLETE} 才可继续合并
     */
    public static Result plan(List<VolumeInfo> infos) {
        if (infos == null || infos.isEmpty()) {
            return fail(Status.NOT_A_SET, "没有可用的分卷信息");
        }
        for (VolumeInfo info : infos) {
            if (!isMultiPart(info)) {
                return fail(Status.NOT_A_SET, "存在单卷或旧格式文件，不属于多卷集合");
            }
        }

        VolumeInfo first = infos.get(0);
        int partCount = first.getPartCount();
        for (VolumeInfo info : infos) {
            if (info.getPartCount() != partCount) {
                return fail(Status.MIXED, "卷数不一致：期望 " + partCount + "，实际 " + info.getPartCount());
            }
            if (!Objects.equals(first.getSetId(), info.getSetId())) {
                return fail(Status.MIXED, "集合 ID 不一致，疑似混入了另一个包");
            }
            if (!Objects.equals(first.getArchiveSha256(), info.getArchiveSha256())) {
                return fail(Status.MIXED, "整包 SHA-256 不一致，疑似混入了另一个包");
            }
            if (!Objects.equals(first.getArchiveName(), info.getArchiveName())) {
                return fail(Status.MIXED, "包名不一致，疑似混入了另一个包");
            }
            if (!Objects.equals(first.getArchiveSize(), info.getArchiveSize())) {
                return fail(Status.MIXED, "整包大小不一致，疑似混入了另一个包");
            }
            if (first.getSourceIsArchive() != info.getSourceIsArchive()) {
                return fail(Status.MIXED, "来源类型不一致，疑似混入了文件分卷和归档分卷");
            }
            if (first.getArchiveSourceIsFolder() != info.getArchiveSourceIsFolder()) {
                return fail(Status.MIXED, "来源文件夹标记不一致，疑似混入了另一个包");
            }
            if (first.getSourceFileCount() != info.getSourceFileCount()) {
                return fail(Status.MIXED, "来源文件数不一致，疑似混入了另一个包");
            }
        }

        // partIndex 与 VOLU 元数据、VolumePartPlan、文件名一致，都是 1-based。
        VolumeInfo[] byIndex = new VolumeInfo[partCount + 1];
        for (VolumeInfo info : infos) {
            int index = info.getPartIndex();
            if (index < 1 || index > partCount) {
                return fail(Status.INVALID_INDEX,
                        "卷序号越界：" + index + "（应为 1.." + partCount + "）");
            }
            if (byIndex[index] != null) {
                return fail(Status.DUPLICATE, "第 " + index + " 卷出现了两次");
            }
            byIndex[index] = info;
        }

        List<Integer> missing = new ArrayList<>();
        List<VolumeInfo> ordered = new ArrayList<>(partCount);
        for (int i = 1; i <= partCount; i++) {
            if (byIndex[i] == null) {
                missing.add(i);
            } else {
                ordered.add(byIndex[i]);
            }
        }
        if (!missing.isEmpty()) {
            return new Result(Status.INCOMPLETE, ordered, missing,
                    "缺少 " + missing.size() + " 卷：" + formatMissing(missing));
        }
        return new Result(Status.COMPLETE, ordered, Collections.<Integer>emptyList(),
                "齐套，共 " + partCount + " 卷");
    }

    private static String formatMissing(List<Integer> missing) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('第').append(missing.get(i)).append('卷');
        }
        return sb.toString();
    }

    private static Result fail(Status status, String detail) {
        return new Result(status, Collections.<VolumeInfo>emptyList(),
                Collections.<Integer>emptyList(), detail);
    }

    /** 已到齐的卷数（用于“已收 7/9”这类提示）。 */
    public static int receivedCount(List<VolumeInfo> infos) {
        if (infos == null) {
            return 0;
        }
        Set<Integer> seen = new HashSet<>();
        for (VolumeInfo info : infos) {
            if (isMultiPart(info)) {
                seen.add(info.getPartIndex());
            }
        }
        return seen.size();
    }
}
