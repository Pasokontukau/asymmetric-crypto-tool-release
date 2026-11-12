package securecomm.ui.util;

/**
 * Worker 任务回调接口，将 Worker 与面板解耦
 */
public interface WorkerCallback<T> {
    /**
     * 进度更新
     * @param progress 进度值 (0-100)
     * @param stepText 步骤说明文本
     */
    void onProgress(int progress, String stepText);

    /**
     * 任务完成，返回结果
     * @param result 执行结果
     */
    void onComplete(T result);

    /**
     * 任务失败，返回异常
     * @param ex 发生的异常
     */
    void onError(Exception ex);
}
