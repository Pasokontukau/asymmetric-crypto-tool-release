package securecomm.ui.worker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.PrivateKey;
import java.security.PublicKey;
import javax.swing.JProgressBar;

import core.model.SignedAndEncryptedData;
import crypto.cipher.Decryptor;
import crypto.key.KeyUtils;
import crypto.signature.SignatureManager;
import securecomm.ui.util.DecryptResult;
import securecomm.ui.util.WorkerCallback;
import settings.manager.SettingsManager;

/**
 * 文本解密 Worker
 * 从 SecureTextPanel 提取为独立类
 * 输入：密文(base64)、当前密钥对名、对方公钥
 * 输出：DecryptResult（包含明文或输出文件、签名验证结果）
 */
public class DecryptTextWorker extends AbstractCryptoWorker<DecryptResult> {

    private final String ciphertextBase64;
    private final String myKeyPairName;
    private final PublicKey otherPublicKey;

    public DecryptTextWorker(String ciphertextBase64, String myKeyPairName, PublicKey otherPublicKey,
                             JProgressBar progressBar, WorkerCallback<DecryptResult> callback) {
        super(progressBar, callback);
        this.ciphertextBase64 = ciphertextBase64;
        this.myKeyPairName = myKeyPairName;
        this.otherPublicKey = otherPublicKey;
    }

    @Override
    protected DecryptResult doInBackground() throws Exception {
        // 步骤 1：加载私钥
        publishProgress(5, "加载私钥...");
        PrivateKey myPrivateKey = crypto.key.PrivateKeyAccessService.getPrivateKey(myKeyPairName);
        if (myPrivateKey == null) {
            return null;
        }

        // 步骤 2：公钥已由面板加载
        publishProgress(30, "加载公钥...");

        // 步骤 3：base64 解码
        publishProgress(50, "解码密文...");
        byte[] data = KeyUtils.decodeBase64(ciphertextBase64);

        // 步骤 4：从字节解析 SignedAndEncryptedData
        publishProgress(60, "解析加密数据...");
        SignedAndEncryptedData signedAndEncryptedData = SignedAndEncryptedData.fromBytes(data);

        // 步骤 5：解密
        publishProgress(70, "解密处理...");
        byte[] decryptedData = Decryptor.decryptData(signedAndEncryptedData.getEncryptedData(), myPrivateKey);

        // 步骤 6：验证签名
        publishProgress(80, "验证签名...");
        boolean signatureValid = SignatureManager.verifyBytes(
                decryptedData, signedAndEncryptedData.getSignature(), otherPublicKey);

        publishProgress(90, "处理结果...");
        DecryptResult result = new DecryptResult();
        result.signatureValid = signatureValid;

        // 步骤 7：判断是否为文件（有原始文件名则写入文件）
        if (signedAndEncryptedData.hasOriginalFileName()) {
            String outputDir = SettingsManager.getDefaultOutputDirectory();
            if (outputDir == null || outputDir.isEmpty()) {
                outputDir = System.getProperty("java.io.tmpdir");
            }
            java.io.File outputDirectory = new java.io.File(outputDir);
            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs();
            }
            String outputFileName = signedAndEncryptedData.getOriginalFileName(myPrivateKey);
            java.io.File outputFile = new java.io.File(outputDirectory, outputFileName);
            Files.write(outputFile.toPath(), decryptedData);
            publishProgress(100, "完成");
            result.isFile = true;
            result.outputFile = outputFile;
        } else {
            result.isFile = false;
            result.plaintext = new String(decryptedData, StandardCharsets.UTF_8);
            publishProgress(100, "完成");
        }
        return result;
    }
}
