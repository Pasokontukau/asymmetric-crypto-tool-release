package securecomm.ui.util;

import javax.swing.JProgressBar;
import securecomm.service.MessageSink;
import securecomm.service.ProgressSink;

/**
 * 把 {@link ProgressSink} 接到现有的 {@link SmoothProgressManager}：
 * 业务层只给百分比和文案键，这里负责平滑动画与文字。
 */
public class SwingProgressSink implements ProgressSink {

    private final SmoothProgressManager smoothProgress;
    private final JProgressBar fallbackBar;
    private final MessageSink messages;

    public SwingProgressSink(JProgressBar progressBar, MessageSink messages) {
        this.smoothProgress = progressBar == null ? null : new SmoothProgressManager(progressBar);
        this.fallbackBar = progressBar;
        this.messages = messages;
    }

    @Override
    public void stage(int percent, String messageKey, Object... args) {
        String text = messages == null ? messageKey : messages.get(messageKey, args);
        int value = Math.max(0, Math.min(100, percent));
        if (smoothProgress == null) {
            if (fallbackBar != null) {
                fallbackBar.setValue(value);
                fallbackBar.setString(text);
            }
            return;
        }
        if (value >= 100) {
            smoothProgress.setImmediateProgress(100, text);
        } else {
            smoothProgress.setTargetProgress(value, text);
        }
    }

    @Override
    public void done() {
        if (smoothProgress != null) {
            smoothProgress.stop();
        }
    }
}
