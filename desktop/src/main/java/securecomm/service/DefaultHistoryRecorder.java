package securecomm.service;

import history.storage.HistoryStorage;

/** 适配现有 {@link HistoryStorage} 的默认实现，序列化格式不变。 */
public class DefaultHistoryRecorder implements HistoryRecorder {

    @Override
    public void record(HistoryDraft draft) {
        if (draft == null) {
            return;
        }
        if (draft.isSplit == null && draft.totalVolumes == null && draft.currentVolume == null) {
            HistoryStorage.addHistoryRecord(draft.operationType, draft.inputText, draft.outputText,
                    draft.keypairName, draft.contactName, draft.fileInputPath, draft.fileOutputPath,
                    draft.comment);
            return;
        }
        HistoryStorage.addHistoryRecord(draft.operationType, draft.inputText, draft.outputText,
                draft.keypairName, draft.contactName, draft.fileInputPath, draft.fileOutputPath,
                draft.comment, draft.isSplit, draft.totalVolumes, draft.currentVolume);
    }
}
