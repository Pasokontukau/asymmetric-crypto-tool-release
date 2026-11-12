package com.cryptoassistant.app.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cryptoassistant.app.R
import com.cryptoassistant.app.feature.security.AppLockPolicy
import com.cryptoassistant.app.feature.security.BiometricAvailability
import com.cryptoassistant.app.ui.components.AppCard
import com.cryptoassistant.app.ui.components.InfoBullet
import com.cryptoassistant.app.ui.components.SectionTitle
import com.cryptoassistant.app.ui.components.StatusRow
import com.cryptoassistant.app.ui.components.StatusTone
import com.cryptoassistant.app.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * 安全锁设置页。
 *
 * <p>开关默认关闭；开启与关闭都要先过系统验证。页面不保存任何认证状态，
 * 也不显示指纹 ID、失败次数或系统错误原文。关闭安全锁没有“跳过验证”的入口：
 * 如果本机暂时无法验证，用户必须先恢复系统指纹/锁屏凭据。</p>
 */
@Composable
fun SecuritySettingsScreen(
    enabled: Boolean,
    appLockEnabled: Boolean,
    lockTimeoutMinutes: Int,
    availability: BiometricAvailability,
    onSecurityLockChange: suspend (Boolean) -> Unit,
    onAppLockChange: suspend (Boolean) -> Unit,
    onLockTimeoutChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var changing by remember { mutableStateOf(false) }

    val subtitle = stringResource(
        when {
            enabled && availability.canEnable -> R.string.security_subtitle_on
            enabled -> R.string.security_subtitle_on_unavailable
            availability.canEnable -> R.string.security_subtitle_off
            else -> R.string.security_subtitle_off_unavailable
        },
    )

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = Spacing.screenPadding,
            end = Spacing.screenPadding,
            bottom = Spacing.xxl,
        ),
    ) {
        item { Spacer(Modifier.height(Spacing.sm)) }

        item {
            AppCard {
                StatusRow(
                    icon = Icons.Outlined.Fingerprint,
                    title = stringResource(R.string.security_title),
                    subtitle = subtitle,
                    tone = if (enabled) StatusTone.SUCCESS else StatusTone.NEUTRAL,
                    trailing = {
                        Switch(
                            checked = enabled,
                            // 关闭状态且本机无法验证时不允许打开；已开启时保留开关，
                            // 让用户能在恢复系统验证方式后自行关闭。
                            enabled = !changing && (enabled || availability.canEnable),
                            onCheckedChange = { target ->
                                if (!changing) {
                                    scope.launch {
                                        changing = true
                                        onSecurityLockChange(target)
                                        changing = false
                                    }
                                }
                            },
                        )
                    },
                )
            }
        }

        if (!availability.canEnable) {
            item {
                Spacer(Modifier.height(Spacing.sm))
                AppCard {
                    Text(
                        text = stringResource(availabilityMessage(availability)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (enabled) {
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = stringResource(R.string.security_close_requires_auth),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.security_section_app_lock)) }

        item {
            AppCard {
                StatusRow(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.security_app_lock_title),
                    subtitle = stringResource(
                        appLockSubtitle(
                            masterEnabled = enabled,
                            appLockEnabled = appLockEnabled,
                            timeoutMinutes = lockTimeoutMinutes,
                            availability = availability,
                        ),
                    ),
                    tone = if (enabled && appLockEnabled) {
                        StatusTone.SUCCESS
                    } else {
                        StatusTone.NEUTRAL
                    },
                    trailing = {
                        Switch(
                            checked = appLockEnabled,
                            enabled = !changing && enabled && availability.canEnable,
                            onCheckedChange = { target ->
                                if (!changing) {
                                    scope.launch {
                                        changing = true
                                        onAppLockChange(target)
                                        changing = false
                                    }
                                }
                            },
                        )
                    },
                )
                if (!enabled) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = stringResource(R.string.security_app_lock_need_master),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (appLockEnabled) {
                    // 应用锁关闭时不展示验证间隔；打开开关后才出现这两个选项。
                    Spacer(Modifier.height(Spacing.md))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        text = stringResource(R.string.security_timeout_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        AppLockPolicy.TIMEOUT_OPTIONS_MINUTES.forEach { minutes ->
                            FilterChip(
                                selected = lockTimeoutMinutes == minutes,
                                onClick = { if (!changing) onLockTimeoutChange(minutes) },
                                enabled = !changing && availability.canEnable,
                                label = { Text(stringResource(lockTimeoutLabel(minutes))) },
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = stringResource(R.string.security_timeout_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { SectionTitle(stringResource(R.string.security_section_scope)) }

        item {
            AppCard {
                InfoBullet(stringResource(R.string.security_scope_key))
                InfoBullet(stringResource(R.string.security_scope_logs))
                InfoBullet(stringResource(R.string.security_scope_log_level))
                InfoBullet(stringResource(R.string.security_scope_toggle))
            }
        }

        item { SectionTitle(stringResource(R.string.security_section_method)) }

        item {
            AppCard {
                Text(
                    text = stringResource(verificationMethodText(availability)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@StringRes
private fun appLockSubtitle(
    masterEnabled: Boolean,
    appLockEnabled: Boolean,
    timeoutMinutes: Int,
    availability: BiometricAvailability,
): Int = when {
    !masterEnabled -> R.string.security_app_lock_subtitle_need_master
    appLockEnabled -> if (timeoutMinutes == 0) {
        R.string.security_app_lock_subtitle_immediate
    } else {
        R.string.security_app_lock_subtitle_10min
    }

    !availability.canEnable -> R.string.security_app_lock_subtitle_unavailable
    else -> R.string.security_app_lock_subtitle_off
}

/** 0 = 立即；其余按 10 分钟处理（设置层已归一，这里只负责文案）。 */
@StringRes
private fun lockTimeoutLabel(minutes: Int): Int =
    if (minutes == 0) R.string.security_timeout_immediate else R.string.security_timeout_10min

@StringRes
private fun availabilityMessage(availability: BiometricAvailability): Int = when (availability) {
    BiometricAvailability.READY -> R.string.security_empty
    BiometricAvailability.NONE_ENROLLED -> R.string.security_none_enrolled
    BiometricAvailability.NO_HARDWARE -> R.string.security_no_hardware
    BiometricAvailability.UNAVAILABLE -> R.string.security_unavailable
    BiometricAvailability.SECURITY_UPDATE_REQUIRED -> R.string.security_update_required
    BiometricAvailability.UNKNOWN -> R.string.security_unknown
}

@StringRes
private fun verificationMethodText(availability: BiometricAvailability): Int =
    when (availability) {
        BiometricAvailability.READY -> R.string.security_method_ready
        BiometricAvailability.NONE_ENROLLED -> R.string.security_method_none
        BiometricAvailability.NO_HARDWARE -> R.string.security_method_no_hardware
        BiometricAvailability.UNAVAILABLE -> R.string.security_method_unavailable
        BiometricAvailability.SECURITY_UPDATE_REQUIRED -> R.string.security_method_update
        BiometricAvailability.UNKNOWN -> R.string.security_method_unknown
    }
