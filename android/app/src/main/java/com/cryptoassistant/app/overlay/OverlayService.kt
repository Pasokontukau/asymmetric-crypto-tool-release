package com.cryptoassistant.app.overlay

import android.app.ActivityOptions
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cryptoassistant.app.CryptoAssistantApplication
import com.cryptoassistant.app.MainActivity
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.AppSettings
import com.cryptoassistant.app.data.BallWorkMode
import com.cryptoassistant.app.data.FieldIoOutcome
import com.cryptoassistant.app.data.SettingsRepository
import com.cryptoassistant.app.data.log.AppLog
import com.cryptoassistant.app.data.log.LogFields
import com.cryptoassistant.app.data.log.logFailure
import com.cryptoassistant.app.data.keys.ContactKey
import com.cryptoassistant.app.data.keys.KeyRepository
import com.cryptoassistant.app.data.keys.LocalKeyUnavailableException
import com.cryptoassistant.app.data.keys.PrivateKeyBackup
import com.cryptoassistant.app.feature.ball.BallActionActivity
import com.cryptoassistant.app.feature.ball.resolveRecipient
import com.cryptoassistant.app.feature.ball.ClipContent
import com.cryptoassistant.app.feature.ball.DecryptedMessage
import com.cryptoassistant.app.feature.ball.TextActionUseCase
import com.cryptoassistant.app.feature.common.decryptErrorText
import com.cryptoassistant.app.feature.common.encryptErrorText
import com.cryptoassistant.app.service.TextAccessibilityService
import com.cryptoassistant.app.tile.TileSync
import com.cryptoassistant.app.ui.navigation.AppRoutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 承载悬浮球窗口的前台服务。
 *
 * 三种交互，具体行为由「工作模式」决定（用户在设置里选，优先级高于系统无障碍状态）：
 * - 单击·便捷模式：静默读取当前输入框 → 加密/解密 → 原地替换，全程不弹界面；
 *   读不到输入框时按剪贴板工作：密文解密，明文有接收方则加密后放回剪贴板；
 *   无障碍没连上时每次点球都拉起专用引导面板；
 * - 单击·标准模式：完全不碰输入框，直接打开文字输入面板，按内容自动加密/解密；
 * - 长按：快捷菜单（只选择加密接收方；其余入口都在应用内）；
 * - 拖动：移动球的位置。
 *
 * 另外监听剪贴板：出现新的本工具密文时，在内存里解密，并把球拉长成胶囊显示明文。
 * Android 10+ 只允许持有输入焦点的应用读剪贴板，所以这个监听只在系统允许时生效，
 * 真正的兜底入口是点球后打开的文字面板（标准模式）或就地处理输入框（便捷模式）。
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: SettingsRepository
    private lateinit var keyRepository: KeyRepository
    private var controller: OverlayController? = null
    private var initializing = false
    private val appOps by lazy { getSystemService(AppOpsManager::class.java) }
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }

    private var currentSettings = AppSettings()
    private var lastClipHash = 0
    private var selfClipHash = 0
    private var capsuleTimer: kotlinx.coroutines.Job? = null
    private var capsuleExpandedLimitTimer: kotlinx.coroutines.Job? = null
    private var clipboardDeniedLogged = false

    /** 启停原因只用于 overlay.start / overlay.stop 的 reason 字段（见日志计划 §5.6）。 */
    private var startReason = "user"
    private var stopReason = "user"

    /** 当前进程已渲染过的语言标签；语言变化时重建通知渠道与前台通知。 */
    private var lastLocaleTag = ""

    private val overlayOpListener = object : AppOpsManager.OnOpChangedListener {
        override fun onOpChanged(op: String?, packageName: String?) {
            if (!Settings.canDrawOverlays(this@OverlayService)) {
                // 用户撤销悬浮窗权限：安全停止，移除所有窗口。
                stopReason = "permission"
                AppLog.warn("permission.overlay", "granted=false")
                stopSelf()
            }
        }
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClipboardChanged() }

    override fun onCreate() {
        super.onCreate()
        val app = application as CryptoAssistantApplication
        settings = app.settingsRepository
        keyRepository = app.keyRepository
        lastLocaleTag = resources.configuration.locales[0].toLanguageTag()
        startAsForeground()
        runCatching {
            appOps?.startWatchingMode(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                packageName,
                overlayOpListener,
            )
        }
        scope.launch {
            var previousMode: BallWorkMode? = null
            settings.settings.collect { latest ->
                val changed = previousMode != null && previousMode != latest.ballWorkMode
                currentSettings = latest
                previousMode = latest.ballWorkMode
                if (changed) onWorkModeChanged(latest.ballWorkMode)
            }
        }
        runCatching { clipboard?.addPrimaryClipChangedListener(clipListener) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            // 主题变化后重建窗口，让球与菜单跟随当前主题。
            // 正在初始化时忽略刷新：新窗口会直接读取最新设置。
            if (initializing) return START_NOT_STICKY
            startReason = "theme"
            controller?.hide()
            controller = null
        } else {
            startReason = "user"
        }
        return startOverlay()
    }

    private fun startOverlay(): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopReason = "permission"
            AppLog.warn("permission.overlay", "granted=false")
            stopSelf()
            return START_NOT_STICKY
        }
        // 防止并发 onStartCommand 重复创建悬浮球窗口。
        if (controller != null || initializing) return START_NOT_STICKY
        initializing = true

        scope.launch {
            val current = settings.current()
            if (!current.ballEnabled) {
                initializing = false
                stopReason = "user"
                stopSelf()
                return@launch
            }
            val windowManager = getSystemService(WindowManager::class.java)
            if (windowManager == null) {
                initializing = false
                stopReason = "window"
                stopSelf()
                return@launch
            }

            val overlay = OverlayController(
                context = this@OverlayService,
                windowManager = windowManager,
                themeMode = current.themeMode,
                dynamicColor = current.dynamicColor,
                accentPalette = current.accentPalette,
                amoledDark = current.amoledDark,
                ballSize = current.ballSize,
                ballOpacity = current.ballOpacity,
                onPositionPersisted = { xFraction, yFraction ->
                    scope.launch { settings.saveBallPosition(xFraction, yFraction) }
                },
                onTap = { handleTap() },
                onLongPress = { handleLongPress() },
                onCopyPlaintext = { copyCapsulePlaintext() },
                onDismissCapsule = { dismissCapsule() },
                onExpandCapsule = { expandCapsule() },
                onCollapseCapsule = { collapseCapsule() },
                onSelectContact = { id -> selectContact(id) },
            )
            overlay.setWorkMode(current.ballWorkMode)
            controller = overlay
            initializing = false
            if (!overlay.show(current.ballXFraction, current.ballYFraction)) {
                // 窗口加不上时不要留下「服务在跑但没有球」的假状态；磁贴会据此显示为关闭。
                controller = null
                overlay.hide()
                stopReason = "window"
                stopSelf()
                return@launch
            }
            isRunning = true
            TileSync.refresh(this@OverlayService)
            AppLog.debug("overlay.start", "reason=$startReason")
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onConfigurationChanged()
        // 通知渠道名称/描述只在创建时读取；系统语言或应用语言变化后重建同名渠道，
        // 并用新文案重新发布前台通知。悬浮球窗口没有 Activity 生命周期，ComposeView
        // 不会随配置变化自动换资源，所以球/菜单/胶囊也必须整体重建一次。
        val localeTag = newConfig.locales[0].toLanguageTag()
        if (localeTag != lastLocaleTag) {
            lastLocaleTag = localeTag
            startAsForeground()
            if (!initializing) {
                startReason = "locale"
                controller?.hide()
                controller = null
                startOverlay()
            }
        }
    }

    override fun onDestroy() {
        runCatching { appOps?.stopWatchingMode(overlayOpListener) }
        runCatching { clipboard?.removePrimaryClipChangedListener(clipListener) }
        capsuleTimer?.cancel()
        capsuleExpandedLimitTimer?.cancel()
        controller?.hide()
        controller = null
        initializing = false
        isRunning = false
        scope.cancel()
        // 球不在了，控制中心里的磁贴要立刻跟着变灰（进程内状态，不依赖任何持久化）。
        TileSync.refresh(this@OverlayService)
        AppLog.debug("overlay.stop", "reason=$stopReason")
        super.onDestroy()
    }

    // region 单击 / 长按

    private fun handleTap() {
        if (controller?.isCapsuleVisible() == true) {
            dismissCapsule()
            return
        }
        if (controller?.isMenuOpen() == true) {
            controller?.dismissMenu()
            return
        }
        when (currentSettings.ballWorkMode) {
            BallWorkMode.STANDARD -> handleStandardTap()
            BallWorkMode.QUICK -> handleQuickTap()
        }
    }

    /**
     * 工作模式热切换：立即生效，不需要重启服务。
     *
     * 切到标准模式时顺手丢掉在途的写回目标——否则之前那次读取攒下的任务
     * 仍会在胶囊关闭后把文字写进输入框，与用户的选择相违。
     */
    private fun onWorkModeChanged(mode: BallWorkMode) {
        if (mode == BallWorkMode.STANDARD) TextAccessibilityService.instance?.clearTarget()
        if (controller?.isCapsuleVisible() == true) dismissCapsule()
        controller?.dismissMenu()
        controller?.setWorkMode(mode)
        AppLog.debug("overlay.mode", "mode=${mode.name}")
    }

    private fun handleLongPress() {
        if (controller?.isCapsuleVisible() == true) dismissCapsule()
        val keys = keyRepository.snapshot.value
        // 唯一联系人自动显示为已选；多联系人且未选择时不替用户猜。
        val selected = resolveRecipient(keys.contacts, currentSettings.lastContactId)
        AppLog.debug(
            "ball.longPress",
            "mode=${currentSettings.ballWorkMode.name} recipientKnown=${selected != null}",
        )
        AppLog.debug(
            "text.recipient",
            "contacts=${keys.contacts.size} selected=${selected != null} " +
                "auto=${keys.contacts.size == 1 && selected != null}",
        )
        controller?.showMenu(
            contacts = keys.contacts,
            selectedContactId = selected?.id,
            mode = currentSettings.ballWorkMode,
        )
    }

    private fun selectContact(id: String) {
        val contact = keyRepository.snapshot.value.contacts.firstOrNull { it.id == id } ?: return
        scope.launch { settings.setLastContactId(id) }
        toast(getString(R.string.ball_toast_recipient_set, contact.name))
    }

    // endregion

    // region 单击静默加密

    /**
     * 便捷模式单击：把当前输入框里的文字「就地变形」。
     *
     * - 输入框里是明文 → 加密并替回原处（不打开任何界面）；
     * - 输入框里已经是本工具密文 → 解密并替回原处，同时把球拉长成胶囊展示明文；
     * - 焦点处没有输入框（未开无障碍 / 服务被回收 / 当前界面没有输入框）→ 改按剪贴板工作。
     *
     * 无障碍没连上时不会再静默：**每次**点球都拉起专用引导面板，面板里可去开启/修复
     * 无障碍、关掉便捷模式，或明确选择“先按剪贴板处理”一次；见 [shouldShowA11yGuide]
     * 与 [a11yUnavailableNotice]。服务已连接但当前界面没有可用输入框时仍走剪贴板，不弹引导。
     *
     * 缺少密钥或联系人时只用一条 Toast 说明，不弹面板；需要完整面板时在设置里切到标准模式。
     */
    private fun handleQuickTap() {
        val service = TextAccessibilityService.instance
        val snapshot = service?.readFocusedField()
        val content = snapshot?.let { TextActionUseCase.inspect(it.text) }
        Log.i(
            TAG,
            "tap: service=${service != null} field=${snapshot != null} " +
                "lenBucket=${snapshot?.let { LogFields.lengthBucket(it.text.length) } ?: "-"} " +
                "kind=${content.kindLabel()}",
        )
        // 没有焦点字段时仍写出前台包名，否则无法从日志判断“球是在哪个应用里被点的”。
        val fieldFields = snapshot?.facts?.fields()
            ?: service?.lastReadPackageFields()?.takeIf { it.isNotBlank() }
        AppLog.debug(
            "ball.tap",
            "mode=QUICK field=${snapshot != null} kind=${content.kindLabel()} " +
                "reason=${fieldReadReason(service, snapshot)}" +
                (fieldFields?.let { " $it" } ?: ""),
        )
        if (service != null && snapshot != null) {
            AppLog.debug(
                "text.detect",
                "kind=${content.kindLabel()} " +
                    "lenBucket=${LogFields.lengthBucket(snapshot.text.length)}",
            )
            if (!snapshot.editable) {
                AppLog.warn("ball.tap", "mode=QUICK field=true kind=read_only")
                toast(getString(R.string.ball_toast_field_read_only))
                return
            }
            if (content is ClipContent.Cipher) {
                decryptFocusedField(service, snapshot)
            } else {
                encryptFocusedField(service, snapshot)
            }
            return
        }
        // 无障碍没连上（系统设置里写着“已启用”但服务没运行也算）：每次点球都拉起专用
        // 引导面板，不再有“只弹一次、之后静默”的分支。服务已连接但没有可用输入框时，
        // 仍按剪贴板兜底，不弹引导。
        if (shouldShowA11yGuide(serviceConnected = service != null)) {
            val notice = a11yUnavailableNotice(TextAccessibilityService.isEnabled(this))
            if (openA11yGuide(notice)) return
            // 系统拒绝后台拉起 Activity 时仍给出本次的剪贴板结果；面板已提示打不开。
            handleClipboardTap(notice)
            return
        }
        // 服务已连接但没有可用输入框：把字段读取原因带进剪贴板兜底提示，
        // 让用户知道是“没焦点 / 空 / 密码框跳过”，而不是只看到剪贴板结果。
        handleClipboardTap(fieldOutcome = service?.lastReadOutcome)
    }

    /**
     * 这次点球为什么没走输入框：`ok` / `not_connected` / 或服务的读取结论（枚举，无内容）。
     * 只用于日志；用户可见的解释在引导面板与剪贴板提示里。
     */
    private fun fieldReadReason(
        service: TextAccessibilityService?,
        snapshot: TextAccessibilityService.FieldSnapshot?,
    ): String = when {
        service == null -> "not_connected"
        snapshot != null -> "ok"
        else -> service.lastReadOutcome?.name?.lowercase() ?: "unknown"
    }

    /**
     * 便捷模式点球、无障碍没连上时的专用引导面板。
     *
     * 面板不预读剪贴板；用户必须显式点「先按剪贴板处理」才会读。面板里另有便捷模式开关
     * 和「去开启/修复无障碍服务」入口，用户可以直接在这里选择关掉便捷模式。
     *
     * @return 面板是否真的打开；false 时调用方继续走剪贴板兜底（面板已 toast 说明打不开）。
     */
    private fun openA11yGuide(notice: A11yUnavailableNotice): Boolean {
        AppLog.debug(
            "ball.tap",
            "mode=QUICK field=false kind=a11y_guide reason=${notice.name.lowercase()} " +
                "a11yEnabled=${TextAccessibilityService.isEnabled(this)}",
        )
        val opened = openCapsule(
            action = OverlayAction.ENCRYPT_TEXT,
            source = BallActionActivity.SOURCE_GUIDE,
            guideReason = notice,
            mode = BallWorkMode.QUICK,
            ballRect = controller?.ballRectOnScreen(),
        )
        if (!opened) {
            AppLog.warn("ball.tap", "mode=QUICK field=false kind=a11y_guide_launch_failed")
        }
        return opened
    }

    /**
     * 标准模式单击：不读剪贴板、不碰无障碍，直接打开文字输入面板。
     * 输入框里自动识别：明文 → 加密；本工具密文 → 解密。
     */
    private fun handleStandardTap() {
        AppLog.debug("ball.tap", "mode=STANDARD field=false kind=input_panel")
        startTextInputPanel()
    }

    /** 标准模式单击：空白输入面板；不预先读取剪贴板，也不走无障碍。 */
    private fun startTextInputPanel() {
        openCapsule(
            action = OverlayAction.ENCRYPT_TEXT,
            source = BallActionActivity.SOURCE_INPUT,
            mode = BallWorkMode.STANDARD,
            ballRect = controller?.ballRectOnScreen(),
        )
    }

    /**
     * 便捷模式在读不到输入框时的剪贴板兜底：
     * 密文 → 解密进胶囊；明文且有接收方 → 加密后放回剪贴板；
     * 明文但没有接收方 / 空 / 读不到 → 一条带下一步的提示。
     *
     * [notice] 只在引导面板被系统拒绝后台拉起时非空：提示要带上“无障碍未连接”这个前提，
     * 避免用户看到“读了剪贴板”却不知道点球为什么没有改写输入框。
     * [fieldOutcome] 在服务已连接、但没有可用输入框时传入：提示带上“无焦点/空/密码框跳过”，
     * 不弹无障碍引导，也不误报未连接。两者都为 null 时保持原有剪贴板提示。
     */
    private fun handleClipboardTap(
        notice: A11yUnavailableNotice? = null,
        fieldOutcome: FieldIoOutcome? = null,
    ) {
        val prefix = when {
            notice == A11yUnavailableNotice.SERVICE_NOT_RUNNING ->
                getString(R.string.ball_prefix_a11y_not_running)

            notice == A11yUnavailableNotice.NOT_ENABLED ->
                getString(R.string.ball_prefix_a11y_not_enabled)

            fieldOutcome == FieldIoOutcome.EMPTY -> getString(R.string.ball_prefix_field_empty)
            fieldOutcome == FieldIoOutcome.PASSWORD_SKIPPED ->
                getString(R.string.ball_prefix_password_skipped)

            fieldOutcome == FieldIoOutcome.NO_FOCUS -> getString(R.string.ball_prefix_no_focus)
            else -> ""
        }
        scope.launch {
            val read = controller?.readClipboardWhileFocused { readClipboardText() }
                ?: FocusedClipboard.NoFocus
            val outcome = clipboardOutcome(read) {
                TextActionUseCase.inspect(it) is ClipContent.Cipher
            }
            val keys = keyRepository.snapshot.value
            val recipient = resolveRecipient(keys.contacts, currentSettings.lastContactId)
            val plan = quickTapPlan(outcome, hasRecipient = recipient != null)
            when (plan) {
                TapPlan.DecryptClipboard -> {
                    if (notice != null) {
                        toast(clipboardFallbackMessage(prefix, ClipboardOutcome.CIPHER))
                    }
                    (read as? FocusedClipboard.Content)?.let { decryptCipherText(it.text) }
                }

                TapPlan.EncryptClipboard -> {
                    AppLog.debug(
                        "ball.tap",
                        "mode=QUICK field=false kind=clipboard_plaintext_encrypted recipientKnown=true",
                    )
                    val text = (read as? FocusedClipboard.Content)?.text
                    val contact = recipient
                    if (text.isNullOrBlank() || contact == null) {
                        toast(clipboardFallbackMessage(prefix, ClipboardOutcome.EMPTY))
                    } else {
                        encryptClipboardText(text, contact, prefix)
                    }
                }

                is TapPlan.NothingToDo -> when (plan.outcome) {
                    ClipboardOutcome.EMPTY -> {
                        AppLog.warn("ball.tap", "mode=QUICK field=false kind=clipboard_empty")
                        toast(clipboardFallbackMessage(prefix, ClipboardOutcome.EMPTY))
                    }

                    // 明文但没有接收方：不猜人、不改剪贴板，只给下一步。
                    ClipboardOutcome.PLAINTEXT -> {
                        AppLog.debug(
                            "ball.tap",
                            "mode=QUICK field=false kind=clipboard_plaintext_no_recipient " +
                                "recipientKnown=false",
                        )
                        toast(clipboardFallbackMessage(prefix, ClipboardOutcome.PLAINTEXT))
                    }

                    else -> {
                        AppLog.warn("ball.tap", "mode=QUICK field=false kind=clipboard_unavailable")
                        toast(clipboardFallbackMessage(prefix, ClipboardOutcome.UNREADABLE))
                    }
                }
            }
        }
    }

    /**
     * 便捷模式读不到输入框时，把剪贴板里的明文加密给 [contact] 并放回剪贴板。
     * 原文不 trim、不清洗；不写任何输入框，也不自动粘贴。
     */
    private fun encryptClipboardText(
        plaintext: String,
        contact: ContactKey,
        prefix: String,
    ) {
        scope.launch {
            val started = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val identity = keyRepository.snapshot.value.identity
                        ?: throw LocalKeyUnavailableException(missing = true)
                    val privateKey = keyRepository.currentPrivateKey()
                        ?: throw LocalKeyUnavailableException(missing = false)
                    val receiver = PrivateKeyBackup.decodePublicKey(contact.publicKeyBase64)
                    val cipher = TextActionUseCase.encrypt(plaintext, privateKey, receiver)
                    cipher to (identity.fingerprint == contact.fingerprint)
                }
            }
            result
                .onSuccess { (cipher, recipientIsSelf) ->
                    val copied = copyToClipboard(cipher)
                    AppLog.info(
                        "ball.encrypt",
                        "source=clipboard recipientKnown=true recipientIsSelf=$recipientIsSelf " +
                            "clipboardWrite=$copied ms=${System.currentTimeMillis() - started}",
                    )
                    toast(
                        if (copied) {
                            clipboardFallbackMessage(prefix, ClipboardOutcome.PLAINTEXT, contact.name)
                        } else {
                            getString(R.string.ball_toast_encrypted_not_copied, prefix)
                        },
                    )
                }
                .onFailure { error ->
                    AppLog.error(
                        "ball.tap",
                        error,
                        "kind=clipboard_plaintext_encrypt_failed",
                    )
                    toast(getString(R.string.ball_toast_encrypt_failed, prefix))
                }
        }
    }

    /** 剪贴板里的密文：解密并把球拉长成胶囊（不写回任何输入框）。 */
    private fun decryptCipherText(raw: String) {
        val content = TextActionUseCase.inspect(raw) as? ClipContent.Cipher ?: return
        scope.launch {
            val started = System.currentTimeMillis()
            withContext(Dispatchers.IO) { decryptBlocking(content) }
                .onSuccess { decrypted ->
                    AppLog.info(
                        "ball.decrypt",
                        "source=clipboard senderKnown=${decrypted.sender != null} " +
                            "ms=${System.currentTimeMillis() - started}",
                    )
                    showCapsule(
                        OverlayCapsulePayload(
                            plaintext = decrypted.plaintext,
                            senderName = decrypted.sender?.name,
                        ),
                    )
                }
                .onFailure {
                    logFailure(
                        "ball.decrypt",
                        it,
                        "source=clipboard ms=${System.currentTimeMillis() - started}",
                    )
                    toast(decryptErrorText(this@OverlayService, it))
                }
        }
    }

    /** 输入框里已是密文：就地解回明文，并把结果放进拉长后的胶囊。 */
    private fun decryptFocusedField(
        service: TextAccessibilityService,
        snapshot: TextAccessibilityService.FieldSnapshot,
    ) {
        val content = TextActionUseCase.inspect(snapshot.text) as? ClipContent.Cipher ?: return
        scope.launch {
            val started = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) { decryptBlocking(content) }
            result
                .onSuccess { decrypted ->
                    val write = service.replaceFieldText(snapshot, decrypted.plaintext)
                    AppLog.info(
                        "ball.decrypt",
                        "source=field senderKnown=${decrypted.sender != null} " +
                            "writeback=${write.success} verdict=${write.verdict} " +
                            "pkg=${snapshot.facts.packageName} " +
                            "cls=${snapshot.facts.className} sel=${snapshot.facts.hasSelection} " +
                            "ms=${System.currentTimeMillis() - started}",
                    )
                    showCapsule(
                        OverlayCapsulePayload(
                            plaintext = decrypted.plaintext,
                            senderName = decrypted.sender?.name,
                        ),
                    )
                    if (!write.success) {
                        toast(getString(R.string.ball_toast_decrypted_not_written))
                    }
                }
                .onFailure {
                    logFailure(
                        "ball.decrypt",
                        it,
                        "source=field ms=${System.currentTimeMillis() - started}",
                    )
                    toast(decryptErrorText(this@OverlayService, it))
                }
        }
    }

    private fun decryptBlocking(content: ClipContent.Cipher): Result<DecryptedMessage> =
        runCatching {
            val privateKey = keyRepository.currentPrivateKey()
                ?: throw LocalKeyUnavailableException(
                    missing = keyRepository.snapshot.value.identity == null,
                )
            TextActionUseCase.decrypt(
                content,
                privateKey,
                keyRepository.snapshot.value.contacts,
            )
        }

    /**
     * 加密写回失败时的提示：按回读结论区分“没生效 / 无法判定 / 内容刚被改 / 写不进去”。
     *
     * 旧文案只会说“输入框已变化”，而实际上绝大多数失败是“动作被接受但没应用”，
     * 与事实不符；这里只描述确定的结论。
     */
    private fun encryptFailureMessage(verdict: String): String = when (verdict) {
        "not_applied" -> getString(R.string.ball_writeback_not_applied)
        "snapshot_changed" -> getString(R.string.ball_writeback_snapshot_changed)
        "abandoned" -> getString(R.string.ball_writeback_abandoned)
        else -> getString(R.string.ball_writeback_failed)
    }

    /** 输入框里是明文：加密并原地替回。 */
    private fun encryptFocusedField(
        service: TextAccessibilityService,
        snapshot: TextAccessibilityService.FieldSnapshot,
    ) {
        val keys = keyRepository.snapshot.value
        if (keys.identity == null) {
            toast(getString(R.string.key_error_no_identity))
            return
        }
        val contact = resolveRecipient(keys.contacts, currentSettings.lastContactId)
        if (contact == null) {
            // 缺联系人不进加密流程：直接按“没联系人 / 没选接收方”给出下一步。
            toast(
                getString(
                    if (keys.contacts.isEmpty()) {
                        R.string.ball_no_contacts_long_press
                    } else {
                        R.string.ball_choose_recipient_long_press
                    },
                ),
            )
            return
        }
        scope.launch {
            val started = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val privateKey = keyRepository.currentPrivateKey()
                        ?: throw LocalKeyUnavailableException(missing = false)
                    val receiver = PrivateKeyBackup.decodePublicKey(contact.publicKeyBase64)
                    val cipher = TextActionUseCase.encrypt(snapshot.text.trim(), privateKey, receiver)
                    Triple(cipher, contact.name, contact.fingerprint == keys.identity?.fingerprint)
                }
            }
            result
                .onSuccess { (cipher, contactName, recipientIsSelf) ->
                    val write = service.replaceFieldText(snapshot, cipher)
                    AppLog.info(
                        "ball.encrypt",
                        "source=field recipientKnown=true recipientIsSelf=$recipientIsSelf " +
                            "writeback=${write.success} verdict=${write.verdict} " +
                            "pkg=${snapshot.facts.packageName} cls=${snapshot.facts.className} " +
                            "sel=${snapshot.facts.hasSelection} " +
                            "ms=${System.currentTimeMillis() - started}",
                    )
                    if (write.success) {
                        toast(getString(R.string.ball_toast_encrypted_replaced, contactName))
                    } else {
                        copyToClipboard(cipher)
                        toast(encryptFailureMessage(write.verdict))
                    }
                }
                .onFailure { error ->
                    logFailure(
                        "ball.encrypt",
                        error,
                        "source=field ms=${System.currentTimeMillis() - started}",
                    )
                    toast(encryptErrorText(this@OverlayService, error))
                }
        }
    }

    // endregion

    // region 剪贴板监听

    /**
     * 剪贴板变化时尝试识别新密文。Android 10+ 对没有输入焦点的应用直接返回 null，
     * 这时只能静默放弃（不刷日志），由用户点球后手动处理。
     */
    private fun onClipboardChanged() {
        // 标准模式明确不主动读剪贴板：复制密文不会自己弹明文，需要用户点球后手动处理。
        if (currentSettings.ballWorkMode != BallWorkMode.QUICK) return
        val text = readClipboardText()
        if (text == null) {
            // Android 10+ 对没有输入焦点的应用直接返回 null；只记一次，避免刷日志。
            if (!clipboardDeniedLogged) {
                clipboardDeniedLogged = true
                Log.i(TAG, "clipboard unavailable in background, manual entry still works")
                AppLog.warn("clipboard.unavailable", "reason=background")
            }
            return
        }
        if (text.isBlank()) return
        val hash = text.hashCode()
        if (hash == lastClipHash) return
        lastClipHash = hash
        // 自己写进剪贴板的内容（例如加密结果）不再回头解密，避免来回弹胶囊。
        if (hash == selfClipHash) return

        val content = TextActionUseCase.inspect(text) as? ClipContent.Cipher ?: return
        AppLog.debug("clipboard.cipher", "detected=true")
        scope.launch {
            val decrypted = withContext(Dispatchers.IO) {
                runCatching {
                    val privateKey = keyRepository.currentPrivateKey() ?: return@runCatching null
                    TextActionUseCase.decrypt(
                        content,
                        privateKey,
                        keyRepository.snapshot.value.contacts,
                    )
                }.onFailure { logFailure("clipboard.decrypt", it) }.getOrNull()
            } ?: return@launch
            AppLog.debug("clipboard.decrypt", "ok=true")
            showCapsule(
                OverlayCapsulePayload(
                    plaintext = decrypted.plaintext,
                    senderName = decrypted.sender?.name,
                ),
            )
        }
    }

    private fun readClipboardText(): String? = runCatching {
        val clip = clipboard?.primaryClip ?: return@runCatching null
        if (clip.itemCount == 0) return@runCatching null
        clip.getItemAt(0).coerceToText(this)?.toString()
    }.getOrNull()

    // endregion

    // region 胶囊

    private fun showCapsule(payload: OverlayCapsulePayload) {
        val overlay = controller ?: return
        overlay.showCapsule(payload)
        capsuleExpandedLimitTimer?.cancel()
        startCapsuleTimer()
    }

    /** 展开胶囊面板：暂停 20 秒收回计时，改设 120 秒上限，避免明文无限停留。 */
    private fun expandCapsule() {
        controller?.expandCapsule()
        capsuleTimer?.cancel()
        capsuleExpandedLimitTimer?.cancel()
        capsuleExpandedLimitTimer = scope.launch {
            delay(CAPSULE_EXPANDED_LIMIT_MS)
            controller?.collapseCapsule()
        }
    }

    /** 收起展开面板：取消上限计时，恢复 20 秒收回计时。 */
    private fun collapseCapsule() {
        controller?.collapseCapsule()
        capsuleExpandedLimitTimer?.cancel()
        startCapsuleTimer()
    }

    private fun startCapsuleTimer() {
        capsuleTimer?.cancel()
        capsuleTimer = scope.launch {
            // 明文不在屏幕上无限停留：20 秒后自动收回。
            delay(CAPSULE_TIMEOUT_MS)
            controller?.hideCapsule()
        }
    }

    private fun dismissCapsule() {
        capsuleTimer?.cancel()
        capsuleExpandedLimitTimer?.cancel()
        controller?.hideCapsule()
    }

    private fun copyCapsulePlaintext() {
        if (controller?.isCapsuleVisible() != true) return
        val text = controller?.capsulePlaintext() ?: return
        copyToClipboard(text)
        AppLog.info("capsule.copy", "target=clipboard")
        toast(getString(R.string.ball_toast_plain_copied))
    }

    /**
     * 便捷模式剪贴板兜底的统一文案：纯逻辑层只给种类，这里按当前语言取资源。
     */
    private fun clipboardFallbackMessage(
        prefix: String,
        outcome: ClipboardOutcome,
        recipientName: String? = null,
    ): String = when (clipboardFallbackKind(outcome, hasRecipient = recipientName != null)) {
        ClipboardFallback.ENCRYPTED_TO ->
            getString(R.string.ball_fallback_encrypted_to, prefix, recipientName.orEmpty())

        ClipboardFallback.CHOOSE_CONTACT ->
            getString(R.string.ball_fallback_choose_contact, prefix)

        ClipboardFallback.COPY_FIRST -> getString(R.string.ball_fallback_copy_first, prefix)
        ClipboardFallback.UNREADABLE -> getString(R.string.ball_fallback_unreadable, prefix)
        ClipboardFallback.DECRYPTED -> getString(R.string.ball_fallback_decrypted, prefix)
    }

    private fun copyToClipboard(text: String): Boolean {
        val manager = clipboard ?: return false
        // 先记下自己的内容：setPrimaryClip 可能同步触发监听器，晚赋值会被当成外部密文。
        val previous = selfClipHash
        selfClipHash = text.hashCode()
        val copied = runCatching {
            manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        }.isSuccess
        // 写入失败就恢复，避免这个哈希误伤之后真正的同内容剪贴板。
        if (!copied) selfClipHash = previous
        return copied
    }

    // endregion

    private fun openCapsule(
        action: OverlayAction,
        source: String,
        guideReason: A11yUnavailableNotice? = null,
        mode: BallWorkMode = currentSettings.ballWorkMode,
        /** 球的屏幕矩形：面板会从它展开；为空表示不是从悬浮球进来的（磁贴等）。 */
        ballRect: Rect? = null,
    ): Boolean {
        val intent = Intent(this, BallActionActivity::class.java).apply {
            // NO_ANIMATION 才会真正压掉“新任务”级别的入场/退场动画；
            // 只靠 makeCustomAnimation / overrideActivityTransition 在部分版本上压不住，
            // 面板就会被系统从底部带进来。
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
            putExtra(BallActionActivity.EXTRA_ACTION, action.name)
            putExtra(BallActionActivity.EXTRA_SOURCE, source)
            putExtra(BallActionActivity.EXTRA_WORK_MODE, mode.name)
            guideReason?.let { putExtra(BallActionActivity.EXTRA_GUIDE_REASON, it.name) }
            ballRect?.let { putExtra(BallActionActivity.EXTRA_BALL_RECT, it) }
        }
        // 从悬浮窗拉起 Activity 依赖系统的后台启动策略，个别机型会拒绝；
        // 标准模式下这是单击主路径，失败必须让用户知道该从应用内进入，而不是静默无反应。
        // 面板自己负责形态动画，系统窗口动画会和幽灵矩形叠在一起；这里直接关掉。
        return runCatching {
            val options = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
            startActivity(intent, options)
            true
        }
            .onFailure {
                Log.w(TAG, "无法打开文字动作胶囊", it)
                AppLog.error("capsule.open", it, "kind=launch")
                toast(getString(R.string.tile_launch_failed))
            }
            .getOrDefault(false)
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.overlay_channel_description)
            setShowBadge(false)
        }
        manager?.createNotificationChannel(channel)

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** 识别结果短名，只用于日志；不含内容或精确长度。 */
    private fun ClipContent?.kindLabel(): String = when (this) {
        is ClipContent.Cipher -> "cipher"
        ClipContent.NotOurs -> "plain"
        ClipContent.Empty, null -> "unknown"
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "overlay_ball"
        private const val MAX_FIELD_CHARS = 20_000
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_REFRESH = "com.cryptoassistant.app.action.REFRESH_OVERLAY"
        private const val CAPSULE_TIMEOUT_MS = 20_000L
        private const val CAPSULE_EXPANDED_LIMIT_MS = 120_000L

        /**
         * 球窗口是否真的在屏幕上。快捷设置磁贴以此为准，因此重启/强停后会自然显示为关闭，
         * 点一下即可重新拉起，不需要额外的持久化字段。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, OverlayService::class.java)) }
        }

        fun refreshTheme(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_REFRESH)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
