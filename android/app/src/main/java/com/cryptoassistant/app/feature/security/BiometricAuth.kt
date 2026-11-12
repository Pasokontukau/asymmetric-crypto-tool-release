package com.cryptoassistant.app.feature.security

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.log.AppLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** 本机能不能弹一次可用的身份验证。 */
enum class BiometricAvailability {
    READY,
    NONE_ENROLLED,
    NO_HARDWARE,
    UNAVAILABLE,
    SECURITY_UPDATE_REQUIRED,
    UNKNOWN,
    ;

    val canEnable: Boolean get() = this == READY
}

sealed interface BiometricAuthResult {
    data object Success : BiometricAuthResult

    data object Cancelled : BiometricAuthResult

    /** 系统层面不可用（没有录入、没有硬件、暂时不可用）；不是用户取消。 */
    data class Unavailable(val message: String) : BiometricAuthResult

    /** 用户在场但验证失败/被锁定；可以重试。 */
    data class Failed(val message: String) : BiometricAuthResult
}

/**
 * 生物识别与设备凭据的唯一入口。
 *
 * <p>只使用系统 [BiometricPrompt]，不自绘指纹 UI，不读取/保存任何生物特征。
 * 错误统一分类，UI 只需决定“继续还是停”。日志只写动作与结果枚举。</p>
 */
object BiometricAuth {

    /**
     * 28+ 允许锁屏凭据兜底；26–27 的 FingerprintManager 回退不保证支持设备凭据，
     * 因此这两版只接受生物识别。这个边界与 [promptInfo] 的分支必须保持一致。
     */
    private fun supportsDeviceCredential(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    private fun authenticators(): Int =
        if (supportsDeviceCredential()) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

    fun availability(context: Context): BiometricAvailability {
        val manager = BiometricManager.from(context)
        val status = runCatching { manager.canAuthenticate(authenticators()) }
            .getOrElse { return BiometricAvailability.UNKNOWN }
        val resolved = when {
            status != BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> status
            // 个别 ROM 不支持组合查询：分别看锁屏凭据与强生物识别。
            supportsDeviceCredential() &&
                runCatching {
                    manager.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                }.getOrDefault(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED) ==
                BiometricManager.BIOMETRIC_SUCCESS -> BiometricManager.BIOMETRIC_SUCCESS

            else -> runCatching {
                manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            }.getOrDefault(status)
        }
        // 没有指纹但设置了锁屏 PIN/图案时，系统面板仍可用设备凭据完成验证。
        if (resolved != BiometricManager.BIOMETRIC_SUCCESS &&
            supportsDeviceCredential() &&
            isDeviceSecure(context) &&
            (resolved == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ||
                resolved == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE)
        ) {
            return BiometricAvailability.READY
        }
        return when (resolved) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability.UNAVAILABLE
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                BiometricAvailability.SECURITY_UPDATE_REQUIRED

            else -> BiometricAvailability.UNKNOWN
        }
    }

    private fun isDeviceSecure(context: Context): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.isDeviceSecure == true

    /**
     * 弹一次系统验证。
     *
     * <p>必须在 Activity 仍在前台时调用；Service 里不要直接调用，也不要在后台尝试。
     * 取消、锁定、无硬件都返回不同结果，调用方不得把“取消”当成“成功”。</p>
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        action: SecurityAction,
    ): BiometricAuthResult {
        if (activity.isFinishing || activity.isDestroyed) {
            return BiometricAuthResult.Unavailable(
                activity.getString(R.string.security_prompt_closed),
            )
        }
        // 系统验证面板可能让 Activity 短暂 onStop；不能把它算成“应用进入后台”。
        SecuritySession.beginExternalFlow()
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val resumed = AtomicBoolean(false)
                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(
                            result: BiometricPrompt.AuthenticationResult,
                        ) {
                            if (!resumed.compareAndSet(false, true)) return
                            AppLog.info("security.auth", "action=${action.logName} result=ok")
                            continuation.resume(BiometricAuthResult.Success)
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence,
                        ) {
                            if (!resumed.compareAndSet(false, true)) return
                            val result = when (errorCode) {
                                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                                BiometricPrompt.ERROR_USER_CANCELED,
                                BiometricPrompt.ERROR_CANCELED,
                                -> BiometricAuthResult.Cancelled

                                BiometricPrompt.ERROR_LOCKOUT ->
                                    BiometricAuthResult.Failed(
                                        activity.getString(R.string.security_prompt_lockout),
                                    )

                                BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
                                    BiometricAuthResult.Failed(
                                        activity.getString(R.string.security_prompt_locked),
                                    )

                                BiometricPrompt.ERROR_NO_BIOMETRICS ->
                                    BiometricAuthResult.Unavailable(
                                        activity.getString(R.string.security_prompt_no_biometrics),
                                    )

                                BiometricPrompt.ERROR_HW_NOT_PRESENT ->
                                    BiometricAuthResult.Unavailable(
                                        activity.getString(R.string.security_prompt_no_hardware),
                                    )

                                BiometricPrompt.ERROR_HW_UNAVAILABLE ->
                                    BiometricAuthResult.Unavailable(
                                        activity.getString(R.string.security_prompt_hw_unavailable),
                                    )

                                BiometricPrompt.ERROR_TIMEOUT ->
                                    BiometricAuthResult.Failed(
                                        activity.getString(R.string.security_prompt_timeout),
                                    )

                                else -> BiometricAuthResult.Failed(
                                    activity.getString(R.string.security_prompt_failed),
                                )
                            }
                            AppLog.warn(
                                "security.auth",
                                "action=${action.logName} result=${result.logName}",
                            )
                            continuation.resume(result)
                        }

                        // 单次识别失败不结束面板，让用户可以在同一对话框里重试。
                        override fun onAuthenticationFailed() = Unit
                    },
                )
                continuation.invokeOnCancellation {
                    if (resumed.compareAndSet(false, true)) prompt.cancelAuthentication()
                }
                try {
                    prompt.authenticate(promptInfo(action, activity))
                } catch (error: Exception) {
                    if (resumed.compareAndSet(false, true)) {
                        AppLog.error(
                            "security.auth",
                            error,
                            "action=${action.logName} result=error",
                        )
                        continuation.resume(
                            BiometricAuthResult.Failed(
                                activity.getString(R.string.security_prompt_open_failed),
                            ),
                        )
                    }
                }
            }
        }.also { SecuritySession.endExternalFlow() }
    }

    private fun promptInfo(action: SecurityAction, context: Context): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(action.titleRes))
            .setSubtitle(context.getString(action.subtitleRes))
        if (supportsDeviceCredential()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(authenticators())
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
        } else {
            // 26–27 只走指纹；允许设备凭据会与旧版回退实现冲突。
            builder.setNegativeButtonText(context.getString(R.string.common_cancel))
        }
        return builder.build()
    }
}

private val BiometricAuthResult.logName: String
    get() = when (this) {
        BiometricAuthResult.Success -> "ok"
        BiometricAuthResult.Cancelled -> "cancel"
        is BiometricAuthResult.Unavailable -> "unavailable"
        is BiometricAuthResult.Failed -> "failed"
    }

/** 从 Compose 的 ContextThemeWrapper 链里找到宿主 Activity。 */
fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return current as? FragmentActivity
}
