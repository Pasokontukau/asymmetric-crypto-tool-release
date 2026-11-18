package securecomm.ui.worker;

import java.security.PublicKey;

/**
 * 批量加密 Worker 的宿主接口：共用的收尾在 {@link BatchHost}，
 * 这里只加加密方向独有的两步。
 */
public interface EncryptBatchHost extends BatchHost {

    /** 面板现有的公钥选择逻辑；结果用于签名与加密，缺公钥时抛异常。 */
    PublicKey loadPublicKey(String selection) throws Exception;

    /** 收尾：把已处理的临时 ZIP 从输入列表移除，并刷新详情与按钮态。 */
    void afterBatch();
}
