package history.model;

public enum HistoryType {
    ENCRYPT("加密"),
    DECRYPT("解密"),
    SIGN("签名"),
    VERIFY("验证"),
    KEYGEN("密钥生成"),
    KEYIMPORT("密钥导入"),
    KEYEXPORT("密钥导出"),
    CONTACT_ADD("添加联系人"),
    CONTACT_EDIT("编辑联系人"),
    CONTACT_DELETE("删除联系人"),
    OTHER("其他");

    private final String displayName;

    HistoryType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
