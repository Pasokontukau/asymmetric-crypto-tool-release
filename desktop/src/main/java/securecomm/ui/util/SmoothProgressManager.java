package securecomm.ui.util;

import javax.swing.JProgressBar;

/**
 * 平滑进度管理器，用于让进度条更丝滑地过渡，同时支持显示步骤信息
 */
public class SmoothProgressManager {
    private final JProgressBar progressBar;
    private final javax.swing.Timer timer;
    private int targetProgress = 0;
    private int currentProgress = 0;
    private String currentStepText = "";
    private static final int STEP = 1;
    private static final int DELAY = 15;

    public SmoothProgressManager(JProgressBar progressBar) {
        this.progressBar = progressBar;
        this.timer = new javax.swing.Timer(DELAY, e -> updateProgress());
        this.timer.setRepeats(true);
    }

    /**
     * 设置目标进度和步骤信息，开始平滑过渡
     */
    public void setTargetProgress(int target, String stepText) {
        this.targetProgress = Math.min(100, Math.max(0, target));
        this.currentStepText = stepText;
        if (!timer.isRunning()) {
            timer.start();
        }
    }

    /**
     * 设置目标进度，开始平滑过渡（保留上一个步骤文字）
     */
    public void setTargetProgress(int target) {
        setTargetProgress(target, this.currentStepText);
    }

    /**
     * 立即设置进度和步骤信息（用于最终完成或重置）
     */
    public void setImmediateProgress(int progress, String stepText) {
        this.targetProgress = Math.min(100, Math.max(0, progress));
        this.currentProgress = this.targetProgress;
        this.currentStepText = stepText;
        if (timer.isRunning()) {
            timer.stop();
        }
        progressBar.setValue(this.currentProgress);
        progressBar.setString(stepText);
    }

    /**
     * 立即设置进度（用于最终完成或重置）
     */
    public void setImmediateProgress(int progress) {
        setImmediateProgress(progress, progressBar.getString());
    }

    /**
     * 停止平滑动画
     */
    public void stop() {
        timer.stop();
    }

    /**
     * 更新进度的内部方法
     */
    private void updateProgress() {
        if (currentProgress < targetProgress) {
            currentProgress = Math.min(currentProgress + STEP, targetProgress);
            progressBar.setValue(currentProgress);
            if (!currentStepText.isEmpty()) {
                progressBar.setString(currentStepText);
            }
        } else if (currentProgress > targetProgress) {
            currentProgress = Math.max(currentProgress - STEP, targetProgress);
            progressBar.setValue(currentProgress);
            if (!currentStepText.isEmpty()) {
                progressBar.setString(currentStepText);
            }
        } else {
            timer.stop();
        }
    }
}
