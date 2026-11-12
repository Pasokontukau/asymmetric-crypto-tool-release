package com.cryptoassistant.app.data.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入框诊断字段的脱敏与比对规则。
 *
 * 这些字段会直接进入日志，所以测试同时锁住两件事：结构信息可读、
 * 但任何文字内容或精确长度都不能出现。
 */
class FieldFactsTest {

    private fun facts(
        textLength: Int = 12,
        selectionLength: Int = 0,
    ) = FieldFacts(
        packageName = FieldFacts.packageLabel("com.tencent.mm"),
        className = FieldFacts.classLabel("android.widget.EditText"),
        viewId = FieldFacts.viewIdLabel("com.tencent.mm:id/chat_input"),
        windowId = 42,
        editable = true,
        password = false,
        visible = true,
        hasSelection = selectionLength > 0,
        lengthBucket = LogFields.lengthBucket(textLength),
        selectionBucket = if (selectionLength > 0) {
            LogFields.lengthBucket(selectionLength)
        } else {
            "-"
        },
        inWebView = false,
    )

    @Test
    fun `package label keeps only safe characters`() {
        assertEquals("com.tencent.mm", FieldFacts.packageLabel("com.tencent.mm"))
        assertEquals("com.examplesecret", FieldFacts.packageLabel("com.example\nsecret"))
        assertEquals(FieldFacts.UNKNOWN, FieldFacts.packageLabel(""))
        assertEquals(FieldFacts.UNKNOWN, FieldFacts.packageLabel(null))
        assertEquals(64, FieldFacts.packageLabel("a".repeat(100)).length)
    }

    @Test
    fun `class label uses simple name only`() {
        assertEquals("EditText", FieldFacts.classLabel("android.widget.EditText"))
        assertEquals("MMEditText", FieldFacts.classLabel("com.tencent.mm.ui.MMEditText"))
        assertEquals(FieldFacts.UNKNOWN, FieldFacts.classLabel(""))
        assertEquals(FieldFacts.UNKNOWN, FieldFacts.classLabel(null))
    }

    @Test
    fun `view id drops package prefix`() {
        assertEquals("chat_input", FieldFacts.viewIdLabel("com.tencent.mm:id/chat_input"))
        assertEquals("message_text", FieldFacts.viewIdLabel("message_text"))
        assertNull(FieldFacts.viewIdLabel(null))
        assertNull(FieldFacts.viewIdLabel("   "))
    }

    @Test
    fun `formatted fields carry structure but no text`() {
        val line = facts().fields()
        assertTrue(line.contains("pkg=com.tencent.mm"))
        assertTrue(line.contains("cls=EditText"))
        assertTrue(line.contains("vid=chat_input"))
        assertTrue(line.contains("win=42"))
        assertTrue(line.contains("vis=true"))
        assertTrue(line.contains("sel=false"))
        assertTrue(line.contains("len=1-16"))
        assertTrue(line.contains("web=false"))
        assertFalse(line.contains("secret"))
    }

    @Test
    fun `selection is expressed as a length bucket`() {
        val line = facts(textLength = 40, selectionLength = 20).fields()
        assertTrue(line.contains("sel=true"))
        assertTrue(line.contains("selLen=17-64"))
        assertTrue(line.contains("len=17-64"))
    }

    @Test
    fun `unknown node keeps foreground package but no fake numbers`() {
        val line = FieldFacts.unknown("com.tencent.mm").fields()
        assertTrue(line.contains("pkg=com.tencent.mm"))
        assertTrue(line.contains("cls=unknown"))
        assertTrue(line.contains("vis=false"))
        assertTrue(line.contains("vid=-"))
        assertTrue(line.contains("win=-1"))
        assertTrue(line.contains("len=unknown"))
    }

    @Test
    fun `password node never exposes a length`() {
        val hidden = FieldFacts.withoutText(
            packageName = "com.example.bank",
            className = "android.widget.EditText",
            viewId = "com.example.bank:id/password",
            windowId = 7,
            editable = true,
            password = true,
            visible = true,
            inWebView = false,
        )
        assertEquals("hidden", hidden.lengthBucket)
        assertEquals("-", hidden.selectionBucket)
        assertFalse(hidden.hasSelection)
        assertTrue(hidden.fields().contains("pwd=true"))
    }

    @Test
    fun `text match separates whitespace invisible chars and selections`() {
        assertEquals(FieldTextMatch.EXACT, fieldTextMatch("abc", "abc", false))
        assertEquals(FieldTextMatch.TRIM_EQUAL, fieldTextMatch("abc", " abc ", false))
        assertEquals(
            FieldTextMatch.INVISIBLE_ONLY,
            fieldTextMatch("abc", "a\u200Bb\uFEFFc", false),
        )
        assertEquals(
            FieldTextMatch.SELECTION_PART,
            fieldTextMatch("abc", "xxabcxx", true),
        )
        assertEquals(FieldTextMatch.DIFFERENT, fieldTextMatch("abc", "xyz", false))
        assertEquals(FieldTextMatch.DIFFERENT, fieldTextMatch("abc", "xxabcxx", false))
    }

    @Test
    fun `compare fields expose buckets not exact lengths`() {
        val line = fieldCompareFields("abcdef", " abcdef ", false)
        assertEquals("cmp=trim_equal snapLen=1-16 curLen=1-16", line)
        assertFalse(line.contains("abcdef"))
    }

    @Test
    fun `write guard proceeds when the whole field is unchanged`() {
        // D14-A：快照恒为整框，选区不参与比较——整框一致就该写。
        assertEquals(WriteGuard.PROCEED, writeGuard("输入框整段文字", "输入框整段文字"))
        assertEquals(WriteGuard.PROCEED, writeGuard("abc", " abc "))
        assertEquals(WriteGuard.PROCEED, writeGuard("abc", "a\u200Bbc"))
        assertTrue(WriteGuard.PROCEED.proceed)
    }

    @Test
    fun `write guard retries the check once before giving up`() {
        // 用户又打了字：首次不一致只允许 refresh 重读一次，重读后仍不一致就放弃，不写入。
        assertEquals(WriteGuard.RETRY, writeGuard("abc", "abcd"))
        assertEquals(
            WriteGuard.PROCEED_AFTER_REFRESH,
            writeGuard("abc", "abc", afterRefresh = true),
        )
        assertEquals(WriteGuard.ABORT_CHANGED, writeGuard("abc", "abcd", afterRefresh = true))
        assertFalse(WriteGuard.RETRY.proceed)
        assertFalse(WriteGuard.ABORT_CHANGED.proceed)
        assertTrue(WriteGuard.PROCEED_AFTER_REFRESH.proceed)
    }

    @Test
    fun `write guard never guesses a selection snapshot`() {
        // 旧行为是读选区（"明文"）却跟整框（"明文****"）比，必然 selection_part。
        // 现在调用方一律传 false：包含关系只能算“内容变了”，不会猜成“选区拼接”而继续写。
        assertEquals(FieldTextMatch.DIFFERENT, fieldTextMatch("明文", "明文****", false))
        assertFalse(fieldTextMatch("明文", "明文****", false).matchesContent)
        assertEquals(WriteGuard.RETRY, writeGuard("明文", "明文****"))
        assertEquals(WriteGuard.ABORT_CHANGED, writeGuard("明文", "明文****", afterRefresh = true))
    }

    @Test
    fun `write guard aborts when the field cannot be read`() {
        assertEquals(WriteGuard.ABORT_UNREADABLE, writeGuard("abc", null))
        assertFalse(WriteGuard.ABORT_UNREADABLE.proceed)
    }

    @Test
    fun `readback ladder confirms success at the first sample`() {
        // 150ms 已应用：QQ 这类即时生效，不再等 800ms。
        assertEquals(
            ReadbackVerdict.EARLY_OK,
            judgeReadback("密文", "明文", "密文", samePkg = true, finalSample = false),
        )
        // trim / 不可见字符差异也算已应用（与写前核对同一套归一规则）。
        assertEquals(
            ReadbackVerdict.EARLY_OK,
            judgeReadback("密文", "明文", " 密文 ", samePkg = true, finalSample = false),
        )
        assertTrue(ReadbackVerdict.EARLY_OK.success)
    }

    @Test
    fun `readback ladder waits when the write has not landed yet`() {
        // 仍是原文：还有 800ms 那一跳，先给 PENDING（备忘录 WebView 的延迟生效）。
        assertEquals(
            ReadbackVerdict.PENDING,
            judgeReadback("密文", "明文", "明文", samePkg = true, finalSample = false),
        )
        // 800ms 才看到新内容 → late_ok，而不是失败。
        assertEquals(
            ReadbackVerdict.LATE_OK,
            judgeReadback("密文", "明文", "密文", samePkg = true, finalSample = true),
        )
        assertTrue(ReadbackVerdict.LATE_OK.success)
    }

    @Test
    fun `readback ladder reports not applied only after the last sample`() {
        assertEquals(
            ReadbackVerdict.NOT_APPLIED,
            judgeReadback("密文", "明文", "明文", samePkg = true, finalSample = true),
        )
        assertFalse(ReadbackVerdict.NOT_APPLIED.success)
    }

    @Test
    fun `readback ladder abandons when the text was changed`() {
        // 既不是替换串也不是原文：用户自己改了字，不能覆盖，也不该报“没写进去”。
        assertEquals(
            ReadbackVerdict.ABANDONED,
            judgeReadback("密文", "明文", "明文加了一句", samePkg = true, finalSample = false),
        )
        assertEquals(
            ReadbackVerdict.ABANDONED,
            judgeReadback("密文", "明文", "明文加了一句", samePkg = true, finalSample = true),
        )
        assertFalse(ReadbackVerdict.ABANDONED.success)
    }

    @Test
    fun `readback ladder abandons when the foreground app changed`() {
        // 内容碰巧相同也不能当成功：写入的框已经不在前台了。
        assertEquals(
            ReadbackVerdict.ABANDONED,
            judgeReadback("密文", "明文", "密文", samePkg = false, finalSample = false),
        )
    }

    @Test
    fun `tree probe v2 describes structure without text`() {
        // T17 v2：只加结构计数；不能因为诊断把输入框文字写进日志。
        val line = treeProbeFields(
            TreeProbeFacts(
                rootWindowId = 7,
                focusWindowId = 7,
                windowCount = 3,
                activeWindowCount = 1,
                visited = 12,
                rootChildCount = 2,
                rootClassName = "FrameLayout",
                editableAll = 2,
                editableVisible = 2,
                editableInvisible = 0,
                inputLikeAll = 2,
                inputLikeVisible = 2,
                hiddenFocus = false,
                readonly = false,
                textBucket = "1-16",
                firstClass = "EditText",
                firstViewId = "input",
                firstLengthBucket = "1-16",
            ),
        )
        assertEquals(
            "probeVer=2 rootWin=7 focusWin=7 sameWin=true windows=3 activeWins=1 " +
                "visited=12 rootKids=2 rootCls=FrameLayout editableCandidates=2 editableAll=2 " +
                "editableVisible=2 editableInvisible=0 inputLikeAll=2 inputLikeVisible=2 " +
                "hiddenFocus=false readonly=false textBucket=1-16 " +
                "firstCls=EditText firstVid=input firstLen=1-16",
            line,
        )
        assertFalse(line.contains("secret"))
    }

    @Test
    fun `tree probe v2 keeps invisible editable nodes without text`() {
        // 微信 H6 的关键分叉：真输入框若存在但 vis=false，必须能在日志里看到数量与类名。
        val line = treeProbeFields(
            TreeProbeFacts(
                rootWindowId = 7,
                focusWindowId = 7,
                windowCount = 0,
                activeWindowCount = 0,
                visited = 300,
                rootChildCount = 4,
                rootClassName = "FrameLayout",
                editableAll = 1,
                editableVisible = 0,
                editableInvisible = 1,
                inputLikeAll = 1,
                inputLikeVisible = 0,
                hiddenFocus = true,
                readonly = true,
                textBucket = "unknown",
                firstInvisibleClass = "EditText",
                firstInvisibleViewId = "chat_input",
            ),
        )
        assertTrue(line.contains("editableCandidates=0"))
        assertTrue(line.contains("editableAll=1"))
        assertTrue(line.contains("editableInvisible=1"))
        assertTrue(line.contains("firstInvisCls=EditText"))
        assertTrue(line.contains("firstInvisVid=chat_input"))
        assertFalse(line.contains("firstCls="))
        assertFalse(line.contains("text="))
    }

    @Test
    fun `tree probe v2 separates input-like nodes from editable nodes`() {
        // 控件存在但 isEditable=false 时，editableAll 为 0，inputLikeVisible 仍能给出证据。
        val line = treeProbeFields(
            TreeProbeFacts(
                rootWindowId = 7,
                focusWindowId = 7,
                windowCount = 0,
                activeWindowCount = 0,
                visited = 40,
                rootChildCount = 1,
                rootClassName = "FrameLayout",
                editableAll = 0,
                editableVisible = 0,
                editableInvisible = 0,
                inputLikeAll = 1,
                inputLikeVisible = 1,
                hiddenFocus = false,
                readonly = false,
                textBucket = "1-16",
            ),
        )
        assertTrue(line.contains("editableAll=0"))
        assertTrue(line.contains("inputLikeAll=1"))
        assertTrue(line.contains("inputLikeVisible=1"))
        assertFalse(line.contains("firstInvisCls="))
        assertFalse(line.contains("firstCls="))
    }

    @Test
    fun `input-like classification ignores editable state`() {
        assertTrue(FieldFacts.looksLikeInputControl("android.widget.EditText", null))
        assertTrue(FieldFacts.looksLikeInputControl("com.tencent.mm.ui.MMEditText", null))
        assertTrue(FieldFacts.looksLikeInputControl("androidx.compose.ui.BasicTextField", null))
        assertTrue(FieldFacts.looksLikeInputControl(null, "com.tencent.mm:id/chat_input"))
        assertTrue(FieldFacts.looksLikeInputControl("android.widget.TextView", "note_edit"))
        assertFalse(FieldFacts.looksLikeInputControl("android.widget.TextView", "message_text"))
        assertFalse(FieldFacts.looksLikeInputControl(null, null))
    }

    @Test
    fun `readback ladder treats unreadable as pending then abandoned`() {
        assertEquals(
            ReadbackVerdict.PENDING,
            judgeReadback("密文", "明文", null, samePkg = true, finalSample = false),
        )
        assertEquals(
            ReadbackVerdict.ABANDONED,
            judgeReadback("密文", "明文", null, samePkg = true, finalSample = true),
        )
    }
}
