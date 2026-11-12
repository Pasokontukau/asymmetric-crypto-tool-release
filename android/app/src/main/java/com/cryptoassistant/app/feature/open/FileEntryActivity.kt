package com.cryptoassistant.app.feature.open

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.cryptoassistant.app.CryptoAssistantApplication
import com.cryptoassistant.app.MainActivity
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.BallWorkMode
import com.cryptoassistant.app.data.keys.ContactKey
import com.cryptoassistant.app.data.log.AppLog
import com.cryptoassistant.app.data.log.logFailure
import com.cryptoassistant.app.feature.files.FileTaskMode
import com.cryptoassistant.app.feature.files.FileTaskResult
import com.cryptoassistant.app.feature.files.FileTaskRunner
import com.cryptoassistant.app.feature.files.FileTaskRuntime
import com.cryptoassistant.app.feature.files.FileTaskStorage
import com.cryptoassistant.app.feature.files.SelectedFile
import com.cryptoassistant.app.feature.files.canShareFiles
import com.cryptoassistant.app.feature.files.fileTaskMessageFor
import com.cryptoassistant.app.feature.files.openWithOtherApp
import com.cryptoassistant.app.feature.files.shareFile
import com.cryptoassistant.app.ui.theme.CryptoAssistantTheme
import crypto.file.FileCancelledException
import crypto.file.FileProgressListener
import crypto.volume.VolumeProgressListener
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 系统文件入口的透明宿主（VIEW / SEND / SEND_MULTIPLE）。
 *
 * <p>第 3 小批起，入口在「文件快捷处理」开关与便捷模式都打开时直接处理单文件：
 * 判型 → 纯策略 → [FileTaskRunner] → 可见 HUD；用户点「分享/打开」才把结果交给
 * 外部应用。任一条件不满足就原样把 Intent 转给 [MainActivity]，走现有文件页。</p>
 *
 * <p>安全边界：只处理单文件；加密接收方只取 `lastContactId` 且联系人仍存在；
 * 解密结果签名无效或发送者未知时不提供打开，直接转文件页；不自动发送、不自动选聊天对象。
 * HUD 显示的名称来自系统/文件头，日志只写模式、结果与原因枚举。</p>
 */
class FileEntryActivity : ComponentActivity() {

    private val app by lazy { application as CryptoAssistantApplication }
    private val runner by lazy { FileTaskRunner(contentResolver, app.keyRepository) }
    private val cancelFlag = AtomicBoolean(false)

    private var stage: HudStage by mutableStateOf(HudStage.Preparing)
    private var workJob: Job? = null

    private var mode = FileTaskMode.DECRYPT
    private var displayName = ""
    private var recipientName: String? = null
    private var recipientTail: String? = null

    private var taskDirectory: File? = null
    private var operationStarted = false
    private var leavingForExternal = false
    private var handedOffToExternal = false
    private var finished = false
    private var hudShown = false
    private var startedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // HUD 状态不跨重建恢复：旋转由 configChanges 挡住；进程被杀后临时目录由冷启动清理。
        if (savedInstanceState != null) {
            finish()
            return
        }
        workJob = lifecycleScope.launch { begin(intent) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 新请求到达时不再继续旧任务；取消当前，把新 Intent 交给现有文件页。
        cancelFlag.set(true)
        AppLog.debug("file.auto.hud", "op=${mode.name.lowercase()} action=replace")
        forwardToFilePage(intent)
    }

    override fun onResume() {
        super.onResume()
        // 已交给系统 chooser 后保持本 Activity 存活，等用户回到 HUD 再关闭。
        // 这样 chooser 选中的应用启动时，宿主任务仍然存在（与文件页 MainActivity 的条件一致）。
        if (handedOffToExternal && !finished) {
            AppLog.debug("file.auto.hud", "op=${mode.name.lowercase()} action=close reason=external_return")
            finishHud()
        }
    }

    override fun onStop() {
        super.onStop()
        if (finished || leavingForExternal || isChangingConfigurations) return
        // D9-4：离开 HUD（返回/Home/切换应用）= 取消并清理本次临时结果。
        AppLog.debug("file.auto.hud", "op=${mode.name.lowercase()} action=cancel reason=leave")
        cancelFlag.set(true)
        finishHud()
    }

    override fun onDestroy() {
        workJob?.cancel()
        cleanupTaskDirectory()
        endOperation()
        super.onDestroy()
    }

    // region 入口与策略

    private suspend fun begin(sourceIntent: Intent) {
        val settings = app.settingsRepository.current()
        val entryMode = sourceIntent.getStringExtra(MainActivity.EXTRA_ENTRY_MODE)?.let { raw ->
            runCatching { FileTaskMode.valueOf(raw) }.getOrNull()
        }
        when (val parsed = parseIncomingFiles(this, sourceIntent, requestedMode = entryMode)) {
            null -> finishHud()

            is IncomingParseResult.Rejected -> {
                // 现有文件页负责同名提示与日志；这里保持拒绝路径完全一致。
                forwardToFilePage(sourceIntent)
            }

            is IncomingParseResult.Accepted -> {
                val request = parsed.request
                val titleHint = if (request.uris.size == 1) {
                    sourceIntent.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString()
                } else {
                    null
                }
                val plan = withContext(Dispatchers.IO) {
                    runCatching {
                        IncomingEntryPolicy.plan(this@FileEntryActivity, request, titleHint)
                    }.getOrNull()
                }
                if (plan == null || plan.total != 1) {
                    AppLog.debug("file.auto.policy", "decision=fallback reason=not_single n=${request.uris.size}")
                    forwardToFilePage(sourceIntent)
                    return
                }
                val entry = plan.decrypt.firstOrNull() ?: plan.encrypt.firstOrNull()
                if (entry == null) {
                    forwardToFilePage(sourceIntent)
                    return
                }

                val snapshot = app.keyRepository.snapshot.value
                val recipient = snapshot.contacts.firstOrNull { it.id == settings.lastContactId }
                val facts = PipelineFacts(
                    enabled = settings.fileQuickPipeline,
                    quickMode = settings.ballWorkMode == BallWorkMode.QUICK,
                    forcedEntryMode = entryMode != null,
                    fileCount = plan.total,
                    mixed = plan.isMixed,
                    mode = entry.mode,
                    readGranted = request.readGranted,
                    identityExists = snapshot.identity != null,
                    recipientAvailable = recipient != null,
                    // 单文件入口总会探测文件头：加密方向本应拿到 NOT_CIPHER。
                    // 读头失败（UNREADABLE/null）时不能排除“其实是密文”，宁可回退文件页，
                    // 也不在 HUD 里把密文再加密一次。
                    encryptFileLooksEncrypted = entry.probe != ProbeOutcome.NOT_CIPHER,
                    encryptSize = entry.size,
                    targetAvailable = if (entry.mode == FileTaskMode.ENCRYPT) {
                        canShareFiles(this)
                    } else {
                        true
                    },
                )
                when (val decision = FilePipelinePolicy.decide(facts)) {
                    is PipelineDecision.Fallback -> {
                        AppLog.debug(
                            "file.auto.policy",
                            "decision=fallback reason=${decision.reason.name.lowercase()} n=${plan.total}",
                        )
                        forwardToFilePage(sourceIntent)
                    }

                    is PipelineDecision.Auto -> {
                        AppLog.debug(
                            "file.auto.policy",
                            "decision=auto op=${decision.mode.name.lowercase()} n=${plan.total}",
                        )
                        startWork(entry, recipient)
                    }
                }
            }
        }
    }

    // endregion

    // region 执行

    private suspend fun startWork(entry: EntryFile, recipient: ContactKey?) {
        mode = entry.mode
        val source = SelectedFile(
            uri = entry.uri,
            displayName = FileTaskStorage.sanitizeFileName(
                entry.displayName,
                getString(R.string.files_unnamed),
            ),
            size = entry.size,
            mimeType = entry.mimeType,
        )
        displayName = source.displayName
        recipientName = recipient?.name
        recipientTail = recipient?.fingerprint
            ?.filter { it.isLetterOrDigit() }
            ?.takeLast(4)
        startedAt = System.currentTimeMillis()
        showHud()

        val directory = File(
            File(filesDir, FileTaskStorage.DIRECTORY_NAME),
            UUID.randomUUID().toString(),
        )
        if (!directory.mkdirs() && !directory.isDirectory) {
            showFailure(IllegalStateException("cannot create task directory"))
            return
        }
        taskDirectory = directory
        FileTaskRuntime.beginOperation()
        operationStarted = true
        AppLog.info("file.auto", "op=${mode.name.lowercase()} result=start n=1")

        try {
            val keys = runner.prepareKeys(mode, recipient)
            val result = if (mode == FileTaskMode.ENCRYPT) {
                runner.encrypt(
                    source = source,
                    privateKey = keys.privateKey,
                    recipient = requireNotNull(keys.recipient),
                    recipientName = recipient?.name,
                    directory = directory,
                    reservedNames = mutableSetOf(),
                    onProgress = progressListener(),
                    cancel = cancelFlag,
                )
            } else {
                // 多卷包的单个分卷不能在这里静默解密成分片；只读元数据后明确拒绝。
                val partInfo = runCatching {
                    runner.readVolumeInfo(source.uri, keys.privateKey)
                }.getOrNull()
                val partCount = partInfo?.partCount ?: 1
                if (partCount > 1) {
                    AppLog.info(
                        "file.auto",
                        "op=decrypt result=fallback reason=external_part " +
                            "part=${(partInfo?.partIndex ?: 0) + 1}/$partCount",
                    )
                    Toast.makeText(
                        this,
                        getString(
                            R.string.file_entry_external_part,
                            (partInfo?.partIndex ?: 0) + 1,
                            partCount,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                    forwardToFilePage(intent)
                    return
                }
                runner.decrypt(
                    source = source,
                    privateKey = keys.privateKey,
                    knownSenders = keys.knownSenders,
                    directory = directory,
                    onProgress = progressListener(),
                    onVolumeProgress = volumeProgressListener(),
                    cancel = cancelFlag,
                )
            }
            onResult(result, sourceIntent = intent)
        } catch (error: FileCancelledException) {
            AppLog.info("file.auto", "op=${mode.name.lowercase()} result=cancelled")
            finishHud()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            showFailure(error)
        }
    }

    private fun onResult(result: FileTaskResult, sourceIntent: Intent) {
        if (mode == FileTaskMode.DECRYPT &&
            (result.signatureValid != true || result.senderName == null)
        ) {
            // D9-7：签名无效/发送者未知不提供“打开”，回文件页让用户看到原有警告。
            AppLog.warn(
                "file.auto",
                "op=decrypt result=fallback reason=signature_or_sender " +
                    "ms=${System.currentTimeMillis() - startedAt}",
            )
            Toast.makeText(
                this,
                getString(R.string.hud_signature_fallback),
                Toast.LENGTH_LONG,
            ).show()
            forwardToFilePage(sourceIntent)
            return
        }
        stage = HudStage.Done(result)
        AppLog.info(
            "file.auto",
            "op=${mode.name.lowercase()} result=ok ms=${System.currentTimeMillis() - startedAt}",
        )
    }

    private fun showFailure(error: Throwable) {
        logFailure("file.auto", error, "op=${mode.name.lowercase()}")
        cleanupTaskDirectory()
        stage = HudStage.Failed(fileTaskMessageFor(this, error))
    }

    private fun progressListener(): FileProgressListener =
        FileProgressListener { phase, processed, total ->
            val current = stage
            if (current is HudStage.Preparing || current is HudStage.Running) {
                stage = HudStage.Running(
                    phase = phase,
                    processed = processed,
                    total = total,
                    volumeIndex = (current as? HudStage.Running)?.volumeIndex,
                    volumeTotal = (current as? HudStage.Running)?.volumeTotal,
                )
            }
        }

    private fun volumeProgressListener(): VolumeProgressListener =
        VolumeProgressListener { index, count, processed, total ->
            val current = stage
            if (current is HudStage.Preparing || current is HudStage.Running) {
                stage = HudStage.Running(
                    phase = (current as? HudStage.Running)?.phase,
                    processed = processed,
                    total = total,
                    volumeIndex = index,
                    volumeTotal = count,
                )
            }
        }

    // endregion

    // region HUD 生命周期

    private fun showHud() {
        if (hudShown) return
        hudShown = true
        setContent {
            CryptoAssistantTheme {
                BackHandler { cancelFromHud() }
                PipelineHud(
                    mode = mode,
                    fileName = displayName,
                    stage = stage,
                    recipientName = recipientName,
                    recipientTail = recipientTail,
                    onShare = { onShareClicked() },
                    onOpen = { onOpenClicked() },
                    onFallback = { forwardToFilePage(intent) },
                    onCancel = { cancelFromHud() },
                )
            }
        }
    }

    private fun onShareClicked() {
        val result = (stage as? HudStage.Done)?.result ?: return
        AppLog.debug("file.auto.hud", "op=encrypt action=share")
        // 必须先置位：chooser 盖住 HUD 会触发 onStop，不能在那里当成“离开取消”并清理结果。
        leavingForExternal = true
        if (shareFile(this, result)) {
            markHandedOff()
        } else {
            leavingForExternal = false
        }
    }

    private fun onOpenClicked() {
        val result = (stage as? HudStage.Done)?.result ?: return
        AppLog.debug("file.auto.hud", "op=decrypt action=open")
        leavingForExternal = true
        if (openWithOtherApp(this, result)) {
            markHandedOff()
        } else {
            leavingForExternal = false
        }
    }

    /**
     * 启动 chooser 后不立即 finish：HUD 保持存活到用户返回，确保目标应用启动时宿主任务仍在。
     * 结果目录由 [FileShare] 写入的 6 小时租约保护，此时可以安全释放“清理被忙碌拒绝”的占位。
     */
    private fun markHandedOff() {
        handedOffToExternal = true
        endOperation()
    }

    private fun cancelFromHud() {
        AppLog.debug("file.auto.hud", "op=${mode.name.lowercase()} action=cancel")
        cancelFlag.set(true)
        finishHud()
    }

    private fun finishHud() {
        if (finished) return
        finished = true
        workJob?.cancel()
        cleanupTaskDirectory()
        endOperation()
        finish()
    }

    private fun endOperation() {
        if (!operationStarted) return
        operationStarted = false
        FileTaskRuntime.endOperation()
    }

    private fun cleanupTaskDirectory() {
        val directory = taskDirectory ?: return
        taskDirectory = null
        // 已交给外部应用的目录继续受 6 小时租约保护；其余立即删除。
        if (FileTaskStorage.hasActiveLease(directory)) return
        FileTaskStorage.deleteRecursively(directory)
    }

    private fun forwardToFilePage(sourceIntent: Intent) {
        if (finished) return
        val next = Intent(sourceIntent).setClass(this, MainActivity::class.java)
        startActivity(next)
        finishHud()
    }

    // endregion

}

private sealed interface HudStage {
    data object Preparing : HudStage

    data class Running(
        val phase: FileProgressListener.Phase?,
        val processed: Long,
        val total: Long,
        val volumeIndex: Int?,
        val volumeTotal: Int?,
    ) : HudStage

    data class Done(val result: FileTaskResult) : HudStage

    data class Failed(val message: String) : HudStage
}

@Composable
private fun PipelineHud(
    mode: FileTaskMode,
    fileName: String,
    stage: HudStage,
    recipientName: String?,
    recipientTail: String?,
    onShare: () -> Unit,
    onOpen: () -> Unit,
    onFallback: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 420.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (mode == FileTaskMode.ENCRYPT) {
                            Icons.Outlined.Lock
                        } else {
                            Icons.Outlined.LockOpen
                        },
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            if (mode == FileTaskMode.ENCRYPT) {
                                R.string.nav_title_encrypt_files
                            } else {
                                R.string.nav_title_decrypt_files
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.common_cancel),
                        )
                    }
                }

                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                when (stage) {
                    HudStage.Preparing -> LoadingRow(stringResource(R.string.hud_preparing))

                    is HudStage.Running -> {
                        val fraction = if (stage.total > 0) {
                            (stage.processed.toFloat() / stage.total).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        Text(
                            phaseLabel(
                                stage.phase,
                                stage.volumeIndex,
                                stage.volumeTotal,
                            ),
                        )
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    is HudStage.Done -> {
                        if (mode == FileTaskMode.ENCRYPT) {
                            Text(
                                text = stringResource(
                                    R.string.hud_recipient,
                                    recipientName ?: stringResource(R.string.hud_unnamed),
                                    recipientTail ?: "----",
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.hud_confirm_share),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.files_share_cipher))
                            }
                        } else {
                            Text(
                                text = stringResource(
                                    R.string.files_signature_valid_from,
                                    stage.result.senderName
                                        ?: stringResource(R.string.hud_sender_unknown),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (stage.result.volumeCount != null) {
                                val verified = stage.result.contentHashVerified == true &&
                                    stage.result.partNamesVerified == true
                                Text(
                                    text = stringResource(
                                        if (verified) {
                                            R.string.hud_volume_verified
                                        } else {
                                            R.string.hud_volume
                                        },
                                        stage.result.volumeCount,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.files_open_other_app))
                            }
                        }
                        OutlinedButton(onClick = onFallback, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.hud_fallback))
                        }
                        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.common_close))
                        }
                    }

                    is HudStage.Failed -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stage.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Button(onClick = onFallback, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.hud_fallback))
                        }
                        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.common_close))
                        }
                    }
                }

                if (stage is HudStage.Preparing ||
                    stage is HudStage.Running
                ) {
                    Text(
                        text = stringResource(R.string.hud_cancel_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun phaseLabel(
    phase: FileProgressListener.Phase?,
    volumeIndex: Int?,
    volumeTotal: Int?,
): String {
    val base = stringResource(
        when (phase) {
            FileProgressListener.Phase.SIGNING -> R.string.hud_phase_signing
            FileProgressListener.Phase.ENCRYPTING -> R.string.hud_phase_encrypting
            FileProgressListener.Phase.DECRYPTING -> R.string.hud_phase_decrypting
            null -> R.string.hud_phase_processing
        },
    )
    return if (volumeIndex != null && volumeTotal != null) {
        stringResource(R.string.hud_phase_volume, base, volumeIndex, volumeTotal)
    } else {
        base
    }
}
