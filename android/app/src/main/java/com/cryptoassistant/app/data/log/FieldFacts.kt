package com.cryptoassistant.app.data.log

/**
 * 一次输入框读写的**结构事实**，只用于诊断日志与设置页状态。
 *
 * 隐私边界（与 [AppLog] 一致）：
 * - 只包含「哪个应用、哪类控件、哪个窗口/资源 id、是否有选区、长度档位、是否在 WebView 内」；
 * - 不持有、也不输出输入框里的文字；长度只保留 [LogFields.lengthBucket] 的档位；
 * - 密码框在构造前就被跳过，`lengthBucket=hidden`，不会因为诊断去读敏感输入框。
 *
 * 这些字段是排查「微信写回看似成功但没生效」的关键：`cls`/`vid`/`web` 能区分自绘控件与
 * 标准 `EditText`，`win`/`sel`/`len` 能区分窗口重建、部分选区与输入法组合文本。
 */
data class FieldFacts(
    val packageName: String,
    val className: String,
    val viewId: String?,
    val windowId: Int,
    val editable: Boolean,
    val password: Boolean,
    /** 节点是否对用户可见；读写都成功但 `vis=false` 时，命中的可能是隐藏/重复编辑节点。 */
    val visible: Boolean,
    val hasSelection: Boolean,
    val lengthBucket: String,
    val selectionBucket: String,
    val inWebView: Boolean,
) {

    /** 单行字段串；字段顺序固定，便于日志阅读与 grep。 */
    fun fields(): String = buildString {
        append("pkg=").append(packageName)
        append(" cls=").append(className)
        append(" vid=").append(viewId ?: "-")
        append(" win=").append(windowId)
        append(" edit=").append(editable)
        append(" pwd=").append(password)
        append(" vis=").append(visible)
        append(" sel=").append(hasSelection)
        append(" selLen=").append(selectionBucket)
        append(" len=").append(lengthBucket)
        append(" web=").append(inWebView)
    }

    companion object {
        const val UNKNOWN = "unknown"
        private const val HIDDEN_LENGTH = "hidden"
        private val NON_PACKAGE = Regex("[^A-Za-z0-9_.]")
        private val NON_CLASS = Regex("[^A-Za-z0-9_]")
        private val NON_VIEW_ID = Regex("[^A-Za-z0-9_.-]")

        /** 包名只保留 `[A-Za-z0-9_.]`，空值/怪值归一为 [UNKNOWN]。 */
        fun packageLabel(raw: String?): String {
            val cleaned = raw.orEmpty()
                .trim()
                .replace(NON_PACKAGE, "")
                .take(64)
            return cleaned.ifBlank { UNKNOWN }
        }

        /** 类名只保留简单名（`android.widget.EditText` → `EditText`）。 */
        fun classLabel(raw: String?): String {
            val simple = raw.orEmpty()
                .substringAfterLast('.')
                .substringAfterLast('$')
                .replace(NON_CLASS, "")
                .take(32)
            return simple.ifBlank { UNKNOWN }
        }

        /** 资源 id 去掉 `包名:id/` 前缀，只留控件名，避免日志被长串占满。 */
        fun viewIdLabel(raw: String?): String? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            val short = value.substringAfter(":id/", value)
                .replace(NON_VIEW_ID, "")
                .take(40)
            return short.ifBlank { null }
        }

        /** 结构意义上的“像输入控件”：只看类名/资源 id，不看 `isEditable`，也不读文字。 */
        fun looksLikeInputControl(className: String?, viewId: String?): Boolean {
            val cls = className.orEmpty()
            if (cls.contains("EditText") || cls.contains("TextField")) return true
            val id = viewId.orEmpty().lowercase()
            return id.contains("input") || id.contains("edit")
        }

        /** 没有焦点控件/没有 root 时的占位，只保留前台包名（如果拿到了）。 */
        fun unknown(packageName: String? = null): FieldFacts = FieldFacts(
            packageName = packageLabel(packageName),
            className = UNKNOWN,
            viewId = null,
            windowId = -1,
            editable = false,
            password = false,
            visible = false,
            hasSelection = false,
            lengthBucket = UNKNOWN,
            selectionBucket = "-",
            inWebView = false,
        )

        /** 密码框等“不能读文字”的节点：只记录结构，长度标记为 hidden。 */
        fun withoutText(
            packageName: String?,
            className: String?,
            viewId: String?,
            windowId: Int,
            editable: Boolean,
            password: Boolean,
            visible: Boolean,
            inWebView: Boolean,
        ): FieldFacts = FieldFacts(
            packageName = packageLabel(packageName),
            className = classLabel(className),
            viewId = viewIdLabel(viewId),
            windowId = windowId,
            editable = editable,
            password = password,
            visible = visible,
            hasSelection = false,
            lengthBucket = HIDDEN_LENGTH,
            selectionBucket = "-",
            inWebView = inWebView,
        )
    }
}

/** 写回前/回读时，快照文字与当前文字的差异类别（只记录类别，不记录文字）。 */
enum class FieldTextMatch(val label: String) {
    /** 完全一致。 */
    EXACT("exact"),

    /** 仅首尾空白不同（现有比较已用 trim，属于可继续的情况）。 */
    TRIM_EQUAL("trim_equal"),

    /** 去掉零宽字符后一致：输入法/应用插入了不可见标记。 */
    INVISIBLE_ONLY("invisible_only"),

    /** 快照只是整框的一部分（部分选区），整框里仍能找到它。 */
    SELECTION_PART("selection_part"),

    /** 真实内容不同：用户改过字，或应用/输入法提交了新内容。 */
    DIFFERENT("different"),
}

private val INVISIBLE_CHARS = Regex("[\\u200B-\\u200F\\u2060\\uFEFF]")

/**
 * 判定快照文字与当前文字的关系。
 *
 * @param expectedWasSelection 读取时是否是选区文字；为 true 时额外识别“整框包含选区”的情况。
 * D14-A 之后点球恒读整框，写回路径一律传 `false`；该分支只为兼容旧日志语义
 * （历史日志里出现过 `cmp=selection_part`，需要能对上）与将来的分段读取场景保留。
 */
fun fieldTextMatch(
    expected: String,
    current: String,
    expectedWasSelection: Boolean,
): FieldTextMatch {
    if (expected == current) return FieldTextMatch.EXACT
    if (expected.trim() == current.trim()) return FieldTextMatch.TRIM_EQUAL
    val expectedClean = expected.replace(INVISIBLE_CHARS, "").trim()
    val currentClean = current.replace(INVISIBLE_CHARS, "").trim()
    if (expectedClean == currentClean) return FieldTextMatch.INVISIBLE_ONLY
    if (expectedWasSelection && expected.isNotEmpty() && current.contains(expected)) {
        return FieldTextMatch.SELECTION_PART
    }
    return FieldTextMatch.DIFFERENT
}

/** 比对结论的结构化字段：类别 + 两侧长度档位，不含文字与精确长度。 */
fun fieldCompareFields(
    expected: String,
    current: String,
    expectedWasSelection: Boolean,
): String {
    val match = fieldTextMatch(expected, current, expectedWasSelection)
    return "cmp=${match.label} " +
        "snapLen=${LogFields.lengthBucket(expected.length)} " +
        "curLen=${LogFields.lengthBucket(current.length)}"
}

/** 三种“内容未变”的比对结果：可以安全写入。 */
val FieldTextMatch.matchesContent: Boolean
    get() = this == FieldTextMatch.EXACT ||
        this == FieldTextMatch.TRIM_EQUAL ||
        this == FieldTextMatch.INVISIBLE_ONLY

/**
 * 写回前的核对结论。
 *
 * 写回是“拿读取时的快照”去替换“当前焦点框”，两者之间用户可能已经改了字、
 * 应用可能重建了节点。核对的作用是：**宁可把结果放进剪贴板，也不覆盖用户刚输入的文字**。
 */
enum class WriteGuard(val label: String, val proceed: Boolean) {
    /** 内容未变，可以写。 */
    PROCEED("proceed", true),

    /** 首次比对不一致，`node.refresh()` 重读后一致，可以写。 */
    PROCEED_AFTER_REFRESH("proceed_after_refresh", true),

    /** 首次比对不一致：允许 refresh 后重读一次再判（目前 `tries=1` 的来源）。 */
    RETRY("retry", false),

    /** 重读后仍不一致：内容真的变了，放弃写入。 */
    ABORT_CHANGED("abort_changed", false),

    /** 重读拿不到文字（节点被回收/不可读），无法核对，放弃写入。 */
    ABORT_UNREADABLE("abort_unreadable", false),
}

/**
 * 写前核对（纯函数，JVM 可测）。
 *
 * @param expected 读取时的快照文字（D14-A 之后恒为整框）
 * @param current 当前读到的文字；`null` 表示读不到
 * @param afterRefresh 这一次是否是 `node.refresh()` + 重读之后的第二次判定
 *
 * 只重试**核对**，不重试**写入**：写动作返回成功却没生效时再写一次可能写两遍或破坏格式。
 */
fun writeGuard(
    expected: String,
    current: String?,
    afterRefresh: Boolean = false,
): WriteGuard {
    if (current == null) return WriteGuard.ABORT_UNREADABLE
    val same = fieldTextMatch(expected, current, expectedWasSelection = false).matchesContent
    return when {
        same && afterRefresh -> WriteGuard.PROCEED_AFTER_REFRESH
        same -> WriteGuard.PROCEED
        afterRefresh -> WriteGuard.ABORT_CHANGED
        else -> WriteGuard.RETRY
    }
}

/**
 * 结构探针日志（T17 v2，只描述窗口与节点结构，不含任何文字）。
 *
 * v1 只数“可见且可编辑”，微信的 `editableCandidates=0` 分不清“没有暴露输入框”（H6-B）
 * 与“输入框存在但被标成不可见”（H6-D）。v2 补 `editableAll`/`editableInvisible`，
 * 以及不看 `isEditable` 的 `inputLikeAll`/`inputLikeVisible`，只加计数，不改变读写。
 */
data class TreeProbeFacts(
    val rootWindowId: Int,
    val focusWindowId: Int,
    val windowCount: Int,
    val activeWindowCount: Int,
    val visited: Int,
    val rootChildCount: Int,
    val rootClassName: String,
    val editableAll: Int,
    val editableVisible: Int,
    val editableInvisible: Int,
    val inputLikeAll: Int,
    val inputLikeVisible: Int,
    val hiddenFocus: Boolean,
    val readonly: Boolean,
    val textBucket: String,
    val firstClass: String? = null,
    val firstViewId: String? = null,
    val firstLengthBucket: String? = null,
    val firstInvisibleClass: String? = null,
    val firstInvisibleViewId: String? = null,
)

fun treeProbeFields(facts: TreeProbeFacts): String = buildString {
    append("probeVer=2")
    append(" rootWin=").append(facts.rootWindowId)
    append(" focusWin=").append(facts.focusWindowId)
    append(" sameWin=").append(facts.rootWindowId == facts.focusWindowId)
    append(" windows=").append(facts.windowCount)
    append(" activeWins=").append(facts.activeWindowCount)
    append(" visited=").append(facts.visited)
    append(" rootKids=").append(facts.rootChildCount)
    append(" rootCls=").append(facts.rootClassName)
    // 兼容 v1 的字段名：editableCandidates 等于 editableVisible。
    append(" editableCandidates=").append(facts.editableVisible)
    append(" editableAll=").append(facts.editableAll)
    append(" editableVisible=").append(facts.editableVisible)
    append(" editableInvisible=").append(facts.editableInvisible)
    append(" inputLikeAll=").append(facts.inputLikeAll)
    append(" inputLikeVisible=").append(facts.inputLikeVisible)
    append(" hiddenFocus=").append(facts.hiddenFocus)
    append(" readonly=").append(facts.readonly)
    append(" textBucket=").append(facts.textBucket)
    if (facts.editableVisible > 0) {
        append(" firstCls=").append(facts.firstClass ?: "unknown")
        append(" firstVid=").append(facts.firstViewId ?: "-")
        append(" firstLen=").append(facts.firstLengthBucket ?: "?")
    }
    if (facts.editableInvisible > 0) {
        append(" firstInvisCls=").append(facts.firstInvisibleClass ?: "unknown")
        append(" firstInvisVid=").append(facts.firstInvisibleViewId ?: "-")
    }
}

/**
 * 写后回读阶梯的判定结果。
 *
 * 写动作返回 true 只说明“动作被系统接受”，不等于“文字真的进了输入框”：
 * 微信这类 WebView/自绘控件会接受动作却不应用，备忘录这类会延迟 800ms 才生效。
 * 所以成功必须由回读确认，且要能区分“没写进去”与“无法判定”。
 */
enum class ReadbackVerdict(val label: String, val success: Boolean) {
    /** 150ms 回读已应用。 */
    EARLY_OK("early_ok", true),

    /** 800ms 才应用（WebView 类延迟生效）。 */
    LATE_OK("late_ok", true),

    /** 两次回读都还是写前的内容：动作被接受但没应用。 */
    NOT_APPLIED("not_applied", false),

    /** 期间切了应用、焦点不可读，或用户自己改了字：无法判定，不算失败也不能覆盖。 */
    ABANDONED("abandoned", false),

    /** 150ms 尚未得出结论，等 800ms 那一次（仅中间态，不会成为最终结果）。 */
    PENDING("pending", false),
}

/**
 * 回读阶梯的单步判定（纯函数，JVM 可测）。
 *
 * @param expected 刚写入的替换串
 * @param snapshot 写入前的原文（用来区分“还没生效”与“被改成了别的”）
 * @param observed 本次回读到的文字；`null` 表示读不到
 * @param samePkg 焦点是否仍在写入时的那个应用
 * @param finalSample 这是不是最后一跳（800ms）；否则只给 `PENDING` 继续等
 *
 * 四条硬规则：
 * 1. 切了应用就 `ABANDONED`，不在别的应用里把“碰巧相同”当成功；
 * 2. 文字仍是原文 → 没生效：还有下一跳就等，最后一跳才 `NOT_APPLIED`；
 * 3. 文字既不是替换串也不是原文 → 用户/应用改过，`ABANDONED`（不当失败，更不能重写）；
 * 4. 读不到文字 → 还有下一跳就等，最后一跳 `ABANDONED`。
 */
fun judgeReadback(
    expected: String,
    snapshot: String,
    observed: String?,
    samePkg: Boolean,
    finalSample: Boolean,
): ReadbackVerdict {
    if (!samePkg) return ReadbackVerdict.ABANDONED
    if (observed == null) {
        return if (finalSample) ReadbackVerdict.ABANDONED else ReadbackVerdict.PENDING
    }
    if (fieldTextMatch(expected, observed, expectedWasSelection = false).matchesContent) {
        return if (finalSample) ReadbackVerdict.LATE_OK else ReadbackVerdict.EARLY_OK
    }
    if (fieldTextMatch(snapshot, observed, expectedWasSelection = false).matchesContent) {
        return if (finalSample) ReadbackVerdict.NOT_APPLIED else ReadbackVerdict.PENDING
    }
    return ReadbackVerdict.ABANDONED
}
