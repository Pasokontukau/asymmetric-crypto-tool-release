package com.cryptoassistant.app.feature.keys

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.keys.IdentityKey
import com.cryptoassistant.app.data.keys.KeyBackupText
import com.cryptoassistant.app.data.keys.KeyError
import com.cryptoassistant.app.data.keys.KeyOperationException
import com.cryptoassistant.app.data.keys.KeySnapshot
import com.cryptoassistant.app.data.keys.PrivateKeyBackup
import com.cryptoassistant.app.data.keys.keyErrorFor
import com.cryptoassistant.app.data.keys.keyErrorMessage
import com.cryptoassistant.app.data.log.AppLog
import com.cryptoassistant.app.feature.security.SecurityAction
import com.cryptoassistant.app.feature.security.SecuritySession
import com.cryptoassistant.app.ui.components.AppCard
import com.cryptoassistant.app.ui.components.InfoBullet
import com.cryptoassistant.app.ui.components.KeyValueRow
import com.cryptoassistant.app.ui.components.SectionTitle
import com.cryptoassistant.app.ui.components.StatusPill
import com.cryptoassistant.app.ui.components.StatusTone
import com.cryptoassistant.app.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Toast 与失败提示在协程里生成，不能用 stringResource；这里只取文案，不参与重组。
@SuppressLint("LocalContextGetResourceValueCall", "LocalContextResourcesRead")
@Composable
fun KeysScreen(
    snapshot: KeySnapshot,
    error: KeyError?,
    onCreate: suspend (String) -> Result<IdentityKey>,
    onImport: suspend (String, String, CharArray) -> Result<IdentityKey>,
    onExport: suspend (CharArray) -> Result<String>,
    onDelete: suspend () -> Result<Unit>,
    /** 安全锁开启时先验证；关闭时实现方直接返回 true。 */
    onAuthorize: suspend (SecurityAction) -> Boolean,
    /** 安全锁当前是否开启；用于在开启瞬间关闭已打开的敏感对话框。 */
    securityLockEnabled: Boolean,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val identity = snapshot.identity
    var showCreate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var pendingSaveText by remember { mutableStateOf<String?>(null) }
    var saveFailureRes by remember { mutableStateOf<Int?>(null) }
    var authorizing by remember { mutableStateOf(false) }

    val clipboardLabel = stringResource(R.string.keys_clipboard_label)
    val copiedText = stringResource(R.string.keys_copied)
    val copyUnverifiedText = stringResource(R.string.keys_copy_unverified)
    val copyFailedText = stringResource(R.string.keys_copy_failed)
    val exportStateLostText = stringResource(R.string.keys_export_state_lost)
    val saveCancelledText = stringResource(R.string.keys_save_cancelled)
    val savedText = stringResource(R.string.keys_saved)

    val copyPublicKey: (String) -> Unit = { publicKey ->
        scope.launch {
            val outcome = copyToClipboard(context, clipboardLabel, publicKey)
            Toast.makeText(
                context,
                when (outcome) {
                    CopyOutcome.VERIFIED -> copiedText
                    CopyOutcome.UNVERIFIED -> copyUnverifiedText
                    CopyOutcome.FAILED -> copyFailedText
                },
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // 安全锁从关到开时，不能让“开启前已经打开的导出/导入/删除对话框”绕过后来的验证。
    LaunchedEffect(securityLockEnabled) {
        if (securityLockEnabled) {
            showExport = false
            showImport = false
            showDelete = false
            pendingSaveText = null
            saveFailureRes = null
        }
    }

    val authorize: (SecurityAction, () -> Unit) -> Unit = { action, block ->
        if (!authorizing) {
            scope.launch {
                authorizing = true
                try {
                    if (onAuthorize(action)) block()
                } finally {
                    authorizing = false
                }
            }
        }
    }
    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        SecuritySession.endExternalFlow()
        val text = pendingSaveText
        if (text == null) {
            Toast.makeText(context, exportStateLostText, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        if (uri == null) {
            pendingSaveText = null
            Toast.makeText(context, saveCancelledText, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { sink ->
                        sink.write(text.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("output stream unavailable")
                }
            }
            written.onSuccess {
                pendingSaveText = null
                Toast.makeText(context, savedText, Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                // 保留 pendingSaveText，让「保存失败」对话框可以重试，不用重新导出口令。
                AppLog.warn("key.export.save", "result=failed class=${error.javaClass.simpleName}")
                saveFailureRes = R.string.keys_save_failed
            }
        }
    }

    // 系统没有可用的文件保存界面（或系统拒绝启动）时，launch() 会抛出异常；
    // 从协程里直接抛会结束进程，所以统一收口成可重试的提示并写诊断日志。
    val launchSavePicker: () -> Unit = {
        if (pendingSaveText == null) {
            Toast.makeText(context, exportStateLostText, Toast.LENGTH_LONG).show()
        } else {
            // 系统保存页停留时间不计入应用锁的离开时长。
            SecuritySession.beginExternalFlow()
            runCatching { saveBackup.launch(defaultBackupFileName()) }
                .onSuccess { AppLog.info("key.export.picker", "result=ok") }
                .onFailure { error ->
                    SecuritySession.endExternalFlow()
                    AppLog.error("key.export.picker", error, "result=failed")
                    saveFailureRes = R.string.keys_save_picker_failed
                }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Spacing.screenPadding,
            end = Spacing.screenPadding,
            bottom = Spacing.xxl,
        ),
    ) {
        error?.let { keyError ->
            item {
                Spacer(Modifier.height(Spacing.md))
                AppCard {
                    Text(
                        text = stringResource(keyError.messageRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onClearError) {
                        Text(stringResource(R.string.common_got_it))
                    }
                }
            }
        }

        if (identity == null) {
            item {
                Spacer(Modifier.height(Spacing.sm))
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.VpnKey,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(Spacing.md))
                        Text(
                            text = stringResource(R.string.keys_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        text = stringResource(R.string.keys_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.lg))
                    Button(
                        onClick = { showCreate = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(Spacing.sm))
                        Text(stringResource(R.string.keys_create_button))
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedButton(
                        onClick = {
                            authorize(SecurityAction.IMPORT_BACKUP) { showImport = true }
                        },
                        enabled = !authorizing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Outlined.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(Spacing.sm))
                        Text(stringResource(R.string.keys_import_button))
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.keys_section_notes)) }

            item {
                AppCard {
                    InfoBullet(stringResource(R.string.keys_note_generate))
                    InfoBullet(stringResource(R.string.keys_note_backup))
                    InfoBullet(stringResource(R.string.keys_note_offline))
                }
            }
        } else {
            item {
                Spacer(Modifier.height(Spacing.sm))
                AppCard(
                    onClick = { copyPublicKey(identity.publicKeyBase64) },
                    onClickLabel = stringResource(R.string.contacts_copy_key),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = identity.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        StatusPill(
                            text = stringResource(R.string.keys_status_ready),
                            tone = StatusTone.SUCCESS,
                        )
                        Spacer(Modifier.size(Spacing.md))
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = stringResource(
                            R.string.home_key_fingerprint,
                            PrivateKeyBackup.formatFingerprint(identity.fingerprint),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = stringResource(R.string.keys_tap_to_copy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    KeyValueRow(
                        stringResource(R.string.keys_label_key_size),
                        stringResource(R.string.keys_value_key_size, identity.keySize),
                    )
                    KeyValueRow(
                        stringResource(R.string.keys_label_created_at),
                        formatCreatedAt(
                            identity.createdAt,
                            stringResource(R.string.keys_unknown_created_at),
                        ),
                    )
                    identity.lastImportedAt?.let { importedAt ->
                        KeyValueRow(
                            stringResource(R.string.keys_label_last_imported),
                            formatDate(importedAt),
                        )
                    }
                    KeyValueRow(
                        stringResource(R.string.keys_label_private_protection),
                        stringResource(R.string.keys_value_private_protection),
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.keys_section_backup)) }

            item {
                AppCard {
                    Text(
                        text = stringResource(R.string.keys_backup_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    OutlinedButton(
                        onClick = {
                            authorize(SecurityAction.EXPORT_BACKUP) { showExport = true }
                        },
                        enabled = !authorizing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Outlined.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(Spacing.sm))
                        Text(stringResource(R.string.keys_export_button))
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedButton(
                        onClick = {
                            authorize(SecurityAction.IMPORT_BACKUP) { showImport = true }
                        },
                        enabled = !authorizing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Outlined.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(Spacing.sm))
                        Text(stringResource(R.string.keys_replace_button))
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.keys_section_danger)) }

            item {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.size(Spacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.keys_delete_row_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.keys_delete_row_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    TextButton(
                        onClick = { showDelete = true },
                        enabled = !authorizing,
                    ) {
                        Text(
                            stringResource(R.string.keys_delete_button),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(Spacing.section)) }
    }

    if (showCreate) {
        CreateIdentityDialog(
            onDismiss = { showCreate = false },
            onCreate = onCreate,
        )
    }

    if (showImport) {
        ImportIdentityDialog(
            replacesExisting = identity != null,
            onDismiss = { showImport = false },
            onImport = onImport,
        )
    }

    if (showExport) {
        ExportBackupDialog(
            onDismiss = { showExport = false },
            onExport = onExport,
            onExported = { backup ->
                showExport = false
                // 文件-only：生成后立即落到用户选择的位置，不再展示可复制的文本。
                pendingSaveText = backup
                launchSavePicker()
            },
        )
    }

    saveFailureRes?.let { messageRes ->
        AlertDialog(
            // 失败时不让点外部消失：备份只在内存里，误触会丢。
            onDismissRequest = {},
            title = { Text(stringResource(R.string.keys_save_failed_title)) },
            text = {
                Text(
                    stringResource(messageRes) + "\n" +
                        stringResource(R.string.keys_save_failed_hint),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        saveFailureRes = null
                        launchSavePicker()
                    },
                ) { Text(stringResource(R.string.keys_save_retry)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        saveFailureRes = null
                        pendingSaveText = null
                    },
                ) { Text(stringResource(R.string.keys_save_discard)) }
            },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.keys_delete_dialog_title)) },
            text = {
                Text(stringResource(R.string.keys_delete_dialog_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!authorizing) {
                            showDelete = false
                            scope.launch {
                                authorizing = true
                                try {
                                    if (!onAuthorize(SecurityAction.DELETE_IDENTITY)) return@launch
                                    onDelete().onFailure { error ->
                                        Toast.makeText(
                                            context,
                                            keyErrorMessage(context, error),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                } finally {
                                    authorizing = false
                                }
                            }
                        }
                    },
                ) {
                    Text(
                        stringResource(R.string.keys_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun CreateIdentityDialog(
    onDismiss: () -> Unit,
    onCreate: suspend (String) -> Result<IdentityKey>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    val createdText = stringResource(R.string.keys_created)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.keys_create_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.keys_name_label)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = stringResource(R.string.keys_create_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (busy) {
                    Spacer(Modifier.height(Spacing.md))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(Spacing.md))
                        Text(
                            stringResource(R.string.keys_generating),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                errorRes?.let { messageRes ->
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        text = stringResource(messageRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank(),
                onClick = {
                    busy = true
                    errorRes = null
                    scope.launch {
                        onCreate(name.trim())
                            .onSuccess {
                                Toast.makeText(context, createdText, Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                            .onFailure { error ->
                                errorRes = keyErrorFor(error).messageRes
                                busy = false
                            }
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (busy) R.string.keys_creating else R.string.keys_create,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

// 文件读取提示在协程里生成，不能用 stringResource；只取提示文案，不参与重组。
@SuppressLint("LocalContextGetResourceValueCall", "LocalContextResourcesRead")
@Composable
private fun ImportIdentityDialog(
    replacesExisting: Boolean,
    onDismiss: () -> Unit,
    onImport: suspend (String, String, CharArray) -> Result<IdentityKey>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var pickedFile by remember { mutableStateOf<LoadedBackup?>(null) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var readingFile by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var fileNotice by remember { mutableStateOf<String?>(null) }
    val importedText = stringResource(R.string.keys_imported)

    val pickBackupFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        SecuritySession.endExternalFlow()
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            readingFile = true
            errorRes = null
            fileNotice = null
            pickedFile = null
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val displayName = queryDisplayName(context, uri)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                        KeyBackupText.readAtMost(stream)
                    } ?: throw IllegalStateException("stream unavailable")
                    val text = KeyBackupText.decode(bytes).getOrThrow()
                    LoadedBackup(displayName, text, bytes.size)
                }
            }
            readingFile = false
            loaded.onSuccess { file ->
                when (val kind = KeyBackupText.inspect(file.text)) {
                    is KeyBackupText.Kind.Bundle -> {
                        pickedFile = file
                        if (name.isBlank()) name = kind.name
                        fileNotice = context.getString(
                            R.string.keys_file_notice_bundle,
                            file.displayLabel(context),
                            kind.keySize,
                        )
                    }

                    is KeyBackupText.Kind.Invalid -> {
                        errorRes = kind.reason.messageRes
                    }
                }
                if (pickedFile != null) {
                    AppLog.info("key.backup.file", "action=read result=ok")
                }
            }.onFailure { error ->
                AppLog.warn("key.backup.file", "action=read result=failed")
                errorRes = if (error is KeyOperationException) {
                    error.reason.messageRes
                } else {
                    R.string.keys_read_failed
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy && !readingFile) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (replacesExisting) {
                        R.string.keys_import_dialog_title_replace
                    } else {
                        R.string.keys_import_dialog_title
                    },
                ),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (replacesExisting) {
                    Text(
                        text = stringResource(R.string.keys_import_replace_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(Spacing.md))
                }
                Text(
                    text = stringResource(R.string.keys_import_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(
                    onClick = {
                        // 系统没有可用的文件选择界面时不能让异常杀掉进程。
                        SecuritySession.beginExternalFlow()
                        runCatching { pickBackupFile.launch(arrayOf("*/*")) }
                            .onFailure { error ->
                                SecuritySession.endExternalFlow()
                                AppLog.error("key.backup.file", error, "action=launch_picker result=failed")
                                errorRes = R.string.keys_import_picker_failed
                            }
                    },
                    enabled = !busy && !readingFile,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Outlined.FileUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(Spacing.sm))
                    Text(
                        stringResource(
                            when {
                                readingFile -> R.string.keys_reading
                                pickedFile != null -> R.string.keys_reselect_file
                                else -> R.string.keys_select_file
                            },
                        ),
                    )
                }
                fileNotice?.let { notice ->
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pickedFile != null) {
                    Spacer(Modifier.height(Spacing.md))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.keys_name_label)) },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Spacing.md))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.keys_password_label)) },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (busy) {
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        stringResource(R.string.keys_verifying_import),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (readingFile) {
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        stringResource(R.string.keys_reading_file),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                errorRes?.let { messageRes ->
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        text = stringResource(messageRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && !readingFile && pickedFile != null &&
                    name.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    val file = pickedFile ?: return@TextButton
                    busy = true
                    errorRes = null
                    scope.launch {
                        onImport(name.trim(), file.text, password.toCharArray())
                            .onSuccess {
                                Toast.makeText(context, importedText, Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                            .onFailure { error ->
                                errorRes = keyErrorFor(error).messageRes
                                busy = false
                            }
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (busy) R.string.keys_importing else R.string.keys_import,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !busy && !readingFile, onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun ExportBackupDialog(
    onDismiss: () -> Unit,
    onExport: suspend (CharArray) -> Result<String>,
    onExported: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.keys_export_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        R.string.keys_export_password_hint,
                        PrivateKeyBackup.MIN_PASSWORD_LENGTH,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.md))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.keys_password_label)) },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.md))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text(stringResource(R.string.keys_reenter_password)) },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        stringResource(R.string.keys_encrypting),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                errorRes?.let { messageRes ->
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        text = stringResource(messageRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && password.length >= PrivateKeyBackup.MIN_PASSWORD_LENGTH,
                onClick = {
                    if (password != confirm) {
                        errorRes = R.string.keys_password_mismatch
                        return@TextButton
                    }
                    busy = true
                    errorRes = null
                    scope.launch {
                        onExport(password.toCharArray())
                            .onSuccess { backup ->
                                busy = false
                                onExported(backup)
                            }
                            .onFailure { error ->
                                errorRes = keyErrorFor(error).messageRes
                                busy = false
                            }
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (busy) R.string.keys_exporting else R.string.keys_export,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** 剪贴板写入结果：校验通过 / 已写入但系统不允许回读 / 明确失败。 */
private enum class CopyOutcome { VERIFIED, UNVERIFIED, FAILED }

/**
 * 写入剪贴板并尽量用描述（label + 时间戳）校验；不读正文。
 *
 * <p>读正文会触发 Android 13+ 的系统提示「xxx pasted from your clipboard」，
 * 还可能因为后台/ROM 限制返回 null，把成功误报成失败。描述读不到时返回
 * [CopyOutcome.UNVERIFIED]，由调用方提示重试，而不是直接报失败。</p>
 */
private suspend fun copyToClipboard(
    context: android.content.Context,
    label: String,
    text: String,
): CopyOutcome {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
        ?: return CopyOutcome.FAILED
    val outcome = writeAndVerify(clipboard, ClipData.newPlainText(label, text), label)
    when (outcome) {
        CopyOutcome.VERIFIED -> AppLog.info("key.public.copy", "result=verified")
        CopyOutcome.UNVERIFIED -> AppLog.info("key.public.copy", "result=unverified")
        CopyOutcome.FAILED -> AppLog.warn("key.public.copy", "result=failed")
    }
    return outcome
}

/**
 * 只读剪贴板描述（label + 时间戳）验证写入，不读剪贴板正文。
 *
 * <p>Android 13 起读取剪贴板正文会弹出系统提示「xxx pasted from your clipboard」；
 * 对一次「复制」来说这是误导，也会盖掉应用自己的成功提示。描述不需要读正文，
 * 用本次写入的 label 和新鲜时间戳即可判断系统是否真的换上了我们的 ClipData。</p>
 */
private suspend fun writeAndVerify(
    clipboard: ClipboardManager,
    clip: ClipData,
    label: String,
): CopyOutcome {
    val startedAt = System.currentTimeMillis()
    if (runCatching { clipboard.setPrimaryClip(clip) }.isFailure) {
        return CopyOutcome.FAILED
    }
    repeat(READBACK_ATTEMPTS) { attempt ->
        val description = runCatching { clipboard.primaryClipDescription }.getOrNull()
        if (description != null) {
            val labelText = description.label?.toString()
            // 少数 ROM 会丢掉 label；此时用「新鲜时间戳 + text/plain」兜底。
            val labelMatches = labelText == label ||
                (labelText == null && description.hasMimeType("text/plain"))
            // 时间戳来自系统保存的 ClipDescription；允许 1 秒的时钟/提交误差。
            val fresh = description.timestamp >= startedAt - CLIP_TIMESTAMP_TOLERANCE_MS
            if (labelMatches && fresh) return CopyOutcome.VERIFIED
            if (attempt == READBACK_ATTEMPTS - 1) {
                // 描述仍是旧的（label 不同或时间戳过旧）：系统没有接受这次写入。
                return CopyOutcome.FAILED
            }
        }
        if (attempt < READBACK_ATTEMPTS - 1) delay(READBACK_DELAY_MS)
    }
    return CopyOutcome.UNVERIFIED
}

/** 从 SAF 的 content:// 查询显示名；只用于界面提示，不写日志。 */
private fun queryDisplayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()

private data class LoadedBackup(
    val displayName: String?,
    val text: String,
    val byteCount: Int,
) {
    fun displayLabel(context: android.content.Context): String {
        val name = displayName?.take(MAX_DISPLAY_NAME)
            ?: context.getString(R.string.keys_selected_file)
        return context.getString(
            R.string.keys_display_label,
            name,
            formatBytes(byteCount),
        )
    }
}

private fun formatBytes(count: Int): String =
    if (count >= 1024) String.format(Locale.US, "%.1f KB", count / 1024.0) else "$count B"

private const val MAX_DISPLAY_NAME = 48
private const val READBACK_ATTEMPTS = 5
private const val READBACK_DELAY_MS = 40L
private const val CLIP_TIMESTAMP_TOLERANCE_MS = 1_000L

private fun defaultBackupFileName(): String =
    "aegix-key-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".txt"

private fun formatCreatedAt(timestamp: Long, unknownText: String): String =
    if (timestamp <= IdentityKey.UNKNOWN_CREATED_AT) {
        unknownText
    } else {
        formatDate(timestamp)
    }

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
