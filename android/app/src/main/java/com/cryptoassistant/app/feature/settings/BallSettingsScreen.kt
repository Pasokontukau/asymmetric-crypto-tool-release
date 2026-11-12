package com.cryptoassistant.app.feature.settings

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.AppSettings
import com.cryptoassistant.app.data.BallOpacity
import com.cryptoassistant.app.data.BallSize
import com.cryptoassistant.app.data.BallWorkMode
import com.cryptoassistant.app.data.FieldIoAction
import com.cryptoassistant.app.data.FieldIoOutcome
import com.cryptoassistant.app.data.FieldIoRecord
import com.cryptoassistant.app.ui.components.AppCard
import com.cryptoassistant.app.ui.components.SectionTitle
import com.cryptoassistant.app.ui.components.StatusRow
import com.cryptoassistant.app.tile.BallTileService
import com.cryptoassistant.app.ui.components.StatusTone
import com.cryptoassistant.app.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun BallSettingsScreen(
    settings: AppSettings,
    overlayGranted: Boolean,
    accessibilityEnabled: Boolean,
    /** 无障碍服务此刻是否真的连着；系统里写着已启用但服务没运行时为 false。 */
    accessibilityConnected: Boolean,
    onEnableBall: () -> Unit,
    onDisableBall: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onOpenAccessibility: () -> Unit,
    /** 勾选同意后落盘同意记录（时间 + 披露文案版本）。 */
    onRecordA11yConsent: () -> Unit,
    onFilePipelineChange: (Boolean) -> Unit,
    onBallWorkModeChange: (BallWorkMode) -> Unit,
    onBallSizeChange: (BallSize) -> Unit,
    onBallOpacityChange: (BallOpacity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val tileLabel = stringResource(R.string.tile_ball_label)
    val tileHelpTitle = stringResource(R.string.tile_help_title)
    val tileHelpBody = stringResource(R.string.tile_help_body)
    var showAccessibilityDialog by remember { mutableStateOf(false) }
    var showTileHelpDialog by remember { mutableStateOf(false) }

    /**
     * Android 13+ 可以请系统直接弹「添加磁贴」确认框；低版本或不支持的 ROM 返回 false，
     * 由调用方展开手动添加说明。回调在主线程，所以可以直接改 Compose 状态。
     */
    val requestAddTile: () -> Boolean = {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            false
        } else {
            val manager = context.getSystemService(StatusBarManager::class.java)
            val appContext = context.applicationContext
            val requested = manager != null && runCatching {
                manager.requestAddTileService(
                    ComponentName(context, BallTileService::class.java),
                    tileLabel,
                    Icon.createWithResource(context, R.drawable.ic_tile_ball),
                    ContextCompat.getMainExecutor(context),
                ) { result ->
                    when (result) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                            Toast.makeText(appContext, R.string.tile_added, Toast.LENGTH_SHORT).show()

                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                            Toast.makeText(appContext, R.string.tile_already_added, Toast.LENGTH_SHORT)
                                .show()

                        else -> showTileHelpDialog = true
                    }
                }
            }.isSuccess
            requested
        }
    }
    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted }

    LifecycleResumeEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationsGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        onPauseOrDispose { }
    }

    val ballActive = settings.ballEnabled && overlayGranted
    val quick = settings.ballWorkMode == BallWorkMode.QUICK

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenPadding),
    ) {
        SectionTitle(stringResource(R.string.settings_ball_section_work_mode))

        AppCard {
            StatusRow(
                icon = if (quick) Icons.Outlined.Accessibility else Icons.Outlined.ContentPaste,
                title = stringResource(R.string.mode_quick),
                subtitle = when {
                    quick && accessibilityEnabled && accessibilityConnected ->
                        stringResource(R.string.settings_ball_quick_status_ready)

                    quick && accessibilityEnabled ->
                        stringResource(R.string.settings_ball_quick_status_not_running)

                    quick -> stringResource(R.string.settings_ball_quick_status_a11y_off)
                    else -> stringResource(R.string.settings_ball_quick_status_off)
                },
                tone = when {
                    quick && accessibilityEnabled && accessibilityConnected -> StatusTone.SUCCESS
                    quick -> StatusTone.WARNING
                    else -> StatusTone.NEUTRAL
                },
                trailing = {
                    Switch(
                        checked = quick,
                        onCheckedChange = { enabled ->
                            onBallWorkModeChange(
                                if (enabled) BallWorkMode.QUICK else BallWorkMode.STANDARD,
                            )
                        },
                    )
                },
            )
            // 无障碍只在便捷模式下才有意义：关掉开关时整栏一起消失，不留下用不到的权限提示。
            if (quick) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StatusRow(
                    icon = Icons.Outlined.Accessibility,
                    title = stringResource(R.string.settings_ball_a11y_title),
                    subtitle = when {
                        accessibilityEnabled && accessibilityConnected ->
                            stringResource(R.string.settings_ball_a11y_status_ready)

                        accessibilityEnabled ->
                            stringResource(R.string.settings_ball_a11y_status_not_running)

                        else -> stringResource(R.string.settings_ball_a11y_status_off)
                    },
                    tone = if (accessibilityEnabled && accessibilityConnected) {
                        StatusTone.SUCCESS
                    } else {
                        StatusTone.WARNING
                    },
                    actionLabel = when {
                        accessibilityEnabled && accessibilityConnected ->
                            stringResource(R.string.settings_ball_a11y_action_manage)

                        accessibilityEnabled -> stringResource(R.string.settings_ball_a11y_action_fix)
                        else -> stringResource(R.string.settings_ball_a11y_action_enable)
                    },
                    onAction = if (accessibilityEnabled) {
                        onOpenAccessibility
                    } else {
                        { showAccessibilityDialog = true }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StatusRow(
                    icon = Icons.Outlined.FolderOpen,
                    title = stringResource(R.string.settings_ball_file_pipeline_title),
                    subtitle = if (settings.fileQuickPipeline) {
                        stringResource(R.string.settings_ball_file_pipeline_on)
                    } else {
                        stringResource(R.string.settings_ball_file_pipeline_off)
                    },
                    tone = if (settings.fileQuickPipeline) {
                        StatusTone.SUCCESS
                    } else {
                        StatusTone.NEUTRAL
                    },
                    trailing = {
                        Switch(
                            checked = settings.fileQuickPipeline,
                            onCheckedChange = onFilePipelineChange,
                        )
                    },
                )
            }
            // 任务书 §4 要求的第三种状态：最近一次文本节点读写测试结果（只存原因，不存内容）。
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StatusRow(
                icon = Icons.Outlined.TaskAlt,
                title = stringResource(R.string.settings_ball_last_io_title),
                subtitle = fieldIoSubtitle(settings.lastFieldIo, quick),
                tone = when (settings.lastFieldIo?.outcome) {
                    FieldIoOutcome.SUCCESS -> StatusTone.SUCCESS
                    FieldIoOutcome.SNAPSHOT_MISMATCH,
                    FieldIoOutcome.NO_TARGET,
                    FieldIoOutcome.WRITE_FAILED,
                    -> StatusTone.WARNING

                    else -> StatusTone.NEUTRAL
                },
            )
        }

        SectionTitle(stringResource(R.string.tile_ball_label))

        AppCard {
            StatusRow(
                icon = Icons.Outlined.TouchApp,
                title = stringResource(R.string.tile_ball_label),
                subtitle = when {
                    ballActive && !quick ->
                        stringResource(
                            R.string.settings_ball_status_active_standard,
                            ballSizeShort(settings.ballSize),
                            ballOpacityShort(settings.ballOpacity),
                        )

                    ballActive ->
                        stringResource(
                            R.string.settings_ball_status_active_quick,
                            ballSizeShort(settings.ballSize),
                            ballOpacityShort(settings.ballOpacity),
                        )

                    settings.ballEnabled && !overlayGranted ->
                        stringResource(R.string.settings_ball_status_permission_revoked)

                    else -> stringResource(R.string.settings_ball_status_off)
                },
                tone = when {
                    ballActive -> StatusTone.SUCCESS
                    settings.ballEnabled && !overlayGranted -> StatusTone.ERROR
                    else -> StatusTone.NEUTRAL
                },
                trailing = {
                    Switch(
                        checked = ballActive,
                        onCheckedChange = { checked ->
                            if (checked) onEnableBall() else onDisableBall()
                        },
                    )
                },
            )
            // 权限行在「打算开球」或缺权限时才有意义；已授权且球关着就收起，不占版面。
            if (settings.ballEnabled || !overlayGranted) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StatusRow(
                    icon = Icons.Outlined.TouchApp,
                    title = stringResource(R.string.settings_ball_overlay_title),
                    subtitle = if (overlayGranted) {
                        stringResource(R.string.settings_ball_overlay_granted)
                    } else {
                        stringResource(R.string.settings_ball_overlay_revoked)
                    },
                    tone = if (overlayGranted) StatusTone.SUCCESS else StatusTone.ERROR,
                    actionLabel = if (overlayGranted) {
                        null
                    } else {
                        stringResource(R.string.settings_ball_overlay_action_grant)
                    },
                    onAction = onRequestOverlayPermission,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StatusRow(
                icon = Icons.Outlined.Widgets,
                title = stringResource(R.string.settings_ball_add_tile_title),
                subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    stringResource(R.string.settings_ball_add_tile_subtitle_new)
                } else {
                    stringResource(R.string.settings_ball_add_tile_subtitle_old)
                },
                tone = StatusTone.NEUTRAL,
                actionLabel = stringResource(R.string.settings_ball_add_tile_action),
                onAction = { if (!requestAddTile()) showTileHelpDialog = true },
            )
        }

        SectionTitle(stringResource(R.string.nav_title_appearance))

        AppCard {
            Text(
                text = stringResource(R.string.settings_ball_size_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                BallSize.entries.forEach { size ->
                    FilterChip(
                        selected = settings.ballSize == size,
                        onClick = { onBallSizeChange(size) },
                        label = { Text(ballSizeLabel(size)) },
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.settings_ball_opacity_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                BallOpacity.entries.forEach { opacity ->
                    FilterChip(
                        selected = settings.ballOpacity == opacity,
                        onClick = { onBallOpacityChange(opacity) },
                        label = { Text(ballOpacityLabel(opacity)) },
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.settings_ball_appearance_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 常驻通知属于运行中的前台服务：球关着就不占版面，开球后再提示授权。
        if (ballActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SectionTitle(stringResource(R.string.settings_ball_section_notifications))

            AppCard {
                StatusRow(
                    icon = Icons.Outlined.Notifications,
                    title = stringResource(R.string.settings_ball_notification_title),
                    subtitle = if (notificationsGranted) {
                        stringResource(R.string.settings_ball_notification_granted)
                    } else {
                        stringResource(R.string.settings_ball_notification_hidden)
                    },
                    tone = if (notificationsGranted) StatusTone.SUCCESS else StatusTone.WARNING,
                    actionLabel = if (notificationsGranted) {
                        null
                    } else {
                        stringResource(R.string.settings_ball_notification_action_allow)
                    },
                    onAction = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
            }
        }

        Spacer(Modifier.height(Spacing.section))
    }

    if (showAccessibilityDialog) {
        AccessibilityDisclosureDialog(
            consentAt = settings.a11yConsentAt,
            onConfirm = {
                showAccessibilityDialog = false
                onRecordA11yConsent()
                onOpenAccessibility()
            },
            onDismiss = { showAccessibilityDialog = false },
        )
    }

    if (showTileHelpDialog) {
        AlertDialog(
            onDismissRequest = { showTileHelpDialog = false },
            icon = { Icon(Icons.Outlined.Widgets, contentDescription = null) },
            title = { Text(tileHelpTitle) },
            text = { Text(tileHelpBody) },
            confirmButton = {
                TextButton(onClick = { showTileHelpDialog = false }) {
                    Text(stringResource(R.string.common_got_it))
                }
            },
        )
    }
}

@Composable
private fun ballSizeLabel(size: BallSize): String = stringResource(
    when (size) {
        BallSize.SMALL -> R.string.settings_ball_size_small
        BallSize.STANDARD -> R.string.settings_ball_size_standard
        BallSize.LARGE -> R.string.settings_ball_size_large
    },
)

@Composable
private fun ballSizeShort(size: BallSize): String = stringResource(
    when (size) {
        BallSize.SMALL -> R.string.settings_ball_size_small_short
        BallSize.STANDARD -> R.string.settings_ball_size_standard_short
        BallSize.LARGE -> R.string.settings_ball_size_large_short
    },
)

@Composable
private fun ballOpacityLabel(opacity: BallOpacity): String = stringResource(
    when (opacity) {
        BallOpacity.SOLID -> R.string.settings_ball_opacity_solid
        BallOpacity.SOFT -> R.string.settings_ball_opacity_soft
        BallOpacity.FAINT -> R.string.settings_ball_opacity_faint
    },
)

@Composable
private fun ballOpacityShort(opacity: BallOpacity): String = ballOpacityLabel(opacity)

/**
 * 「最近一次输入框读写」状态行的文案。
 * 只由原因枚举 + 时间 + 包名组成，绝不包含输入框里的文字内容。
 */
@Composable
private fun fieldIoSubtitle(record: FieldIoRecord?, quickMode: Boolean): String = when {
    record == null && !quickMode -> stringResource(R.string.field_io_standard_idle)
    record == null -> stringResource(R.string.field_io_no_record)
    else -> {
        val action = stringResource(
            when (record.action) {
                FieldIoAction.READ -> R.string.field_io_action_read
                FieldIoAction.WRITE -> R.string.field_io_action_write
            },
        )
        val outcome = stringResource(
            when (record.outcome) {
                FieldIoOutcome.SUCCESS -> R.string.field_io_outcome_success
                FieldIoOutcome.EMPTY -> R.string.field_io_outcome_empty
                FieldIoOutcome.NO_FOCUS -> R.string.field_io_outcome_no_focus
                FieldIoOutcome.PASSWORD_SKIPPED -> R.string.field_io_outcome_password_skipped
                FieldIoOutcome.SNAPSHOT_MISMATCH -> R.string.field_io_outcome_snapshot_mismatch
                FieldIoOutcome.NO_TARGET -> R.string.field_io_outcome_no_target
                FieldIoOutcome.WRITE_FAILED -> R.string.field_io_outcome_write_failed
            },
        )
        // 用组合期的 LocalConfiguration 取语言，保证切换语言后时间格式跟着刷新。
        val locale = LocalConfiguration.current.locales[0]
        val time = SimpleDateFormat("MM-dd HH:mm", locale).format(Date(record.at))
        val pkg = record.packageName.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        stringResource(R.string.field_io_detail, action, outcome, time, pkg)
    }
}
