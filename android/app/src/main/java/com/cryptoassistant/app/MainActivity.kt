package com.cryptoassistant.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.cryptoassistant.app.data.AppSettings
import com.cryptoassistant.app.data.AccentPalette
import com.cryptoassistant.app.data.ThemeMode
import com.cryptoassistant.app.data.log.AppLog
import com.cryptoassistant.app.feature.files.FileTaskMode
import com.cryptoassistant.app.feature.open.EntryPlan
import com.cryptoassistant.app.feature.open.IncomingEntryPolicy
import com.cryptoassistant.app.feature.open.IncomingFiles
import com.cryptoassistant.app.feature.open.IncomingParseResult
import com.cryptoassistant.app.feature.open.parseIncomingFiles
import com.cryptoassistant.app.feature.security.SecurityGate
import com.cryptoassistant.app.feature.security.SecuritySession
import com.cryptoassistant.app.ui.navigation.AppNavigation
import com.cryptoassistant.app.ui.navigation.AppRoutes
import com.cryptoassistant.app.ui.theme.CryptoAssistantTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : FragmentActivity() {

    private val pendingRoute = MutableStateFlow<String?>(null)
    private val pendingImport = MutableStateFlow<IncomingFiles?>(null)

    /** 混合批量：请求先停在这里，用户选择“只解密/只加密/取消”后再投递。 */
    private val pendingMixedBatch = MutableStateFlow<MixedBatchPrompt?>(null)

    /** 判型进行中；VIEW/SEND/SEND_MULTIPLE 没有显式模式时短暂为 true。 */
    private val resolvingEntry = MutableStateFlow(false)

    /** 上一次已经投递给页面的外部请求；用来识别"同一次打开的旋转重建"，避免重放。 */
    private var deliveredImport: String? = null

    private var resolveJob: Job? = null
    private var resolveGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppLog.debug(
            "app.open",
            "from=${entryName(intent?.action)} cold=${savedInstanceState == null}",
        )

        deliveredImport = savedInstanceState?.getString(KEY_DELIVERED_IMPORT)
        handleIntent(intent, restored = savedInstanceState != null)

        val app = application as CryptoAssistantApplication
        val repository = app.settingsRepository
        val keyRepository = app.keyRepository
        setContent {
            // 设置未加载时不要先用默认值绘一帧：应用锁开启时那会闪出业务内容。
            val settingsFlow = remember(repository) {
                repository.settings.map { it as AppSettings? }
            }
            val loadedSettings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
            val settings = loadedSettings
            val route by pendingRoute.collectAsStateWithLifecycle()
            val importRequest by pendingImport.collectAsStateWithLifecycle()
            val mixedBatch by pendingMixedBatch.collectAsStateWithLifecycle()
            val resolving by resolvingEntry.collectAsStateWithLifecycle()

            val themeMode = settings?.themeMode ?: ThemeMode.SYSTEM
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val view = LocalView.current
            SideEffect {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }

            CryptoAssistantTheme(
                themeMode = themeMode,
                dynamicColor = settings?.dynamicColor ?: false,
                accentPalette = settings?.accentPalette ?: AccentPalette.INDIGO,
                amoledDark = settings?.amoledDark ?: false,
            ) {
                if (settings == null) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {}
                } else {
                    SecurityGate(settings = settings) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.background,
                            ) {
                                AppNavigation(
                                    repository = repository,
                                    keyRepository = keyRepository,
                                    settings = settings,
                                    pendingRoute = route,
                                    onPendingRouteConsumed = { pendingRoute.value = null },
                                    importRequest = importRequest,
                                    onImportConsumed = { pendingImport.value = null },
                                )
                            }
                            // 判型是本地文件头读取，通常几十毫秒；遮罩只是避免用户在路由确定前先看到首页。
                            if (resolving) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Surface(
                                        shape = MaterialTheme.shapes.large,
                                        color = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 6.dp,
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(
                                                horizontal = 20.dp,
                                                vertical = 16.dp,
                                            ),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Text(
                                                text = stringResource(R.string.open_mode_resolving),
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        mixedBatch?.let { prompt ->
                            MixedBatchDialog(
                                prompt = prompt,
                                onDecrypt = { decideMixed(prompt, decrypt = true) },
                                onEncrypt = { decideMixed(prompt, decrypt = false) },
                                onCancel = { cancelMixed(prompt) },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, restored = false)
    }

    /**
     * 处理外部入口：应用内的 `navigate_to` 路由，以及系统文件入口。
     *
     * <p>系统文件入口自 2026-10-31 起由透明 [com.cryptoassistant.app.feature.open.FileEntryActivity]
     * 接收并原样转发到这里，所以解析/判型逻辑不变：命中显式 `entry_mode`（adb/测试）
     * 或 SEND 的直接投递；其余 VIEW 走 [IncomingModeResolver] 决定进加密页还是解密页。
     * 判不出来时用二选一对话框，不猜。</p>
     *
     * <p>解析成功与拒绝都会写一条不含路径/文件名的诊断日志；拒绝时只给可读提示，
     * 不进入空白文件页。</p>
     */
    private fun handleIntent(intent: Intent?, restored: Boolean) {
        intent?.getStringExtra(EXTRA_NAVIGATE_TO)?.let { route -> pendingRoute.value = route }
        if (intent == null) return

        // 磁贴长按：系统按 QS_TILE_PREFERENCES 约定拉起本 Activity，回首页即可。
        if (intent.action == TileService.ACTION_QS_TILE_PREFERENCES) {
            AppLog.debug("app.open", "from=QS_TILE_PREFERENCES")
            pendingRoute.value = AppRoutes.HOME
            return
        }

        // 显式模式（adb/测试）优先；没有时由解析层按 VIEW/SEND 兜底。
        val entryMode = intent.getStringExtra(EXTRA_ENTRY_MODE)?.let { raw ->
            runCatching { FileTaskMode.valueOf(raw) }.getOrNull()
        }
        // 新的外部请求到达时，先撤掉上一次还没选完的混合批量对话框。
        if (!restored) pendingMixedBatch.value = null
        when (val result = parseIncomingFiles(this, intent, callerPackage(), entryMode)) {
            null -> return

            is IncomingParseResult.Rejected -> {
                // 旋转重建时不要重复弹同一个拒绝提示/重复写同一条日志。
                if (!restored) {
                    AppLog.warn(
                        "file.import.reject",
                        "reason=${result.reason} ${result.diagnostic}",
                    )
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }

            is IncomingParseResult.Accepted -> {
                val incoming = result.request
                val key = importKey(incoming)
                if (restored && key == deliveredImport) return

                if (entryMode != null) {
                    deliver(
                        request = incoming.copy(mode = entryMode),
                        diagnostic = result.diagnostic,
                        entry = entryMode.name,
                        by = "action",
                        confidence = "-",
                        sourceKey = key,
                    )
                } else {
                    routeIncoming(
                        incoming = incoming,
                        diagnostic = result.diagnostic,
                        // EXTRA_TITLE 是整批标题，不能拿来给每个文件当文件名；只有单文件才采用。
                        titleHint = if (incoming.uris.size == 1) {
                            intent.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString()
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    /**
     * 所有外部入口的统一分流：按文件名/文件头判型，再决定进哪一页。
     *
     * <p>VIEW / SEND / SEND_MULTIPLE 共用；最多等 [ENTRY_RESOLVE_TIMEOUT_MS]。
     * 超时或读不到按“否则加密”兜底，不再让用户在二选一里猜。</p>
     */
    private fun routeIncoming(
        incoming: IncomingFiles,
        diagnostic: String,
        titleHint: String?,
    ) {
        resolveJob?.cancel()
        val generation = ++resolveGeneration
        resolvingEntry.value = true
        resolveJob = lifecycleScope.launch {
            val plan = withTimeoutOrNull(ENTRY_RESOLVE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        IncomingEntryPolicy.plan(this@MainActivity, incoming, titleHint)
                    }.getOrNull()
                }
            }
            if (generation != resolveGeneration) return@launch
            resolvingEntry.value = false

            if (plan == null) {
                AppLog.warn("file.import.route", "$diagnostic by=timeout -> encrypt")
                deliver(
                    request = incoming.copy(mode = FileTaskMode.ENCRYPT),
                    diagnostic = diagnostic,
                    entry = "-",
                    by = "timeout",
                    confidence = "LOW",
                    sourceKey = importKey(incoming),
                )
                return@launch
            }

            AppLog.debug("file.import.plan", plan.diagnostic)
            (plan.decrypt + plan.encrypt).forEach { entry ->
                AppLog.verbose(
                    "file.import.plan.item",
                    "mode=${entry.mode.name} by=${entry.by} " +
                        "conf=${entry.confidence.name} name=${entry.nameSource.name}",
                )
            }
            when {
                plan.decrypt.isEmpty() -> deliver(
                    request = incoming.copy(mode = FileTaskMode.ENCRYPT),
                    diagnostic = diagnostic,
                    entry = "-",
                    by = "policy:encrypt",
                    confidence = "-",
                    sourceKey = importKey(incoming),
                )

                plan.encrypt.isEmpty() -> deliver(
                    request = incoming.copy(
                        mode = FileTaskMode.DECRYPT,
                        uris = plan.decrypt.map { it.uri },
                    ),
                    diagnostic = diagnostic,
                    entry = "-",
                    by = "policy:decrypt",
                    confidence = "-",
                    sourceKey = importKey(incoming),
                )

                else -> {
                    pendingMixedBatch.value = MixedBatchPrompt(incoming, plan, diagnostic)
                    AppLog.info("file.import.route", "${plan.diagnostic} by=mixed -> prompt")
                }
            }
        }
    }

    /** 混合批量：用户选“只解密”或“只加密”，只把对应子集投递给页面。 */
    private fun decideMixed(prompt: MixedBatchPrompt, decrypt: Boolean) {
        pendingMixedBatch.value = null
        val chosen = if (decrypt) prompt.plan.decrypt else prompt.plan.encrypt
        if (chosen.isEmpty()) return
        val skipped = prompt.plan.total - chosen.size
        if (skipped > 0) {
            Toast.makeText(
                this,
                resources.getQuantityString(
                    R.plurals.mixed_batch_skipped,
                    skipped,
                    skipped,
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
        deliver(
            request = prompt.request.copy(
                mode = if (decrypt) FileTaskMode.DECRYPT else FileTaskMode.ENCRYPT,
                uris = chosen.map { it.uri },
            ),
            diagnostic = prompt.diagnostic,
            entry = "-",
            by = if (decrypt) "mixed:decrypt" else "mixed:encrypt",
            confidence = "-",
            sourceKey = importKey(prompt.request),
        )
    }

    /** 用户取消混合批量：记下这次请求已处理，避免旋转后重复弹窗。 */
    private fun cancelMixed(prompt: MixedBatchPrompt) {
        pendingMixedBatch.value = null
        deliveredImport = importKey(prompt.request)
        AppLog.info("file.import.route", "${prompt.diagnostic} by=mixed:cancel")
        Toast.makeText(this, R.string.mixed_batch_cancelled, Toast.LENGTH_SHORT).show()
    }

    /**
     * 把一次已确定模式的请求投递给页面。
     *
     * <p>持久化授权由 ViewModel 在接受请求后统一 take/release；这样 busy/too_many
     * 被拒绝时不会留下无人释放的授权。</p>
     */
    private fun deliver(
        request: IncomingFiles,
        diagnostic: String,
        entry: String,
        by: String,
        confidence: String,
        sourceKey: String = importKey(request),
    ) {
        val route = when (request.mode) {
            FileTaskMode.DECRYPT -> AppRoutes.FILES_DECRYPT
            FileTaskMode.ENCRYPT -> AppRoutes.FILES_ENCRYPT
        }
        AppLog.info(
            "file.import",
            "$diagnostic entry=$entry mode=${request.mode.name} by=$by conf=$confidence route=$route",
        )
        // sourceKey 用原始请求的 key：混合批量只投递子集时，旋转重建也不会再次弹窗/重放。
        deliveredImport = sourceKey
        pendingImport.value = request
        pendingRoute.value = route
    }

    /** 同一组 URI 的顺序可能不同；排序后再拼，只用于旋转重建防重放。 */
    private fun importKey(files: IncomingFiles): String =
        files.action + "|" + files.uris.map { it.toString() }.sorted().joinToString("|")

    /** 外部入口统一的短名：只含动作类别，不写完整 Intent/action 串。 */
    private fun entryName(action: String?): String = when (action) {
        Intent.ACTION_VIEW -> "VIEW"
        Intent.ACTION_SEND -> "SEND"
        Intent.ACTION_SEND_MULTIPLE -> "SEND_MULTIPLE"
        else -> "MAIN"
    }

    /** Android 14+ 可直接取启动方包名；更早版本用 referrer 兜底，取不到就写 ?。 */
    private fun callerPackage(): String? {
        val launched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { launchedFromPackage }.getOrNull()
        } else {
            null
        }
        return launched ?: runCatching { referrer?.authority }.getOrNull()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_DELIVERED_IMPORT, deliveredImport)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        // 只有主界面参与应用锁；球面板与文件入口不经过这里。
        SecuritySession.onForeground()
    }

    override fun onStop() {
        // 旋转/分屏等配置变更会重建 Activity，不是真正的“离开应用”；
        // 尤其「立即验证」不能因此误锁，所以只在非配置变更时记录离开时间。
        if (!isChangingConfigurations) {
            // 先记录离开时间，再交给生命周期；外部流程（SAF/分享/验证）不计时。
            SecuritySession.onBackground()
        }
        super.onStop()
        // 悬浮球位置由 OverlayService 持久化；此处无需清理敏感状态。
    }

    companion object {
        const val EXTRA_NAVIGATE_TO = "navigate_to"

        /** 系统文件入口带来的显式模式（[FileTaskMode.name]）；缺省时按 VIEW/SEND 兜底。 */
        const val EXTRA_ENTRY_MODE = "entry_mode"

        /** 判型最多等这么久；超时与读不到一样按「无法判断」处理，交给用户选择。 */
        private const val ENTRY_RESOLVE_TIMEOUT_MS = 1_500L
        private const val KEY_DELIVERED_IMPORT = "delivered_import"
    }
}

/** 一次混合批量等待用户选子集；不落盘、不跨进程。 */
private data class MixedBatchPrompt(
    val request: IncomingFiles,
    val plan: EntryPlan,
    val diagnostic: String,
)

/**
 * 混合批量兜底：不替用户猜整批方向，也不做“全部解密/全部加密”。
 *
 * <p>两个按钮分别只处理 `.aegix` 子集或普通文件子集，另一组本次不导入；
 * 取消则丢弃本次请求，用户可回原应用分两次分享。</p>
 */
@Composable
private fun MixedBatchDialog(
    prompt: MixedBatchPrompt,
    onDecrypt: () -> Unit,
    onEncrypt: () -> Unit,
    onCancel: () -> Unit,
) {
    val decryptCount = prompt.plan.decrypt.size
    val encryptCount = prompt.plan.encrypt.size
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.mixed_batch_title)) },
        text = {
            Text(
                stringResource(
                    R.string.mixed_batch_body,
                    prompt.plan.total,
                    decryptCount,
                    encryptCount,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onDecrypt, enabled = decryptCount > 0) {
                Text(
                    pluralStringResource(
                        R.plurals.mixed_batch_decrypt,
                        decryptCount,
                        decryptCount,
                    ),
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onEncrypt, enabled = encryptCount > 0) {
                    Text(
                        pluralStringResource(
                            R.plurals.mixed_batch_encrypt,
                            encryptCount,
                            encryptCount,
                        ),
                    )
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.mixed_batch_cancel))
                }
            }
        },
    )
}
