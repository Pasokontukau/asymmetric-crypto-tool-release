package com.cryptoassistant.app.data.keys

/**
 * 本机私钥不可用：尚未创建身份，或身份存在但 Keystore 解封失败。
 *
 * <p>纯逻辑层只抛出类型，界面按 [missing] 映射到自己的资源文案；异常里的中文
 * 只用于日志，不再作为用户可见文案。</p>
 */
internal class LocalKeyUnavailableException(
    val missing: Boolean,
    cause: Throwable? = null,
) : IllegalStateException(
    if (missing) "尚未创建本机密钥" else "无法解封本机私钥",
    cause,
)
