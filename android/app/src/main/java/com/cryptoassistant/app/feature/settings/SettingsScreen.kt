package com.cryptoassistant.app.feature.settings

import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cryptoassistant.app.BuildConfig
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.AppSettings
import com.cryptoassistant.app.data.BallWorkMode
import com.cryptoassistant.app.data.log.LogLevel
import com.cryptoassistant.app.ui.components.AppCard
import com.cryptoassistant.app.ui.components.SectionTitle
import com.cryptoassistant.app.ui.components.SettingsNavRow
import com.cryptoassistant.app.ui.navigation.AppRoutes
import com.cryptoassistant.app.ui.theme.Spacing

/**
 * 设置首页只展示分类入口，具体开关都收在各自的二级页面里，避免一屏平铺所有选项。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    logLevel: LogLevel,
    overlayGranted: Boolean,
    accessibilityEnabled: Boolean,
    /** 无障碍服务此刻是否真的连着；与“系统设置里已启用”可能不一致。 */
    accessibilityConnected: Boolean,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ballActive = settings.ballEnabled && overlayGranted

    val themeLabel = stringResource(settings.themeMode.labelRes)
    val accentLabel = stringResource(settings.accentPalette.labelRes)
    val dynamicLabel = stringResource(R.string.appearance_dynamic_title)
    val amoledSuffix = stringResource(R.string.settings_appearance_amoled_suffix)
    val appearanceSubtitle = if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && settings.dynamicColor
    ) {
        "$themeLabel · $dynamicLabel"
    } else {
        themeLabel + " · " + accentLabel + if (settings.amoledDark) " · $amoledSuffix" else ""
    }

    val quickLabel = stringResource(R.string.mode_quick)
    val standardLabel = stringResource(R.string.mode_standard)
    val ballState = stringResource(
        if (ballActive) R.string.settings_ball_state_on else R.string.settings_ball_state_off,
    )
    // 标准模式不提无障碍：那不是当前工作方式的一部分。
    val ballSubtitle = when {
        settings.ballWorkMode == BallWorkMode.STANDARD -> "$ballState · $standardLabel"
        !ballActive -> "$ballState · $quickLabel"
        !accessibilityEnabled -> stringResource(R.string.settings_ball_quick_a11y_off)
        !accessibilityConnected -> stringResource(R.string.settings_ball_quick_a11y_not_running)
        else -> "$ballState · $quickLabel"
    }

    val logSubtitle = if (logLevel == LogLevel.OFF) {
        stringResource(R.string.settings_logs_off)
    } else {
        stringResource(R.string.settings_logs_recording, stringResource(logLevel.labelRes))
    }
    val archiveVolumeSubtitle = stringResource(
        if (settings.packageMultipleFiles) {
            R.string.settings_file_processing_subtitle_package
        } else {
            R.string.settings_file_processing_subtitle
        },
        stringResource(settings.archiveVolumeSize.labelRes),
    )

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Spacing.screenPadding,
            end = Spacing.screenPadding,
            bottom = Spacing.xxl,
        ),
    ) {
        item {
            Spacer(Modifier.height(Spacing.sm))
        }

        item { SectionTitle(stringResource(R.string.settings_section_personal)) }

        item {
            AppCard {
                SettingsNavRow(
                    icon = Icons.Outlined.Palette,
                    title = stringResource(R.string.nav_title_appearance),
                    subtitle = appearanceSubtitle,
                    onClick = { onNavigate(AppRoutes.SETTINGS_APPEARANCE) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsNavRow(
                    icon = Icons.Outlined.TouchApp,
                    title = stringResource(R.string.nav_title_ball),
                    subtitle = ballSubtitle,
                    onClick = { onNavigate(AppRoutes.SETTINGS_BALL) },
                )
            }
        }

        item { SectionTitle(stringResource(R.string.settings_section_security)) }

        item {
            AppCard {
                SettingsNavRow(
                    icon = Icons.Outlined.Fingerprint,
                    title = stringResource(R.string.nav_title_security),
                    subtitle = when {
                        !settings.securityLockEnabled ->
                            stringResource(R.string.settings_security_off)
                        settings.appLockEnabled ->
                            stringResource(R.string.settings_security_app_lock)
                        else -> stringResource(R.string.settings_security_on)
                    },
                    onClick = { onNavigate(AppRoutes.SETTINGS_SECURITY) },
                )
            }
        }

        item { SectionTitle(stringResource(R.string.settings_section_data)) }

        item {
            AppCard {
                SettingsNavRow(
                    icon = Icons.Outlined.FolderOpen,
                    title = stringResource(R.string.nav_title_file_processing),
                    subtitle = archiveVolumeSubtitle,
                    onClick = { onNavigate(AppRoutes.SETTINGS_FILE_PROCESSING) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsNavRow(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(R.string.nav_title_logs),
                    subtitle = logSubtitle,
                    onClick = { onNavigate(AppRoutes.SETTINGS_LOGS) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CacheCleanupRow()
            }
        }

        item { SectionTitle(stringResource(R.string.settings_section_about)) }

        item {
            AppCard {
                SettingsNavRow(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.settings_about_title),
                    subtitle = stringResource(
                        R.string.settings_about_subtitle,
                        BuildConfig.VERSION_NAME,
                    ),
                    onClick = { onNavigate(AppRoutes.SETTINGS_ABOUT) },
                )
            }
        }
    }
}
