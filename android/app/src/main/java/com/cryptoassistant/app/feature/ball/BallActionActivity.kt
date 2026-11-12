package com.cryptoassistant.app.feature.ball

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.cryptoassistant.app.CryptoAssistantApplication
import com.cryptoassistant.app.MainActivity
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.A11Y_DISCLOSURE_VERSION
import com.cryptoassistant.app.data.AppSettings
import com.cryptoassistant.app.data.BallWorkMode
import com.cryptoassistant.app.data.keys.ContactKey
import com.cryptoassistant.app.data.keys.KeyRepository
import com.cryptoassistant.app.data.keys.LocalKeyUnavailableException
import com.cryptoassistant.app.data.keys.PrivateKeyBackup
import com.cryptoassistant.app.data.log.AppLog
import com.cryptoassistant.app.data.log.LogFields
import com.cryptoassistant.app.data.log.logFailure
import com.cryptoassistant.app.feature.common.decryptErrorText
import com.cryptoassistant.app.feature.common.encryptErrorText
import com.cryptoassistant.app.overlay.A11yUnavailableNotice
import com.cryptoassistant.app.overlay.BallWindowBridge
import com.cryptoassistant.app.overlay.OverlayAction
import com.cryptoassistant.app.ui.components.StatusPill
import com.cryptoassistant.app.ui.components.StatusTone
import com.cryptoassistant.app.ui.theme.CryptoAssistantTheme
import com.cryptoassistant.app.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* * 胶囊当前处理的动作。 */
private enum class BallMode { ENCRYPT, DECRYPT }

/** 标准模式输入面板的三态：还没输入 / 已识别为明文 / 已识别为本工具密文。 */
private enum class InputIntent { EMPTY, ENCRYPT, DECRYPT }

/** 剪贴板读取状态：读取前 / 已读到 / 被系统拒绝。 */
private sealed interface ClipState {
    data object Loading : ClipState
    data class Loaded(val text: String) : ClipState
    data object Unreadable : ClipState
}

/**
 * 悬浮球文字动作的轻量胶囊（架构文档 7.2 的「系统弹窗式小面板」降级路径）。
 *
 * 为什么需要它：Android 10+ 只允许「当前持有输入焦点的应用」读取剪贴板，而 TYPE_APPLICATION_OVERLAY
 * 的悬浮球窗口是 FLAG_NOT_FOCUSABLE，直接读剪贴板在多数 ROM 上会被拦截。所以点球之后先展开这个
 * 属于本应用、尺寸很小、覆盖当前应用之上的透明 Activity，在它具备焦点时完成读取与加解密，
 * 完成后立即关闭，用户停留在原来的聊天/文档应用里。
 *
 * 隐私边界：明文只存在于本界面的内存状态，关闭即销毁；解密出的明文不会自动写入剪贴板，
 * 必须由用户主动点「复制明文」。
 */
class BallActionActivity : ComponentActivity() {

    private var mode by mutableStateOf(BallMode.DECRYPT)
    private var clipState by mutableStateOf<ClipState>(ClipState.Loading)
    private var source by mutableStateOf(SOURCE_CLIPBOARD)

    private var guideReason by mutableStateOf<A11yUnavailableNotice?>(null)
    private var clipboardRead = false

    /** 标准模式：面板里不应再出现「输入框 / 无障碍」类的暗示，文案单独分支。 */
    private var standardMode by mutableStateOf(false)

    /** 面板是从悬浮球展开的：销毁时必须把球交还，否则球会永远消失。必须是快照状态：
     * 展开协程可能因重组/布局重启再跑一次，值不可观察时守卫会失效，桥的引用计数就多减 1。 */
    private var hidBallForMorph by mutableStateOf(false)

    /** 从快捷设置磁贴/一键入口进来：读完剪贴板后自动决定是解密还是加密。 */
    private var autoClipboard by mutableStateOf(false)

    /** 「去开启无障碍服务」需先过披露+同意（任务书 §2.4）；未同意时先弹披露弹窗。 */
    private var showA11yDisclosure by mutableStateOf(false)

    /** 防止多条退出路径重复 finish。 */
    private var finishing = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        disableWindowAnimation()
        // 旋转重建：球已由上一个实例隐藏，新实例直接沿用，不能重复 hide/show。
        // 进程被杀后重建时桥里没有隐藏记录，必须重新隐藏，否则球会浮在面板上面。
        hidBallForMorph = savedInstanceState?.getBoolean(STATE_BALL_HIDDEN) == true &&
            BallWindowBridge.isHiding()
        applyMode(intent)
        applySource(intent)
        // 用户在 GUIDE 面板里明确选了「先按剪贴板处理」后旋转：保持剪贴板区，
        // 不能因为 intent 仍是 SOURCE_GUIDE 就退回引导页。只恢复“选择”，不保存剪贴板正文；
        // 重建后由 onWindowFocusChanged 重新读取一次。
        if (savedInstanceState?.getBoolean(STATE_GUIDE_TO_CLIPBOARD) == true &&
            intent?.getStringExtra(EXTRA_SOURCE) == SOURCE_GUIDE
        ) {
            source = SOURCE_CLIPBOARD
            clipboardRead = false
            clipState = ClipState.Loading
        }
        AppLog.debug("capsule.open", "source=$source")
        val app = application as CryptoAssistantApplication
        val ballRectScreen = ballRectExtra()
        setContent {
            val settings by app.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            var closing by remember { mutableStateOf(false) }
            // 返回键先播收回动画；预测性返回的预览做不到自定义，这一段接受。
            BackHandler(enabled = !closing) { closing = true }
            CryptoAssistantTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                accentPalette = settings.accentPalette,
                amoledDark = settings.amoledDark,
            ) {
                BallPanelHost(
                    ballRectScreen = ballRectScreen,
                    skipIntro = savedInstanceState != null,
                    closing = closing,
                    ballIconSize = settings.ballSize.iconDp.dp,
                    ballAlreadyHidden = hidBallForMorph,
                    onDismiss = { closing = true },
                    onHideBall = {
                        // 同一次展开只隐藏一次：首帧布局可能触发多轮，重复 hide 会让计数泄漏。
                        if (!hidBallForMorph) {
                            hidBallForMorph = true
                            BallWindowBridge.hide()
                        }
                    },
                    onExitFinished = { finishPanel() },
                ) {
                BallActionScreen(
                    mode = mode,
                    clipState = clipState,
                    keyRepository = app.keyRepository,
                    preferredContactId = settings.lastContactId,
                    source = source,
                    guideReason = guideReason,
                    standardMode = standardMode,
                    quickMode = settings.ballWorkMode == BallWorkMode.QUICK,
                    onQuickModeChange = { enabled ->
                        // 面板内直接改设置；下一个点球就按新模式走，不必先关闭面板。
                        standardMode = !enabled
                        lifecycleScope.launch {
                            app.settingsRepository.setBallWorkMode(
                                if (enabled) BallWorkMode.QUICK else BallWorkMode.STANDARD,
                            )
                        }
                    },
                    onUseGuideClipboard = {
                        // 只有用户明确点这个出口才读剪贴板；GUIDE 面板打开时绝不预读。
                        source = SOURCE_CLIPBOARD
                        clipboardRead = false
                        clipState = ClipState.Loading
                        readClipboard()
                    },
                    autoClipboard = autoClipboard,
                    onSwitchMode = { next -> mode = next },
                    onRetryClipboard = { readClipboard() },
                    onOpenApp = { route -> openApp(route) },
                    onOpenAccessibility = { onOpenAccessibility() },
                    onDismiss = { closing = true },
                )
                }
                if (showA11yDisclosure) {
                    com.cryptoassistant.app.feature.settings.AccessibilityDisclosureDialog(
                        consentAt = settings.a11yConsentAt,
                        onConfirm = { confirmA11yConsent() },
                        onDismiss = { showA11yDisclosure = false },
                    )
                }
            }
        }
    }

    /**
     * 胶囊里的「去开启无障碍服务」入口：已同意过当前版本披露就直接跳系统设置并关闭胶囊；
     * 否则先弹披露+独立同意弹窗（不得用系统授权页替代披露）。
     */
    private fun onOpenAccessibility() {
        val app = application as CryptoAssistantApplication
        lifecycleScope.launch {
            if (app.settingsRepository.current().a11yConsentVersion == A11Y_DISCLOSURE_VERSION) {
                openAccessibilitySettings(applicationContext)
                finishPanel()
            } else {
                showA11yDisclosure = true
            }
        }
    }

    /** 同意落盘（时间 + 文案版本）→ 跳系统无障碍设置 → 关闭胶囊。 */
    private fun confirmA11yConsent() {
        val app = application as CryptoAssistantApplication
        lifecycleScope.launch {
            app.settingsRepository.recordA11yConsent(A11Y_DISCLOSURE_VERSION)
            showA11yDisclosure = false
            openAccessibilitySettings(applicationContext)
            finishPanel()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 剪贴板只能在拿到窗口焦点后读；输入框来源不需要这一步。
        if (hasFocus && source == SOURCE_CLIPBOARD && !clipboardRead) readClipboard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyMode(intent)
        applySource(intent)
        if (source == SOURCE_CLIPBOARD) readClipboard()
    }

    private fun applyMode(intent: Intent?) {
        mode = modeOf(intent)
    }

    /** 输入框来源的文本已由悬浮球服务在读焦点时取好，胶囊不再碰剪贴板。 */
    private fun applySource(intent: Intent?) {
        source = intent?.getStringExtra(EXTRA_SOURCE) ?: SOURCE_CLIPBOARD
        standardMode = intent?.getStringExtra(EXTRA_WORK_MODE) == BallWorkMode.STANDARD.name
        autoClipboard = intent?.getStringExtra(EXTRA_ACTION) == ACTION_AUTO_CLIPBOARD
        guideReason = intent?.getStringExtra(EXTRA_GUIDE_REASON)
            ?.let { stored -> A11yUnavailableNotice.entries.firstOrNull { it.name == stored } }
        if (source == SOURCE_GUIDE) {
            // 专用引导面板：不预读剪贴板，也不把输入框内容带进来；
            // 用户点「先按剪贴板处理」后才切到 SOURCE_CLIPBOARD 读取。
            clipboardRead = true
            clipState = ClipState.Loaded("")
        } else if (source == SOURCE_INPUT) {
            // 标准模式单击的空白输入框：绝不自动读剪贴板。
            clipboardRead = true
            clipState = ClipState.Loaded("")
        } else {
            // 剪贴板来源（含磁贴）：拿窗口焦点后由 onWindowFocusChanged 读一次。
            clipboardRead = false
            clipState = ClipState.Loading
        }
    }

    /**
     * 旧面板（便捷模式 / 显式剪贴板入口）的默认动作。
     *
     * 标准模式单击的输入面板不使用这个默认值：它按输入内容自动识别明文/密文。
     */
    private fun modeOf(intent: Intent?): BallMode {
        val action = intent?.getStringExtra(EXTRA_ACTION)
        return if (action == OverlayAction.ENCRYPT_TEXT.name) BallMode.ENCRYPT else BallMode.DECRYPT
    }

    private fun readClipboard() {
        val text = runCatching {
            val manager = getSystemService(ClipboardManager::class.java)
            val clip = manager?.primaryClip
            if (clip == null || clip.itemCount == 0) {
                ""
            } else {
                clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
            }
        }.getOrNull()
        if (text == null) {
            AppLog.warn("clipboard.unavailable", "reason=capsule")
            clipState = ClipState.Unreadable
            return
        }
        clipboardRead = true
        clipState = ClipState.Loaded(text)
    }

    @Suppress("DEPRECATION")
    private fun ballRectExtra(): Rect? =
        intent?.getParcelableExtra(EXTRA_BALL_RECT) as? Rect

    private fun openApp(route: String) {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(MainActivity.EXTRA_NAVIGATE_TO, route)
                },
            )
        }
        finishPanel()
    }

    /** 展开/收回由面板自己画，禁掉系统 Activity/task 过渡，避免两层动画叠在一起。 */
    @Suppress("DEPRECATION")
    private fun disableWindowAnimation() {
        // 主题里的 windowAnimationStyle 是兜底；这里再按窗口覆盖一次，覆盖 ROM 的主题差异。
        window.setWindowAnimations(0)
        window.setDimAmount(0f)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }

    /**
     * 关闭的最后一步：先恢复真球，再把整个 Activity 窗口变透明，等一帧确认透明帧已提交后再 finish。
     *
     * 如果直接 finish，系统 task CLOSE 过渡会拿最后一帧（还在球位置的代理球）当快照下滑，
     * 用户就会看到“悬浮球分身”。
     */
    @Suppress("DEPRECATION")
    private fun finishPanel() {
        if (finishing) return
        finishing = true
        restoreBall()
        // Window 本身没有 alpha setter，改 WindowManager.LayoutParams 才会提交到窗口层。
        window.attributes = window.attributes.also { it.alpha = 0f }
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val decor = window.decorView
        // 第一个 post 让 alpha=0 的帧完成绘制；第二个 post 再结束 Activity。
        decor.postOnAnimation {
            decor.postOnAnimation {
                if (!isFinishing && !isDestroyed) finish()
                overridePendingTransition(0, 0)
            }
        }
    }

    /** 把球交还给悬浮窗；重复调用安全，只会在真正隐藏过的时候释放一次。 */
    private fun restoreBall() {
        if (hidBallForMorph) {
            hidBallForMorph = false
            BallWindowBridge.show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_BALL_HIDDEN, hidBallForMorph)
        outState.putBoolean(
            STATE_GUIDE_TO_CLIPBOARD,
            source == SOURCE_CLIPBOARD && intent?.getStringExtra(EXTRA_SOURCE) == SOURCE_GUIDE,
        )
    }

    override fun onDestroy() {
        // 无论从哪条路径结束（叉/返回/超时/系统回收），都要把球交还。
        // 配置变化例外：交给重建后的新实例继续持有，避免球在旋转时闪一下。
        if (!isChangingConfigurations) restoreBall()
        super.onDestroy()
    }

    companion object {
        private const val STATE_BALL_HIDDEN = "ball_hidden"
        private const val STATE_GUIDE_TO_CLIPBOARD = "guide_to_clipboard"

        const val EXTRA_ACTION = "action"
        const val EXTRA_SOURCE = "source"

        /** 为什么没读输入框而改读了剪贴板（无障碍未连接/没有输入框）。 */

        /** 专用引导面板的原因（[A11yUnavailableNotice.name]）；仅 SOURCE_GUIDE 使用。 */
        const val EXTRA_GUIDE_REASON = "guide_reason"

        /** 悬浮球当前的工作模式（[BallWorkMode.name]），只影响文案与降级横幅的取舍。 */
        const val EXTRA_WORK_MODE = "work_mode"

        /** 悬浮球的屏幕矩形：面板从它展开；磁贴等非悬浮球入口不传。 */
        const val EXTRA_BALL_RECT = "ball_rect"

        /** 自动判断入口（快捷设置磁贴）：密文解密、明文切到加密页。 */
        const val ACTION_AUTO_CLIPBOARD = "auto_clipboard"

        /** 直接读写当前应用的输入框（需无障碍服务）。 */
        /** 标准模式单击：空白可编辑输入框，不读取剪贴板。 */
        const val SOURCE_INPUT = "input"

        /** 便捷模式无障碍未连接：专用引导面板，不预读剪贴板。 */
        const val SOURCE_GUIDE = "guide"

        /** 降级：读写系统剪贴板。 */
        const val SOURCE_CLIPBOARD = "clipboard"
    }
}

@Composable
private fun BallActionScreen(
    mode: BallMode,
    clipState: ClipState,
    keyRepository: KeyRepository,
    preferredContactId: String?,
    source: String,
    guideReason: A11yUnavailableNotice?,
    standardMode: Boolean,
    quickMode: Boolean,
    onQuickModeChange: (Boolean) -> Unit,
    onUseGuideClipboard: () -> Unit,
    autoClipboard: Boolean,
    onSwitchMode: (BallMode) -> Unit,
    onRetryClipboard: () -> Unit,
    onOpenApp: (String) -> Unit,
    onOpenAccessibility: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 资源文案在组合期读取，避免在回调/协程里通过 LocalContext 查资源（lint 会报不一致）。
    val copiedToast = stringResource(R.string.ball_toast_copied)
    val clipLabelCipher = stringResource(R.string.clip_label_cipher)
    val clipLabelPlain = stringResource(R.string.clip_label_plain)
    val scope = rememberCoroutineScope()
    val keys by keyRepository.snapshot.collectAsStateWithLifecycle()
    val identity = keys.identity
    val contacts = keys.contacts

    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var cipherResult by remember { mutableStateOf<String?>(null) }
    var encryptedFor by remember { mutableStateOf<ContactKey?>(null) }
    var message by remember { mutableStateOf<DecryptedMessage?>(null) }
    var decryptAttempted by remember { mutableStateOf(false) }
    var plainCopied by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableStateOf(PLAINTEXT_TIMEOUT_SECONDS) }

    // 接收方只由长按菜单设置；唯一联系人可直接使用，多联系人未选择则为 null。
    val targetContact = resolveRecipient(contacts, preferredContactId)

    val clipText = (clipState as? ClipState.Loaded)?.text
    val content = remember(clipText) { TextActionUseCase.inspect(clipText) }

    fun copyToClipboard(label: String, value: String) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        manager?.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT)
            .show()
    }

    fun switchMode(next: BallMode) {
        if (next == mode) return
        message = null
        errorText = null
        cipherResult = null
        decryptAttempted = false
        onSwitchMode(next)
    }

    fun startDecrypt() {
        val cipher = content as? ClipContent.Cipher ?: return
        if (busy) return
        busy = true
        errorText = null
        scope.launch {
            val started = System.currentTimeMillis()
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val privateKey = keyRepository.currentPrivateKey()
                        ?: throw LocalKeyUnavailableException(missing = identity == null)
                    TextActionUseCase.decrypt(cipher, privateKey, contacts)
                }
            }
            busy = false
            result
                .onSuccess {
                    AppLog.info(
                        "capsule.decrypt",
                        "source=$source senderKnown=${it.sender != null} " +
                            "ms=${System.currentTimeMillis() - started}",
                    )
                    message = it
                }
                .onFailure {
                    logFailure(
                        "capsule.decrypt",
                        it,
                        "source=$source ms=${System.currentTimeMillis() - started}",
                    )
                    errorText = decryptErrorText(context, it)
                }
        }
    }

    fun startEncrypt() {
        // 与桌面端保持一致：加密前去掉首尾空白，避免把不可见的换行也发给对方。
        val plain = clipText?.trim().orEmpty()
        val contact = targetContact ?: return
        if (busy || plain.isBlank()) return
        busy = true
        errorText = null
        scope.launch {
            val started = System.currentTimeMillis()
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val privateKey = keyRepository.currentPrivateKey()
                        ?: throw LocalKeyUnavailableException(missing = identity == null)
                    val receiver = PrivateKeyBackup.decodePublicKey(contact.publicKeyBase64)
                    TextActionUseCase.encrypt(plain, privateKey, receiver)
                }
            }
            busy = false
            result
                .onSuccess { cipher ->
                    AppLog.info(
                        "capsule.encrypt",
                        "source=$source recipientKnown=true " +
                            "ms=${System.currentTimeMillis() - started}",
                    )
                    cipherResult = cipher
                    encryptedFor = contact
                    copyToClipboard(clipLabelCipher, cipher)
                }
                .onFailure {
                    logFailure(
                        "capsule.encrypt",
                        it,
                        "source=$source ms=${System.currentTimeMillis() - started}",
                    )
                    errorText = encryptErrorText(context, it)
                }
        }
    }

    // 快捷设置磁贴入口：剪贴板是普通明文时自动切到加密页，少点一次「改为加密」。
    // 密文仍走下面的自动解密；空剪贴板保持解密页文案（两边提示等价，不来回跳）。
    LaunchedEffect(autoClipboard, mode, content) {
        if (autoClipboard && mode == BallMode.DECRYPT && content is ClipContent.NotOurs) {
            switchMode(BallMode.ENCRYPT)
        }
    }

    // 解密是用户点球时的明确意图，识别到密文后自动执行一次。
    LaunchedEffect(mode, content, identity) {
        if (mode == BallMode.DECRYPT &&
            content is ClipContent.Cipher &&
            identity != null &&
            !decryptAttempted
        ) {
            decryptAttempted = true
            startDecrypt()
        }
    }

    // 明文在胶囊里最多停留 60 秒，超时即关闭以清除内存中的明文；倒计时直接显示给用户。
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        secondsLeft = PLAINTEXT_TIMEOUT_SECONDS
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        }
        onDismiss()
    }

    // 遮罩、最终尺寸与容器动画由 BallPanelHost 负责；这里只提供真实卡片。
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 400.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 6.dp,
    ) {
            if (source == BallActionActivity.SOURCE_INPUT) {
                InputTextSection(
                    keyRepository = keyRepository,
                    contacts = contacts,
                    lastContactId = preferredContactId,
                    onOpenApp = onOpenApp,
                    onDismiss = onDismiss,
                )
            } else if (source == BallActionActivity.SOURCE_GUIDE) {
                GuideSection(
                    reason = guideReason ?: A11yUnavailableNotice.NOT_ENABLED,
                    quickMode = quickMode,
                    onQuickModeChange = onQuickModeChange,
                    onUseClipboard = onUseGuideClipboard,
                    onOpenAccessibility = onOpenAccessibility,
                    onDismiss = onDismiss,
                )
            } else {
            Column(
                modifier = Modifier
                    .padding(Spacing.xl)
                    .verticalScroll(rememberScrollState()),
            ) {
                CapsuleHeader(
                    mode = mode,
                    // fromField 仅用于输入框解密标题（标准模式输入面板）；胶囊本身恒为剪贴板来源。
                    fromField = false,
                    subtitle = if (standardMode) {
                        // 标准模式下不能出现「开启无障碍」——那不是用户选的方式，也不是缺陷。
                        stringResource(R.string.ball_subtitle_clipboard_auto_copy)
                    } else {
                        stringResource(R.string.ball_subtitle_clipboard_a11y)
                    },
                    onDismiss = onDismiss,
                )
                Spacer(Modifier.height(Spacing.lg))

                when (val state = clipState) {
                    ClipState.Loading -> BusyRow(stringResource(R.string.ball_loading_clipboard))

                    ClipState.Unreadable -> {
                        InfoBlock(
                            icon = Icons.Outlined.WarningAmber,
                            title = stringResource(R.string.ball_clipboard_unreadable_title),
                            body = stringResource(R.string.ball_clipboard_unreadable_body),
                            tone = StatusTone.WARNING,
                        )
                        Spacer(Modifier.height(Spacing.lg))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.common_close))
                            }
                            Button(onClick = onRetryClipboard, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.common_retry))
                            }
                        }
                    }

                    is ClipState.Loaded -> when (mode) {
                        BallMode.ENCRYPT -> EncryptSection(
                            clipText = state.text,
                            content = content,
                            hasIdentity = identity != null,
                            contacts = contacts,
                            targetContact = targetContact,
                            busy = busy,
                            errorText = errorText,
                            cipherResult = cipherResult,
                            encryptedFor = encryptedFor,
                            onEncrypt = { startEncrypt() },
                            onSwitchToDecrypt = { switchMode(BallMode.DECRYPT) },
                            onOpenApp = onOpenApp,
                            onCopy = { cipher -> copyToClipboard(clipLabelCipher, cipher) },
                            onDismiss = onDismiss,
                        )

                        BallMode.DECRYPT -> DecryptSection(
                            clipText = state.text,
                            content = content,
                            standardMode = standardMode,
                            hasIdentity = identity != null,
                            busy = busy,
                            errorText = errorText,
                            message = message,
                            secondsLeft = secondsLeft,
                            plainCopied = plainCopied,
                            onRetry = { startDecrypt() },
                            onSwitchToEncrypt = { switchMode(BallMode.ENCRYPT) },
                            onOpenApp = onOpenApp,
                            onCopyPlaintext = { text ->
                                copyToClipboard(clipLabelPlain, text)
                                plainCopied = true
                            },
                            onDismiss = onDismiss,
                        )
                    }
                }
            }
            }
    }
}

/**
 * 标准模式单击后的统一文字面板：一个输入框，自动识别明文 / 本工具密文。
 *
 * - 明文 → 用长按菜单选定的接收方加密，并自动复制到剪贴板；
 * - 本工具密文 → 用本机私钥解密，明文只在本面板显示；
 * - 「从剪贴板填入」是唯一会读剪贴板的按钮，且必须由用户主动点击。
 */
@Composable
private fun InputTextSection(
    keyRepository: KeyRepository,
    contacts: List<ContactKey>,
    lastContactId: String?,
    onOpenApp: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 资源文案在组合期读取，避免在回调里通过 LocalContext 查资源。
    val copiedToast = stringResource(R.string.ball_toast_copied)
    val clipboardEmptyToast = stringResource(R.string.ball_toast_clipboard_empty)
    val clipLabelCipher = stringResource(R.string.clip_label_cipher)
    val clipLabelPlain = stringResource(R.string.clip_label_plain)
    val scope = rememberCoroutineScope()
    val keys by keyRepository.snapshot.collectAsStateWithLifecycle()
    val identity = keys.identity
    val target = resolveRecipient(contacts, lastContactId)

    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var cipherResult by remember { mutableStateOf<String?>(null) }
    var encryptedFor by remember { mutableStateOf<ContactKey?>(null) }
    var decrypted by remember { mutableStateOf<DecryptedMessage?>(null) }
    var plainCopied by remember { mutableStateOf(false) }

    val content = remember(input) { TextActionUseCase.inspect(input) }
    val wantsDecrypt = content is ClipContent.Cipher
    val inputIntent = when {
        input.isBlank() -> InputIntent.EMPTY
        wantsDecrypt -> InputIntent.DECRYPT
        else -> InputIntent.ENCRYPT
    }
    val plain = input.trim()

    // 只记识别类型与长度桶：不写输入内容，也不写精确长度。
    LaunchedEffect(content) {
        val kind = when (content) {
            is ClipContent.Cipher -> "cipher"
            ClipContent.NotOurs -> "plain"
            ClipContent.Empty -> "unknown"
        }
        AppLog.debug("text.detect", "kind=$kind lenBucket=${LogFields.lengthBucket(input.length)}")
    }

    fun copyToClipboard(label: String, value: String) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        manager?.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT)
            .show()
    }

    fun fillFromClipboard() {
        val value = runCatching {
            val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
            if (clip == null || clip.itemCount == 0) "" else
                clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        }.getOrNull()
        if (value.isNullOrBlank()) {
            Toast.makeText(
                context,
                clipboardEmptyToast,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        input = value.take(MAX_INPUT_CHARS)
        errorText = null
        cipherResult = null
        decrypted = null
        plainCopied = false
    }

    fun startEncrypt() {
        val contact = target ?: return
        if (busy || plain.isBlank()) return
        busy = true
        errorText = null
        scope.launch {
            val started = System.currentTimeMillis()
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val privateKey = keyRepository.currentPrivateKey()
                        ?: throw LocalKeyUnavailableException(missing = identity == null)
                    val receiver = PrivateKeyBackup.decodePublicKey(contact.publicKeyBase64)
                    TextActionUseCase.encrypt(plain, privateKey, receiver)
                }
            }
            busy = false
            result
                .onSuccess { cipher ->
                    AppLog.info(
                        "capsule.input.encrypt",
                        "source=input recipientKnown=true " +
                            "ms=${System.currentTimeMillis() - started}",
                    )
                    cipherResult = cipher
                    encryptedFor = contact
                    copyToClipboard(clipLabelCipher, cipher)
                }
                .onFailure {
                    logFailure(
                        "capsule.input.encrypt",
                        it,
                        "source=input ms=${System.currentTimeMillis() - started}",
                    )
                    errorText = encryptErrorText(context, it)
                }
        }
    }

    fun startDecrypt() {
        val cipher = content as? ClipContent.Cipher ?: return
        if (busy) return
        busy = true
        errorText = null
        scope.launch {
            val started = System.currentTimeMillis()
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val privateKey = keyRepository.currentPrivateKey()
                        ?: throw LocalKeyUnavailableException(missing = identity == null)
                    TextActionUseCase.decrypt(cipher, privateKey, contacts)
                }
            }
            busy = false
            result
                .onSuccess {
                    AppLog.info(
                        "capsule.input.decrypt",
                        "source=input senderKnown=${it.sender != null} " +
                            "ms=${System.currentTimeMillis() - started}",
                    )
                    decrypted = it
                }
                .onFailure {
                    logFailure(
                        "capsule.input.decrypt",
                        it,
                        "source=input ms=${System.currentTimeMillis() - started}",
                    )
                    errorText = decryptErrorText(context, it)
                }
        }
    }

    LaunchedEffect(decrypted) {
        if (decrypted == null) return@LaunchedEffect
        // 明文不在屏幕上无限停留：到点自动关闭，不额外显示倒计时文案。
        delay(PLAINTEXT_TIMEOUT_SECONDS * 1000L)
        onDismiss()
    }

    Column(
        modifier = Modifier
            .padding(Spacing.xl)
            .verticalScroll(rememberScrollState()),
    ) {
        val showingEncryptResult = cipherResult != null
        val showingDecryptResult = decrypted != null
        CapsuleHeader(
            mode = when {
                showingEncryptResult -> BallMode.ENCRYPT
                showingDecryptResult || wantsDecrypt -> BallMode.DECRYPT
                else -> BallMode.ENCRYPT
            },
            fromField = wantsDecrypt,
            // 空输入不预判方向：盾牌只表达「这里可以做加解密」。
            icon = if (!showingEncryptResult && !showingDecryptResult &&
                inputIntent == InputIntent.EMPTY
            ) {
                Icons.Outlined.Shield
            } else {
                null
            },
            titleOverride = when {
                showingEncryptResult -> stringResource(R.string.ball_header_encrypt_done)
                showingDecryptResult -> stringResource(R.string.ball_header_decrypt_done)
                inputIntent == InputIntent.EMPTY -> stringResource(R.string.ball_header_input)
                wantsDecrypt -> stringResource(R.string.ball_header_decrypt_text)
                else -> stringResource(R.string.ball_header_encrypt_text)
            },
            onDismiss = onDismiss,
        )
        Spacer(Modifier.height(Spacing.lg))

        if (!showingEncryptResult && !showingDecryptResult) {
            OutlinedTextField(
                value = input,
                onValueChange = { next ->
                    if (next.length <= MAX_INPUT_CHARS) {
                        input = next
                        errorText = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.ball_input_placeholder)) },
                minLines = 4,
                maxLines = 8,
                enabled = !busy,
            )
            Spacer(Modifier.height(Spacing.xs))
            TextButton(onClick = { fillFromClipboard() }, enabled = !busy) {
                Text(stringResource(R.string.ball_input_fill_from_clipboard))
            }

            if (inputIntent == InputIntent.ENCRYPT) {
                Spacer(Modifier.height(Spacing.sm))
                if (target != null) {
                    Text(
                        text = stringResource(R.string.ball_input_target, target.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else if (contacts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ball_no_contacts_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    TextButton(onClick = { onOpenApp("contacts") }) {
                        Text(stringResource(R.string.ball_go_add_contacts))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.ball_input_choose_recipient),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            if (identity == null) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = stringResource(R.string.ball_no_identity_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                TextButton(onClick = { onOpenApp("keys") }) {
                    Text(stringResource(R.string.ball_go_create_key))
                }
            }

            if (errorText != null) {
                Spacer(Modifier.height(Spacing.md))
                ErrorBlock(errorText.orEmpty())
            }

            Spacer(Modifier.height(Spacing.md))
            val canAct = !busy && if (wantsDecrypt) {
                identity != null
            } else {
                plain.isNotBlank() && target != null && identity != null
            }
            Button(
                onClick = { if (wantsDecrypt) startDecrypt() else startEncrypt() },
                enabled = canAct,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        if (wantsDecrypt) {
                            stringResource(R.string.ball_busy_decrypting)
                        } else {
                            stringResource(R.string.ball_busy_encrypting)
                        },
                    )
                } else {
                    Text(
                        when (inputIntent) {
                            InputIntent.EMPTY -> stringResource(R.string.ball_action_encrypt_or_decrypt)
                            InputIntent.DECRYPT -> stringResource(R.string.ball_action_decrypt)
                            InputIntent.ENCRYPT -> stringResource(R.string.ball_action_encrypt_copy)
                        },
                    )
                }
            }
        }

        if (showingEncryptResult) {
            val contact = encryptedFor
            if (contact != null) {
                Text(
                    text = stringResource(R.string.ball_input_target, contact.name),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(
                        R.string.ball_fingerprint,
                        PrivateKeyBackup.formatFingerprint(contact.fingerprint),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Spacing.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(
                    onClick = { copyToClipboard(clipLabelCipher, cipherResult.orEmpty()) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.common_copy_again)) }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_done))
                }
            }
        }

        if (showingDecryptResult) {
            val result = decrypted!!
            val sender = result.sender
            StatusPill(
                text = if (sender != null) {
                    stringResource(R.string.capsule_signature_from, sender.name)
                } else {
                    stringResource(R.string.ball_sender_unknown)
                },
                tone = if (sender != null) StatusTone.SUCCESS else StatusTone.WARNING,
            )
            Spacer(Modifier.height(Spacing.md))
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = result.plaintext,
                        modifier = Modifier
                            .padding(Spacing.md)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(
                    onClick = {
                        copyToClipboard(clipLabelPlain, result.plaintext)
                        plainCopied = true
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (plainCopied) {
                            stringResource(R.string.common_copy_again)
                        } else {
                            stringResource(R.string.ball_copy_plaintext)
                        },
                    )
                }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_done))
                }
            }
        }
    }
}

@Composable
private fun CapsuleHeader(
    mode: BallMode,
    fromField: Boolean,
    subtitle: String? = null,
    titleOverride: String? = null,
    /** 覆盖左侧图标；null 时按 [mode] 显示锁/开锁。 */
    icon: ImageVector? = null,
    onDismiss: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon
                    ?: if (mode == BallMode.ENCRYPT) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titleOverride ?: when {
                    mode == BallMode.ENCRYPT -> stringResource(R.string.ball_header_encrypt_text)
                    fromField -> stringResource(R.string.ball_header_decrypt_text)
                    else -> stringResource(R.string.ball_header_decrypt_clipboard)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_close))
        }
    }
}

/**
 * 便捷模式无障碍未连接时的专用引导面板（T13）。
 *
 * 与旧的“剪贴板面板顶部挂一条降级提示”不同：这里不预读剪贴板，用户必须先做选择——
 * 去开启/修复无障碍、关掉便捷模式改走标准模式，或明确点「先按剪贴板处理」一次。
 * 每次无障碍未连接点球都会重新进入这里，直到服务真正连上或用户关掉便捷模式。
 */
@Composable
private fun GuideSection(
    reason: A11yUnavailableNotice,
    quickMode: Boolean,
    onQuickModeChange: (Boolean) -> Unit,
    onUseClipboard: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onDismiss: () -> Unit,
) {
    val notEnabled = reason == A11yUnavailableNotice.NOT_ENABLED
    val quickOff = !quickMode
    Column(
        modifier = Modifier
            .padding(Spacing.xl)
            .verticalScroll(rememberScrollState()),
    ) {
        CapsuleHeader(
            mode = BallMode.ENCRYPT,
            fromField = false,
            subtitle = if (quickMode) {
                stringResource(R.string.ball_guide_subtitle_quick)
            } else {
                stringResource(R.string.ball_guide_subtitle_standard)
            },
            titleOverride = when {
                quickOff -> stringResource(R.string.ball_guide_title_switched_standard)
                notEnabled -> stringResource(R.string.ball_guide_title_a11y_not_enabled)
                else -> stringResource(R.string.ball_guide_title_a11y_not_running)
            },
            icon = Icons.Outlined.Shield,
            onDismiss = onDismiss,
        )
        Spacer(Modifier.height(Spacing.lg))
        InfoBlock(
            icon = if (quickOff) Icons.Outlined.Shield else Icons.Outlined.WarningAmber,
            title = when {
                quickOff -> stringResource(R.string.ball_guide_info_standard_title)
                notEnabled -> stringResource(R.string.ball_guide_info_not_enabled_title)
                else -> stringResource(R.string.ball_guide_info_not_running_title)
            },
            body = when {
                quickOff -> stringResource(R.string.ball_guide_info_standard_body)
                notEnabled -> stringResource(R.string.ball_guide_info_not_enabled_body)
                else -> stringResource(R.string.ball_guide_info_not_running_body)
            },
            tone = if (quickOff) StatusTone.SUCCESS else StatusTone.WARNING,
        )
        Spacer(Modifier.height(Spacing.lg))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.mode_quick),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = if (quickMode) {
                                stringResource(R.string.ball_guide_quick_on_hint)
                            } else {
                                stringResource(R.string.ball_guide_quick_off_hint)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Spacer(Modifier.width(Spacing.md))
                    Switch(checked = quickMode, onCheckedChange = onQuickModeChange)
                }
                if (!quickMode) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = stringResource(R.string.ball_guide_switched_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        if (!quickOff) {
            Spacer(Modifier.height(Spacing.lg))
            Button(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (notEnabled) {
                        stringResource(R.string.ball_guide_action_enable_a11y)
                    } else {
                        stringResource(R.string.ball_guide_action_fix_a11y)
                    },
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        TextButton(onClick = onUseClipboard, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Outlined.ContentPaste,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(stringResource(R.string.ball_guide_action_clipboard))
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_close))
        }
        Text(
            text = stringResource(R.string.ball_guide_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun openAccessibilitySettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun EncryptSection(
    clipText: String,
    content: ClipContent,
    hasIdentity: Boolean,
    contacts: List<ContactKey>,
    targetContact: ContactKey?,
    busy: Boolean,
    errorText: String?,
    cipherResult: String?,
    encryptedFor: ContactKey?,
    onEncrypt: () -> Unit,
    onSwitchToDecrypt: () -> Unit,
    onOpenApp: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (cipherResult != null) {
        StatusPill(stringResource(R.string.ball_encrypt_success), StatusTone.SUCCESS)
        if (encryptedFor != null) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(
                    R.string.ball_encrypt_to_fingerprint_tail,
                    encryptedFor.name,
                    encryptedFor.fingerprint.takeLast(FINGERPRINT_TAIL_LENGTH)
                        .chunked(4).joinToString(" "),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = stringResource(R.string.ball_cipher_copied_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        CipherPreview(cipherResult)
        Spacer(Modifier.height(Spacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(onClick = { onCopy(cipherResult) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.common_copy_again))
            }
            Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.common_done))
            }
        }
        return
    }

    when {
        clipText.isBlank() -> {
            InfoBlock(
                icon = Icons.Outlined.ContentPaste,
                title = stringResource(R.string.ball_clipboard_empty_title),
                body = stringResource(R.string.ball_clipboard_empty_body),
                tone = StatusTone.NEUTRAL,
            )
            Spacer(Modifier.height(Spacing.lg))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_got_it))
            }
        }

        content is ClipContent.Cipher -> {
            InfoBlock(
                icon = Icons.Outlined.WarningAmber,
                title = stringResource(R.string.ball_clipboard_already_cipher_title),
                body = stringResource(R.string.ball_clipboard_already_cipher_body),
                tone = StatusTone.WARNING,
            )
            Spacer(Modifier.height(Spacing.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_close))
                }
                Button(onClick = onSwitchToDecrypt, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ball_switch_to_decrypt))
                }
            }
        }

        !hasIdentity -> {
            InfoBlock(
                icon = Icons.Outlined.Lock,
                title = stringResource(R.string.ball_no_identity_title),
                body = stringResource(R.string.ball_no_identity_encrypt_body),
                tone = StatusTone.WARNING,
            )
            Spacer(Modifier.height(Spacing.lg))
            Button(onClick = { onOpenApp("keys") }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ball_go_create_key))
            }
        }

        contacts.isEmpty() -> {
            InfoBlock(
                icon = Icons.Outlined.ContentPaste,
                title = stringResource(R.string.ball_no_contacts_title),
                body = stringResource(R.string.ball_no_contacts_encrypt_body),
                tone = StatusTone.WARNING,
            )
            Spacer(Modifier.height(Spacing.lg))
            Button(onClick = { onOpenApp("contacts") }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ball_go_add_contacts))
            }
        }

        else -> {
            Text(
                text = stringResource(R.string.ball_clipboard_text_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xs))
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = clipText.trim(),
                    modifier = Modifier.padding(Spacing.md),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(Spacing.lg))

            Text(
                text = stringResource(R.string.ball_menu_encrypt_to),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xs))
            if (targetContact != null) {
                Text(
                    text = targetContact.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        R.string.ball_recipient_fingerprint_hint,
                        PrivateKeyBackup.formatFingerprint(targetContact.fingerprint),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                InfoBlock(
                    icon = Icons.Outlined.WarningAmber,
                    title = stringResource(R.string.ball_no_recipient_title),
                    body = stringResource(R.string.ball_no_recipient_body),
                    tone = StatusTone.WARNING,
                )
            }

            if (errorText != null) {
                Spacer(Modifier.height(Spacing.md))
                ErrorBlock(errorText)
            }

            Spacer(Modifier.height(Spacing.lg))
            Button(
                onClick = onEncrypt,
                enabled = !busy && targetContact != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.ball_busy_encrypting))
                } else {
                    Text(stringResource(R.string.ball_action_encrypt_copy))
                }
            }
        }
    }
}

@Composable
private fun DecryptSection(
    clipText: String,
    content: ClipContent,
    standardMode: Boolean,
    hasIdentity: Boolean,
    busy: Boolean,
    errorText: String?,
    message: DecryptedMessage?,
    secondsLeft: Int,
    plainCopied: Boolean,
    onRetry: () -> Unit,
    onSwitchToEncrypt: () -> Unit,
    onOpenApp: (String) -> Unit,
    onCopyPlaintext: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (message != null) {
        val sender = message.sender
        StatusPill(
            text = if (sender != null) {
                stringResource(R.string.capsule_signature_from, sender.name)
            } else {
                stringResource(R.string.ball_sender_unknown)
            },
            tone = if (sender != null) StatusTone.SUCCESS else StatusTone.WARNING,
        )
        if (sender != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(
                    R.string.ball_fingerprint,
                    PrivateKeyBackup.formatFingerprint(sender.fingerprint),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.ball_signature_invalid_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp),
        ) {
            SelectionContainer {
                Text(
                    text = message.plaintext,
                    modifier = Modifier
                        .padding(Spacing.md)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = pluralStringResource(
                R.plurals.ball_plaintext_countdown,
                secondsLeft,
                secondsLeft,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (plainCopied) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.ball_plain_copied_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(Spacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(
                onClick = { onCopyPlaintext(message.plaintext) },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (plainCopied) {
                        stringResource(R.string.common_copy_again)
                    } else {
                        stringResource(R.string.ball_copy_plaintext)
                    },
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.common_done))
            }
        }
        return
    }

    when {
        clipText.isBlank() -> {
            InfoBlock(
                icon = Icons.Outlined.ContentPaste,
                title = stringResource(R.string.ball_nothing_to_decrypt_title),
                body = if (standardMode) {
                    stringResource(R.string.ball_nothing_to_decrypt_body_standard)
                } else {
                    stringResource(R.string.ball_nothing_to_decrypt_body_clipboard)
                },
                tone = StatusTone.NEUTRAL,
            )
            Spacer(Modifier.height(Spacing.lg))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_got_it))
            }
        }

        content is ClipContent.NotOurs -> {
            InfoBlock(
                icon = Icons.Outlined.WarningAmber,
                title = stringResource(R.string.ball_not_ours_title),
                body = stringResource(R.string.ball_not_ours_body),
                tone = StatusTone.WARNING,
            )
            Spacer(Modifier.height(Spacing.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_close))
                }
                Button(onClick = onSwitchToEncrypt, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ball_switch_to_encrypt))
                }
            }
        }

        !hasIdentity -> {
            InfoBlock(
                icon = Icons.Outlined.Lock,
                title = stringResource(R.string.ball_no_identity_title),
                body = stringResource(R.string.ball_no_identity_decrypt_body),
                tone = StatusTone.WARNING,
            )
            Spacer(Modifier.height(Spacing.lg))
            Button(onClick = { onOpenApp("keys") }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ball_go_create_key))
            }
        }

        errorText != null -> {
            ErrorBlock(errorText)
            Spacer(Modifier.height(Spacing.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_close))
                }
                Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_retry))
                }
            }
        }

        else -> BusyRow(stringResource(R.string.ball_busy_decrypting))
    }
}

@Composable
private fun CipherPreview(cipher: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = cipher,
                modifier = Modifier.padding(Spacing.md),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InfoBlock(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    tone: StatusTone,
) {
    val tint = when (tone) {
        StatusTone.WARNING -> MaterialTheme.colorScheme.tertiary
        StatusTone.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(Spacing.md))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorBlock(text: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(Spacing.md),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun BusyRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


private const val PLAINTEXT_TIMEOUT_SECONDS = 60

/**
 * 标准模式输入框上限，防止一次塞入超大文本；存取仍只在内存里。
 * 50k 是为了容纳「约 3.6 万字明文加密后的 Base64 密文」再粘回来解密。
 */
private const val MAX_INPUT_CHARS = 50_000

/** 结果页只显示指纹尾号，便于口头核对又不刷屏。 */
private const val FINGERPRINT_TAIL_LENGTH = 8
