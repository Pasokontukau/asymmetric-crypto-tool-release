package com.cryptoassistant.app.feature.settings

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.log.AppLog
import com.cryptoassistant.app.feature.files.FileTaskCacheUsage
import com.cryptoassistant.app.feature.files.FileTaskRuntime
import com.cryptoassistant.app.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * 设置首页的“清除缓存”入口。
 *
 * <p>只清理 `files/file-tasks`，不碰密钥、日志、联系人、SharedPreferences 或用户保存到
 * 其他位置的文件。运行/保存中由 [FileTaskRuntime.busy] 拒绝；清理完成后由
 * [FileTaskRuntime.clearCache] 发事件，存活的文件页 ViewModel 会重置。</p>
 */
// Toast 在协程里执行，不能用 stringResource；这里只取 Toast 文案，不参与重组。
@SuppressLint("LocalContextGetResourceValueCall", "LocalContextResourcesRead")
@Composable
fun CacheCleanupRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val busy by FileTaskRuntime.busy.collectAsStateWithLifecycle()
    val clearing by FileTaskRuntime.clearing.collectAsStateWithLifecycle()

    var usage by remember { mutableStateOf<FileTaskCacheUsage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableIntStateOf(0) }
    var showConfirm by remember { mutableStateOf(false) }
    var showBusy by remember { mutableStateOf(false) }

    LaunchedEffect(refreshToken) {
        loading = true
        loadFailed = false
        runCatching { FileTaskRuntime.inspectCache(context) }
            .onSuccess { usage = it }
            .onFailure { loadFailed = true }
        loading = false
    }

    // 从文件页/系统设置返回时重新计算；清理完成也走 usage 的本地更新。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshToken++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val openDialog = {
        when {
            clearing -> Unit
            busy -> showBusy = true
            else -> showConfirm = true
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = openDialog)
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.cache_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle(usage, loading, loadFailed, busy, clearing, context),
                style = MaterialTheme.typography.bodySmall,
                color = if (loadFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        TextButton(
            onClick = openDialog,
            enabled = !clearing,
        ) {
            Text(
                stringResource(
                    if (clearing) R.string.common_clearing else R.string.common_clear,
                ),
            )
        }
    }

    if (showBusy) {
        AlertDialog(
            onDismissRequest = { showBusy = false },
            title = { Text(stringResource(R.string.cache_busy_title)) },
            text = { Text(stringResource(R.string.cache_busy_body)) },
            confirmButton = {
                TextButton(onClick = { showBusy = false }) {
                    Text(stringResource(R.string.common_got_it))
                }
            },
        )
    }

    if (showConfirm) {
        val current = usage
        val empty = current != null && current.directories == 0 && current.files == 0
        AlertDialog(
            onDismissRequest = { if (!clearing) showConfirm = false },
            title = { Text(stringResource(R.string.cache_confirm_title)) },
            text = {
                Column {
                    if (current == null) {
                        Text(
                            stringResource(
                                if (loading) {
                                    R.string.cache_confirm_calculating
                                } else {
                                    R.string.cache_confirm_unreadable
                                },
                            ),
                        )
                    } else if (empty) {
                        Text(stringResource(R.string.cache_confirm_empty))
                    } else {
                        Text(
                            stringResource(
                                R.string.cache_confirm_summary,
                                current.directories,
                                current.files,
                                Formatter.formatShortFileSize(context, current.bytes),
                            ),
                        )
                        if (current.activeLeases > 0) {
                            Spacer(Modifier.height(Spacing.sm))
                            Text(
                                stringResource(
                                    R.string.cache_confirm_lease,
                                    current.activeLeases,
                                ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        stringResource(R.string.cache_confirm_scope),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (clearing) {
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            stringResource(R.string.cache_clearing),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = current != null && !empty && !clearing && !busy,
                    onClick = {
                        scope.launch {
                            runCatching { FileTaskRuntime.clearCache(context) }
                                .onSuccess { result ->
                                    usage = result.remaining
                                    showConfirm = false
                                    val freed = Formatter.formatShortFileSize(
                                        context,
                                        result.freedBytes,
                                    )
                                    val message = if (result.failedEntries > 0) {
                                        context.resources.getQuantityString(
                                            R.plurals.cache_done_partial,
                                            result.failedEntries,
                                            freed,
                                            result.failedEntries,
                                        )
                                    } else {
                                        context.getString(R.string.cache_done, freed)
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                                .onFailure { error ->
                                    showConfirm = false
                                    loadFailed = true
                                    AppLog.error("cache.clear", error, "kind=cache")
                                    Toast.makeText(
                                        context,
                                        R.string.cache_failed,
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (clearing) R.string.common_clearing else R.string.common_clear,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !clearing,
                    onClick = { showConfirm = false },
                ) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun subtitle(
    usage: FileTaskCacheUsage?,
    loading: Boolean,
    loadFailed: Boolean,
    busy: Boolean,
    clearing: Boolean,
    context: Context,
): String {
    if (loadFailed) return stringResource(R.string.cache_subtitle_unreadable)
    if (clearing) return stringResource(R.string.cache_subtitle_clearing)
    if (loading && usage == null) return stringResource(R.string.cache_subtitle_calculating)
    val current = usage ?: return stringResource(R.string.cache_subtitle_unreadable)
    val base = if (current.directories == 0 && current.files == 0) {
        stringResource(R.string.cache_subtitle_empty)
    } else {
        listOf(
            pluralStringResource(
                R.plurals.cache_subtitle_dirs,
                current.directories,
                current.directories,
            ),
            pluralStringResource(
                R.plurals.cache_subtitle_files,
                current.files,
                current.files,
            ),
            Formatter.formatShortFileSize(context, current.bytes),
        ).joinToString(" · ")
    }
    val suffix = when {
        busy -> stringResource(R.string.cache_suffix_busy)
        current.activeLeases > 0 ->
            pluralStringResource(
                R.plurals.cache_suffix_lease,
                current.activeLeases,
                current.activeLeases,
            )
        else -> null
    }
    return if (suffix == null) base else "$base · $suffix"
}
