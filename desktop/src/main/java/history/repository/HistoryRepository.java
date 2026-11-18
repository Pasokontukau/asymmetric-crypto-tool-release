package history.repository;

import history.model.HistoryRecord;
import history.storage.HistoryExport;
import history.storage.HistoryStorage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Set;

/**
 * 历史记录的薄仓库层（解耦 DR-4）。
 *
 * <p>历史页只依赖这里：查询、筛选、收藏、备注、删除、清空与导出。
 * 读取仍走 {@link HistoryStorage}，导出仍走 {@link HistoryExport}，不改序列化格式。</p>
 */
public class HistoryRepository {

    /** 明文导出格式；加密导出走 {@link #writeEncrypted}。 */
    public enum Format {
        JSON,
        CSV,
        HTML
    }

    public List<HistoryRecord> loadAll() {
        return HistoryStorage.loadHistory();
    }

    public Set<String> contacts() {
        return HistoryStorage.loadAllContactsFromHistory();
    }

    public List<HistoryRecord> search(String keyword) {
        return HistoryStorage.searchHistory(keyword);
    }

    public List<HistoryRecord> byContact(String contactName) {
        return HistoryStorage.loadHistoryByContact(contactName);
    }

    public List<HistoryRecord> starred() {
        return HistoryStorage.loadStarredHistory();
    }

    /**
     * 历史页的统一查询入口：联系人与关键词二选一时联系人优先；收藏再与结果取交集。
     *
     * <p>与迁移前搜索框/联系人/收藏三个筛选的组合结果一致；类型筛选仍由页面处理。</p>
     */
    public List<HistoryRecord> query(String keyword, String contactName, boolean starredOnly) {
        List<HistoryRecord> records;
        if (contactName != null) {
            records = byContact(contactName);
        } else if (keyword != null && !keyword.trim().isEmpty()) {
            records = search(keyword);
        } else {
            records = loadAll();
        }
        if (starredOnly) {
            List<HistoryRecord> starred = new java.util.ArrayList<HistoryRecord>();
            for (HistoryRecord record : records) {
                if (record.isStarred()) {
                    starred.add(record);
                }
            }
            return starred;
        }
        return records;
    }

    public void setStarred(String id, boolean starred) {
        HistoryStorage.setStarred(id, starred);
    }

    public void addNotes(String id, String notes) {
        HistoryStorage.addNotes(id, notes);
    }

    /** 数据目录搬迁后批量改写历史里的路径前缀。 */
    public int updateFilePaths(String oldDir, String newDir) {
        return HistoryStorage.updateFilePaths(oldDir, newDir);
    }

    public void delete(String id) {
        HistoryStorage.deleteHistoryRecord(id);
    }

    public void clear() {
        HistoryStorage.clearHistory();
    }

    public void writeEncrypted(File target, List<HistoryRecord> records, char[] password)
            throws IOException, GeneralSecurityException {
        HistoryExport.writeEncrypted(target, records, password);
    }

    public void writePlain(File target, List<HistoryRecord> records, Format format)
            throws IOException {
        String content;
        switch (format) {
            case JSON:
                content = HistoryExport.toJson(records);
                break;
            case CSV:
                content = HistoryExport.toCsv(records);
                break;
            case HTML:
                content = HistoryExport.toHtml(records);
                break;
            default:
                throw new IllegalArgumentException("未知导出格式: " + format);
        }
        Files.write(target.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}
