package com.cryptoassistant.app.feature.security

import androidx.annotation.StringRes
import com.cryptoassistant.app.R

/**
 * 需要生物识别/设备凭据确认的动作。
 *
 * <p>标题/副标题以资源 ID 保存，由调用方按当前语言解析后展示在系统 BiometricPrompt 上；
 * 不要在这里放密钥、文件名或任何用户内容。</p>
 */
enum class SecurityAction(
    @get:StringRes val titleRes: Int,
    @get:StringRes val subtitleRes: Int,
) {
    EXPORT_BACKUP(
        titleRes = R.string.security_action_verify,
        subtitleRes = R.string.security_action_export_backup,
    ),
    IMPORT_BACKUP(
        titleRes = R.string.security_action_verify,
        subtitleRes = R.string.security_action_import_backup,
    ),
    DELETE_IDENTITY(
        titleRes = R.string.security_action_verify,
        subtitleRes = R.string.security_action_delete_identity,
    ),
    LOG_EXPORT(
        titleRes = R.string.security_action_verify,
        subtitleRes = R.string.security_action_log_export,
    ),
    LOG_CLEAR(
        titleRes = R.string.security_action_verify,
        subtitleRes = R.string.security_action_log_clear,
    ),
    LOG_LEVEL_CHANGE(
        titleRes = R.string.security_action_verify,
        subtitleRes = R.string.security_action_log_level,
    ),
    ENABLE_LOCK(
        titleRes = R.string.security_action_enable_lock_title,
        subtitleRes = R.string.security_action_enable_lock,
    ),
    DISABLE_LOCK(
        titleRes = R.string.security_action_disable_lock_title,
        subtitleRes = R.string.security_action_disable_lock,
    ),
    APP_LOCK_ENABLE(
        titleRes = R.string.security_action_app_lock_enable_title,
        subtitleRes = R.string.security_action_app_lock_enable,
    ),
    APP_LOCK_DISABLE(
        titleRes = R.string.security_action_app_lock_disable_title,
        subtitleRes = R.string.security_action_app_lock_disable,
    ),
    UNLOCK_APP(
        titleRes = R.string.security_action_unlock_app_title,
        subtitleRes = R.string.security_action_unlock_app,
    ),
    ;

    /** 日志里只写小写枚举，不写标题/副标题，避免文案变化影响检索。 */
    val logName: String get() = name.lowercase()
}

/**
 * “这个动作当前要不要验证”的唯一判断点。
 *
 * <p>采用纯函数，方便 JVM 单测；安全锁关闭时，日常密钥操作保持原样。
 * 开启/关闭安全锁本身永远需要验证，避免设备在已解锁状态下被他人直接关掉。</p>
 */
object SecurityLockPolicy {

    fun requiresAuth(action: SecurityAction, lockEnabled: Boolean): Boolean = when (action) {
        SecurityAction.ENABLE_LOCK,
        SecurityAction.DISABLE_LOCK,
        SecurityAction.APP_LOCK_ENABLE,
        SecurityAction.APP_LOCK_DISABLE,
        SecurityAction.UNLOCK_APP -> true

        SecurityAction.EXPORT_BACKUP,
        SecurityAction.IMPORT_BACKUP,
        SecurityAction.DELETE_IDENTITY,
        SecurityAction.LOG_EXPORT,
        SecurityAction.LOG_CLEAR,
        SecurityAction.LOG_LEVEL_CHANGE -> lockEnabled
    }
}
