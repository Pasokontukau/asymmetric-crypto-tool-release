package com.cryptoassistant.app.overlay

import com.cryptoassistant.app.data.BallWorkMode

/**
 * 悬浮球两种工作模式下的交互决策（纯逻辑，无 Android 依赖，可被 JVM 单测覆盖）。
 *
 * 背景：便捷模式读「当前输入框」，标准模式只读「剪贴板」。把点球落点抽成纯函数，
 * 是为了让两条链路的分叉只有一处、并且能被测试锁住——尤其是「标准模式永远不碰输入框」
 * 这条边界。
 */

/**
 * 一次「临时取得焦点后读剪贴板」的结论。
 *
 * 之所以要分三档，而不是像以前那样把「空」和「读不到」都塞进 null：
 * 标准模式下每次点球都要读剪贴板，提示语必须分得开，否则用户会误以为是应用坏了。
 */
sealed interface FocusedClipboard {
    /** 读到了非空文字。 */
    data class Content(val text: String) : FocusedClipboard

    /** 拿到了焦点，但剪贴板里没有文字（系统也可能静默返回空）。 */
    data object Empty : FocusedClipboard

    /** 连窗口焦点都没拿到，这次读取作废。 */
    data object NoFocus : FocusedClipboard
}

/** 剪贴板内容的四分类（只保留结论，不携带明文）。 */
enum class ClipboardOutcome { CIPHER, PLAINTEXT, EMPTY, UNREADABLE }

/**
 * 把一次读取折算成结论。[looksLikeCipher] 由调用方注入（项目里是 `TextActionUseCase.inspect`），
 * 这样本文件仍然与密码学实现解耦。
 */
fun clipboardOutcome(
    read: FocusedClipboard,
    looksLikeCipher: (String) -> Boolean,
): ClipboardOutcome = when (read) {
    is FocusedClipboard.Content ->
        if (looksLikeCipher(read.text)) ClipboardOutcome.CIPHER else ClipboardOutcome.PLAINTEXT
    FocusedClipboard.Empty -> ClipboardOutcome.EMPTY
    FocusedClipboard.NoFocus -> ClipboardOutcome.UNREADABLE
}

/** 便捷模式单击之后的落点；标准模式单击固定打开文字输入面板，不走这里。 */
sealed interface TapPlan {
    /** 剪贴板是密文：静默解密，球拉长为胶囊显示明文。 */
    data object DecryptClipboard : TapPlan

    /** 剪贴板是明文且已有接收方：加密后放回剪贴板，不写任何输入框。 */
    data object EncryptClipboard : TapPlan

    /** 不该自动动手：只给一条带下一步的提示（空 / 读不到 / 明文但没有接收方）。 */
    data class NothingToDo(val outcome: ClipboardOutcome) : TapPlan
}

/**
 * 便捷模式的剪贴板兜底决策（用于无障碍已连接但当前界面没有可用输入框，
 * 或引导面板被系统拒绝后台拉起）。
 *
 * 2026-11-02 起，剪贴板是明文且已有接收方时不再只提示，而是就地加密并放回剪贴板
 * （W1）：微信等读不到输入框的应用里，用户复制一段话再点球即可得到密文。
 * 没有接收方时不猜人、不改剪贴板，只提示先长按球选联系人（W3）。
 * 标准模式不经过这里。
 */
fun quickTapPlan(outcome: ClipboardOutcome, hasRecipient: Boolean): TapPlan = when {
    outcome == ClipboardOutcome.CIPHER -> TapPlan.DecryptClipboard
    outcome == ClipboardOutcome.PLAINTEXT && hasRecipient -> TapPlan.EncryptClipboard
    else -> TapPlan.NothingToDo(outcome)
}

/**
 * 便捷模式剪贴板兜底提示的种类。
 *
 * [prefix] 解释“为什么没走输入框”，由调用方拼在整句前面；具体文案按当前语言从资源取，
 * 纯逻辑层不持有中文。
 */
enum class ClipboardFallback { ENCRYPTED_TO, CHOOSE_CONTACT, COPY_FIRST, UNREADABLE, DECRYPTED }

fun clipboardFallbackKind(
    outcome: ClipboardOutcome,
    hasRecipient: Boolean,
): ClipboardFallback = when (outcome) {
    ClipboardOutcome.PLAINTEXT ->
        if (hasRecipient) ClipboardFallback.ENCRYPTED_TO else ClipboardFallback.CHOOSE_CONTACT

    ClipboardOutcome.EMPTY -> ClipboardFallback.COPY_FIRST
    ClipboardOutcome.UNREADABLE -> ClipboardFallback.UNREADABLE
    ClipboardOutcome.CIPHER -> ClipboardFallback.DECRYPTED
}

/**
 * 便捷模式点球、但无障碍服务没连上时，是否拉起专用引导面板。
 *
 * <p>「系统设置里已启用」和「服务真的在运行」是两件事：应用更新、强制停止、ROM 回收都会让
 * 服务停掉，而设置里仍写着已启用。此前的实现只在“未启用”时提示一次，之后静默改读剪贴板，
 * 用户会以为球坏了。T13 起规则固定为：只要服务没连上，**每次**点球都拉起引导面板；
 * 面板里可选择去开启/修复无障碍、关掉便捷模式，或明确选择“先按剪贴板处理”一次。</p>
 *
 * 服务已连接但当前界面没有可用输入框（未聚焦/空/密码框）不算“未连接”：
 * 仍走剪贴板兜底，不弹引导，避免每次点在聊天列表上都盖一层面板。
 */
fun shouldShowA11yGuide(serviceConnected: Boolean): Boolean = !serviceConnected

/**
 * 未连接时要区分「系统里没启用」和「系统里启用但服务没运行」：修复动作不同
 * （去开启 vs 关掉再重开一次），提示文案也不能混。
 */
enum class A11yUnavailableNotice { NOT_ENABLED, SERVICE_NOT_RUNNING }

fun a11yUnavailableNotice(enabledInSettings: Boolean): A11yUnavailableNotice =
    if (enabledInSettings) {
        A11yUnavailableNotice.SERVICE_NOT_RUNNING
    } else {
        A11yUnavailableNotice.NOT_ENABLED
    }

