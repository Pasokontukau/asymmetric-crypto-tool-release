package securecomm.ui.worker;

import core.model.FileInfo;
import java.util.List;

/**
 * 批量加密/解密共用的界面收尾（DR-2 复看后合并）：
 * 结果展示、状态标签、历史记录与联系人名解析对两个方向完全同形，只由适配器的
 * 构造参数区分方向，避免两份逐字重复的接口与适配器。
 */
public interface BatchHost {

    /** 把产物放进结果列表、刷新详情、切到文件结果卡并更新保存按钮。 */
    void showOutputs(List<FileInfo> outputs);

    /** 更新结果标签；{@code error=true} 时用错误色，否则用成功色。 */
    void showResult(String text, boolean error);

    /** 记一条批量历史（方向由实现决定）。 */
    void recordHistory(
            String contactName,
            String resultText,
            int fileCount,
            List<FileInfo> inputRecords,
            List<FileInfo> outputRecords,
            String keyPairName,
            long elapsedMs);

    /** 选择项是“联系人 - 名字”时返回名字，否则返回 {@code null}。 */
    String contactNameOrNull(String selection);
}
