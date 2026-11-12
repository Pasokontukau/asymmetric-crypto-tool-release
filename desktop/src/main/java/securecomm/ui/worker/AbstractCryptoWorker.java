package securecomm.ui.worker;

import java.util.List;
import javax.swing.JProgressBar;
import javax.swing.SwingWorker;

import securecomm.ui.util.SmoothProgressManager;
import securecomm.ui.util.SecureTextProgressUpdate;
import securecomm.ui.util.WorkerCallback;

/**
 * Worker 基类：统一进度管理和回调处理
 * 所有后台加密/解密任务都应继承此类
 */
public abstract class AbstractCryptoWorker<T> extends SwingWorker<T, SecureTextProgressUpdate> {

    protected final SmoothProgressManager progressManager;
    protected final WorkerCallback<T> callback;

    /**
     * 构造函数
     * @param progressBar 进度条组件，可为 null（表示不需要进度显示）
     * @param callback 任务回调接口
     */
    protected AbstractCryptoWorker(JProgressBar progressBar, WorkerCallback<T> callback) {
        this.progressManager = (progressBar != null) ? new SmoothProgressManager(progressBar) : null;
        this.callback = callback;
    }

    /**
     * 便捷方法：发布进度更新
     */
    protected void publishProgress(int progress, String stepText) {
        publish(new SecureTextProgressUpdate(progress, stepText));
    }

    /**
     * 处理进度更新（在 EDT 线程执行）
     */
    @Override
    protected void process(List<SecureTextProgressUpdate> chunks) {
        if (progressManager != null && chunks != null && !chunks.isEmpty()) {
            // 只处理最新的一条更新，避免 UI 闪烁
            SecureTextProgressUpdate latest = chunks.get(chunks.size() - 1);
            if (latest.progress == 100) {
                progressManager.setImmediateProgress(100, latest.stepText);
            } else {
                progressManager.setTargetProgress(latest.progress, latest.stepText);
            }
        }
    }

    /**
     * 任务完成处理（在 EDT 线程执行）
     */
    @Override
    protected void done() {
        // 停止平滑动画
        if (progressManager != null) {
            progressManager.stop();
        }

        try {
            T result = get();
            if (callback != null) {
                callback.onComplete(result);
            }
        } catch (Exception ex) {
            if (callback != null) {
                callback.onError(ex);
            }
        }
    }
}
