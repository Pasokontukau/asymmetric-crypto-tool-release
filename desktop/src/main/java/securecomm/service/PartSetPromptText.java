package securecomm.service;

/**
 * 外部分卷相关的用户提示（DR-3 3.5 后收敛）。
 *
 * <p>批量解密的分流提示、单文件解密的多卷异常提示与面板上的多卷操作指引都从这里取，
 * 避免同一句话在 {@code DecryptFilesWorker}、{@code FileDecryptService} 与
 * {@code SecureTextPanel} 各写一份、日后漂移。只拼字符串，不读文件、不依赖 Swing。</p>
 */
public final class PartSetPromptText {

    private PartSetPromptText() {
    }

    /** 分卷与普通文件混选：不允许，要求单独选择。 */
    public static String mixedSelection() {
        return "分卷请单独选择，不要和其它文件混在一起。";
    }

    /** 缺卷；{@code detail} 来自检查结果（例如“缺第 2 卷”）。 */
    public static String missingParts(String detail) {
        return "分卷不齐（" + detail + "），请补齐后重新解密。";
    }

    /** 混入了另一个分卷包。 */
    public static String mixedSets() {
        return "选中的分卷不属于同一个包，请只保留同一套分卷。";
    }

    /** 同一卷号被选了两次。 */
    public static String duplicateVolumes() {
        return "同一卷号被选择了两次，请移除重复文件。";
    }

    /** 卷号越界或非法。 */
    public static String invalidVolumeNumber() {
        return "卷号无效，请重新选择完整分卷。";
    }

    /** 其余拒绝原因（不应发生）的兜底。 */
    public static String unmergeable() {
        return "无法合并选中的分卷。";
    }

    /** 单个解密入口读到多卷集合中的一卷：不能单独解密，指向批量解密。 */
    public static String singleVolumeCannotDecrypt(Integer partIndex, int partCount) {
        String index = partIndex == null ? "未知" : String.valueOf(partIndex);
        return "这是外部分卷第 " + index + "/" + partCount
                + " 卷，不能单独解密；请把全部 " + partCount
                + " 卷一起选中后点「解密并验证」，批量解密会自动合并。";
    }

    /** 单文件解密捕获缺卷异常时的说明。 */
    public static String missingCount(int missingCount) {
        return "分卷不完整：还缺 " + missingCount + " 卷";
    }

    /** 合并入口没有收到任何分卷。 */
    public static String noPartsSelected() {
        return "请先选择分卷";
    }

    /** 文件页的操作指引：多卷要整组选中，再走批量入口。 */
    public static String volumeSelectionHint() {
        return "多卷外部包请把全部卷一起选中后点「解密并验证」";
    }
}
