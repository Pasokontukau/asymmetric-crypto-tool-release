package com.cryptoassistant.app.data.keys

import android.content.Context
import androidx.annotation.StringRes
import com.cryptoassistant.app.R

/**
 * 密钥/联系人/备份操作的稳定失败原因。
 *
 * <p>数据层只抛出 [KeyOperationException]，不再把中文文案交给界面；
 * 界面按 [messageRes] 取当前语言。技术细节保留在日志里，不直接展示。</p>
 */
enum class KeyError(@get:StringRes val messageRes: Int) {
    NO_IDENTITY(R.string.key_error_no_identity),
    UNWRAP(R.string.key_error_unwrap),
    INVALID_FORMAT(R.string.key_error_format),
    DELETE_FAILED(R.string.key_error_delete_failed),
    IDENTITY_FILE_READ(R.string.key_error_identity_file),
    CONTACTS_FILE_READ(R.string.key_error_contacts_file),
    BACKUP_FORMAT(R.string.key_error_backup_format),
    BACKUP_PASSWORD(R.string.key_error_backup_password),
    BACKUP_KEY_SHORT(R.string.key_error_backup_key_short),
    BACKUP_MISMATCH(R.string.key_error_backup_mismatch),
    PUBLIC_KEY_INVALID(R.string.key_error_public_key_invalid),
    RSA_ONLY(R.string.key_error_rsa_only),
    PUBLIC_KEY_SHORT(R.string.key_error_public_key_short),
    OWN_KEY(R.string.key_error_own_key),
    DUPLICATE_CONTACT(R.string.key_error_duplicate_contact),
    CONTACT_MISSING(R.string.key_error_contact_missing),
    KEY_SIZE(R.string.key_error_key_size),
    GENERIC(R.string.key_error_generic),
    BACKUP_EMPTY(R.string.key_backup_empty),
    BACKUP_TOO_LARGE(R.string.key_backup_too_large),
    BACKUP_NOT_UTF8(R.string.key_backup_not_utf8),
    BACKUP_BINARY(R.string.key_backup_binary),
    BACKUP_CONTENT_EMPTY(R.string.key_backup_content_empty),
    BACKUP_UNKNOWN_FORMAT(R.string.key_backup_unknown_format),
    BACKUP_NOT_OURS(R.string.key_backup_not_ours),
}

internal class KeyOperationException(
    val reason: KeyError,
    /** 只进日志/测试的技术细节，不直接展示。 */
    detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail, cause)

internal fun keyErrorFor(error: Throwable): KeyError = when (error) {
    is KeyOperationException -> error.reason
    is LocalKeyUnavailableException ->
        if (error.missing) KeyError.NO_IDENTITY else KeyError.UNWRAP

    else -> KeyError.GENERIC
}

internal fun keyErrorMessage(context: Context, error: Throwable): String =
    context.getString(keyErrorFor(error).messageRes)
