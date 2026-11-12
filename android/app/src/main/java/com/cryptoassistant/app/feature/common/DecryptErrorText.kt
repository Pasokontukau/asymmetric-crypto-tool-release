package com.cryptoassistant.app.feature.common

import android.content.Context
import androidx.annotation.StringRes
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.keys.LocalKeyUnavailableException
import com.cryptoassistant.app.data.keys.keyErrorMessage

/**
 * 文本解密的稳定失败原因。
 *
 * <p>球路径（OverlayService）和胶囊路径（BallActionActivity）共用同一套判断：
 * BadPadding / IllegalBlockSize 基本只有两种原因——密文不是发给本机密钥的，或内容
 * 被改过；本机私钥不可用按“未创建 / 解封失败”区分；其余异常只给本地化兜底，
 * 原文进日志，不在界面暴露异常类名或中文技术信息。</p>
 */
internal enum class DecryptError(@get:StringRes val messageRes: Int) {
    WRONG_RECIPIENT(R.string.decrypt_error_wrong_key),
    NO_IDENTITY(R.string.decrypt_error_no_identity),
    PRIVATE_KEY(R.string.decrypt_error_private_key),
    GENERIC(R.string.decrypt_error_generic),
}

internal fun decryptErrorFor(error: Throwable): DecryptError {
    val name = error.javaClass.simpleName
    return when {
        name.contains("BadPadding") || name.contains("IllegalBlockSize") ->
            DecryptError.WRONG_RECIPIENT

        error is LocalKeyUnavailableException ->
            if (error.missing) DecryptError.NO_IDENTITY else DecryptError.PRIVATE_KEY

        else -> DecryptError.GENERIC
    }
}

internal fun decryptErrorText(context: Context, error: Throwable): String =
    context.getString(decryptErrorFor(error).messageRes)

/**
 * 文本加密的失败文案：本机私钥不可用按“未创建 / 解封失败”区分，其余只给本地化兜底，
 * 原文进日志，不在界面暴露异常类名或技术信息。
 */
internal fun encryptErrorText(context: Context, error: Throwable): String =
    if (error is LocalKeyUnavailableException) {
        keyErrorMessage(context, error)
    } else {
        context.getString(R.string.ball_error_encrypt_failed)
    }
