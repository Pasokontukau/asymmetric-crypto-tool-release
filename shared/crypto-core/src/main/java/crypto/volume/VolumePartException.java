package crypto.volume;

/** 外部分卷写入的计划、大小或源文件一致性问题。 */
public final class VolumePartException extends Exception {

    public enum Code {
        /** 参数、路径、元数据字段或输出文件不合法。 */
        INVALID_PLAN,
        /** 计划、实际密文长度或最终文件大小与上限不一致。 */
        SIZE_MISMATCH,
        /** ZIP 在写入期间被修改。 */
        SOURCE_CHANGED,
        /** 临时文件、目录或重命名等本地 I/O 失败。 */
        IO_ERROR
    }

    private final Code code;

    public VolumePartException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public VolumePartException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
