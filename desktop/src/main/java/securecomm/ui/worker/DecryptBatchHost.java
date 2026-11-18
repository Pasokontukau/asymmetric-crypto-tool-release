package securecomm.ui.worker;

import java.security.PublicKey;
import java.util.List;

/**
 * 批量解密 Worker 的宿主接口：共用的收尾在 {@link BatchHost}，
 * 这里只加解密方向独有的“发送方候选”。
 */
public interface DecryptBatchHost extends BatchHost {

    /**
     * 解析“发送方候选公钥”。面板会先做原有公钥选择（保留其弹框/报错副作用），
     * 再返回候选列表；缺公钥时抛异常，由 Worker 的失败分支统一展示。
     */
    List<PublicKey> resolveSenderCandidates(String selection) throws Exception;

    /** 批量任务结束（成功、失败、取消都会调用）：界面恢复默认状态。 */
    void batchFinished();
}
