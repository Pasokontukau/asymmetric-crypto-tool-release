package com.cryptoassistant.app.data.log

/**
 * 诊断日志等级。
 *
 * <p>顺序即阈值：`ERROR` 只写错误，`VERBOSE` 写全部。`OFF` 不写任何记录。
 * 中文名称放在设置页，避免数据层耦合 UI 文案。</p>
 */
enum class LogLevel(val wireName: String, val shortName: Char) {
    OFF("off", '-'),
    ERROR("error", 'E'),
    WARN("warn", 'W'),
    INFO("info", 'I'),
    DEBUG("debug", 'D'),
    VERBOSE("verbose", 'V'),
    ;

    val isEnabled: Boolean get() = this != OFF

    /** 当前阈值是否允许写入 [event]。 */
    fun allows(event: LogLevel): Boolean =
        this != OFF && event != OFF && event.ordinal <= ordinal

    companion object {
        fun fromWire(value: String?): LogLevel? =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) }

        /** 测试阶段 debug 详细，发布版本默认常规。 */
        fun defaultFor(debugBuild: Boolean): LogLevel =
            if (debugBuild) VERBOSE else INFO
    }
}
