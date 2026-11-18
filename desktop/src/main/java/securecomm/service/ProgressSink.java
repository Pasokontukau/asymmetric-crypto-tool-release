package securecomm.service;

/**
 * 进度边界：业务层只汇报“百分比 + 文案键 + 参数”，
 * 具体是 JProgressBar、平滑动画还是一条日志，由实现决定。
 */
public interface ProgressSink {

    /** 汇报当前阶段；百分比 0–100，越大越靠后。 */
    void stage(int percent, String messageKey, Object... args);

    /** 收尾：停止平滑动画、把进度条定格。 */
    void done();
}
