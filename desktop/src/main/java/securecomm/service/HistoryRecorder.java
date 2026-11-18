package securecomm.service;

/** 历史记录的写入边界；实现见 {@link DefaultHistoryRecorder}。 */
public interface HistoryRecorder {

    /** 追加一条记录；失败实现方自行记日志，不向界面抛。 */
    void record(HistoryDraft draft);
}
