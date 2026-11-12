package history.model;

public enum RecordCategory {
    TEXT("文本"),
    BATCH("批量文件"),
    KEY("密钥操作"),
    CONTACT("联系人操作"),
    SYSTEM("系统操作");

    private final String displayName;

    RecordCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
