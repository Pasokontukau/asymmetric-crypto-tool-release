package com.cryptoassistant.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮球两种工作模式的交互矩阵（对应《悬浮球权限双模式规划》§4 与 §9.1）。
 *
 * 标准模式单击固定打开文字输入面板（不读剪贴板、不读输入框），
 * 因此这里只测便捷模式的剪贴板决策；长按菜单已精简为只选接收方，不再有动作行。
 */
class BallInteractionTest {

    // region 便捷模式：读不到输入框时只走剪贴板分支

    @Test
    fun `便捷模式 明文有接收方时就地加密，无接收方才提示`() {
        // T15 之后不再有 hasField=true 分支：便捷模式能读到输入框时会直接走静默写回，
        // 不会回头调用这个决策函数。
        assertEquals(
            TapPlan.DecryptClipboard,
            quickTapPlan(ClipboardOutcome.CIPHER, hasRecipient = false),
        )
        // 密文解密不依赖接收方。
        assertEquals(
            TapPlan.DecryptClipboard,
            quickTapPlan(ClipboardOutcome.CIPHER, hasRecipient = true),
        )
        // W1：已有接收方时直接把明文加密回剪贴板，不弹面板、不写输入框。
        assertEquals(
            TapPlan.EncryptClipboard,
            quickTapPlan(ClipboardOutcome.PLAINTEXT, hasRecipient = true),
        )
        // 没有接收方时不猜人、不改剪贴板，只提示先选联系人。
        assertEquals(
            TapPlan.NothingToDo(ClipboardOutcome.PLAINTEXT),
            quickTapPlan(ClipboardOutcome.PLAINTEXT, hasRecipient = false),
        )
        assertEquals(
            TapPlan.NothingToDo(ClipboardOutcome.EMPTY),
            quickTapPlan(ClipboardOutcome.EMPTY, hasRecipient = true),
        )
        assertEquals(
            TapPlan.NothingToDo(ClipboardOutcome.UNREADABLE),
            quickTapPlan(ClipboardOutcome.UNREADABLE, hasRecipient = false),
        )
    }

    @Test
    fun `剪贴板兜底种类覆盖四种结果`() {
        // 文案本身按当前语言取资源；纯逻辑层只决定“该说哪一句”。
        assertEquals(
            ClipboardFallback.ENCRYPTED_TO,
            clipboardFallbackKind(ClipboardOutcome.PLAINTEXT, hasRecipient = true),
        )
        assertEquals(
            ClipboardFallback.CHOOSE_CONTACT,
            clipboardFallbackKind(ClipboardOutcome.PLAINTEXT, hasRecipient = false),
        )
        assertEquals(
            ClipboardFallback.COPY_FIRST,
            clipboardFallbackKind(ClipboardOutcome.EMPTY, hasRecipient = true),
        )
        assertEquals(
            ClipboardFallback.UNREADABLE,
            clipboardFallbackKind(ClipboardOutcome.UNREADABLE, hasRecipient = false),
        )
        assertEquals(
            ClipboardFallback.DECRYPTED,
            clipboardFallbackKind(ClipboardOutcome.CIPHER, hasRecipient = false),
        )
    }

    // endregion

    // region 读取结论折算

    @Test
    fun `读取结论按内容与密文判定折算`() {
        val looksLikeCipher = { text: String -> text.startsWith("CIPHER:") }
        assertEquals(
            ClipboardOutcome.CIPHER,
            clipboardOutcome(FocusedClipboard.Content("CIPHER:abc"), looksLikeCipher),
        )
        assertEquals(
            ClipboardOutcome.PLAINTEXT,
            clipboardOutcome(FocusedClipboard.Content("普通文字"), looksLikeCipher),
        )
        assertEquals(
            ClipboardOutcome.EMPTY,
            clipboardOutcome(FocusedClipboard.Empty, looksLikeCipher),
        )
        assertEquals(
            ClipboardOutcome.UNREADABLE,
            clipboardOutcome(FocusedClipboard.NoFocus, looksLikeCipher),
        )
    }

    // endregion

    // region 无障碍未连接时的点球引导（T13：每次点球都拉起专用面板）

    @Test
    fun `无障碍已连接时点球不弹引导`() {
        assertFalse(shouldShowA11yGuide(serviceConnected = true))
    }

    @Test
    fun `无障碍未连接时每次都弹引导`() {
        // 没有“只弹一次”的状态：调用两次结果必须相同。
        assertTrue(shouldShowA11yGuide(serviceConnected = false))
        assertTrue(shouldShowA11yGuide(serviceConnected = false))
    }

    @Test
    fun `未连接提示要区分未启用与服务没运行`() {
        assertEquals(
            A11yUnavailableNotice.NOT_ENABLED,
            a11yUnavailableNotice(enabledInSettings = false),
        )
        assertEquals(
            A11yUnavailableNotice.SERVICE_NOT_RUNNING,
            a11yUnavailableNotice(enabledInSettings = true),
        )
    }

    // endregion
}
