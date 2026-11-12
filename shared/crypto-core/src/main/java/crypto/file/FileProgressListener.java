package crypto.file;

/**
 * 文件加解密进度回调。
 *
 * <p>加解密都在流上分块进行，回调按固定字节步长触发，避免大文件产生过多 UI 更新。</p>
 */
public interface FileProgressListener {

    enum Phase {
        SIGNING,
        ENCRYPTING,
        DECRYPTING
    }

    /**
     * @param phase     当前阶段
     * @param processed 当前阶段已处理字节
     * @param total     当前阶段总字节；0 表示空文件
     */
    void onProgress(Phase phase, long processed, long total);
}
