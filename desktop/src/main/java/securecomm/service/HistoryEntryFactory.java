package securecomm.service;

/**
 * 批量结果文案与历史摘要的组装规则（解耦 DR-1）。
 *
 * <p>这些字符串原先内联在两个 Worker 的 {@code done()} 里，无法单独测试。
 * 这里保持与现有实现逐字一致：</p>
 * <ul>
 *   <li>加密：全成功 → "成功 N"；全失败 → "失败 N"；混合 → "成功 N，失败 M"。</li>
 *   <li>解密：在加密基础上还要区分"验证失败"（签名无效但解出了内容）。</li>
 *   <li>摘要：动作 + 数量，如"批量加密 3 个文件"。</li>
 * </ul>
 */
public final class HistoryEntryFactory {

    private HistoryEntryFactory() {
    }

    /** 批量操作摘要，例如"批量加密 3 个文件"。 */
    public static String batchSummary(String action, int fileCount) {
        return action + " " + fileCount + " 个文件";
    }

    /** 批量加密结果文案。 */
    public static String encryptBatchResult(int successCount, int failCount) {
        if (failCount == 0) {
            return "成功 " + successCount;
        }
        if (successCount == 0) {
            return "失败 " + failCount;
        }
        return "成功 " + successCount + "，失败 " + failCount;
    }

    /** 批量解密结果文案；签名无效但成功解出的计入 signatureFailCount。 */
    public static String decryptBatchResult(int successCount, int failCount, int signatureFailCount) {
        boolean hasSuccess = successCount > 0;
        boolean hasFail = failCount > 0;
        boolean hasSignatureFail = signatureFailCount > 0;

        if (!hasSuccess && !hasFail && !hasSignatureFail) {
            return "";
        }
        if (!hasFail && !hasSignatureFail) {
            return "成功 " + successCount;
        }
        if (!hasSuccess) {
            StringBuilder text = new StringBuilder();
            if (hasFail) {
                text.append("失败 ").append(failCount);
            }
            if (hasSignatureFail) {
                if (text.length() > 0) {
                    text.append("，");
                }
                text.append("验证失败 ").append(signatureFailCount);
            }
            return text.toString();
        }
        StringBuilder text = new StringBuilder("成功 ").append(successCount);
        if (hasFail) {
            text.append("，失败 ").append(failCount);
        }
        if (hasSignatureFail) {
            text.append("，验证失败 ").append(signatureFailCount);
        }
        return text.toString();
    }
}
