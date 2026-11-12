package com.cryptoassistant.app.feature.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cryptoassistant.app.BuildConfig
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.log.AppLog
import com.cryptoassistant.app.data.log.LogLevel
import com.cryptoassistant.app.feature.security.SecurityAction
import com.cryptoassistant.app.feature.security.SecuritySession
import com.cryptoassistant.app.ui.components.AppCard
import com.cryptoassistant.app.ui.components.KeyValueRow
import com.cryptoassistant.app.ui.components.SectionTitle
import com.cryptoassistant.app.ui.components.StatusRow
import com.cryptoassistant.app.ui.components.StatusTone
import com.cryptoassistant.app.ui.theme.Spacing
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 日志设置页：点按等级行更改记录等级，长按导出，清空使用单独按钮。
 * 日志文件在应用私有目录，导出走系统文件选择器，不需要存储权限。
 */
@Composable
fun LogSettingsScreen(
    logLevel: LogLevel,
    onLevelChange: (LogLevel) -> Unit,
    /** 安全锁开启时先验证；关闭时实现方直接返回 true。 */
    onAuthorize: suspend (SecurityAction) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf(AppLog.Stats(0, 0)) }
    var recent by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showLevelDialog by remember { mutableStateOf(false) }
    val defaultLevel = AppLog.defaultLevel
    val exportedText = stringResource(R.string.logs_exported)
    val exportFailedText = stringResource(R.string.logs_export_failed)
    val exportPickerFailedText = stringResource(R.string.logs_export_picker_failed)

    LaunchedEffect(refreshKey, logLevel) {
        val snapshot = withContext(Dispatchers.IO) {
            AppLog.stats() to AppLog.recentEntries(PREVIEW_ENTRIES)
        }
        stats = snapshot.first
        recent = snapshot.second
    }

    val exporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        SecuritySession.endExternalFlow()
        if (uri == null) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = true
            try {
                val exportedStats = stats
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = AppLog.exportText()
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(text.toByteArray(Charsets.UTF_8))
                        } ?: throw IllegalStateException("output stream unavailable")
                    }
                }
                result
                    .onSuccess {
                        AppLog.info(
                            "log.export",
                            "lines=${exportedStats.lines} bytes=${exportedStats.bytes} " +
                                "level=${logLevel.wireName}",
                        )
                        Toast.makeText(context, exportedText, Toast.LENGTH_SHORT).show()
                        refreshKey++
                    }
                    .onFailure { error ->
                        AppLog.warn("log.export", "result=failed class=${error.javaClass.simpleName}")
                        Toast.makeText(
                            context,
                            exportFailedText,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            } finally {
                busy = false
            }
        }
    }

    val exportLogs: () -> Unit = {
        if (!busy) {
            scope.launch {
                busy = true
                try {
                    if (onAuthorize(SecurityAction.LOG_EXPORT)) {
                        val stamp = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US))
                        // 系统没有可用的文件保存界面时 launch() 会抛异常；从协程里直接抛会结束进程。
                        SecuritySession.beginExternalFlow()
                        runCatching { exporter.launch("crypto-assistant-log-$stamp.txt") }
                            .onSuccess { AppLog.info("log.export.picker", "result=ok") }
                            .onFailure { error ->
                                SecuritySession.endExternalFlow()
                                AppLog.error("log.export.picker", error, "result=failed")
                                Toast.makeText(
                                    context,
                                    exportPickerFailedText,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                    }
                } finally {
                    busy = false
                }
            }
        }
    }

    val requestLevelChange: (LogLevel) -> Unit = { level ->
        if (!busy && level != logLevel) {
            scope.launch {
                busy = true
                try {
                    if (onAuthorize(SecurityAction.LOG_LEVEL_CHANGE)) {
                        onLevelChange(level)
                    }
                } finally {
                    busy = false
                }
            }
        }
    }

    val clearLogs: () -> Unit = {
        if (!busy) {
            scope.launch {
                busy = true
                try {
                    if (onAuthorize(SecurityAction.LOG_CLEAR)) {
                        withContext(Dispatchers.IO) { AppLog.clear() }
                        refreshKey++
                    }
                } finally {
                    busy = false
                }
            }
        }
    }

    val levelLabel = stringResource(logLevel.labelRes)
    val defaultLevelLabel = stringResource(defaultLevel.labelRes)
    val exportActionText = stringResource(R.string.logs_action_export)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenPadding),
    ) {
        Spacer(Modifier.height(Spacing.sm))

        AppCard {
            StatusRow(
                icon = Icons.Outlined.BugReport,
                title = stringResource(R.string.logs_current_level, levelLabel),
                subtitle = stringResource(R.string.logs_actions_hint),
                tone = if (logLevel == LogLevel.OFF) StatusTone.NEUTRAL else StatusTone.SUCCESS,
                modifier = Modifier
                    .combinedClickable(
                        enabled = !busy,
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.logs_action_change_level),
                        onLongClickLabel = stringResource(R.string.logs_action_export),
                        onClick = { showLevelDialog = true },
                        onLongClick = exportLogs,
                    )
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(exportActionText) {
                                exportLogs()
                                true
                            },
                        )
                    },
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(
                    R.string.logs_current_build,
                    BuildConfig.BUILD_TYPE,
                    defaultLevelLabel,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(
                modifier = Modifier.padding(top = Spacing.md),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(Modifier.height(Spacing.sm))
            KeyValueRow(
                stringResource(R.string.logs_label_lines),
                pluralStringResource(
                    R.plurals.logs_value_lines,
                    stats.lines,
                    stats.lines,
                ),
            )
            KeyValueRow(
                stringResource(R.string.logs_label_size),
                formatSize(stats.bytes),
            )
            KeyValueRow(
                stringResource(R.string.logs_label_rotation),
                stringResource(R.string.logs_value_rotation),
            )
            KeyValueRow(
                stringResource(R.string.logs_label_location),
                stringResource(R.string.logs_value_location),
            )
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.logs_privacy_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.logs_level_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.lg))
            Button(
                onClick = { showClearDialog = true },
                enabled = stats.lines > 0 && !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.logs_clear_button))
            }
        }

        SectionTitle(
            text = stringResource(R.string.logs_section_recent),
            trailing = {
                TextButton(onClick = { refreshKey++ }) {
                    Text(stringResource(R.string.logs_refresh))
                }
            },
        )

        AppCard {
            Text(
                text = pluralStringResource(
                    R.plurals.logs_preview_hint,
                    PREVIEW_ENTRIES,
                    PREVIEW_ENTRIES,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.sm))
            if (recent.isEmpty()) {
                Text(
                    text = stringResource(R.string.logs_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        recent.forEach { entry ->
                            entry.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                            Spacer(Modifier.height(Spacing.xs))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.section))
    }

    if (showLevelDialog) {
        AlertDialog(
            onDismissRequest = { showLevelDialog = false },
            title = { Text(stringResource(R.string.logs_level_dialog_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .selectableGroup(),
                ) {
                    Text(
                        text = stringResource(R.string.logs_level_dialog_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    LogLevel.entries.forEach { level ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = level == logLevel,
                                    enabled = !busy,
                                    role = Role.RadioButton,
                                    onClick = { requestLevelChange(level) },
                                )
                                .padding(vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = level == logLevel, onClick = null)
                            Spacer(Modifier.width(Spacing.sm))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(level.labelRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource(level.descriptionRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            // 恢复默认放在弹窗按钮区：选项列表会滚动，藏在列表底部时用户找不到。
            dismissButton = if (logLevel != defaultLevel) {
                {
                    TextButton(
                        onClick = { requestLevelChange(defaultLevel) },
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.logs_restore_default, defaultLevelLabel))
                    }
                }
            } else {
                null
            },
            confirmButton = {
                TextButton(onClick = { showLevelDialog = false }) {
                    Text(stringResource(R.string.logs_done))
                }
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.logs_clear_dialog_title)) },
            text = { Text(stringResource(R.string.logs_clear_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        clearLogs()
                    },
                ) {
                    Text(
                        stringResource(R.string.logs_clear_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

private const val PREVIEW_ENTRIES = 30

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}
