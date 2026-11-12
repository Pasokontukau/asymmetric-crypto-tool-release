package securecomm.ui.util;

/**
 * 进度更新信息包装类
 */
public class SecureTextProgressUpdate {
    public final int progress;
    public final String stepText;

    public SecureTextProgressUpdate(int progress, String stepText) {
        this.progress = progress;
        this.stepText = stepText;
    }
}
