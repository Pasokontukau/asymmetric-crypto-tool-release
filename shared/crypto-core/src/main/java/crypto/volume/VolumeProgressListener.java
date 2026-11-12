package crypto.volume;

/**
 * VOLU 分卷解密进度。
 *
 * <p>用于让 UI 显示“第 i / N 卷”；[processed]/[total] 是整体明文字节进度，
 * 不是当前卷内部的进度。</p>
 */
public interface VolumeProgressListener {

    /**
     * @param volumeIndex 当前卷序号，1-based
     * @param volumeCount 总卷数
     * @param processed   已解出的总明文字节
     * @param total       VolumeInfo.originalFileSize；0 表示未知
     */
    void onVolumeProgress(int volumeIndex, int volumeCount, long processed, long total);
}
