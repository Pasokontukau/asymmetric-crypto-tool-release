package securecomm.ui.worker;

import java.security.PrivateKey;
import java.security.PublicKey;
import javax.swing.JProgressBar;

import core.model.SignedAndEncryptedData;
import crypto.message.SecureMessage;
import securecomm.ui.util.WorkerCallback;

/**
 * 文本加密 Worker
 * 从 SecureTextPanel 提取为独立类
 * 输入：原文、当前密钥对名、对方公钥
 * 输出：SignedAndEncryptedData
 */
public class EncryptTextWorker extends AbstractCryptoWorker<SignedAndEncryptedData> {

    private final String plaintext;
    private final String myKeyPairName;
    private final PublicKey otherPublicKey;

    /**
     * 构造函数
     * @param plaintext 原文
     * @param myKeyPairName 当前密钥对名称（用于获取私钥签名）
     * @param otherPublicKey 对方公钥（已在面板中预先加载）
     * @param progressBar 进度条
     * @param callback 回调接口
     */
    public EncryptTextWorker(String plaintext, String myKeyPairName, PublicKey otherPublicKey,
                             JProgressBar progressBar, WorkerCallback<SignedAndEncryptedData> callback) {
        super(progressBar, callback);
        this.plaintext = plaintext;
        this.myKeyPairName = myKeyPairName;
        this.otherPublicKey = otherPublicKey;
    }

    @Override
    protected SignedAndEncryptedData doInBackground() throws Exception {
        // 步骤 1：加载私钥
        publishProgress(5, "加载私钥...");
        PrivateKey myPrivateKey = crypto.key.PrivateKeyAccessService.getPrivateKey(myKeyPairName);
        if (myPrivateKey == null) {
            return null;
        }

        // 步骤 2：公钥已由面板加载
        publishProgress(30, "加载公钥...");

        // 步骤 3：加密
        publishProgress(55, "执行加密...");
        publishProgress(70, "签名中...");
        SignedAndEncryptedData signedAndEncryptedData = SecureMessage.signAndEncryptText(
                plaintext, myPrivateKey, otherPublicKey);

        publishProgress(85, "处理完成...");
        publishProgress(100, "完成");
        return signedAndEncryptedData;
    }
}
