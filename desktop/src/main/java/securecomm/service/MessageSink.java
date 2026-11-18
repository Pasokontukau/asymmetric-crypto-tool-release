package securecomm.service;

/**
 * 文案边界：业务层只传“键 + 参数”，由实现决定渲染方式，
 * 避免把中文串散落在编排代码里（也便于以后接 Android 的资源表）。
 */
public interface MessageSink {

    /** 取一条已本地化的文案；参数按 {@code String.format} 填充。 */
    String get(String key, Object... args);
}
