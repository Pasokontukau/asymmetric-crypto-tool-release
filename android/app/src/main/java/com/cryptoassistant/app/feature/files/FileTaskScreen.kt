package com.cryptoassistant.app.feature.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.keys.ContactKey
import com.cryptoassistant.app.data.keys.KeySnapshot
import com.cryptoassistant.app.data.keys.PrivateKeyBackup
import com.cryptoassistant.app.feature.open.IncomingFiles
import com.cryptoassistant.app.feature.security.SecuritySession
import com.cryptoassistant.app.ui.components.AppCard
import com.cryptoassistant.app.ui.components.KeyValueRow
import com.cryptoassistant.app.ui.theme.LocalExtendedColors
import com.cryptoassistant.app.ui.theme.Spacing
import crypto.file.FileProgressListener
import crypto.volume.VolumeSetPlan
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 文件页：从 SAF 选文件 → 流式加密/解密 → 结果留在私有目录 → 分享或另存。
 *
 * 加密与解密是两个独立页面（首页两个入口分别直达），模式由路由的 [initialMode] 决定，
 * 页面内不再提供切换标签；软件的整体介绍集中在「使用说明」页，这里只保留操作本身。
 * 页面不自带密钥管理或联系人编辑；缺少密钥/联系人时给出真实入口。
 * 所有状态来自 [FileTaskViewModel]，旋转屏幕不会丢失正在执行的任务。
 */
@Composable
fun FileTaskScreen(
    keySnapshot: KeySnapshot,
    onOpenKeys: () -> Unit,
    onOpenContacts: () -> Unit,
    modifier: Modifier = Modifier,
    initialMode: FileTaskMode = FileTaskMode.ENCRYPT,
    importRequest: IncomingFiles? = null,
    onImportConsumed: () -> Unit = {},
    /** 由 AppNavigation 从设置快照读出；ViewModel 不直接读 DataStore。 */
    archiveVolumeBytes: Long = ArchiveVolumeSize.MB_500.bytes,
    /** 新选择的默认打包值；用户仍可在文件页逐次关闭。 */
    defaultPackageEnabled: Boolean = true,
    taskViewModel: FileTaskViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by taskViewModel.state.collectAsStateWithLifecycle()

    // 路由决定首次进入的标签页；只应用一次，用户手动切换后不会被重组改回去。
    LaunchedEffect(initialMode) {
        taskViewModel.applyInitialMode(initialMode)
    }

    // 设置只改“下一次选择”的默认值；当前 READY 选择不会被远程改掉。
    LaunchedEffect(defaultPackageEnabled) {
        taskViewModel.setDefaultPackageEnabled(defaultPackageEnabled)
    }

    // 系统入口进来的文件：只让与入口模式一致的目标页消费。
    // 加密/解密两个路由共享同一个 importRequest，若不判断模式，导航过渡期间旧页面会先
    // 把请求吃掉，甚至把本页 ViewModel 切成另一种模式。
    LaunchedEffect(importRequest, initialMode) {
        val request = importRequest ?: return@LaunchedEffect
        if (request.mode != initialMode) return@LaunchedEffect
        taskViewModel.adoptImportedFiles(
            uris = request.uris,
            mode = request.mode,
            releasePersistable = request.persistable,
        )
        onImportConsumed()
    }
    val identity = keySnapshot.identity
    val contacts = keySnapshot.contacts
    val recipient = contacts.firstOrNull { it.id == state.recipientId }

    // 选中的联系人被删除时清掉选择，避免任务开始后才报错。
    LaunchedEffect(contacts, state.recipientId) {
        if (state.recipientId != null && recipient == null) {
            taskViewModel.selectRecipient(null)
        }
    }

    // 保存时要记住是哪一项的结果；SAF 回调本身只带回目标 URI，Activity 重建也不能丢。
    var pendingSaveItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExtractItemId by rememberSaveable { mutableStateOf<String?>(null) }

    val openSource = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        SecuritySession.endExternalFlow()
        if (uris.isNotEmpty()) taskViewModel.selectSources(uris)
    }
    // 缺卷时补选：保留已选队列，只追加新卷；重新选择仍走 openSource。
    val openMoreSource = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        SecuritySession.endExternalFlow()
        if (uris.isNotEmpty()) taskViewModel.addSources(uris)
    }
    val openDirectory = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        SecuritySession.endExternalFlow()
        uri?.let(taskViewModel::selectDirectory)
    }
    val saveResult = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        SecuritySession.endExternalFlow()
        val itemId = pendingSaveItemId
        pendingSaveItemId = null
        if (uri != null && itemId != null) {
            if (itemId == ARCHIVE_RESULT_ID) {
                taskViewModel.saveArchiveResultTo(uri)
            } else {
                taskViewModel.saveResultTo(itemId, uri)
            }
        }
    }
    // 多卷必须一次选目录，ViewModel 用 DocumentsContract.createDocument 逐卷写出；
    // 单卷仍走上面的 CreateDocument，保持旧版“另存为”体验。
    val saveArchiveDirectory = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        SecuritySession.endExternalFlow()
        if (uri != null) taskViewModel.saveAllPartsTo(uri)
    }
    // 解压同样一次选一个目标文件夹；回调只带回 tree URI，所以记住是哪一项。
    val extractDirectory = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        SecuritySession.endExternalFlow()
        val itemId = pendingExtractItemId
        pendingExtractItemId = null
        if (uri != null && itemId != null) {
            taskViewModel.extractResultTo(itemId, uri)
        }
    }
    val requestSave: (String, String) -> Unit = { itemId, fileName ->
        pendingSaveItemId = itemId
        // 系统选择器停留时间不计入应用锁的离开时长。
        SecuritySession.beginExternalFlow()
        saveResult.launch(fileName)
    }
    val requestExtract: (String) -> Unit = { itemId ->
        pendingExtractItemId = itemId
        SecuritySession.beginExternalFlow()
        extractDirectory.launch(null)
    }

    // 开始前只提示会拆卷的项；单文件走精确预算，打包走保守估算。
    state.startPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = taskViewModel::dismissStartPreview,
            title = {
                Text(
                    stringResource(
                        if (preview.lines.any { it.packaged } && preview.totalParts == 1) {
                            R.string.files_volume_preview_title_package
                        } else {
                            R.string.files_volume_preview_title
                        },
                    ),
                )
            },
            text = {
                Column {
                    Text(
                        text = stringResource(
                            R.string.files_volume_preview_intro,
                            preview.volumeLimitLabel,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    preview.lines.forEach { line ->
                        Text(
                            text = stringResource(
                                R.string.files_volume_preview_line,
                                line.name + if (line.packaged) {
                                    stringResource(R.string.files_volume_preview_archive)
                                } else {
                                    ""
                                },
                                line.partCount,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = stringResource(R.string.files_volume_preview_total, preview.totalParts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (preview.lines.any { it.packaged }) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = stringResource(R.string.files_volume_preview_estimated),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (preview.tooMany) {
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = stringResource(R.string.files_volume_preview_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        text = stringResource(R.string.files_volume_preview_question),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { taskViewModel.confirmStartPreview(archiveVolumeBytes) }) {
                    Text(stringResource(R.string.files_volume_preview_start))
                }
            },
            dismissButton = {
                TextButton(onClick = taskViewModel::dismissStartPreview) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = Spacing.screenPadding,
            end = Spacing.screenPadding,
            bottom = Spacing.xxl,
        ),
    ) {
        item {
            Spacer(Modifier.height(Spacing.sm))
            // 功能介绍集中在「首页 → 使用说明」工具书；这里只保留阻止操作的真实提示。
            if (identity == null) {
                MissingKeyBanner(mode = state.mode, onOpenKeys = onOpenKeys)
            }
        }

        state.error?.let { message ->
            item {
                Spacer(Modifier.height(Spacing.md))
                AppCard {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(Spacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.files_error_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = taskViewModel::clearError) {
                        Text(stringResource(R.string.common_got_it))
                    }
                }
            }
        }

        state.notice?.let { message ->
            item {
                Spacer(Modifier.height(Spacing.md))
                AppCard {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val archiveResult = state.archiveResult
        when {
            state.isRunning -> item {
                Spacer(Modifier.height(Spacing.lg))
                RunningCard(state = state, onCancel = taskViewModel::cancel)
            }

            // 归档结果优先于批量汇总：打包成功后 queue 仍是源文件队列，不能显示成 0 个成功。
            archiveResult != null -> item {
                Spacer(Modifier.height(Spacing.lg))
                ArchiveResultCard(
                    state = state,
                    result = archiveResult,
                    onSharePart = { part -> shareArchivePart(context, archiveResult, part) },
                    onShareAll = { shareArchive(context, archiveResult) },
                    onSaveSingle = {
                        requestSave(ARCHIVE_RESULT_ID, archiveResult.displayName)
                    },
                    onSaveAll = {
                        SecuritySession.beginExternalFlow()
                        saveArchiveDirectory.launch(null)
                    },
                    onReset = taskViewModel::reset,
                )
            }

            state.isBatch && state.status in setOf(
                FileTaskStatus.SUCCEEDED,
                FileTaskStatus.FAILED,
                FileTaskStatus.PARTIAL,
            ) -> {
                item {
                    Spacer(Modifier.height(Spacing.lg))
                    BatchSummaryCard(
                        state = state,
                        onShareAll = { results -> shareFiles(context, results) },
                        onReset = taskViewModel::reset,
                    )
                }
                items(state.queue, key = { it.id }) { item ->
                    BatchItemCard(
                        state = state,
                        item = item,
                        onShare = { result -> shareFile(context, result) },
                        onOpen = { result -> openWithOtherApp(context, result) },
                        onSave = { result -> requestSave(item.id, result.displayName) },
                        onExtract = { requestExtract(item.id) },
                        onCancelExtract = taskViewModel::cancelExtraction,
                    )
                }
            }

            state.result != null -> item {
                Spacer(Modifier.height(Spacing.lg))
                ResultCard(
                    state = state,
                    onShare = { result -> shareFile(context, result) },
                    onOpen = { result -> openWithOtherApp(context, result) },
                    onSave = { result ->
                        state.queue.singleOrNull()?.let { requestSave(it.id, result.displayName) }
                    },
                    onExtract = {
                        state.queue.singleOrNull()?.let { requestExtract(it.id) }
                    },
                    onCancelExtract = taskViewModel::cancelExtraction,
                    onReset = taskViewModel::reset,
                )
            }

            else -> {
                item {
                    Spacer(Modifier.height(Spacing.section))
                    SourceCard(
                        mode = state.mode,
                        items = state.queue,
                        loading = state.loading,
                        onPick = {
                            SecuritySession.beginExternalFlow()
                            openSource.launch(arrayOf("*/*"))
                        },
                        onPickDirectory = {
                            SecuritySession.beginExternalFlow()
                            openDirectory.launch(null)
                        },
                    )
                }
                state.externalPartSet?.let { externalSet ->
                    item {
                        Spacer(Modifier.height(Spacing.md))
                        ExternalPartSetCard(
                            set = externalSet,
                            onAddMissing = {
                                SecuritySession.beginExternalFlow()
                                openMoreSource.launch(arrayOf("*/*"))
                            },
                            onReselect = {
                                SecuritySession.beginExternalFlow()
                                openSource.launch(arrayOf("*/*"))
                            },
                            onSelectGroup = taskViewModel::selectExternalPartGroup,
                        )
                    }
                }
                if (state.mode == FileTaskMode.ENCRYPT) {
                    val encryptedCount = state.queue.count { it.source.alreadyEncrypted }
                    if (encryptedCount > 0) {
                        item {
                            Spacer(Modifier.height(Spacing.md))
                            AlreadyEncryptedNotice(count = encryptedCount)
                        }
                    }
                    item {
                        Spacer(Modifier.height(Spacing.md))
                        RecipientCard(
                            contacts = contacts,
                            recipientId = state.recipientId,
                            onSelect = taskViewModel::selectRecipient,
                            onOpenContacts = onOpenContacts,
                        )
                    }
                    if (state.packageEligible) {
                        item {
                            Spacer(Modifier.height(Spacing.md))
                            ArchivePackageCard(
                                enabled = state.packageEnabled,
                                volumeLimitLabel = ArchiveVolumeSize.entries
                                    .firstOrNull { it.bytes == archiveVolumeBytes }
                                    ?.let { stringResource(it.labelRes) },
                                onEnabledChange = taskViewModel::setPackageEnabled,
                            )
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(Spacing.lg))
                    StartCard(
                        mode = state.mode,
                        fileCount = state.runnableCount,
                        skippedCount = state.queue.size - state.runnableCount,
                        hasIdentity = identity != null,
                        hasRecipient = recipient != null,
                        mergeParts = state.externalPartSet
                            ?.takeIf { it.isMultiPart && it.status == VolumeSetPlan.Status.COMPLETE }
                            ?.partCount,
                        mergeToFile = state.externalPartSet?.sourceIsArchive == false,
                        externalSetBlocked = state.externalPartSet?.blocksStart == true,
                        onStart = { taskViewModel.requestStart(archiveVolumeBytes) },
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(Spacing.section))
            SecurityNote()
        }
    }
}

/** 没有本机密钥时的提示：这是阻止操作的真实条件，功能介绍见「使用说明」页。 */
@Composable
private fun MissingKeyBanner(mode: FileTaskMode, onOpenKeys: () -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.VpnKey,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(Spacing.md))
            Text(
                text = stringResource(
                    if (mode == FileTaskMode.ENCRYPT) {
                        R.string.files_missing_key_encrypt
                    } else {
                        R.string.files_missing_key_decrypt
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        OutlinedButton(onClick = onOpenKeys, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.files_open_keys))
        }
    }
}

@Composable
private fun SourceCard(
    mode: FileTaskMode,
    items: List<FileQueueItem>,
    loading: Boolean,
    onPick: () -> Unit,
    onPickDirectory: () -> Unit,
) {
    AppCard {
        Text(
            text = stringResource(R.string.files_step_source),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = if (mode == FileTaskMode.ENCRYPT) {
                stringResource(R.string.files_source_desc_encrypt)
            } else {
                stringResource(
                    R.string.files_source_desc_decrypt,
                    EncryptedFileFormat.EXTENSION,
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        OutlinedButton(
            onClick = onPick,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                stringResource(
                    if (items.isEmpty()) R.string.files_pick_files else R.string.files_repick_files,
                ),
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        OutlinedButton(
            onClick = onPickDirectory,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(stringResource(R.string.files_pick_folder))
        }
        Text(
            text = stringResource(
                R.string.files_scan_limit,
                DirectoryWalker.MAX_FILES,
                DirectoryWalker.MAX_TOTAL_BYTES / (1024L * 1024L * 1024L),
                DirectoryWalker.MAX_ENTRIES,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        if (loading) {
            Spacer(Modifier.height(Spacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.files_loading_sources),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (items.size > 1) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.files_selected_count_label, items.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (items.isNotEmpty()) {
            val visible = if (items.size == 1) items else items.take(MAX_VISIBLE_SOURCE_FILES)
            visible.forEach { item ->
                Spacer(Modifier.height(Spacing.sm))
                SourceFileRow(
                    file = item.source,
                    error = item.error.takeIf { item.status == FileQueueStatus.FAILED },
                )
            }
            val hidden = items.size - visible.size
            if (hidden > 0) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.files_more_hidden, hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SourceFileRow(file: SelectedFile, error: String?) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (error == null) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (error == null) Icons.Outlined.Description else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = if (error == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (error == null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val path = file.relativePath?.takeIf { it != file.displayName }
                val sizeText = file.size?.let(::formatBytes)
                    ?: stringResource(R.string.files_size_unknown)
                val alreadySuffix = stringResource(R.string.files_already_cipher_suffix)
                Text(
                    text = error ?: buildString {
                        if (path != null) {
                            append(path)
                            append(" · ")
                        }
                        append(sizeText)
                        if (file.alreadyEncrypted) append(" · ").append(alreadySuffix)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 加密前提醒：选中的文件里已经包含本工具密文。
 *
 * 不阻止继续——二次加密有时是用户本意；这里只把后果说清楚，并给出改走解密的路径。
 */
@Composable
private fun AlreadyEncryptedNotice(count: Int) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.files_already_cipher_title, count),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.files_already_cipher_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ArchivePackageCard(
    enabled: Boolean,
    volumeLimitLabel: String?,
    onEnabledChange: (Boolean) -> Unit,
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.files_archive_option_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.files_archive_option_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        if (volumeLimitLabel != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(
                    R.string.files_archive_option_volume,
                    volumeLimitLabel,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RecipientCard(
    contacts: List<ContactKey>,
    recipientId: String?,
    onSelect: (String) -> Unit,
    onOpenContacts: () -> Unit,
) {
    AppCard {
        Text(
            text = stringResource(R.string.files_step_recipient),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.files_recipient_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (contacts.isEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.files_no_contacts),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenContacts) {
                Text(stringResource(R.string.files_add_contacts))
            }
            return@AppCard
        }
        Spacer(Modifier.height(Spacing.sm))
        Column(modifier = Modifier.selectableGroup()) {
            contacts.forEach { contact ->
                val selected = contact.id == recipientId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onSelect(contact.id) },
                        )
                        .padding(vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.width(Spacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = contact.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.files_fingerprint_tail,
                                fingerprintTail(contact.fingerprint),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        val selectedContact = contacts.firstOrNull { it.id == recipientId }
        if (selectedContact != null) {
            Spacer(Modifier.height(Spacing.sm))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(
                    R.string.files_encrypting_to,
                    selectedContact.name,
                    fingerprintTail(selectedContact.fingerprint),
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ExternalPartSetCard(
    set: ExternalPartSetState,
    onAddMissing: () -> Unit,
    onReselect: () -> Unit,
    onSelectGroup: (String) -> Unit,
) {
    val complete = set.status == VolumeSetPlan.Status.COMPLETE
    AppCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = if (complete) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = if (complete) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.files_external_group_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = when (set.status) {
                        VolumeSetPlan.Status.COMPLETE -> if (set.isMultiPart) {
                            stringResource(
                                if (set.sourceIsArchive) {
                                    R.string.files_external_set_ready
                                } else {
                                    R.string.files_external_set_ready_file
                                },
                                set.partCount,
                            )
                        } else {
                            stringResource(R.string.files_external_set_single)
                        }

                        VolumeSetPlan.Status.INCOMPLETE -> stringResource(
                            R.string.file_error_external_missing,
                            set.missingIndexes.joinToString("、") { it.toString() },
                            set.partCount,
                        )

                        VolumeSetPlan.Status.DUPLICATE ->
                            stringResource(R.string.file_error_external_duplicate)

                        VolumeSetPlan.Status.MIXED -> if (set.groups.size > 1) {
                            stringResource(R.string.files_external_mixed_groups, set.groups.size)
                        } else {
                            stringResource(R.string.files_external_mixed_plain)
                        }

                        else -> stringResource(R.string.file_error_external_invalid)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (set.isMultiPart) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = stringResource(
                            R.string.files_external_group_received,
                            set.receivedCount,
                            set.partCount,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        if (set.groups.size > 1 || set.hasNonPartSelection) {
            Spacer(Modifier.height(Spacing.md))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(Spacing.sm))
            if (set.hasNonPartSelection && set.groups.size == 1) {
                Text(
                    text = stringResource(R.string.files_external_mixed_plain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(Spacing.xs))
            }
            set.groups.forEach { group ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                R.string.files_external_group_set,
                                group.setCode,
                            ) + (group.archiveName?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.files_external_group_received,
                                group.receivedCount,
                                group.partCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onSelectGroup(group.setId) }) {
                        Text(stringResource(R.string.files_external_group_select))
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))
        if (complete) {
            Text(
                text = stringResource(R.string.files_external_group_hint, set.partCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (set.status == VolumeSetPlan.Status.INCOMPLETE) {
                    OutlinedButton(
                        onClick = onAddMissing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.files_external_add_missing))
                    }
                }
                OutlinedButton(
                    onClick = onReselect,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.files_external_reselect))
                }
            }
        }
    }
}

@Composable
private fun StartCard(
    mode: FileTaskMode,
    fileCount: Int,
    skippedCount: Int,
    hasIdentity: Boolean,
    hasRecipient: Boolean,
    mergeParts: Int?,
    /** 分卷还原后是原文件（单文件直接分卷）而不是压缩包。 */
    mergeToFile: Boolean,
    externalSetBlocked: Boolean,
    onStart: () -> Unit,
) {
    val encrypt = mode == FileTaskMode.ENCRYPT
    val hasSource = fileCount > 0
    val enabled = hasSource && hasIdentity && (!encrypt || hasRecipient) && !externalSetBlocked
    Column {
        Button(
            onClick = onStart,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Icon(
                imageVector = if (encrypt) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                if (mergeParts != null) {
                    stringResource(R.string.files_start_merge_parts, mergeParts)
                } else {
                    stringResource(
                        when {
                            fileCount > 1 && encrypt ->
                                R.string.files_start_encrypt_multi

                            fileCount > 1 -> R.string.files_start_decrypt_multi
                            encrypt -> R.string.files_start_encrypt
                            else -> R.string.files_start_decrypt
                        },
                        if (fileCount > 1) fileCount else 0,
                    )
                },
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = when {
                !hasIdentity -> stringResource(R.string.files_hint_need_identity)
                !hasSource -> stringResource(R.string.files_hint_need_source)
                encrypt && !hasRecipient -> stringResource(R.string.files_hint_need_recipient)
                externalSetBlocked -> stringResource(R.string.files_hint_external_blocked)
                mergeParts != null -> stringResource(
                    if (mergeToFile) {
                        R.string.files_hint_merge_parts_file
                    } else {
                        R.string.files_hint_merge_parts
                    },
                    mergeParts,
                )
                fileCount > 1 -> stringResource(
                    if (encrypt) {
                        R.string.files_hint_batch_encrypt
                    } else {
                        R.string.files_hint_batch_decrypt
                    },
                    fileCount,
                )

                else -> stringResource(
                    if (encrypt) R.string.files_hint_encrypt else R.string.files_hint_decrypt,
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (skippedCount > 0) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.files_hint_skipped, skippedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RunningCard(state: FileTaskUiState, onCancel: () -> Unit) {
    val encrypt = state.mode == FileTaskMode.ENCRYPT
    val archive = state.archiveRunning
    val progress = if (archive) {
        ArchiveProgress.overall(
            phase = state.archivePhase,
            filesDone = state.archiveFilesDone,
            filesTotal = state.archiveFilesTotal,
            processed = state.processed,
            total = state.total,
            volumeIndex = state.volumeIndex,
            volumeTotal = state.volumeTotal,
        )
    } else {
        progressFraction(state)
    }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                val index = state.queue.indexOfFirst { it.id == state.currentItemId }
                val totalFiles = state.queue.size
                if (!archive && totalFiles > 1 && index >= 0) {
                    Text(
                        text = stringResource(
                            R.string.files_progress_index,
                            index + 1,
                            totalFiles,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                val title = when (state.archivePhase) {
                    ArchivePhase.PACKAGING -> R.string.files_archive_phase_packaging
                    ArchivePhase.WRITING -> R.string.files_archive_phase_writing
                    else -> phaseTitle(state.phase)
                }
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val archiveSubtitle = when {
                    state.archivePhase == ArchivePhase.PACKAGING &&
                        state.archiveFilesTotal > 0 -> stringResource(
                        R.string.files_archive_progress_files,
                        state.archiveFilesDone,
                        state.archiveFilesTotal,
                    )

                    state.archivePhase == ArchivePhase.PACKAGING ->
                        stringResource(R.string.files_archive_phase_packaging_subtitle)

                    state.archivePhase == ArchivePhase.WRITING && state.volumeIndex != null ->
                        stringResource(
                            R.string.files_archive_progress_volume,
                            state.volumeIndex,
                            state.volumeTotal ?: 0,
                        )

                    else -> null
                }
                Text(
                    text = archiveSubtitle ?: state.currentItem?.source?.displayName
                        ?: stringResource(phaseSubtitle(state.phase, encrypt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(Spacing.lg))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${(progress * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (state.total > 0) {
                    "${formatBytes(state.processed)} / ${formatBytes(state.total)}"
                } else {
                    formatBytes(state.processed)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!archive && state.queue.size > 1) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(
                    R.string.files_batch_completed_count,
                    state.completedCount,
                    state.queue.size,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!archive && state.volumeIndex != null && state.volumeTotal != null) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(
                    R.string.files_volume_progress,
                    state.volumeIndex,
                    state.volumeTotal,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = stringResource(
                when {
                    archive -> R.string.files_archive_running_hint
                    encrypt -> R.string.files_running_encrypt_hint
                    else -> R.string.files_running_decrypt_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_cancel))
        }
    }
}

@Composable
private fun BatchSummaryCard(
    state: FileTaskUiState,
    onShareAll: (List<FileTaskResult>) -> Unit,
    onReset: () -> Unit,
) {
    val results = state.results
    val allSucceeded = state.successCount == state.queue.size
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        if (allSucceeded) {
                            LocalExtendedColors.current.successContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (allSucceeded) {
                        Icons.Outlined.CheckCircle
                    } else {
                        Icons.Outlined.ErrorOutline
                    },
                    contentDescription = null,
                    tint = if (allSucceeded) {
                        LocalExtendedColors.current.onSuccessContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (allSucceeded) R.string.files_batch_done else R.string.files_batch_end,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        R.string.files_batch_summary,
                        state.queue.size,
                        state.successCount,
                        state.failureCount,
                        state.cancelledCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.failureCount > 0 || state.cancelledCount > 0) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.files_batch_failure_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        if (results.size > 1) {
            Button(
                onClick = { onShareAll(results) },
                enabled = !state.saving && !state.extracting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.files_share_all, results.size))
            }
            Spacer(Modifier.height(Spacing.sm))
        }
        TextButton(
            onClick = onReset,
            enabled = !state.saving && !state.extracting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.files_process_next_batch))
        }
    }
}

@Composable
private fun BatchItemCard(
    state: FileTaskUiState,
    item: FileQueueItem,
    onShare: (FileTaskResult) -> Unit,
    onOpen: (FileTaskResult) -> Unit,
    onSave: (FileTaskResult) -> Unit,
    onExtract: () -> Unit,
    onCancelExtract: () -> Unit,
) {
    val result = item.result
    val saving = state.savingItemId == item.id
    val extracting = state.extractingItemId == item.id
    val busy = state.saving || state.extracting
    val success = item.status == FileQueueStatus.SUCCEEDED && result != null
    val tint = when {
        success -> LocalExtendedColors.current.success
        item.status == FileQueueStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    AppCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = when {
                    success -> Icons.Outlined.CheckCircle
                    item.status == FileQueueStatus.FAILED -> Icons.Outlined.ErrorOutline
                    else -> Icons.Outlined.Description
                },
                contentDescription = null,
                tint = tint,
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result?.displayName ?: item.source.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        result != null -> stringResource(
                            R.string.files_item_original,
                            item.source.relativePath?.takeIf { it != item.source.displayName }
                                ?: result.sourceName,
                            formatBytes(result.bytes),
                        )

                        item.status == FileQueueStatus.FAILED ->
                            item.error ?: stringResource(R.string.files_item_failed)

                        item.status == FileQueueStatus.CANCELLED ->
                            stringResource(R.string.files_item_cancelled)

                        item.status == FileQueueStatus.RUNNING ->
                            stringResource(R.string.files_item_running)

                        else -> stringResource(R.string.files_item_pending)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.status == FileQueueStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (result != null) {
                    Spacer(Modifier.height(Spacing.xs))
                    if (item.saved) {
                        Text(
                            text = stringResource(R.string.files_item_saved),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalExtendedColors.current.success,
                        )
                    }
                    if (result.mode == FileTaskMode.DECRYPT) {
                        SignatureStatus(result)
                        if (result.volumeCount != null) {
                            Text(
                                text = stringResource(
                                    R.string.files_volume_summary,
                                    result.volumeCount,
                                    stringResource(
                                        if (result.contentHashVerified == true) {
                                            R.string.files_hash_verified
                                        } else {
                                            R.string.files_hash_missing
                                        },
                                    ),
                                    if (result.sourceIsFolder) {
                                        stringResource(R.string.files_folder_zip)
                                    } else {
                                        ""
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(
                                R.string.files_item_recipient,
                                result.recipientName ?: "—",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (result != null) {
            if (extracting) {
                Spacer(Modifier.height(Spacing.sm))
                ExtractProgress(state = state, onCancel = onCancelExtract)
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { onShare(result) },
                    enabled = !busy,
                ) {
                    Text(
                        stringResource(
                            if (result.mode == FileTaskMode.ENCRYPT) {
                                R.string.files_share_cipher
                            } else {
                                R.string.files_share_plain
                            },
                        ),
                    )
                }
                if (result.mode == FileTaskMode.DECRYPT) {
                    TextButton(
                        onClick = { onOpen(result) },
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.files_open))
                    }
                }
                if (result.isExtractableArchive() && !extracting) {
                    TextButton(
                        onClick = onExtract,
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.files_extract_action))
                    }
                }
                if (extracting) {
                    TextButton(onClick = onCancelExtract) {
                        Text(stringResource(R.string.files_extract_cancel))
                    }
                } else {
                    TextButton(
                        onClick = { onSave(result) },
                        enabled = !busy,
                    ) {
                        Text(
                            stringResource(
                                if (saving) R.string.files_saving else R.string.files_save,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtractProgress(state: FileTaskUiState, onCancel: () -> Unit) {
    val total = state.extractFilesTotal
    Column(modifier = Modifier.fillMaxWidth()) {
        if (total > 0) {
            val progress = (state.extractFilesDone.toFloat() / total).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(Spacing.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (total > 0) {
                    stringResource(
                        R.string.files_extract_progress,
                        state.extractFilesDone,
                        total,
                    )
                } else {
                    stringResource(R.string.files_extract_preparing)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.files_extract_cancel))
            }
        }
    }
}

@Composable
private fun ResultCard(
    state: FileTaskUiState,
    onShare: (FileTaskResult) -> Unit,
    onOpen: (FileTaskResult) -> Unit,
    onSave: (FileTaskResult) -> Unit,
    onExtract: () -> Unit,
    onCancelExtract: () -> Unit,
    onReset: () -> Unit,
) {
    val result = state.result ?: return
    val encrypt = result.mode == FileTaskMode.ENCRYPT
    val mergedParts = result.mergedPartCount
    val extracting = state.extractingItemId != null &&
        state.extractingItemId == state.queue.singleOrNull()?.id
    val busy = state.saving || state.extracting
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        LocalExtendedColors.current.successContainer,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = LocalExtendedColors.current.onSuccessContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (mergedParts != null) {
                        stringResource(R.string.files_result_merged_title, mergedParts)
                    } else {
                        stringResource(
                            if (encrypt) {
                                R.string.files_result_encrypted
                            } else {
                                R.string.files_result_decrypted
                            },
                        )
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = result.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(Spacing.md))
        KeyValueRow(
            stringResource(R.string.files_label_size),
            formatBytes(result.bytes),
        )
        KeyValueRow(
            stringResource(R.string.files_label_original),
            result.sourceName,
        )
        KeyValueRow(
            stringResource(R.string.files_label_save_state),
            stringResource(
                if (state.queue.singleOrNull()?.saved == true) {
                    R.string.files_saved_location
                } else {
                    R.string.files_not_saved
                },
            ),
        )
        if (encrypt) {
            KeyValueRow(
                stringResource(R.string.files_label_recipient),
                result.recipientName ?: "—",
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(
                    R.string.files_cipher_name_hint,
                    FileTaskStorage.ENCRYPTED_NAME_PREFIX,
                    EncryptedFileFormat.EXTENSION,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.height(Spacing.sm))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(Spacing.sm))
            SignatureStatus(result)
            if (mergedParts != null) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    // 单文件直接分卷还原出来的是原文件，不是压缩包，文案要区分。
                    text = stringResource(
                        if (result.isArchive || result.sourceIsFolder) {
                            R.string.files_result_merged_hint
                        } else {
                            R.string.files_result_merged_file_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (result.volumeCount != null) {
                KeyValueRow(
                    stringResource(R.string.files_label_volume),
                    stringResource(R.string.files_volume_count, result.volumeCount),
                )
                KeyValueRow(
                    stringResource(R.string.files_label_hash),
                    stringResource(
                        if (result.contentHashVerified == true) {
                            R.string.files_hash_ok
                        } else {
                            R.string.files_hash_metadata_missing
                        },
                    ),
                )
                if (result.sourceIsFolder) {
                    KeyValueRow(
                        stringResource(R.string.files_label_type),
                        stringResource(R.string.files_folder_zip_value),
                    )
                }
                if (result.partNamesVerified == false) {
                    Text(
                        text = stringResource(R.string.files_part_names_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.files_gcm_verified),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = stringResource(
                R.string.files_save_lease_hint,
                FileTaskStorage.EXPORT_LEASE_MILLIS / 3_600_000,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        if (encrypt) {
            Button(
                onClick = { onShare(result) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.files_share_cipher_file))
            }
        } else {
            // 明文才谈得上「用某个应用打开」；密文交给 WPS 等应用没有意义，所以加密结果不显示这颗按钮。
            Button(
                onClick = { onOpen(result) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.files_open_other_app))
            }
            Spacer(Modifier.height(Spacing.sm))
            OutlinedButton(
                onClick = { onShare(result) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.files_share_plain_file))
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        OutlinedButton(
            onClick = { onSave(result) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Outlined.SaveAlt,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                stringResource(
                    if (state.saving) R.string.files_saving_ellipsis else R.string.files_save_to,
                ),
            )
        }
        if (result.isExtractableArchive()) {
            Spacer(Modifier.height(Spacing.sm))
            if (extracting) {
                ExtractProgress(state = state, onCancel = onCancelExtract)
            } else {
                OutlinedButton(
                    onClick = onExtract,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.files_extract_to_folder))
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.files_extract_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(
            onClick = onReset,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.files_process_next))
        }
    }
}

@Composable
private fun ArchiveResultCard(
    state: FileTaskUiState,
    result: FileTaskResult,
    onSharePart: (File) -> Unit,
    onShareAll: () -> Unit,
    onSaveSingle: () -> Unit,
    onSaveAll: () -> Unit,
    onReset: () -> Unit,
) {
    val plan = ArchiveSharePlan.from(result)
    val multi = plan.parts.size > 1
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(LocalExtendedColors.current.successContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = LocalExtendedColors.current.onSuccessContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.files_archive_result_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (multi) {
                        stringResource(
                            R.string.files_archive_result_multi,
                            plan.parts.size,
                            formatBytes(result.bytes),
                        )
                    } else {
                        stringResource(
                            R.string.files_archive_result_single,
                            formatBytes(result.bytes),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(Spacing.md))
        KeyValueRow(stringResource(R.string.files_label_original), result.sourceName)
        KeyValueRow(stringResource(R.string.files_label_size), formatBytes(result.bytes))
        KeyValueRow(
            stringResource(R.string.files_label_volume),
            stringResource(R.string.files_volume_count, plan.parts.size),
        )
        result.archiveFileCount?.let { count ->
            KeyValueRow(
                stringResource(R.string.files_archive_file_count),
                count.toString(),
            )
        }
        KeyValueRow(
            stringResource(R.string.files_label_save_state),
            stringResource(
                if (state.archiveSaved) R.string.files_saved_location else R.string.files_not_saved,
            ),
        )
        if (multi) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(
                    R.string.files_archive_result_multi_hint,
                    plan.parts.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.md))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.files_archive_parts_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            plan.parts.forEach { part ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = part.file.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.files_archive_part_meta,
                                part.partIndex,
                                formatBytes(part.file.length()),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { onSharePart(part.file) },
                        enabled = !state.saving,
                    ) {
                        Text(stringResource(R.string.files_archive_share_part))
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xs))
        }
        Spacer(Modifier.height(Spacing.md))
        Button(
            onClick = onShareAll,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Outlined.Share,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                if (multi) {
                    stringResource(R.string.files_archive_share_all, plan.parts.size)
                } else {
                    stringResource(R.string.files_share_cipher_file)
                },
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        OutlinedButton(
            onClick = if (multi) onSaveAll else onSaveSingle,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Outlined.SaveAlt,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                if (state.saving) {
                    stringResource(R.string.files_saving_ellipsis)
                } else if (multi) {
                    stringResource(R.string.files_archive_save_all, plan.parts.size)
                } else {
                    stringResource(R.string.files_save_to)
                },
            )
        }
        TextButton(
            onClick = onReset,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (multi) R.string.files_process_next_batch else R.string.files_process_next,
                ),
            )
        }
    }
}

@Composable
private fun SignatureStatus(result: FileTaskResult) {
    val valid = result.signatureValid == true
    val color = if (valid) LocalExtendedColors.current.success else MaterialTheme.colorScheme.tertiary
    val text = when {
        valid && result.senderName != null ->
            stringResource(R.string.files_signature_valid_from, result.senderName)

        valid -> stringResource(R.string.files_signature_valid_unknown)
        else -> stringResource(R.string.files_signature_invalid)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (valid) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

@Composable
private fun SecurityNote() {
    AppCard {
        Text(
            text = stringResource(R.string.files_security_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.files_security_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val MAX_VISIBLE_SOURCE_FILES = 6

private fun phaseTitle(phase: FileProgressListener.Phase?): Int = when (phase) {
    FileProgressListener.Phase.SIGNING -> R.string.files_phase_signing
    FileProgressListener.Phase.ENCRYPTING -> R.string.files_phase_encrypting
    FileProgressListener.Phase.DECRYPTING -> R.string.files_phase_decrypting
    null -> R.string.files_phase_preparing
}

private fun phaseSubtitle(phase: FileProgressListener.Phase?, encrypt: Boolean): Int = when (phase) {
    FileProgressListener.Phase.SIGNING -> R.string.files_phase_signing_subtitle
    FileProgressListener.Phase.ENCRYPTING -> R.string.files_phase_encrypting_subtitle
    FileProgressListener.Phase.DECRYPTING -> R.string.files_phase_decrypting_subtitle
    null -> if (encrypt) R.string.files_prepare_encrypt else R.string.files_prepare_decrypt
}

private fun progressFraction(state: FileTaskUiState): Float {
    val phaseFraction = if (state.total <= 0) {
        if (state.phase == null) 0f else 1f
    } else {
        (state.processed.toFloat() / state.total).coerceIn(0f, 1f)
    }
    return when (state.phase) {
        FileProgressListener.Phase.SIGNING -> phaseFraction * 0.5f
        FileProgressListener.Phase.ENCRYPTING -> 0.5f + phaseFraction * 0.5f
        FileProgressListener.Phase.DECRYPTING -> phaseFraction
        null -> 0f
    }.coerceIn(0f, 1f)
}

private fun fingerprintTail(fingerprint: String): String =
    PrivateKeyBackup.formatFingerprint(fingerprint).takeLast(19)

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    val pattern = if (value >= 100) "%.0f %s" else "%.1f %s"
    return String.format(Locale.US, pattern, value, units[index])
}
