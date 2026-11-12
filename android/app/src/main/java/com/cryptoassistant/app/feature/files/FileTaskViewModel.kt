package com.cryptoassistant.app.feature.files

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cryptoassistant.app.CryptoAssistantApplication
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.keys.ContactKey
import com.cryptoassistant.app.data.keys.KeyRepository
import com.cryptoassistant.app.data.log.AppLog
import com.cryptoassistant.app.data.log.logFailure
import crypto.file.FileCancelledException
import crypto.file.FileProgressListener
import crypto.message.FileFormatHeader
import crypto.volume.VolumeInfo
import crypto.volume.VolumeProgressListener
import crypto.volume.VolumeSetPlan
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FileTaskMode { ENCRYPT, DECRYPT }

/** 当前选择来自普通多选还是目录树；决定打包资格与 ZIP 根名。 */
enum class SelectionKind { FILES, DIRECTORY }

/** 打包任务内部的阶段；仅用于进度展示。 */
enum class ArchivePhase { PACKAGING, ENCRYPTING, WRITING }

/** 整体任务状态；[PARTIAL] 表示批量里既有成功也有失败/取消。 */
enum class FileTaskStatus { IDLE, READY, RUNNING, SUCCEEDED, FAILED, PARTIAL }

/** 打包结果复用 taskDirectories/保存状态的合成 id；不是普通队列项。 */
internal const val ARCHIVE_RESULT_ID = "__archive__"

/** 外部导入的接收结果；拒绝时不会清空当前任务，由界面显示 notice。 */
enum class ImportAdoptionResult { ACCEPTED, BUSY, TOO_MANY, EMPTY }

/** 队列里单个文件的状态。 */
enum class FileQueueStatus { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class SelectedFile(
    val uri: Uri,
    val displayName: String,
    val size: Long?,
    val mimeType: String?,
    /**
     * 目录递归选择时的相对路径，只用于 UI 区分同名文件；
     * 加密文件头仍只写入 [displayName]，不泄露目录结构。
     */
    val relativePath: String? = null,
    /**
     * 只读取前 4 字节判断「已经是本工具密文」，用于加密前提醒；
     * 不阻止二次加密，也不读取文件内容。
     */
    val alreadyEncrypted: Boolean = false,
)

data class FileTaskResult(
    val mode: FileTaskMode,
    val file: File,
    val displayName: String,
    val bytes: Long,
    val sourceName: String,
    val signatureValid: Boolean? = null,
    val senderName: String? = null,
    val recipientName: String? = null,
    /** VOLU 分卷数；单文件为 null。 */
    val volumeCount: Int? = null,
    /** VOLU 的 SHA-256 是否由元数据提供并核对通过；旧文件缺 hash 时为 false。 */
    val contentHashVerified: Boolean? = null,
    /** VOLU 每卷文件名是否都验证过；旧格式无文件名时为 false。 */
    val partNamesVerified: Boolean? = null,
    /** VOLU 的 sourceIsFolder：输出是文件夹 zip，未自动解压。 */
    val sourceIsFolder: Boolean = false,
    /** 本次结果是 ZIP 打包后分卷（不是旧的单文件加密）。 */
    val isArchive: Boolean = false,
    /** ZIP 内的文件条目数；非归档结果为 null。 */
    val archiveFileCount: Int? = null,
    /** 全部分卷；单文件结果为空，解密结果也保持为空。 */
    val parts: List<File> = emptyList(),
    /** 解密结果由 N 个外部卷合并而来；普通解密/单卷包为 null。 */
    val mergedPartCount: Int? = null,
) {
    /** 分享/保存“全部”时使用的完整文件列表；旧结果退化为 file。 */
    val allFiles: List<File> get() = if (parts.isEmpty()) listOf(file) else parts
}

/**
 * 解密结果是否为可安全解压的归档：新外部单卷包（`sourceIsArchive=true`）、
 * 旧 VOLU 文件夹包（`sourceIsFolder=true`）与多卷合并结果（`isArchive=true`）。
 * 普通单文件解密出的 `.zip` 不在此列，避免把用户原始 ZIP 当成归档包。
 */
internal fun FileTaskResult.isExtractableArchive(): Boolean =
    mode == FileTaskMode.DECRYPT && (isArchive || sourceIsFolder)

/** 外部加密分卷集合中的一卷；[source] 用于重新打开 SAF 流。 */
data class ExternalPartEntry(
    val source: SelectedFile,
    val info: VolumeInfo,
)

/**
 * 解密页对已选文件的集合判定结果。
 *
 * <p>只在至少一卷声明了集合字段时创建；[status] 直接复用 crypto-core 的
 * `VolumeSetPlan`，避免 UI 与核心各写一套缺卷判断。</p>
 */
data class ExternalPartGroupState(
    val setId: String,
    val setCode: String,
    val partCount: Int,
    val receivedCount: Int,
    val status: VolumeSetPlan.Status,
    val missingIndexes: List<Int>,
    val archiveName: String?,
    val entries: List<ExternalPartEntry>,
    /** 这组卷是从压缩包切出来的（true）还是单文件直接切分（false）。 */
    val sourceIsArchive: Boolean = false,
)

data class ExternalPartSetState(
    val status: VolumeSetPlan.Status,
    val partCount: Int,
    val receivedCount: Int,
    val missingIndexes: List<Int>,
    val entries: List<ExternalPartEntry>,
    /** 选中的多个 setId 分组；单组时长度为 1。 */
    val groups: List<ExternalPartGroupState> = emptyList(),
    /** 选择里混入了单卷包/普通文件/读不到的文件。 */
    val hasNonPartSelection: Boolean = false,
    /** 当前唯一可还原的组；多组混选时为 null。 */
    val selectedSetId: String? = null,
    /** 当前组还原后是压缩包（true）还是原文件（false）；文案据此区分。 */
    val sourceIsArchive: Boolean = false,
) {
    /** 真正的多卷包；`partCount == 1` 仍按普通单卷解密。 */
    val isMultiPart: Boolean get() = partCount > 1

    /** 缺卷/重卷/混包/越界时不允许开始。 */
    val blocksStart: Boolean get() = status != VolumeSetPlan.Status.COMPLETE
}

data class FileQueueItem(
    val id: String,
    val source: SelectedFile,
    val status: FileQueueStatus = FileQueueStatus.PENDING,
    val result: FileTaskResult? = null,
    val error: String? = null,
    /**
     * 失败项是否可以重试。元数据读取失败、文件大小不可用、超过 2 GB 这类
     * 预检失败重试也不会成功，标为 false；加解密过程中失败（IO/密钥/格式）保留重试机会。
     */
    val retryable: Boolean = true,
    /** 结果已成功复制到用户选择的位置；只用于展示，不改变临时文件生命周期。 */
    val saved: Boolean = false,
)

data class FileTaskUiState(
    val mode: FileTaskMode = FileTaskMode.ENCRYPT,
    /** 已选文件与逐项结果；单文件时长度就是 1。 */
    val queue: List<FileQueueItem> = emptyList(),
    val currentItemId: String? = null,
    val recipientId: String? = null,
    val status: FileTaskStatus = FileTaskStatus.IDLE,
    val phase: FileProgressListener.Phase? = null,
    val processed: Long = 0,
    val total: Long = 0,
    /** VOLU 当前卷序号/总卷数；单文件为 null。 */
    val volumeIndex: Int? = null,
    val volumeTotal: Int? = null,
    val error: String? = null,
    val notice: String? = null,
    /** 正在读取 SAF 元数据（多选时文件多，需要短暂等待）。 */
    val loading: Boolean = false,
    /** 正在保存哪一项的结果；null 表示没有保存任务。 */
    val savingItemId: String? = null,
    val selectionKind: SelectionKind = SelectionKind.FILES,
    /** 打包开关；单文件永远被 [packageEligible] 拦住，不显示入口。 */
    val packageEnabled: Boolean = true,
    /** 开始前卷数预览；null 表示没有待确认的预览。 */
    val startPreview: VolumeStartPreview? = null,
    /** 仅目录选择时有值，用作 ZIP 根名。 */
    val directoryRootName: String? = null,
    /** 仅目录选择时有值；ZIP 内需要保留的空目录相对路径。 */
    val emptyDirectoryPaths: List<String> = emptyList(),
    val archivePhase: ArchivePhase? = null,
    /** ZIP 打包阶段已处理/总文件数；用于界面显示而不是推断 queue 状态。 */
    val archiveFilesDone: Int = 0,
    val archiveFilesTotal: Int = 0,
    /** 打包任务成功后唯一的结果；普通队列仍用 queue[].result。 */
    val archiveResult: FileTaskResult? = null,
    val archiveSaved: Boolean = false,
    /** 解密选择被识别为外部加密分卷集合时非空；单卷/旧格式为 null。 */
    val externalPartSet: ExternalPartSetState? = null,
    /** 正在解压哪一项的结果；null 表示没有解压任务。 */
    val extractingItemId: String? = null,
    val extractFilesDone: Int = 0,
    val extractFilesTotal: Int = 0,
) {
    val isRunning: Boolean get() = status == FileTaskStatus.RUNNING

    /** 解压与主任务、保存互斥；同一时间只跑一个。 */
    val extracting: Boolean get() = extractingItemId != null

    /** 只有文件夹或至少两个文件才允许打包；单文件永远 false。 */
    val packageEligible: Boolean get() =
        mode == FileTaskMode.ENCRYPT &&
            (selectionKind == SelectionKind.DIRECTORY || queue.size > 1)

    /** 当前选择是否真的走打包路径。 */
    val canPackage: Boolean get() = packageEligible && packageEnabled

    /** 打包要求全部源可读；任一项失败就阻止，而不是静默跳过。 */
    val archiveBlocked: Boolean get() =
        canPackage && queue.any { it.status == FileQueueStatus.FAILED }

    val archiveRunning: Boolean get() = isRunning && archivePhase != null

    /** 单文件页面沿用的字段；批量页面请直接用 [queue]。 */
    val source: SelectedFile? get() = queue.firstOrNull()?.source

    /** 单文件结果；批量时由 [results] 取全部结果。 */
    val result: FileTaskResult? get() = queue.singleOrNull()?.result

    val results: List<FileTaskResult> get() = queue.mapNotNull { it.result }

    val currentItem: FileQueueItem? get() = queue.firstOrNull { it.id == currentItemId }

    val successCount: Int get() = queue.count { it.status == FileQueueStatus.SUCCEEDED }
    val failureCount: Int get() = queue.count { it.status == FileQueueStatus.FAILED }
    val cancelledCount: Int get() = queue.count { it.status == FileQueueStatus.CANCELLED }
    val completedCount: Int get() = successCount + failureCount + cancelledCount

    val isBatch: Boolean get() = queue.size > 1

    /** 可以真正开始处理的数量（元数据读取失败或预检失败的不算，处理失败可重试的算）。 */
    val runnableCount: Int get() =
        queue.count { it.status != FileQueueStatus.FAILED || it.retryable }

    val saving: Boolean get() = savingItemId != null
}

/**
 * 文件加解密任务队列（单文件与多文件共用一套状态机）。
 *
 * <p>输入来自 SAF 的 content:// URI，输出先写在应用私有目录。单文件时行为与旧版一致；
 * 多选时按选择顺序逐个处理，一个文件失败不会中断后面的文件。每个文件有独立的任务目录，
 * 结果保留到「处理下一个」、切换模式或页面销毁；失败/取消的目录立即删除。
 * 加密仍会顺序读两遍原文（签名、加密），所有文件都不整体读进内存。</p>
 */
class FileTaskViewModel(
    application: Application,
    private val keyRepository: KeyRepository,
) : AndroidViewModel(application) {

    /** 生产 ViewModelFactory 走这个构造器；设备测试可注入独立的 KeyRepository。 */
    constructor(application: Application) : this(
        application,
        (application as CryptoAssistantApplication).keyRepository,
    )

    private val resolver = application.contentResolver
    private val filesRoot = application.filesDir
    private val taskRoot = File(filesRoot, FileTaskStorage.DIRECTORY_NAME)
    private val runner = FileTaskRunner(resolver, keyRepository)

    private val _state = MutableStateFlow(FileTaskUiState())
    val state: StateFlow<FileTaskUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var sourceJob: Job? = null
    private var extractJob: Job? = null
    private var initialModeApplied = false
    private val cancelFlag = AtomicBoolean(false)
    private val extractCancelFlag = AtomicBoolean(false)
    private var extractPersistedUri: Uri? = null

    /** 每个队列项一个任务目录，成功后保留到 reset/onCleared。 */
    private val taskDirectories = mutableMapOf<String, File>()

    /** 外部导入时若拿到了持久化读权限，记下来在任务结束时逐个释放。 */
    private val importedPersistedUris = mutableSetOf<Uri>()

    /** 目录选择取得的 tree URI 持久化读权限；扫描/处理结束后释放。 */
    private var directoryPersistedUri: Uri? = null

    /** 当前批次的开始时间与模式，供 file.batch 结束事件记录总耗时。 */
    private var batchStartedAt = 0L
    private var batchMode = FileTaskMode.ENCRYPT

    /** 设置页的“多文件默认打包”；只用于新选择，不改当前 READY 选择。 */
    private var defaultPackageEnabled = true

    init {
        // 设置页清理后，本 ViewModel 可能仍在返回栈里；主动清掉结果引用。
        viewModelScope.launch {
            FileTaskRuntime.cacheCleared.collect { onCacheCleared() }
        }
    }

    /** 只接受路由传入的初始模式，避免重组或返回页面时覆盖用户已选的模式。 */
    fun applyInitialMode(mode: FileTaskMode) {
        if (initialModeApplied) return
        initialModeApplied = true
        if (_state.value.mode != mode) {
            _state.value = FileTaskUiState(
                mode = mode,
                packageEnabled = defaultPackageEnabled,
            )
        }
    }

    /** 设置页改默认值后可立即生效；当前选择保留用户手动开关的结果。 */
    fun setDefaultPackageEnabled(enabled: Boolean) {
        defaultPackageEnabled = enabled
    }

    fun switchMode(mode: FileTaskMode) {
        if (_state.value.isRunning || _state.value.extracting || _state.value.mode == mode) return
        AppLog.debug("file.mode", "switch=${mode.name}")
        releaseTask()
        _state.value = FileTaskUiState(
            mode = mode,
            packageEnabled = defaultPackageEnabled,
        )
    }

    /**
     * 把系统入口收到的文件交给现有状态机：VIEW 切到解密模式，SEND 切到加密模式，
     * 预填来源后停在 READY 等用户确认。新的请求会替换旧的待处理选择；运行/读取中则明确拒绝。
     */
    fun adoptImportedFiles(
        uris: List<Uri>,
        mode: FileTaskMode,
        releasePersistable: Boolean = false,
    ): ImportAdoptionResult {
        if (_state.value.isRunning || _state.value.loading || _state.value.extracting) {
            AppLog.warn("file.import.reject", "reason=busy")
            _state.update {
                it.copy(notice = uiText(R.string.files_busy_notice))
            }
            return ImportAdoptionResult.BUSY
        }
        if (uris.isEmpty()) {
            AppLog.warn("file.import.reject", "reason=no_uri")
            _state.update { it.copy(notice = uiText(R.string.files_no_uri_notice)) }
            return ImportAdoptionResult.EMPTY
        }
        if (FileImportLimits.isTooManyFiles(uris.size)) {
            AppLog.warn("file.import.reject", "reason=too_many n=${uris.size}")
            _state.update {
                it.copy(
                    notice = uiText(
                        R.string.files_too_many_import,
                        FileImportLimits.MAX_FILES,
                    ),
                )
            }
            return ImportAdoptionResult.TOO_MANY
        }

        initialModeApplied = true
        releaseTask()
        _state.value = FileTaskUiState(
            mode = mode,
            packageEnabled = defaultPackageEnabled,
        )
        importedPersistedUris.clear()
        if (releasePersistable) {
            // 只记录真正 take 成功的 URI；失败的仍可依赖随 Intent 授予的临时读权限。
            var taken = 0
            uris.forEach { uri ->
                if (takePersistablePermission(uri) != null) {
                    importedPersistedUris += uri
                    taken++
                }
            }
            AppLog.debug("file.import.persist", "taken=$taken failed=${uris.size - taken}")
        }
        val notice = if (mode == FileTaskMode.ENCRYPT) {
            uiText(R.string.files_received_encrypt, uris.size)
        } else {
            uiText(R.string.files_received_decrypt)
        }
        loadSources(
            uris = uris,
            notice = notice,
            maxKnownBytes = FileImportLimits.MAX_TOTAL_BYTES,
        )
        return ImportAdoptionResult.ACCEPTED
    }

    /** 兼容旧的单文件调用；VIEW 语义固定为解密。 */
    fun adoptImportedFile(uri: Uri, releasePersistable: Boolean = false): ImportAdoptionResult =
        adoptImportedFiles(
            uris = listOf(uri),
            mode = FileTaskMode.DECRYPT,
            releasePersistable = releasePersistable,
        )

    fun selectSource(uri: Uri, notice: String? = null) {
        selectSources(listOf(uri), notice)
    }

    /**
     * 选中一批文件。多选时按系统返回的顺序入队，逐项读取元数据并处理；
     * 单个文件读取失败不会把整批拖垮，失败项会在队列里保留原因。
     */
    fun selectSources(uris: List<Uri>, notice: String? = null) {
        if (_state.value.isRunning || _state.value.loading || _state.value.extracting) return
        if (uris.isEmpty()) return
        AppLog.debug("file.pick", "mode=${_state.value.mode.name} count=${uris.size}")
        releaseTask()
        loadSources(uris, notice)
    }

    /**
     * 在现有选择上补选文件，用于缺卷时只补缺失的那几卷。
     * 保留原队列顺序和权限，重新读取全部 URI 后再做一次集合判定。
     */
    fun addSources(uris: List<Uri>) {
        if (_state.value.isRunning || _state.value.loading || _state.value.extracting) return
        if (uris.isEmpty()) return
        val existing = _state.value.queue.map { it.source.uri }
        val combined = (existing + uris).distinctBy { it.toString() }
        AppLog.debug(
            "file.pick",
            "mode=${_state.value.mode.name} count=${combined.size} append=true",
        )
        loadSources(
            combined,
            notice = uiText(R.string.files_external_added, combined.size - existing.size),
        )
    }

    /**
     * 多 setId 混选时只保留用户选中的一组；重新选择会清掉其余卷，
     * 避免把另一个包的分卷带进合并。
     */
    fun selectExternalPartGroup(setId: String) {
        if (_state.value.isRunning || _state.value.loading || _state.value.extracting) return
        val group = _state.value.externalPartSet?.groups?.firstOrNull { it.setId == setId } ?: return
        AppLog.info("file.part.group", "select=${group.setCode} parts=${group.entries.size}")
        selectSources(group.entries.map { it.source.uri })
    }

    /**
     * 选中一个目录并递归收集文件。
     *
     * <p>加密模式收所有普通文件；解密模式只收 `.aegix`。递归、排序与
     * 文件数/总大小/条目数/深度限制由 [DirectoryWalker] 统一处理。</p>
     */
    fun selectDirectory(treeUri: Uri) {
        if (_state.value.isRunning || _state.value.loading || _state.value.extracting) return
        AppLog.debug("file.pick", "mode=${_state.value.mode.name} count=1 tree=true")
        releaseTask()
        val keepMode = _state.value.mode
        val keepRecipient = _state.value.recipientId
        _state.value = FileTaskUiState(
            mode = keepMode,
            recipientId = keepRecipient,
            packageEnabled = defaultPackageEnabled,
            loading = true,
        )
        directoryPersistedUri = takePersistablePermission(treeUri)
        sourceJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            val scanned = withContext(Dispatchers.IO) {
                runCatching { scanDirectory(treeUri, keepMode) }
            }
            scanned
                .onSuccess { selection ->
                    val items = selection.files.map { file ->
                        FileQueueItem(
                            id = UUID.randomUUID().toString(),
                            source = file,
                        )
                    }
                    AppLog.info(
                        "file.dir",
                        "ok files=${items.size} skipped=${selection.skippedNonMatching} " +
                            "ms=${System.currentTimeMillis() - started}",
                    )
                    _state.update {
                        it.copy(
                            queue = items,
                            status = FileTaskStatus.READY,
                            loading = false,
                            error = null,
                            selectionKind = SelectionKind.DIRECTORY,
                            packageEnabled = defaultPackageEnabled,
                            directoryRootName = selection.rootName,
                            emptyDirectoryPaths = selection.emptyDirectories,
                            notice = if (selection.skippedNonMatching > 0) {
                                uiText(
                                    R.string.files_dir_loaded_skipped,
                                    items.size,
                                    selection.skippedNonMatching,
                                )
                            } else {
                                uiText(R.string.files_dir_loaded, items.size)
                            },
                        )
                    }
                }
                .onFailure { error ->
                    logFailure(
                        "file.dir",
                        error,
                        "mode=${keepMode.name} ms=${System.currentTimeMillis() - started}",
                    )
                    releaseDirectoryPermission()
                    _state.update {
                        it.copy(
                            queue = emptyList(),
                            status = FileTaskStatus.FAILED,
                            loading = false,
                            error = messageFor(error),
                        )
                    }
                }
        }
    }

    private fun loadSources(
        uris: List<Uri>,
        notice: String? = null,
        maxKnownBytes: Long? = null,
    ) {
        val keepMode = _state.value.mode
        val keepRecipient = _state.value.recipientId
        sourceJob?.cancel()
        _state.value = FileTaskUiState(
            mode = keepMode,
            recipientId = keepRecipient,
            packageEnabled = defaultPackageEnabled,
            loading = true,
        )
        sourceJob = viewModelScope.launch {
            // 保持系统返回的选择顺序：失败项也留在原位，批量结果页按同一顺序展示。
            val items = mutableListOf<FileQueueItem>()
            var loadedCount = 0
            var firstError: String? = null
            var knownBytes = 0L
            var tooLarge = false
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    val metadata = runCatching { readMetadata(uri) }
                    val file = metadata.getOrNull()
                    if (file == null) {
                        val error = metadata.exceptionOrNull()
                            ?: IOException("cannot read the selected file")
                        val message = messageFor(error)
                        if (firstError == null) firstError = message
                        items += FileQueueItem(
                            id = UUID.randomUUID().toString(),
                            source = SelectedFile(
                                uri = uri,
                                displayName = fallbackSourceName(uri),
                                size = null,
                                mimeType = runCatching { resolver.getType(uri) }.getOrNull(),
                            ),
                            status = FileQueueStatus.FAILED,
                            error = message,
                            retryable = false,
                        )
                        continue
                    }

                    if (maxKnownBytes != null &&
                        FileImportLimits.exceedsTotalBytes(knownBytes, file.size)
                    ) {
                        tooLarge = true
                        break
                    }
                    if (maxKnownBytes != null && file.size != null) {
                        knownBytes += file.size
                    }
                    loadedCount++
                    items += FileQueueItem(
                        id = UUID.randomUUID().toString(),
                        source = file,
                    )
                }
            }

            if (tooLarge) {
                AppLog.warn(
                    "file.import.reject",
                    "reason=too_large files=${uris.size} known=$knownBytes",
                )
                releasePersistedPermissions()
                _state.update {
                    it.copy(
                        queue = emptyList(),
                        status = FileTaskStatus.FAILED,
                        loading = false,
                        error = uiText(
                            R.string.files_total_too_large,
                            FileImportLimits.MAX_TOTAL_BYTES / (1024L * 1024L * 1024L),
                        ),
                        notice = null,
                    )
                }
                return@launch
            }

            if (loadedCount == 0) {
                releasePersistedPermissions()
                if (uris.size > 1) {
                    // 多选全失败：保留失败项，让批量结果页逐项展示原因。
                    _state.update {
                        it.copy(
                            queue = items,
                            status = FileTaskStatus.FAILED,
                            loading = false,
                            error = null,
                            notice = it.notice ?: uiText(R.string.files_all_unreadable),
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            queue = emptyList(),
                            status = FileTaskStatus.FAILED,
                            loading = false,
                            error = firstError ?: uiText(R.string.files_unreadable_selected),
                        )
                    }
                }
                return@launch
            }

            // 解密页先读 VOLU 头：多卷包必须整体判定缺卷/重卷/混包，不能按普通批量
            // 逐卷解出分片。探测失败（旧格式/非 VOLU/读不了）退回普通路径。
            val externalSet = if (keepMode == FileTaskMode.DECRYPT) {
                probeExternalPartSet(items)
            } else {
                null
            }

            _state.update {
                it.copy(
                    queue = items,
                    status = FileTaskStatus.READY,
                    loading = false,
                    error = if (externalSet?.blocksStart == true) {
                        externalPartSetMessage(externalSet)
                    } else {
                        null
                    },
                    externalPartSet = externalSet,
                    // 读取期间若收到新的外部导入并被拒绝，保留那条拒收提示，不要被首次导入覆盖。
                    notice = it.notice ?: notice ?: if (externalSet?.isMultiPart == true) {
                        externalPartSetMessage(externalSet)
                    } else if (items.size > 1) {
                        uiText(R.string.files_selected_notice, items.size)
                    } else {
                        null
                    },
                )
            }
        }
    }

    /**
     * 逐个读取已选文件的 VOLU 元数据并交给 `VolumeSetPlan` 判定。
     *
     * <p>非 VOLU、旧格式、读不到的文件直接跳过；只要有一卷声明了集合字段，
     * 就不再走普通批量解密。私钥不可用时返回 null，由 start() 统一报“无身份”。</p>
     */
    private suspend fun probeExternalPartSet(items: List<FileQueueItem>): ExternalPartSetState? =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext null
            val privateKey = keyRepository.currentPrivateKey() ?: return@withContext null
            val probed = items.mapNotNull { item ->
                runCatching {
                    ExternalPartSetProbe.readInfo({ openInput(item.source.uri) }, privateKey)
                }.onFailure { error ->
                    AppLog.verbose(
                        "file.part.probe",
                        "ok=false error=${error.javaClass.simpleName}",
                    )
                }.getOrNull()?.let { info -> ExternalPartEntry(item.source, info) }
            }
            val multiParts = probed.filter { VolumeSetPlan.isMultiPart(it.info) }
            if (multiParts.isEmpty()) return@withContext null

            // 按 setId 先分组：同一批次里可能误选了 A 包和 B 包的卷，
            // 或者混入了普通文件/单卷包；分组后让用户只保留一组再合并。
            val groups = multiParts
                .groupBy { it.info.setId.orEmpty() }
                .map { (setId, entries) ->
                    val infos = entries.map { it.info }
                    val plan = VolumeSetPlan.plan(infos)
                    ExternalPartGroupState(
                        setId = setId,
                        setCode = externalSetCode(setId),
                        partCount = infos.firstOrNull()?.partCount ?: entries.size,
                        receivedCount = infos.mapNotNull { it.partIndex }.distinct().size,
                        status = plan.status,
                        missingIndexes = plan.missingIndexes,
                        archiveName = infos.firstNotNullOfOrNull { it.archiveName },
                        entries = entries.sortedBy { it.info.partIndex ?: Int.MAX_VALUE },
                        sourceIsArchive = infos.firstOrNull()?.sourceIsArchive == true,
                    )
                }
                .sortedWith(compareBy({ it.archiveName.orEmpty() }, { it.setCode }))

            val hasNonPartSelection = multiParts.size < items.size
            if (groups.size == 1 && !hasNonPartSelection) {
                val group = groups.first()
                ExternalPartSetState(
                    status = group.status,
                    partCount = group.partCount,
                    receivedCount = group.receivedCount,
                    missingIndexes = group.missingIndexes,
                    entries = group.entries,
                    groups = groups,
                    selectedSetId = group.setId,
                    sourceIsArchive = group.sourceIsArchive,
                )
            } else {
                ExternalPartSetState(
                    status = VolumeSetPlan.Status.MIXED,
                    partCount = groups.maxOfOrNull { it.partCount } ?: 0,
                    receivedCount = groups.sumOf { it.receivedCount },
                    missingIndexes = emptyList(),
                    entries = if (groups.size == 1) groups.first().entries else emptyList(),
                    groups = groups,
                    hasNonPartSelection = hasNonPartSelection,
                    selectedSetId = if (groups.size == 1) groups.first().setId else null,
                    sourceIsArchive = groups.firstOrNull()?.sourceIsArchive == true,
                )
            }
        }

    private fun externalSetCode(setId: String): String =
        runCatching { ArchivePartPolicy.setCode(setId) }
            .getOrElse { setId.take(6).lowercase(Locale.US) }

    private fun externalPartSetMessage(set: ExternalPartSetState): String = when (set.status) {
        VolumeSetPlan.Status.COMPLETE ->
            if (set.isMultiPart) {
                if (set.sourceIsArchive) {
                    uiText(R.string.files_external_set_ready, set.partCount)
                } else {
                    uiText(R.string.files_external_set_ready_file, set.partCount)
                }
            } else {
                uiText(R.string.files_external_set_single)
            }

        VolumeSetPlan.Status.INCOMPLETE -> uiText(
            R.string.file_error_external_missing,
            set.missingIndexes.joinToString("、") { it.toString() },
            set.partCount,
        )

        VolumeSetPlan.Status.DUPLICATE -> uiText(R.string.file_error_external_duplicate)
        VolumeSetPlan.Status.MIXED -> if (set.groups.size > 1) {
            uiText(R.string.files_external_mixed_groups, set.groups.size)
        } else {
            uiText(R.string.file_error_external_mixed)
        }

        else -> uiText(R.string.file_error_external_invalid)
    }

    fun selectRecipient(id: String?) {
        if (_state.value.isRunning) return
        _state.update {
            it.copy(recipientId = id, error = null)
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    /**
     * 界面上的“开始加密”：先给出会拆卷的项和总卷数。
     *
     * <p>单文件按实际大小精确算；多文件/文件夹只能按源大小估算，确认后才真正
     * 打包。估算已超过 4 GiB/99 卷时不弹预览，直接给错误，避免白跑一遍打包。</p>
     */
    fun requestStart(bytes: Long = ArchiveVolumeSize.MB_500.bytes) {
        val current = _state.value
        if (current.startPreview != null) return
        if (current.mode != FileTaskMode.ENCRYPT || current.isRunning ||
            current.loading || current.extracting || current.queue.isEmpty()
        ) {
            start(bytes)
            return
        }
        val preview = try {
            buildStartPreview(current, bytes)
        } catch (error: IllegalArgumentException) {
            logFailure("file.volume.preview", error, "limit=$bytes")
            _state.update { it.copy(error = uiText(R.string.file_error_archive_limit)) }
            return
        }
        if (preview == null) {
            start(bytes)
            return
        }
        _state.update { it.copy(startPreview = preview, error = null) }
    }

    fun dismissStartPreview() {
        _state.update { it.copy(startPreview = null) }
    }

    fun confirmStartPreview(bytes: Long = ArchiveVolumeSize.MB_500.bytes) {
        if (_state.value.startPreview == null) return
        _state.update { it.copy(startPreview = null) }
        start(bytes)
    }

    private fun buildStartPreview(
        current: FileTaskUiState,
        bytes: Long,
    ): VolumeStartPreview? {
        val label = ArchiveVolumeSize.entries.firstOrNull { it.bytes == bytes }
            ?.let { getApplication<Application>().getString(it.labelRes) }
            ?: getApplication<Application>().getString(ArchiveVolumeSize.DEFAULT.labelRes)

        if (current.canPackage) {
            if (current.queue.any { it.status == FileQueueStatus.FAILED }) return null
            val active = current.queue
            if (active.isEmpty() || active.any { it.source.size == null }) return null
            val totalBytes = active.sumOf { it.source.size!!.coerceAtLeast(0L) }
            val rootName = current.directoryRootName
                ?: FileTaskStorage.archiveBaseName(java.time.LocalDateTime.now())
            val displayName = current.directoryRootName
                ?: getApplication<Application>().getString(
                    R.string.files_volume_preview_files,
                    active.size,
                )
            return VolumeStartPreviewPlanner.archive(
                displayName = displayName,
                sourceBytes = totalBytes,
                fileCount = active.size,
                archiveRootName = rootName,
                volumeLimitBytes = bytes,
                volumeLimitLabel = label,
            )
        }

        val single = current.queue.singleOrNull()?.source ?: return null
        val size = single.size ?: return null
        return VolumeStartPreviewPlanner.singleFile(
            fileName = single.displayName,
            fileBytes = size,
            volumeLimitBytes = bytes,
            volumeLimitLabel = label,
        )
    }

    fun start(bytes: Long = ArchiveVolumeSize.MB_500.bytes) {
        val current = _state.value
        if (current.isRunning || current.loading || current.extracting || current.queue.isEmpty()) return
        // 只有「就绪」或「单批全部失败后重试」允许开始；批量完成页已展示结果，不再重复跑。
        if (current.status != FileTaskStatus.READY && current.status != FileTaskStatus.FAILED) return

        val snapshot = keyRepository.snapshot.value
        if (snapshot.identity == null) {
            _state.update { it.copy(error = uiText(R.string.files_error_no_identity)) }
            return
        }

        // 外部加密分卷：缺卷/重卷/混包一律不开始；齐套的多卷包走合并，
        // 不按普通批量逐卷解出分片。单卷包（partCount=1）继续走原路径。
        val externalSet = current.externalPartSet
        if (current.mode == FileTaskMode.DECRYPT && externalSet != null) {
            if (externalSet.blocksStart) {
                _state.update { it.copy(error = externalPartSetMessage(externalSet)) }
                return
            }
            if (externalSet.isMultiPart) {
                startExternalSetMerge(externalSet)
                return
            }
        }

        val contact = if (current.mode == FileTaskMode.ENCRYPT) {
            snapshot.contacts.firstOrNull { it.id == current.recipientId }
        } else {
            null
        }
        if (current.mode == FileTaskMode.ENCRYPT && contact == null) {
            _state.update { it.copy(error = uiText(R.string.files_error_no_recipient)) }
            return
        }

        // 取消过的项、以及处理过程中失败但可重试的项允许直接重跑；
        // 元数据/预检失败的项保持失败并跳过。
        var queue = current.queue.map { item ->
            if (item.status == FileQueueStatus.CANCELLED ||
                (item.status == FileQueueStatus.FAILED && item.retryable)
            ) {
                item.copy(
                    status = FileQueueStatus.PENDING,
                    error = null,
                    result = null,
                    retryable = true,
                    saved = false,
                )
            } else {
                item
            }
        }

        // 硬约束：打包分流必须在逐项 encryptPreflightError 之前。
        // 归档成员允许 >2 GB 或未知大小，只受 ZIP 4 GiB 与单卷上限约束。
        if (current.mode == FileTaskMode.ENCRYPT && current.canPackage) {
            startArchive(contact, bytes, queue)
            return
        }

        // 单个文件超过所选单卷上限时直接切片，不再套 ZIP，也不再受单文件容器
        // 约 2 GB 的上限；阈值与用户选择的卷大小共用同一组档位。
        val singleSourceSize = queue.singleOrNull()
            ?.takeIf { it.status != FileQueueStatus.FAILED }
            ?.source?.size
        if (current.mode == FileTaskMode.ENCRYPT &&
            singleSourceSize != null && singleSourceSize > bytes
        ) {
            startSingleFileVolume(contact, bytes, queue)
            return
        }

        if (current.mode == FileTaskMode.ENCRYPT) {
            queue = queue.map { item ->
                if (item.status == FileQueueStatus.FAILED) {
                    item
                } else {
                    val blocker = encryptPreflightError(item.source.size)
                    if (blocker == null) {
                        item
                    } else {
                        item.copy(status = FileQueueStatus.FAILED, error = blocker, retryable = false)
                    }
                }
            }
        }

        val runnable = queue.count { it.status != FileQueueStatus.FAILED }
        if (runnable == 0) {
            val firstError = queue.firstOrNull { it.error != null }?.error
            _state.update {
                it.copy(
                    queue = queue,
                    status = FileTaskStatus.FAILED,
                    phase = null,
                    error = if (queue.size == 1) firstError else null,
                    notice = if (queue.size > 1) uiText(R.string.files_none_processable) else null,
                )
            }
            return
        }

        val first = queue.first { it.status != FileQueueStatus.FAILED }
        cancelFlag.set(false)
        _state.update {
            it.copy(
                queue = queue,
                status = FileTaskStatus.RUNNING,
                currentItemId = first.id,
                phase = if (current.mode == FileTaskMode.ENCRYPT) {
                    FileProgressListener.Phase.SIGNING
                } else {
                    FileProgressListener.Phase.DECRYPTING
                },
                processed = 0,
                total = first.source.size ?: 0,
                error = null,
                notice = null,
                savingItemId = null,
            )
        }
        batchStartedAt = System.currentTimeMillis()
        batchMode = current.mode
        AppLog.info(
            "file.batch",
            "start mode=${current.mode.name} files=$runnable skipped=${queue.size - runnable}",
        )
        // 登记与设置页清理共用 FileTaskRuntime 的锁，必须放进协程里等锁；
        // endOperation 只在 beginOperation 成功返回后的 finally 里配对归还，
        // 避免协程尚未登记就被取消时多还一次（会把别的任务的 busy 还掉）。
        job = viewModelScope.launch {
            FileTaskRuntime.beginOperation()
            try {
                runQueue(contact)
            } finally {
                FileTaskRuntime.endOperation()
            }
        }
    }

    /** 只影响当前选择；单文件/非加密模式不接受该开关。 */
    fun setPackageEnabled(enabled: Boolean) {
        if (_state.value.isRunning || _state.value.loading) return
        if (!_state.value.packageEligible) return
        _state.update { it.copy(packageEnabled = enabled) }
    }

    /**
     * 齐套多卷包：逐卷解密到同一个 ZIP，整包 SHA 通过后才算成功。
     *
     * <p>成功后把队列折叠为单个结果，复用单结果页的分享/另存/打开；失败保留原选择，
     * 用户可以补卷或直接重试。</p>
     */
    private fun startExternalSetMerge(set: ExternalPartSetState) {
        val entries = set.entries
        val descriptor = entries.firstOrNull()?.info ?: return
        val first = entries.first().source
        val parts = entries.map { entry ->
            ExternalPartInput(entry.info, entry.source.displayName) {
                openInput(entry.source.uri)
            }
        }
        cancelFlag.set(false)
        _state.update {
            it.copy(
                status = FileTaskStatus.RUNNING,
                currentItemId = ARCHIVE_RESULT_ID,
                phase = FileProgressListener.Phase.DECRYPTING,
                processed = 0,
                total = descriptor.archiveSize ?: 0L,
                volumeIndex = 1,
                volumeTotal = set.partCount,
                error = null,
                notice = null,
            )
        }
        batchStartedAt = System.currentTimeMillis()
        batchMode = FileTaskMode.DECRYPT
        AppLog.info("file.part.merge", "start parts=${set.partCount}")
        job = viewModelScope.launch {
            FileTaskRuntime.beginOperation()
            try {
                val keys = runner.prepareKeys(FileTaskMode.DECRYPT, null)
                val directory = prepareTaskDirectory(ARCHIVE_RESULT_ID)
                val result = runner.mergeArchiveParts(
                    parts = parts,
                    descriptor = descriptor,
                    privateKey = keys.privateKey,
                    knownSenders = keys.knownSenders,
                    directory = directory,
                    onVolumeProgress = volumeProgressListener(ARCHIVE_RESULT_ID),
                    cancel = cancelFlag,
                )
                val merged = FileQueueItem(
                    id = ARCHIVE_RESULT_ID,
                    source = first,
                    status = FileQueueStatus.SUCCEEDED,
                    result = result,
                )
                _state.update {
                    it.copy(
                        status = FileTaskStatus.SUCCEEDED,
                        currentItemId = null,
                        phase = null,
                        processed = 0,
                        total = 0,
                        volumeIndex = null,
                        volumeTotal = null,
                        archiveResult = null,
                        queue = listOf(merged),
                        // 结果卡本身会显示“已合并 N 卷”，不再叠一条重复提示。
                        notice = null,
                    )
                }
                AppLog.info(
                    "file.part.merge",
                    "ok parts=${set.partCount} bytes=${result.bytes}",
                )
            } catch (error: FileCancelledException) {
                deleteTaskDirectory(ARCHIVE_RESULT_ID)
                _state.update {
                    it.copy(
                        status = FileTaskStatus.READY,
                        currentItemId = null,
                        phase = null,
                        processed = 0,
                        total = 0,
                        volumeIndex = null,
                        volumeTotal = null,
                        notice = uiText(R.string.files_batch_cancelled),
                    )
                }
            } catch (error: CancellationException) {
                deleteTaskDirectory(ARCHIVE_RESULT_ID)
                throw error
            } catch (error: Exception) {
                deleteTaskDirectory(ARCHIVE_RESULT_ID)
                logFailure("file.part.merge", error, "parts=${set.partCount}")
                _state.update {
                    it.copy(
                        status = FileTaskStatus.FAILED,
                        currentItemId = null,
                        phase = null,
                        processed = 0,
                        total = 0,
                        volumeIndex = null,
                        volumeTotal = null,
                        error = messageFor(error),
                    )
                }
            } finally {
                FileTaskRuntime.endOperation()
            }
        }
    }

    private fun startArchive(
        contact: ContactKey?,
        volumeBytes: Long,
        queue: List<FileQueueItem>,
    ) {
        val failed = queue.count { it.status == FileQueueStatus.FAILED }
        if (failed > 0) {
            _state.update {
                it.copy(
                    queue = queue,
                    status = FileTaskStatus.READY,
                    archivePhase = null,
                    error = uiText(R.string.files_archive_blocked, failed),
                )
            }
            return
        }

        val totalKnown = queue.sumOf { it.source.size?.coerceAtLeast(0L) ?: 0L }
        cancelFlag.set(false)
        _state.update { state ->
            state.copy(
                queue = queue,
                status = FileTaskStatus.RUNNING,
                currentItemId = null,
                phase = FileProgressListener.Phase.SIGNING,
                processed = 0,
                total = totalKnown,
                volumeIndex = null,
                volumeTotal = null,
                archivePhase = ArchivePhase.PACKAGING,
                archiveFilesDone = 0,
                archiveFilesTotal = queue.size,
                archiveResult = null,
                archiveSaved = false,
                error = null,
                notice = null,
                savingItemId = null,
            )
        }
        batchStartedAt = System.currentTimeMillis()
        batchMode = FileTaskMode.ENCRYPT
        AppLog.info(
            "file.archive",
            "start files=${queue.size} limit=$volumeBytes",
        )
        job = viewModelScope.launch {
            FileTaskRuntime.beginOperation()
            try {
                runArchive(contact, volumeBytes)
            } finally {
                FileTaskRuntime.endOperation()
            }
        }
    }

    /**
     * 单个原文件直接切片：不生成 ZIP，接收端合并后还原为原文件。
     *
     * <p>结果复用归档结果卡（分享全部/保存全部/逐卷分享），因为多分卷的收集
     * 方式与归档分卷完全一致；[FileTaskResult.isArchive] 保持 false，接收端
     * 合并时按 `sourceIsArchive=false` 直接还原而不是解压。</p>
     */
    private fun startSingleFileVolume(
        contact: ContactKey?,
        volumeBytes: Long,
        queue: List<FileQueueItem>,
    ) {
        val item = queue.singleOrNull() ?: return
        cancelFlag.set(false)
        _state.update { state ->
            state.copy(
                queue = queue.map {
                    if (it.id == item.id) it.copy(status = FileQueueStatus.RUNNING, error = null) else it
                },
                status = FileTaskStatus.RUNNING,
                currentItemId = item.id,
                phase = FileProgressListener.Phase.SIGNING,
                processed = 0,
                total = item.source.size ?: 0,
                volumeIndex = null,
                volumeTotal = null,
                archivePhase = null,
                archiveFilesDone = 0,
                archiveFilesTotal = 0,
                archiveResult = null,
                archiveSaved = false,
                error = null,
                notice = null,
                savingItemId = null,
            )
        }
        batchStartedAt = System.currentTimeMillis()
        batchMode = FileTaskMode.ENCRYPT
        AppLog.info(
            "file.single.volume",
            "start size=${item.source.size ?: -1} limit=$volumeBytes",
        )
        job = viewModelScope.launch {
            FileTaskRuntime.beginOperation()
            try {
                runSingleFileVolume(contact, volumeBytes)
            } finally {
                FileTaskRuntime.endOperation()
            }
        }
    }

    private suspend fun runSingleFileVolume(contact: ContactKey?, volumeBytes: Long) {
        val current = _state.value
        val item = current.queue.singleOrNull() ?: return
        val started = System.currentTimeMillis()
        try {
            val directory = prepareTaskDirectory(item.id)
            val prepared = runner.prepareKeys(FileTaskMode.ENCRYPT, contact)
            val result = runner.encryptSingleVolume(
                source = item.source,
                privateKey = prepared.privateKey,
                recipient = requireNotNull(prepared.recipient),
                recipientName = contact?.name,
                directory = directory,
                volumeLimitBytes = volumeBytes,
                onHashProgress = { processed, total ->
                    _state.update { state ->
                        if (state.status == FileTaskStatus.RUNNING && state.currentItemId == item.id) {
                            state.copy(
                                phase = FileProgressListener.Phase.SIGNING,
                                processed = processed,
                                total = total,
                            )
                        } else {
                            state
                        }
                    }
                },
                onVolumeProgress = { index, count, processed, total ->
                    _state.update { state ->
                        if (state.status == FileTaskStatus.RUNNING && state.currentItemId == item.id) {
                            state.copy(
                                phase = FileProgressListener.Phase.ENCRYPTING,
                                volumeIndex = index,
                                volumeTotal = count,
                                processed = processed,
                                total = total,
                            )
                        } else {
                            state
                        }
                    }
                },
                cancel = cancelFlag,
            )
            if (cancelFlag.get()) {
                throw FileCancelledException("已取消单文件分卷")
            }
            _state.update { state ->
                state.copy(
                    status = FileTaskStatus.SUCCEEDED,
                    currentItemId = null,
                    phase = null,
                    processed = 0,
                    total = 0,
                    volumeIndex = null,
                    volumeTotal = null,
                    queue = state.queue.map {
                        if (it.id == item.id) {
                            it.copy(
                                status = FileQueueStatus.SUCCEEDED,
                                result = result,
                                error = null,
                            )
                        } else {
                            it
                        }
                    },
                    archiveResult = result,
                    archiveSaved = false,
                    error = null,
                )
            }
            AppLog.info(
                "file.single.volume",
                "ok parts=${result.parts.size} bytes=${result.bytes} " +
                    "ms=${System.currentTimeMillis() - started}",
            )
        } catch (error: FileCancelledException) {
            deleteTaskDirectory(item.id)
            _state.update { state ->
                state.copy(
                    status = FileTaskStatus.READY,
                    currentItemId = null,
                    phase = null,
                    processed = 0,
                    total = 0,
                    volumeIndex = null,
                    volumeTotal = null,
                    queue = state.queue.map {
                        if (it.id == item.id) {
                            it.copy(status = FileQueueStatus.CANCELLED, error = null)
                        } else {
                            it
                        }
                    },
                    notice = uiText(R.string.files_batch_cancelled),
                )
            }
        } catch (error: CancellationException) {
            deleteTaskDirectory(item.id)
            throw error
        } catch (error: Exception) {
            deleteTaskDirectory(item.id)
            val message = messageFor(error)
            logFailure(
                "file.single.volume",
                error,
                "size=${item.source.size ?: -1} ms=${System.currentTimeMillis() - started}",
            )
            _state.update { state ->
                state.copy(
                    status = FileTaskStatus.READY,
                    currentItemId = null,
                    phase = null,
                    processed = 0,
                    total = 0,
                    volumeIndex = null,
                    volumeTotal = null,
                    queue = state.queue.map {
                        if (it.id == item.id) {
                            it.copy(
                                status = FileQueueStatus.FAILED,
                                error = message,
                                retryable = true,
                            )
                        } else {
                            it
                        }
                    },
                    error = message,
                )
            }
        } finally {
            batchStartedAt = 0L
        }
    }

    private suspend fun runArchive(contact: ContactKey?, volumeBytes: Long) {
        val current = _state.value
        val sources = current.queue.map { it.source }
        val sourceIsFolder = current.selectionKind == SelectionKind.DIRECTORY
        val taskId = UUID.randomUUID().toString()
        val started = System.currentTimeMillis()
        try {
            val directory = prepareTaskDirectory(ARCHIVE_RESULT_ID)
            ArchiveSpacePreflight.check(
                availableBytes = directory.usableSpace,
                sourceBytes = knownSourceBytes(current.queue),
            )
            val prepared = runner.prepareKeys(FileTaskMode.ENCRYPT, contact)
            val result = runner.encryptArchive(
                sources = sources,
                rootName = current.directoryRootName,
                emptyDirectories = current.emptyDirectoryPaths,
                sourceIsFolder = sourceIsFolder,
                privateKey = prepared.privateKey,
                recipient = requireNotNull(prepared.recipient),
                recipientName = contact?.name,
                directory = directory,
                archiveRoot = filesRoot,
                taskId = taskId,
                volumeLimitBytes = volumeBytes,
                onArchivePhase = { phase ->
                    _state.update { state ->
                        if (state.status == FileTaskStatus.RUNNING && state.archivePhase != null) {
                            state.copy(archivePhase = phase)
                        } else {
                            state
                        }
                    }
                },
                onArchiveProgress = { processed, filesDone ->
                    _state.update { state ->
                        if (state.status == FileTaskStatus.RUNNING && state.archivePhase != null) {
                            state.copy(
                                processed = processed,
                                archiveFilesDone = filesDone,
                            )
                        } else {
                            state
                        }
                    }
                },
                onVolumeProgress = { index, count, processed, total ->
                    _state.update { state ->
                        if (state.status == FileTaskStatus.RUNNING && state.archivePhase != null) {
                            state.copy(
                                volumeIndex = index,
                                volumeTotal = count,
                                processed = processed,
                                total = total,
                            )
                        } else {
                            state
                        }
                    }
                },
                cancel = cancelFlag,
            )
            if (cancelFlag.get()) {
                throw FileCancelledException("已取消归档")
            }
            _state.update { state ->
                state.copy(
                    status = FileTaskStatus.SUCCEEDED,
                    currentItemId = null,
                    phase = null,
                    processed = 0,
                    total = 0,
                    volumeIndex = null,
                    volumeTotal = null,
                    archivePhase = null,
                    archiveFilesDone = 0,
                    archiveFilesTotal = 0,
                    archiveResult = result,
                    archiveSaved = false,
                    error = null,
                )
            }
            AppLog.info(
                "file.archive",
                "end parts=${result.parts.size} files=${result.archiveFileCount ?: 0} " +
                    "bytes=${result.bytes} ms=${System.currentTimeMillis() - started}",
            )
        } catch (error: FileCancelledException) {
            deleteTaskDirectory(ARCHIVE_RESULT_ID)
            _state.update { state ->
                state.copy(
                    status = FileTaskStatus.READY,
                    currentItemId = null,
                    phase = null,
                    processed = 0,
                    total = 0,
                    volumeIndex = null,
                    volumeTotal = null,
                    archivePhase = null,
                    archiveFilesDone = 0,
                    archiveFilesTotal = 0,
                    archiveResult = null,
                    notice = uiText(R.string.files_batch_cancelled),
                )
            }
        } catch (error: CancellationException) {
            deleteTaskDirectory(ARCHIVE_RESULT_ID)
            throw error
        } catch (error: ArchiveSpaceException) {
            deleteTaskDirectory(ARCHIVE_RESULT_ID)
            _state.update { state ->
                state.copy(
                    status = FileTaskStatus.READY,
                    currentItemId = null,
                    phase = null,
                    processed = 0,
                    total = 0,
                    volumeIndex = null,
                    volumeTotal = null,
                    archivePhase = null,
                    archiveFilesDone = 0,
                    archiveFilesTotal = 0,
                    archiveResult = null,
                    error = uiText(
                        R.string.files_archive_no_space,
                        formatBytes(error.requiredBytes),
                        formatBytes(error.availableBytes),
                    ),
                )
            }
        } catch (error: Exception) {
            deleteTaskDirectory(ARCHIVE_RESULT_ID)
            logFailure(
                "file.archive",
                error,
                "files=${sources.size} ms=${System.currentTimeMillis() - started}",
            )
            _state.update { state ->
                state.copy(
                    status = FileTaskStatus.READY,
                    currentItemId = null,
                    phase = null,
                    processed = 0,
                    total = 0,
                    volumeIndex = null,
                    volumeTotal = null,
                    archivePhase = null,
                    archiveFilesDone = 0,
                    archiveFilesTotal = 0,
                    archiveResult = null,
                    error = messageFor(error),
                )
            }
        } finally {
            ArchiveWorkspace.delete(filesRoot, taskId)
            batchStartedAt = 0L
        }
    }

    fun cancel() {
        if (!_state.value.isRunning) return
        cancelFlag.set(true)
        _state.update { it.copy(notice = uiText(R.string.files_cancelling)) }
    }

    private fun onCacheCleared() {
        if (_state.value.isRunning || _state.value.saving || _state.value.extracting) {
            // 正常路径被 FileTaskRuntime.busy 拦住；这里只防御竞态，不取消正在写的任务。
            AppLog.warn("file.cache", "op=cleared busy=true")
            return
        }
        AppLog.debug("file.cache", "op=reset")
        val mode = _state.value.mode
        releaseTask()
        _state.value = FileTaskUiState(
            mode = mode,
            packageEnabled = defaultPackageEnabled,
            notice = uiText(R.string.files_cache_cleared),
        )
    }

    /** 处理下一个文件：删除本批全部临时结果（有过导出租约的目录除外），保留当前模式。 */
    fun reset() {
        if (_state.value.isRunning || _state.value.saving || _state.value.extracting) return
        AppLog.debug("file.reset", "mode=${_state.value.mode.name}")
        releaseTask()
        _state.value = FileTaskUiState(
            mode = _state.value.mode,
            packageEnabled = defaultPackageEnabled,
        )
    }

    /** 把某一项的结果复制到用户通过 SAF 选择的位置。 */
    fun saveResultTo(itemId: String, uri: Uri) {
        val item = _state.value.queue.firstOrNull { it.id == itemId } ?: return
        val result = item.result ?: return
        if (_state.value.savingItemId != null || _state.value.extracting) return
        _state.update { it.copy(savingItemId = itemId, error = null, notice = null) }
        // 登记与设置页清理共用 FileTaskRuntime 的锁，必须放进协程里等锁；
        // endOperation 只在 beginOperation 正常返回后的 finally 里配对归还，
        // 协程在等锁时被取消就不会多还一次（会把别的任务的 busy 还掉）。
        viewModelScope.launch {
            FileTaskRuntime.beginOperation()
            try {
                val started = System.currentTimeMillis()
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val output = resolver.openOutputStream(uri)
                            ?: throw IOException("cannot write to the selected location")
                        output.use { sink ->
                            result.file.inputStream().use { source ->
                                source.copyTo(sink, DEFAULT_COPY_BUFFER)
                            }
                        }
                    }
                }
                saved
                    .onSuccess {
                        AppLog.info(
                            "file.save",
                            "ok bytes=${result.bytes} ms=${System.currentTimeMillis() - started}",
                        )
                        _state.update { state ->
                            state.copy(
                                savingItemId = null,
                                notice = uiText(R.string.files_saved_location),
                                queue = state.queue.map { queueItem ->
                                    if (queueItem.id == itemId) {
                                        queueItem.copy(saved = true)
                                    } else {
                                        queueItem
                                    }
                                },
                            )
                        }
                    }
                    .onFailure { error ->
                        logFailure("file.save", error, "ms=${System.currentTimeMillis() - started}")
                        _state.update {
                            it.copy(savingItemId = null, error = messageFor(error))
                        }
                    }
            } finally {
                FileTaskRuntime.endOperation()
            }
        }
    }

    /** 单文件页面的兼容入口。 */
    fun saveResultTo(uri: Uri) {
        val itemId = _state.value.queue.singleOrNull()?.id ?: return
        saveResultTo(itemId, uri)
    }

    /**
     * 单卷归档与多卷合并结果共用系统的单文件保存选择器。
     *
     * <p>打包结果只存在 [FileTaskUiState.archiveResult]；多卷合并会把结果折叠成
     * [ARCHIVE_RESULT_ID] 队列项（archiveResult 为 null）。两条来源都必须覆盖，
     * 否则「保存到…」只创建空文件、不写内容。</p>
     */
    fun saveArchiveResultTo(uri: Uri) {
        val result = _state.value.archiveResult
            ?: _state.value.queue.firstOrNull { it.id == ARCHIVE_RESULT_ID }?.result
            ?: return
        if (_state.value.savingItemId != null || _state.value.extracting) return
        _state.update {
            it.copy(
                savingItemId = ARCHIVE_RESULT_ID,
                archiveSaved = false,
                error = null,
                notice = null,
            )
        }
        viewModelScope.launch {
            FileTaskRuntime.beginOperation()
            try {
                val started = System.currentTimeMillis()
                val saved = withContext(Dispatchers.IO) {
                    runCatching { copyFileToUri(uri, result.file) }
                }
                saved
                    .onSuccess {
                        AppLog.info(
                            "file.save",
                            "ok archive parts=1 bytes=${result.file.length()} " +
                                "ms=${System.currentTimeMillis() - started}",
                        )
                        _state.update { state ->
                            state.copy(
                                savingItemId = null,
                                archiveSaved = true,
                                notice = uiText(R.string.files_saved_location),
                                // 多卷合并结果在队列里，结果卡按 queue[].saved 显示保存状态。
                                queue = state.queue.map { item ->
                                    if (item.id == ARCHIVE_RESULT_ID) {
                                        item.copy(saved = true)
                                    } else {
                                        item
                                    }
                                },
                            )
                        }
                    }
                    .onFailure { error ->
                        logFailure("file.save", error, "archive parts=1")
                        _state.update {
                            it.copy(savingItemId = null, archiveSaved = false, error = messageFor(error))
                        }
                    }
            } finally {
                FileTaskRuntime.endOperation()
            }
        }
    }

    /**
     * 多卷归档使用目录树选择器一次写出全部卷。
     *
     * <p>同名文件按 `_2`、`_3` 避让；部分失败保留已成功的卷，并报告
     * “已保存 k/N，第 k+1 卷失败”，只有全部成功才置 [FileTaskUiState.archiveSaved]。</p>
     */
    fun saveAllPartsTo(treeUri: Uri) {
        val result = _state.value.archiveResult ?: return
        val parts = result.parts.ifEmpty { listOf(result.file) }
        if (parts.isEmpty()) return
        if (_state.value.savingItemId != null || _state.value.extracting) return
        _state.update {
            it.copy(
                savingItemId = ARCHIVE_RESULT_ID,
                archiveSaved = false,
                error = null,
                notice = null,
            )
        }
        viewModelScope.launch {
            FileTaskRuntime.beginOperation()
            try {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { saveArchiveParts(treeUri, parts) }
                }
                outcome
                    .onSuccess { saved ->
                        val all = saved.failure == null && saved.saved == parts.size
                        if (saved.failure != null) {
                            logFailure(
                                "file.save_archive",
                                saved.failure,
                                "saved=${saved.saved} total=${parts.size}",
                            )
                        }
                        AppLog.info(
                            "file.save_archive",
                            "ok=$all saved=${saved.saved} total=${parts.size}",
                        )
                        _state.update { state ->
                            state.copy(
                                savingItemId = null,
                                archiveSaved = all,
                                notice = if (all) {
                                    uiText(R.string.files_saved_location)
                                } else {
                                    uiText(
                                        R.string.files_archive_saved_partial,
                                        saved.saved,
                                        parts.size,
                                        saved.failedIndex ?: (saved.saved + 1),
                                    )
                                },
                                error = if (saved.saved == 0) {
                                    saved.failure?.let(::messageFor)
                                } else {
                                    null
                                },
                            )
                        }
                    }
                    .onFailure { error ->
                        logFailure("file.save_archive", error, "total=${parts.size}")
                        _state.update {
                            it.copy(
                                savingItemId = null,
                                archiveSaved = false,
                                error = messageFor(error),
                            )
                        }
                    }
            } finally {
                FileTaskRuntime.endOperation()
            }
        }
    }

    /**
     * 把某一项解密结果安全解压到用户通过 SAF 选择的文件夹。
     *
     * <p>只接受归档结果（新外部单卷包、旧 VOLU 文件夹包、多卷合并结果）；普通单文件
     * 解密出的 ZIP 不提供解压，避免把用户原始压缩包当成本工具的归档。解压与主任务、
     * 保存互斥，一次只跑一个；目标目录权限在结束后释放。</p>
     */
    fun extractResultTo(itemId: String, treeUri: Uri) {
        val current = _state.value
        if (current.isRunning || current.saving || current.extracting) return
        val result = current.queue.firstOrNull { it.id == itemId }?.result ?: return
        if (!result.isExtractableArchive()) return

        releaseExtractPermission()
        extractCancelFlag.set(false)
        _state.update {
            it.copy(
                extractingItemId = itemId,
                extractFilesDone = 0,
                extractFilesTotal = 0,
                error = null,
                notice = null,
            )
        }
        AppLog.info(
            "file.extract",
            "start archive=${result.isArchive} folder=${result.sourceIsFolder}",
        )
        extractJob = viewModelScope.launch {
            FileTaskRuntime.beginOperation()
            var granted = false
            try {
                granted = takeExtractPermission(treeUri)
                val started = System.currentTimeMillis()
                val extracted = withContext(Dispatchers.IO) {
                    ArchiveExtractor.extract(
                        zipFile = result.file,
                        sink = SafArchiveSink(resolver, treeUri),
                        onProgress = { progress ->
                            _state.update { state ->
                                state.copy(
                                    extractFilesDone = progress.filesDone,
                                    extractFilesTotal = progress.filesTotal,
                                )
                            }
                        },
                        cancel = extractCancelFlag,
                    )
                }
                val notice = when {
                    extracted.skipped > 0 && extracted.renamed > 0 -> uiText(
                        R.string.files_extract_partial_renamed,
                        extracted.files,
                        extracted.skipped,
                        extracted.renamed,
                    )

                    extracted.skipped > 0 -> uiText(
                        R.string.files_extract_partial,
                        extracted.files,
                        extracted.skipped,
                    )

                    extracted.renamed > 0 -> uiText(
                        R.string.files_extract_done_renamed,
                        extracted.files,
                        extracted.renamed,
                    )

                    else -> uiText(R.string.files_extract_done, extracted.files)
                }
                _state.update {
                    it.copy(
                        extractingItemId = null,
                        extractFilesDone = 0,
                        extractFilesTotal = 0,
                        notice = notice,
                        error = null,
                    )
                }
                AppLog.info(
                    "file.extract",
                    "ok files=${extracted.files} dirs=${extracted.directories} " +
                        "renamed=${extracted.renamed} skipped=${extracted.skipped} " +
                        "bytes=${extracted.bytes} ms=${System.currentTimeMillis() - started}",
                )
            } catch (error: FileCancelledException) {
                _state.update {
                    it.copy(
                        extractingItemId = null,
                        extractFilesDone = 0,
                        extractFilesTotal = 0,
                        notice = uiText(R.string.files_extract_cancelled),
                    )
                }
                AppLog.info("file.extract", "cancelled")
            } catch (error: CancellationException) {
                _state.update {
                    it.copy(
                        extractingItemId = null,
                        extractFilesDone = 0,
                        extractFilesTotal = 0,
                    )
                }
                throw error
            } catch (error: Exception) {
                logFailure("file.extract", error)
                _state.update {
                    it.copy(
                        extractingItemId = null,
                        extractFilesDone = 0,
                        extractFilesTotal = 0,
                        error = messageFor(error),
                    )
                }
            } finally {
                if (granted) releaseExtractPermission()
                FileTaskRuntime.endOperation()
            }
        }
    }

    fun cancelExtraction() {
        if (!_state.value.extracting) return
        extractCancelFlag.set(true)
        _state.update { it.copy(notice = uiText(R.string.files_cancelling)) }
    }

    override fun onCleared() {
        AppLog.verbose("file.viewmodel", "cleared mode=${_state.value.mode.name}")
        cancelFlag.set(true)
        job?.cancel()
        sourceJob?.cancel()
        extractCancelFlag.set(true)
        extractJob?.cancel()
        extractJob = null
        releaseExtractPermission()
        releasePersistedPermissions()
        deleteAllTaskDirectories()
        ArchiveWorkspace.cleanup(filesRoot)
        super.onCleared()
    }

    // region 队列执行

    private suspend fun runQueue(contact: ContactKey?) {
        val mode = _state.value.mode
        val encryptedNames = mutableSetOf<String>()
        var cancelled = false
        var lastError: String? = null
        try {
            val prepared = runner.prepareKeys(mode, contact)

            val ids = _state.value.queue
                .filter { it.status != FileQueueStatus.FAILED }
                .map { it.id }

            var position = 0
            for (id in ids) {
                position++
                if (cancelFlag.get()) {
                    cancelled = true
                    cancelPendingItems()
                    break
                }
                val item = _state.value.queue.firstOrNull { it.id == id } ?: continue
                if (item.status == FileQueueStatus.FAILED) continue
                AppLog.debug(
                    "file.batch.item.start",
                    "index=$position total=${ids.size} mode=${mode.name} " +
                        "size=${item.source.size ?: -1}",
                )
                val itemStarted = System.currentTimeMillis()

                _state.update { state ->
                    state.copy(
                        currentItemId = id,
                        phase = if (mode == FileTaskMode.ENCRYPT) {
                            FileProgressListener.Phase.SIGNING
                        } else {
                            FileProgressListener.Phase.DECRYPTING
                        },
                        processed = 0,
                        total = item.source.size ?: 0,
                        volumeIndex = null,
                        volumeTotal = null,
                        queue = state.queue.map {
                            if (it.id == id) it.copy(status = FileQueueStatus.RUNNING, error = null) else it
                        },
                    )
                }

                val directory = prepareTaskDirectory(id)
                try {
                    val result = if (mode == FileTaskMode.ENCRYPT) {
                        runner.encrypt(
                            source = item.source,
                            privateKey = prepared.privateKey,
                            recipient = requireNotNull(prepared.recipient),
                            recipientName = contact?.name,
                            directory = directory,
                            reservedNames = encryptedNames,
                            onProgress = progressListener(item.id),
                            cancel = cancelFlag,
                        )
                    } else {
                        runner.decrypt(
                            source = item.source,
                            privateKey = prepared.privateKey,
                            knownSenders = prepared.knownSenders,
                            directory = directory,
                            onProgress = progressListener(item.id),
                            onVolumeProgress = volumeProgressListener(item.id),
                            cancel = cancelFlag,
                        )
                    }
                    updateItem(id) {
                        it.copy(status = FileQueueStatus.SUCCEEDED, result = result, error = null)
                    }
                    AppLog.debug(
                        "file.batch.item",
                        "index=$position total=${ids.size} bytes=${result.bytes} " +
                            "ms=${System.currentTimeMillis() - itemStarted}",
                    )
                } catch (error: FileCancelledException) {
                    deleteTaskDirectory(id)
                    updateItem(id) { it.copy(status = FileQueueStatus.CANCELLED, error = null) }
                    cancelled = true
                    cancelPendingItems()
                    break
                } catch (error: CancellationException) {
                    deleteTaskDirectory(id)
                    updateItem(id) { it.copy(status = FileQueueStatus.CANCELLED, error = null) }
                    throw error
                } catch (error: Exception) {
                    deleteTaskDirectory(id)
                    val message = messageFor(error)
                    lastError = message
                    updateItem(id) { it.copy(status = FileQueueStatus.FAILED, error = message) }
                    val retryable = _state.value.queue.firstOrNull { it.id == id }?.retryable ?: true
                    logFailure(
                        "file.batch.item",
                        error,
                        "index=$position total=${ids.size} retryable=$retryable " +
                            "ms=${System.currentTimeMillis() - itemStarted}",
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 批量开始前的准备失败（例如私钥无法解封）：退回可重试的就绪态，
            // 让用户修好密钥/权限后直接再点开始，而不是把整批标成已处理完。
            logFailure(
                "file.batch",
                error,
                "mode=${batchMode.name} ms=${System.currentTimeMillis() - batchStartedAt}",
            )
            _state.update {
                it.copy(
                    status = FileTaskStatus.READY,
                    phase = null,
                    currentItemId = null,
                    processed = 0,
                    total = 0,
                    error = messageFor(error),
                    notice = null,
                )
            }
            return
        }
        finishBatch(cancelled, lastError)
    }

    private fun finishBatch(cancelled: Boolean, lastError: String?) {
        _state.update { state ->
            val success = state.queue.count { it.status == FileQueueStatus.SUCCEEDED }
            val failure = state.queue.count { it.status == FileQueueStatus.FAILED }
            val batch = state.queue.size > 1
            val status = when {
                cancelled && success == 0 -> FileTaskStatus.READY
                cancelled || (failure > 0 && success > 0) -> FileTaskStatus.PARTIAL
                failure > 0 -> FileTaskStatus.FAILED
                success > 0 -> FileTaskStatus.SUCCEEDED
                else -> FileTaskStatus.READY
            }
            val notice = when {
                cancelled && success > 0 ->
                    uiText(R.string.files_batch_cancelled_partial, success)

                cancelled -> uiText(R.string.files_batch_cancelled)
                failure > 0 && batch -> uiText(R.string.files_batch_incomplete, failure)
                else -> null
            }
            state.copy(
                status = status,
                currentItemId = null,
                phase = null,
                processed = 0,
                total = 0,
                volumeIndex = null,
                volumeTotal = null,
                error = if (!batch && status == FileTaskStatus.FAILED) {
                    lastError ?: state.queue.firstOrNull { it.error != null }?.error
                } else {
                    null
                },
                notice = notice,
            )
        }
        val finished = _state.value
        AppLog.info(
            "file.batch",
            "end mode=${batchMode.name} files=${finished.queue.size} ok=${finished.successCount} " +
                "failed=${finished.failureCount} cancelled=${finished.cancelledCount} " +
                "ms=${if (batchStartedAt > 0) System.currentTimeMillis() - batchStartedAt else 0}",
        )
        batchStartedAt = 0L
    }

    private fun cancelPendingItems() {
        _state.update { state ->
            state.copy(
                queue = state.queue.map { item ->
                    if (item.status == FileQueueStatus.PENDING || item.status == FileQueueStatus.RUNNING) {
                        item.copy(status = FileQueueStatus.CANCELLED)
                    } else {
                        item
                    }
                },
            )
        }
    }

    private fun updateItem(id: String, transform: (FileQueueItem) -> FileQueueItem) {
        _state.update { state ->
            state.copy(
                queue = state.queue.map { item -> if (item.id == id) transform(item) else item },
            )
        }
    }

    private fun progressListener(itemId: String): FileProgressListener {
        // 只记阶段变化：进度回调每个缓冲区都会触发，逐次写会把 1 MB 日志刷满（日志计划 §5.8）。
        var lastPhase: FileProgressListener.Phase? = null
        return FileProgressListener { phase, processed, total ->
            if (phase != lastPhase) {
                lastPhase = phase
                AppLog.verbose(
                    "file.phase",
                    "phase=${phase.name} processed=$processed total=$total",
                )
            }
            _state.update { state ->
                if (state.status != FileTaskStatus.RUNNING || state.currentItemId != itemId) {
                    state
                } else {
                    state.copy(phase = phase, processed = processed, total = total)
                }
            }
        }
    }

    private fun volumeProgressListener(itemId: String): VolumeProgressListener =
        VolumeProgressListener { index, count, processed, total ->
            _state.update { state ->
                if (state.status != FileTaskStatus.RUNNING || state.currentItemId != itemId) {
                    state
                } else {
                    state.copy(
                        volumeIndex = index,
                        volumeTotal = count,
                        processed = processed,
                        total = total,
                    )
                }
            }
        }

    // endregion

    // region 文件与错误

    private data class ArchiveSaveOutcome(
        val saved: Int,
        val failedIndex: Int?,
        val failure: Throwable?,
    )

    private data class DirectorySelection(
        val files: List<SelectedFile>,
        val skippedNonMatching: Int,
        val rootName: String?,
        val emptyDirectories: List<String>,
    )

    /**
     * 在 IO 线程执行 SAF 目录扫描，并把平台异常收敛成用户可读的
     * [DirectoryScanException]；安全异常原样保留给 [messageFor] 翻译。
     */
    private fun scanDirectory(treeUri: Uri, mode: FileTaskMode): DirectorySelection = runCatching {
        val reader = SafDirectoryReader(resolver, treeUri)
        val result = DirectoryWalker.collect(
            rootDocumentId = reader.rootDocumentId(),
            mode = mode,
            listChildren = reader::listChildren,
        )
        DirectorySelection(
            rootName = runCatching { reader.rootName() }.getOrNull(),
            emptyDirectories = result.emptyDirectories,
            files = result.files.map { file ->
                val uri = reader.documentUri(file.id)
                SelectedFile(
                    uri = uri,
                    displayName = FileTaskStorage.sanitizeFileName(
                        file.name,
                        uiText(R.string.files_unnamed),
                    ),
                    size = file.size,
                    // 目录扫描已经拿到大小；MIME 对加解密无用，避免每个文件再查一次 provider。
                    mimeType = null,
                    relativePath = file.relativePath,
                    // 只多读 4 字节判断是否已是密文；不打开内容、不参与扫描上限计算。
                    alreadyEncrypted = probeAlreadyEncrypted(uri),
                )
            },
            skippedNonMatching = result.skippedNonMatching,
        )
    }.getOrElse { error ->
        throw when (error) {
            is DirectoryScanException,
            is DirectoryLimitException,
            is SecurityException -> error

            else -> DirectoryScanException(
                error.message?.takeIf { it.isNotBlank() } ?: "cannot read this directory",
            )
        }
    }

    private fun readMetadata(uri: Uri): SelectedFile {
        var displayName: String? = null
        var size: Long? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        // 先确认真的能打开；权限被撤销或 provider 失效时在选文件阶段就提示。
        // 同一次打开顺带读前 4 字节，用于加密前提醒「这已经是密文」。
        val alreadyEncrypted = openInput(uri).use { input -> hasCipherMagic(input) }
        return SelectedFile(
            uri = uri,
            displayName = FileTaskStorage.sanitizeFileName(displayName, uiText(R.string.files_unnamed)),
            size = size,
            mimeType = resolver.getType(uri),
            alreadyEncrypted = alreadyEncrypted,
        )
    }

    /** 只读前 4 字节判断是否为本工具密文；读不到就当作普通文件，不阻断选择。 */
    private fun probeAlreadyEncrypted(uri: Uri): Boolean = runCatching {
        openInput(uri).use { input -> hasCipherMagic(input) }
    }.getOrDefault(false)

    private fun hasCipherMagic(input: InputStream): Boolean {
        val bytes = ByteArray(4)
        var read = 0
        while (read < 4) {
            val count = input.read(bytes, read, 4 - read)
            if (count <= 0) return false
            read += count
        }
        return ByteBuffer.wrap(bytes).int == FileFormatHeader.MAGIC
    }

    private fun openInput(uri: Uri): InputStream =
        resolver.openInputStream(uri)
            ?: throw IOException("cannot read this file; access may have expired")

    private fun messageFor(error: Throwable): String =
        fileTaskMessageFor(getApplication<Application>(), error)

    /**
     * 逐个创建 DocumentsContract 文档；第 index 项抛错时停止，已写出的卷保留。
     * `failedIndex` 是 1-based 卷号，供界面报告“第 k 卷失败”。
     */
    private fun saveArchiveParts(treeUri: Uri, parts: List<File>): ArchiveSaveOutcome {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
        val usedNames = existingDocumentNames(treeUri, treeDocumentId)
        var saved = 0
        for (index in parts.indices) {
            val part = parts[index]
            try {
                val displayName = ArchiveSharePlan.uniqueName(part.name, usedNames)
                val document = DocumentsContract.createDocument(
                    resolver,
                    parent,
                    ArchiveSharePlan.MIME_TYPE,
                    displayName,
                ) ?: throw IOException("cannot create the archive part document")
                try {
                    copyFileToUri(document, part)
                } catch (error: Exception) {
                    // 已创建的空文档不应留下；删除失败也不能覆盖原始写入错误。
                    runCatching { DocumentsContract.deleteDocument(resolver, document) }
                    throw error
                }
                saved++
            } catch (error: Exception) {
                return ArchiveSaveOutcome(
                    saved = saved,
                    failedIndex = index + 1,
                    failure = error,
                )
            }
        }
        return ArchiveSaveOutcome(saved = saved, failedIndex = null, failure = null)
    }

    /** 读取目标目录已有名称；provider 不支持查询时返回空集合，仍可继续创建。 */
    private fun existingDocumentNames(
        treeUri: Uri,
        treeDocumentId: String,
    ): MutableSet<String> {
        val names = mutableSetOf<String>()
        runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                treeDocumentId,
            )
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val column = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (column >= 0) {
                    while (cursor.moveToNext()) {
                        if (!cursor.isNull(column)) {
                            names += cursor.getString(column).lowercase(Locale.US)
                        }
                    }
                }
            }
        }
        return names
    }

    private fun copyFileToUri(uri: Uri, file: File) {
        val output = resolver.openOutputStream(uri)
            ?: throw IOException("cannot write to the selected location")
        output.use { sink ->
            file.inputStream().use { source ->
                source.copyTo(sink, DEFAULT_COPY_BUFFER)
            }
        }
    }

    private fun uiText(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    /**
     * 归档源总字节数；任一文件大小未知或求和溢出时返回 null，跳过空间预检，
     * 交给写入阶段的 ENOSPC/IO 错误兜底。
     */
    private fun knownSourceBytes(queue: List<FileQueueItem>): Long? {
        var total = 0L
        queue.forEach { item ->
            val size = item.source.size ?: return null
            if (size < 0) return null
            if (total > Long.MAX_VALUE - size) return null
            total += size
        }
        return total
    }

    private fun formatBytes(bytes: Long): String =
        Formatter.formatShortFileSize(getApplication(), bytes)

    private fun prepareTaskDirectory(itemId: String): File {
        deleteTaskDirectory(itemId)
        taskRoot.mkdirs()
        val directory = File(taskRoot, UUID.randomUUID().toString())
        check(directory.mkdirs() || directory.isDirectory) { "cannot create task directory" }
        taskDirectories[itemId] = directory
        return directory
    }

    private fun deleteTaskDirectory(itemId: String) {
        val directory = taskDirectories.remove(itemId) ?: return
        // 结果曾交给其他应用时保留到租约到期：对方可能稍后从「最近文件」再读一次。
        if (FileTaskStorage.hasActiveLease(directory)) {
            AppLog.debug("file.lease", "keep=true")
            return
        }
        FileTaskStorage.deleteRecursively(directory)
    }

    private fun deleteAllTaskDirectories() {
        taskDirectories.keys.toList().forEach(::deleteTaskDirectory)
    }

    private fun releaseTask() {
        cancelFlag.set(true)
        job?.cancel()
        job = null
        sourceJob?.cancel()
        sourceJob = null
        releasePersistedPermissions()
        deleteAllTaskDirectories()
        ArchiveWorkspace.cleanup(filesRoot)
    }

    private fun takePersistablePermission(uri: Uri): Uri? = runCatching {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        uri
    }.getOrNull()

    /** 解压目标需要读写权限；provider 不支持持久化授权时退回本次会话的临时授权。 */
    private fun takeExtractPermission(uri: Uri): Boolean = runCatching {
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        extractPersistedUri = uri
        true
    }.getOrDefault(false)

    private fun releaseExtractPermission() {
        val uri = extractPersistedUri ?: return
        extractPersistedUri = null
        runCatching {
            resolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private fun releasePersistedPermissions() {
        releaseImportedPermission()
        releaseDirectoryPermission()
    }

    /** 释放外部导入时取得的持久化读权限；没拿到过就是空操作。 */
    private fun releaseImportedPermission() {
        if (importedPersistedUris.isEmpty()) return
        val uris = importedPersistedUris.toList()
        importedPersistedUris.clear()
        uris.forEach { uri ->
            runCatching {
                resolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    /** 释放目录树 URI 的持久化读权限；扫描失败或任务结束时调用。 */
    private fun releaseDirectoryPermission() {
        val uri = directoryPersistedUri ?: return
        directoryPersistedUri = null
        runCatching {
            resolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun fallbackSourceName(uri: Uri): String =
        FileTaskStorage.sanitizeFileName(
            uri.lastPathSegment?.substringAfterLast('/'),
            uiText(R.string.files_unreadable_selected),
        )



    private companion object {
        const val DEFAULT_COPY_BUFFER = 64 * 1024
    }
}
