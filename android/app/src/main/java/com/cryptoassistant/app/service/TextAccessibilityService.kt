package com.cryptoassistant.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cryptoassistant.app.CryptoAssistantApplication
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.FieldIoAction
import com.cryptoassistant.app.data.FieldIoOutcome
import com.cryptoassistant.app.data.log.AppLog
import com.cryptoassistant.app.data.log.FieldFacts
import com.cryptoassistant.app.data.log.FieldTextMatch
import com.cryptoassistant.app.data.log.LogFields
import com.cryptoassistant.app.data.log.ReadbackVerdict
import com.cryptoassistant.app.data.log.TreeProbeFacts
import com.cryptoassistant.app.data.log.WriteGuard
import com.cryptoassistant.app.data.log.fieldCompareFields
import com.cryptoassistant.app.data.log.fieldTextMatch
import com.cryptoassistant.app.data.log.judgeReadback
import com.cryptoassistant.app.data.log.matchesContent
import com.cryptoassistant.app.data.log.treeProbeFields
import com.cryptoassistant.app.data.log.writeGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 写回链路的最终结论：写前核对 + 写动作 + 回读阶梯。
 *
 * @param success **只有回读确认后才为 true**；写动作返回 true 不再等于成功
 * @param verdict `early_ok`/`late_ok`，或失败原因 `not_applied`/`abandoned`/`snapshot_changed`/`snapshot_unreadable`/`write_failed`/`password`/`no_focus`/`no_root`/`package_changed`
 */
data class WriteBackResult(val success: Boolean, val verdict: String)

/**
 * 只做一件事的无障碍服务：在用户点悬浮球的那一刻，读写**当前拥有输入焦点的控件**。
 *
 * 隐私边界（与设置页披露一致）：
 * - 不监听、不缓存屏幕内容：`onAccessibilityEvent` 里不做任何采集；
 * - 读取只发生在用户点击悬浮球的瞬间，读取结果直接交给胶囊；
 * - 写回只作用于同一次点球里刚刚读取的那个控件（不再有旧胶囊的延迟写回链）；
 * - 无障碍服务自身不发起网络请求（应用唯一的联网是默认关闭的可选更新检查）；
 *   只在写回失败时记录原因，不记录文字内容。
 */
class TextAccessibilityService : AccessibilityService() {

    /** 一次读取的结果。 */
    data class FieldSnapshot(
        val text: String,
        val editable: Boolean,
        val hasSelection: Boolean,
        val packageName: String,
        /** 只含结构元数据（包名/类名/资源 id/窗口/选区/长度档位/WebView），不含文字。 */
        val facts: FieldFacts,
    )

    /** 最近一次读取的时刻；只用来算写回日志里的 `ageMs`，不保存节点（T15 已删旧胶囊写回链）。 */
    private var targetAt: Long = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 最近一次读取的结论（枚举，不含内容）。
     *
     * 悬浮球用它把“这次为什么没读到输入框”写进日志与提示，避免只看到一个静默的剪贴板结果；
     * 设置页的「最近一次输入框读写」同时会落盘同样的原因。
     */
    @Volatile
    var lastReadOutcome: FieldIoOutcome? = null
        private set

    /** 最近一次读取时的前台包名：没有焦点字段时也能让点球日志写出“在哪个应用”。 */
    @Volatile
    var lastReadPackage: String = ""
        private set

    /** 供悬浮球日志在“没有焦点字段”时使用的包名字段；没有读到过则为空串。 */
    fun lastReadPackageFields(): String =
        lastReadPackage.takeIf { it.isNotBlank() }
            ?.let { "pkg=${FieldFacts.packageLabel(it)}" }
            .orEmpty()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
        AppLog.info("a11y.service", "state=connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 刻意留空：本服务只在用户点球时按需读取，不在这里采集任何内容。
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        clearTarget()
        instance = null
        _connected.value = false
        AppLog.info("a11y.service", "state=disconnected reason=unbind")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearTarget()
        instance = null
        _connected.value = false
        AppLog.info("a11y.service", "state=disconnected reason=destroy")
        super.onDestroy()
    }

    /**
     * 读取当前输入焦点控件：**恒取整个输入框**（D14-A）。
     *
     * 选中的那一段不参与任何决策——点球的目的是一键加密/解密并替换整框文字，
     * 拿选区去比对整框只会让写回被自己的校验拦下（2026-10-29 真机日志 10/10 的
     * `cmp=selection_part`）。选区仍作为结构事实写进日志（`sel=`/`selLen=`）。
     * 只在悬浮球被点击后调用；密码框/PIN 等敏感输入框直接跳过（不读）。
     */
    fun readFocusedField(): FieldSnapshot? {
        val root = rootInActiveWindow
        if (root == null) {
            // 没有 root 时不要保留上一次的包名，否则点球日志会把旧应用误报成本次前台。
            lastReadPackage = ""
            lastReadOutcome = FieldIoOutcome.NO_FOCUS
            logRead("no_root", FieldFacts.unknown())
            logTreeProbe(null, null, "", "no_root")
            recordIo(FieldIoAction.READ, FieldIoOutcome.NO_FOCUS, "")
            return null
        }
        val pkg = root.packageName?.toString().orEmpty()
        lastReadPackage = pkg
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) {
            lastReadOutcome = FieldIoOutcome.NO_FOCUS
            logRead("no_focus", FieldFacts.unknown(pkg))
            logTreeProbe(root, null, pkg, "no_focus")
            recordIo(FieldIoAction.READ, FieldIoOutcome.NO_FOCUS, pkg)
            return null
        }
        // 合规硬要求：密码框、PIN 等敏感输入框必须跳过，不得因为能读到就处理。
        if (isSensitiveInput(node)) {
            val facts = factsWithoutText(node, pkg)
            node.recycleIfNeeded()
            lastReadOutcome = FieldIoOutcome.PASSWORD_SKIPPED
            logRead("password_skipped", facts)
            recordIo(FieldIoAction.READ, FieldIoOutcome.PASSWORD_SKIPPED, pkg)
            return null
        }
        val text = node.text?.toString().orEmpty()
        val selectionLength = selectionLengthOf(node, text.length)
        val facts = factsOf(node, pkg, text.length, selectionLength)
        // 只发一条结构探针：不可见/不可编辑与空内容经常同时出现（微信就是这样），
        // 分成两条只会重复同一组结构字段。hiddenFocus/readonly/textBucket 已能表达两者。
        if (!node.isEditable || !node.isVisibleToUser || text.isBlank()) {
            logTreeProbe(
                root,
                node,
                pkg,
                reason = if (!node.isEditable || !node.isVisibleToUser) {
                    "hidden_or_readonly"
                } else {
                    "empty"
                },
                textLength = text.length,
            )
        }
        if (text.isBlank()) {
            node.recycleIfNeeded()
            lastReadOutcome = FieldIoOutcome.EMPTY
            logRead("empty", facts)
            recordIo(FieldIoAction.READ, FieldIoOutcome.EMPTY, pkg)
            return null
        }
        clearTarget()
        targetAt = System.currentTimeMillis()
        lastReadOutcome = FieldIoOutcome.SUCCESS
        logRead("ok", facts)
        recordIo(FieldIoAction.READ, FieldIoOutcome.SUCCESS, pkg)
        return FieldSnapshot(
            text = text,
            editable = node.isEditable,
            hasSelection = selectionLength > 0,
            packageName = pkg,
            facts = facts,
        )
    }

    /**
     * 不开任何界面、当场替换输入框文字（单击悬浮球的静默路径）。
     *
     * 读取与写入之间用户可能切了应用、或自己改了文字，所以写回前核对包名和当前内容：
     * 对不上先 `refresh()` 重读一次（只重试**核对**，不重试写入）；仍对不上就放弃，
     * 宁可把结果放剪贴板，也不把密文写进别的输入框、不覆盖用户刚输入的文字。
     *
     * 写动作返回 true **不等于**成功：必须经 [verifyWriteback] 的 150ms → 800ms 回读阶梯确认。
     * suspend 只用来等回读（`delay` 不阻塞其它协程），读写仍在主线程完成。
     */
    suspend fun replaceFieldText(
        snapshot: FieldSnapshot,
        replacement: String,
    ): WriteBackResult {
        val ageMs = System.currentTimeMillis() -
            (targetAt.takeIf { it > 0L } ?: System.currentTimeMillis())
        val root = rootInActiveWindow
            ?: return fail("no_root", FieldIoOutcome.NO_FOCUS, FieldFacts.unknown(), ageMs)
        val pkg = root.packageName?.toString().orEmpty()
        if (snapshot.packageName.isNotEmpty() && pkg != snapshot.packageName) {
            return fail(
                "package_changed",
                FieldIoOutcome.SNAPSHOT_MISMATCH,
                FieldFacts.unknown(pkg),
                ageMs,
                "expectedPkg=${snapshot.facts.packageName}",
            )
        }
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return fail("no_focus", FieldIoOutcome.NO_FOCUS, FieldFacts.unknown(pkg), ageMs)
        // 双保险：读取时已跳过密码框；写入前再核一次，防止焦点已移到敏感控件。
        if (isSensitiveInput(node)) {
            val facts = factsWithoutText(node, pkg)
            node.recycleIfNeeded()
            return fail("password", FieldIoOutcome.PASSWORD_SKIPPED, facts, ageMs)
        }
        // 写前核对：快照恒为整框（D14-A），两侧同源；不一致时只重试**核对**，不重试写入。
        var current = node.text?.toString()
        var guard = writeGuard(snapshot.text, current)
        var tries = 0
        if (guard == WriteGuard.RETRY) {
            // 输入法/应用可能刚提交了新内容，refresh 后重读一次；仍不一致说明用户真的改了字，
            // 宁可把结果放剪贴板，也不拿密文覆盖他刚输入的文字。
            node.refresh()
            current = node.text?.toString()
            tries = 1
            guard = writeGuard(snapshot.text, current, afterRefresh = true)
        }
        val selectionLength = selectionLengthOf(node, current?.length ?: 0)
        val facts = factsOf(node, pkg, current?.length ?: 0, selectionLength)
        if (!guard.proceed) {
            val reason = if (guard == WriteGuard.ABORT_UNREADABLE) {
                "snapshot_unreadable"
            } else {
                "snapshot_changed"
            }
            return fail(
                reason,
                FieldIoOutcome.SNAPSHOT_MISMATCH,
                facts,
                ageMs,
                fieldCompareFields(snapshot.text, current.orEmpty(), false) +
                    " snapSel=${snapshot.hasSelection} curSel=${selectionLength > 0}" +
                    " guard=${guard.label}",
                tries,
            )
        }
        val byAction = setText(node, replacement)
        val strategy = if (byAction) "set_text" else "paste"
        if (!byAction && !pasteInto(node, replacement)) {
            return fail(
                "write_failed",
                FieldIoOutcome.WRITE_FAILED,
                facts,
                ageMs,
                "setText=false paste=false",
                tries,
            )
        }
        // 写动作被接受只是开始：成功要由 150ms → 800ms 回读确认。
        val verdict = verifyWriteback(facts, snapshot.text, replacement, strategy)
        recordIo(
            FieldIoAction.WRITE,
            when {
                verdict.success -> FieldIoOutcome.SUCCESS
                // 用户自己改字/切了应用：设置页显示“内容已被改动，放弃写入”比“写入失败”准确。
                verdict == ReadbackVerdict.ABANDONED -> FieldIoOutcome.SNAPSHOT_MISMATCH
                else -> FieldIoOutcome.WRITE_FAILED
            },
            pkg,
        )
        logWrite(
            if (verdict.success) "ok" else "unverified",
            strategy,
            facts,
            ageMs,
            tries,
            verdict,
        )
        return WriteBackResult(verdict.success, verdict.label)
    }

    /**
     * 写回失败只记原因代号、结构字段与时长，不记文字内容。
     *
     * `reason=snapshot_mismatch` 会额外带 `cmp=` 与两侧长度档位，用来区分“选区拼接”、
     * “输入法插入不可见字符”与“用户真的改了字”。
     */
    private fun fail(
        reason: String,
        outcome: FieldIoOutcome,
        facts: FieldFacts,
        ageMs: Long,
        extra: String? = null,
        tries: Int = 0,
    ): WriteBackResult {
        android.util.Log.w(TAG, "replaceFieldText: $reason")
        AppLog.warn(
            "a11y.writeback",
            "reason=$reason tries=$tries ageMs=$ageMs" +
                (extra?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") +
                " ${facts.fields()}",
        )
        recordIo(
            FieldIoAction.WRITE,
            outcome,
            if (facts.packageName == FieldFacts.UNKNOWN) "" else facts.packageName,
        )
        return WriteBackResult(false, reason)
    }

    private fun logRead(result: String, facts: FieldFacts) {
        AppLog.debug("a11y.read", "result=$result ${facts.fields()}")
    }

    private fun logWrite(
        result: String,
        strategy: String,
        facts: FieldFacts,
        ageMs: Long,
        tries: Int = 0,
        verdict: ReadbackVerdict? = null,
    ) {
        AppLog.debug(
            "a11y.writeback",
            "result=$result strategy=$strategy ageMs=$ageMs tries=$tries" +
                (verdict?.let { " verdict=${it.label}" } ?: "") +
                " ${facts.fields()}",
        )
    }

    /**
     * 只读结构事实：包名、控件类型/资源 id、窗口、选区、长度档位、是否在 WebView 内。
     *
     * 不持有文字；`textLength`/`selectionLength` 只用于换算 [LogFields.lengthBucket]。
     */
    private fun factsOf(
        node: AccessibilityNodeInfo,
        pkg: String,
        textLength: Int,
        selectionLength: Int,
    ): FieldFacts = FieldFacts(
        packageName = FieldFacts.packageLabel(pkg),
        className = FieldFacts.classLabel(node.className?.toString()),
        viewId = FieldFacts.viewIdLabel(node.viewIdResourceName),
        windowId = node.windowId,
        editable = node.isEditable,
        password = isSensitiveInput(node),
        visible = node.isVisibleToUser,
        hasSelection = selectionLength > 0,
        lengthBucket = LogFields.lengthBucket(textLength),
        selectionBucket = if (selectionLength > 0) {
            LogFields.lengthBucket(selectionLength)
        } else {
            "-"
        },
        inWebView = isInsideWebView(node),
    )

    /** 节点上的选区长度；越界/无选区一律为 0（D14-A 之后只作为日志事实）。 */
    private fun selectionLengthOf(node: AccessibilityNodeInfo, textLength: Int): Int {
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        return if (start in 0..end && end <= textLength && end > start) end - start else 0
    }

    /** 密码框等不能读文字的节点：只记录结构，长度标记为 hidden。 */
    private fun factsWithoutText(node: AccessibilityNodeInfo, pkg: String): FieldFacts =
        FieldFacts.withoutText(
            packageName = pkg,
            className = node.className?.toString(),
            viewId = node.viewIdResourceName,
            windowId = node.windowId,
            editable = node.isEditable,
            password = true,
            visible = node.isVisibleToUser,
            inWebView = isInsideWebView(node),
        )

    /** 沿父链最多找 [MAX_WEBVIEW_DEPTH] 层；微信/小程序/H5 输入框常包在 WebView 里。 */
    private fun isInsideWebView(node: AccessibilityNodeInfo): Boolean {
        var current = node.parent
        var depth = 0
        while (current != null && depth < MAX_WEBVIEW_DEPTH) {
            val name = current.className?.toString().orEmpty()
            if (name.contains("WebView", ignoreCase = true)) {
                current.recycleIfNeeded()
                return true
            }
            val parent = current.parent
            current.recycleIfNeeded()
            current = parent
            depth++
        }
        current?.recycleIfNeeded()
        return false
    }

    /**
     * 写后回读阶梯：150ms 先判，未生效再等到 800ms 判一次；结论**进入成败判定**。
     *
     * 写动作返回 true 只说明“系统接受了动作”：微信这类自绘/WebView 控件会接受却不应用，
     * 备忘录这类会延迟到 800ms 才生效。每一步都写 `a11y.readback`：
     * `state=` 是这一跳看到什么，`verdict=` 是阶梯结论（early_ok / late_ok / not_applied / abandoned）。
     *
     * 回读只做 binder 读节点，留在主线程；加密/解密仍在 Dispatchers.IO。
     */
    private suspend fun verifyWriteback(
        facts: FieldFacts,
        snapshotText: String,
        expected: String,
        strategy: String,
    ): ReadbackVerdict {
        val startedAt = System.currentTimeMillis()
        var waited = 0L
        READBACK_STEPS_MS.forEachIndexed { index, at ->
            delay(at - waited)
            waited = at
            val root = rootInActiveWindow
            val node = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val observed = node?.text?.toString()
            val pkg = root?.packageName?.toString().orEmpty()
            val samePkg = FieldFacts.packageLabel(pkg) == facts.packageName
            val lastSample = index == READBACK_STEPS_MS.lastIndex
            val verdict = judgeReadback(expected, snapshotText, observed, samePkg, lastSample)
            AppLog.debug(
                "a11y.readback",
                "at=${at}ms state=${readbackState(expected, snapshotText, observed, samePkg)} " +
                    "verdict=${verdict.label} strategy=$strategy samePkg=$samePkg " +
                    "ms=${System.currentTimeMillis() - startedAt} " +
                    "expectedLen=${LogFields.lengthBucket(expected.length)} " +
                    "curLen=${observed?.let { LogFields.lengthBucket(it.length) } ?: "?"} " +
                    "pkg=${FieldFacts.packageLabel(pkg)}",
            )
            node?.recycleIfNeeded()
            if (verdict != ReadbackVerdict.PENDING) return verdict
        }
        // 理论不可达：最后一跳一定会给出非 PENDING 的结论。
        return ReadbackVerdict.ABANDONED
    }

    /** 这一跳看到的原始状态（`state=`），与阶梯结论（`verdict=`）分开记录，便于和旧日志对照。 */
    private fun readbackState(
        expected: String,
        snapshotText: String,
        observed: String?,
        samePkg: Boolean,
    ): String = when {
        !samePkg -> "other_pkg"
        observed == null -> "unreadable"
        fieldTextMatch(expected, observed, false) == FieldTextMatch.EXACT -> "applied"
        fieldTextMatch(expected, observed, false).matchesContent -> "applied_normalized"
        fieldTextMatch(snapshotText, observed, false).matchesContent -> "not_applied"
        else -> "changed"
    }

    fun clearTarget() {
        targetAt = 0L
    }

    /** 读写结果落盘（含结果/原因，不含内容本身），供设置页「最近一次输入框读写」展示。 */
    private fun recordIo(action: FieldIoAction, outcome: FieldIoOutcome, pkg: String) {
        val repo = runCatching {
            (application as CryptoAssistantApplication).settingsRepository
        }.getOrNull() ?: return
        scope.launch { runCatching { repo.recordFieldIo(action, outcome, pkg) } }
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
    }

    private fun pasteInto(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
    }

    /** 结构探针：窗口规模、焦点/root 是否同窗、遍历计数与首个候选结构。只记结构，不记文字。 */
    private fun logTreeProbe(
        root: AccessibilityNodeInfo?,
        focus: AccessibilityNodeInfo?,
        pkg: String,
        reason: String,
        textLength: Int? = null,
    ) {
        val windowInfos = runCatching { windows }.getOrNull().orEmpty()
        val activeWindows = runCatching { windowInfos.count { it.isActive } }.getOrDefault(0)
        val summary = probeTree(root)
        val hiddenFocus = focus?.let {
            runCatching { !it.isVisibleToUser }.getOrDefault(false)
        } ?: false
        val readonly = focus?.let {
            runCatching { !it.isEditable }.getOrDefault(false)
        } ?: false
        AppLog.debug(
            "a11y.probe",
            "reason=$reason " + treeProbeFields(
                TreeProbeFacts(
                    rootWindowId = root?.windowId ?: -1,
                    focusWindowId = focus?.windowId ?: -1,
                    windowCount = windowInfos.size,
                    activeWindowCount = activeWindows,
                    visited = summary.visited,
                    rootChildCount = summary.rootChildCount,
                    rootClassName = summary.rootClassName,
                    editableAll = summary.editableAll,
                    editableVisible = summary.editableVisible,
                    editableInvisible = summary.editableInvisible,
                    inputLikeAll = summary.inputLikeAll,
                    inputLikeVisible = summary.inputLikeVisible,
                    hiddenFocus = hiddenFocus,
                    readonly = readonly,
                    textBucket = textLength?.let { LogFields.lengthBucket(it) } ?: "unknown",
                    firstClass = summary.firstClass,
                    firstViewId = summary.firstViewId,
                    firstLengthBucket = summary.firstLengthBucket,
                    firstInvisibleClass = summary.firstInvisibleClass,
                    firstInvisibleViewId = summary.firstInvisibleViewId,
                ),
            ) + " pkg=${FieldFacts.packageLabel(pkg)}",
        )
        runCatching { windowInfos.forEach { it.recycle() } }
    }

    /**
     * 遍历 root 做结构计数；最多看 [MAX_PROBE_NODES] 个，避免大页面卡住点球。
     *
     * - `editableAll/Visible/Invisible`：可编辑节点按可见性分桶（v1 的 `editableCandidates` 等于 Visible）；
     * - `inputLikeAll/Visible`：类名含 EditText/TextField 或 viewId 含 input/edit，**不看 `isEditable`**；
     * - 只对首个可见候选取长度档位；不可见节点只取类名/资源 id，不读文字；
     * - 密码框整个跳过，连类名都不记。
     */
    private fun probeTree(root: AccessibilityNodeInfo?): TreeProbeSummary {
        if (root == null) return TreeProbeSummary.EMPTY
        var visited = 0
        var editableAll = 0
        var editableVisible = 0
        var editableInvisible = 0
        var inputLikeAll = 0
        var inputLikeVisible = 0
        var firstClass: String? = null
        var firstViewId: String? = null
        var firstLengthBucket: String? = null
        var firstInvisibleClass: String? = null
        var firstInvisibleViewId: String? = null
        val rootChildCount = runCatching { root.childCount }.getOrDefault(0)
        val rootClassName = FieldFacts.classLabel(root.className?.toString())
        val queue = ArrayDeque<ProbeNode>()
        // root 由 rootInActiveWindow 返回，调用方负责生命周期；子节点是本函数取的，需要自己回收。
        queue.addLast(ProbeNode(root, owned = false))
        while (queue.isNotEmpty() && visited < MAX_PROBE_NODES) {
            val item = queue.removeFirst()
            val node = item.node
            visited++
            if (!isSensitiveInput(node)) {
                val editable = runCatching { node.isEditable }.getOrDefault(false)
                val visible = runCatching { node.isVisibleToUser }.getOrDefault(false)
                if (editable) {
                    editableAll++
                    if (visible) {
                        editableVisible++
                        if (firstClass == null) {
                            firstClass = FieldFacts.classLabel(node.className?.toString())
                            firstViewId = FieldFacts.viewIdLabel(node.viewIdResourceName)
                            firstLengthBucket = LogFields.lengthBucket(node.text?.length ?: 0)
                        }
                    } else {
                        editableInvisible++
                        if (firstInvisibleClass == null) {
                            firstInvisibleClass = FieldFacts.classLabel(node.className?.toString())
                            firstInvisibleViewId = FieldFacts.viewIdLabel(node.viewIdResourceName)
                        }
                    }
                }
                if (FieldFacts.looksLikeInputControl(node.className?.toString(), node.viewIdResourceName)) {
                    inputLikeAll++
                    if (visible) inputLikeVisible++
                }
            }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (index in 0 until childCount) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                queue.addLast(ProbeNode(child, owned = true))
            }
            if (item.owned) node.recycleIfNeeded()
        }
        while (queue.isNotEmpty()) {
            val leftover = queue.removeFirst()
            if (leftover.owned) leftover.node.recycleIfNeeded()
        }
        return TreeProbeSummary(
            visited = visited,
            rootChildCount = rootChildCount,
            rootClassName = rootClassName,
            editableAll = editableAll,
            editableVisible = editableVisible,
            editableInvisible = editableInvisible,
            inputLikeAll = inputLikeAll,
            inputLikeVisible = inputLikeVisible,
            firstClass = firstClass,
            firstViewId = firstViewId,
            firstLengthBucket = firstLengthBucket,
            firstInvisibleClass = firstInvisibleClass,
            firstInvisibleViewId = firstInvisibleViewId,
        )
    }

    private class ProbeNode(val node: AccessibilityNodeInfo, val owned: Boolean)

    private data class TreeProbeSummary(
        val visited: Int,
        val rootChildCount: Int,
        val rootClassName: String,
        val editableAll: Int,
        val editableVisible: Int,
        val editableInvisible: Int,
        val inputLikeAll: Int,
        val inputLikeVisible: Int,
        val firstClass: String?,
        val firstViewId: String?,
        val firstLengthBucket: String?,
        val firstInvisibleClass: String?,
        val firstInvisibleViewId: String?,
    ) {
        companion object {
            val EMPTY = TreeProbeSummary(
                visited = 0,
                rootChildCount = 0,
                rootClassName = FieldFacts.UNKNOWN,
                editableAll = 0,
                editableVisible = 0,
                editableInvisible = 0,
                inputLikeAll = 0,
                inputLikeVisible = 0,
                firstClass = null,
                firstViewId = null,
                firstLengthBucket = null,
                firstInvisibleClass = null,
                firstInvisibleViewId = null,
            )
        }
    }

    private fun AccessibilityNodeInfo.recycleIfNeeded() {
        // API 33 起 recycle 已废弃，节点由系统回收；这里兼容旧版本。
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            runCatching { recycle() }
        }
    }

    companion object {
        private const val TAG = "TextA11y"
        /** 回读阶梯的绝对时点（ms）：150ms 先判，未生效再等到 800ms 判一次。 */
        private val READBACK_STEPS_MS = longArrayOf(150L, 800L)
        private const val MAX_WEBVIEW_DEPTH = 8
        /** 结构探针最多访问的节点数：只用于诊断，不能因为页面大而拖慢点球。 */
        private const val MAX_PROBE_NODES = 300

        @Volatile
        var instance: TextAccessibilityService? = null
            private set

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        /** 服务是否真的连着（比读系统设置更即时）。 */
        fun isConnected(): Boolean = instance != null

        /**
         * 系统设置里是否已启用本服务。用户从系统设置返回时用它刷新状态，
         * 因为那时服务可能还没回调 onServiceConnected。
         */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, TextAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}

/**
 * 敏感输入框判定（合规硬要求：密码框/PIN 一律不读不写）。
 *
 * `isPasswordFlag` 是框架从 inputType 推导的主判据；这里额外直接看 inputType 变体，
 * 覆盖 isPassword 可能未覆盖的写法（如可见密码）。纯函数，单元测试覆盖。
 */
internal fun isSensitiveInput(isPasswordFlag: Boolean, inputType: Int): Boolean {
    if (isPasswordFlag) return true
    val cls = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (cls) {
        InputType.TYPE_CLASS_TEXT ->
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }
}

/** 节点包装：`inputType` 读不到（非编辑框或系统裁剪）时退化为只看 `isPassword`。 */
internal fun isSensitiveInput(node: AccessibilityNodeInfo): Boolean =
    isSensitiveInput(
        node.isPassword,
        runCatching { node.inputType }.getOrDefault(0),
    )
