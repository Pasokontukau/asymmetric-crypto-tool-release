package com.cryptoassistant.app.feature.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分享意图只允许携带结果卡上的可见名。
 *
 * <p>加密结果的原始文件名保存在 [FileTaskResult.sourceName]，它只能留在本机；
 * `EXTRA_TITLE`、ClipData 标签与 URI 文件名必须来自 `displayName`。</p>
 */
class ShareLabelTest {

    @Test
    fun `encrypted result exposes only the sanitized display name`() {
        val original = "工资表 2026.xlsx"
        val result = FileTaskResult(
            mode = FileTaskMode.ENCRYPT,
            file = File("/tmp/secure_file_20261109_120000.aegix"),
            displayName = "secure_file_20261109_120000.aegix",
            bytes = 128,
            sourceName = original,
        )

        val label = shareLabel(result)

        assertEquals(result.displayName, label.title)
        assertEquals(result.displayName, label.clipLabel)
        assertFalse(label.title.contains(original))
        assertFalse(label.clipLabel.contains(original))
        assertFalse(label.title.contains(".xlsx"))
        // FileProvider 的 URI 末段来自结果文件本身，加密结果的文件名也必须脱敏。
        assertFalse(result.file.name.contains(original))
        assertFalse(result.file.name.contains(".xlsx"))
    }

    @Test
    fun `decrypted result may expose the recovered original name`() {
        val original = "工资表 2026.xlsx"
        val result = FileTaskResult(
            mode = FileTaskMode.DECRYPT,
            file = File("/tmp/$original"),
            displayName = original,
            bytes = 128,
            sourceName = "secure_file_20261109_120000.aegix",
        )

        val label = shareLabel(result)

        assertEquals(original, label.title)
        assertTrue(label.clipLabel.contains("工资表"))
    }

    @Test
    fun `volume part shares its own sanitized volume name`() {
        val part = File("/tmp/secure_file_20261109_120000_abcdef_vol002.aegix")
        val result = FileTaskResult(
            mode = FileTaskMode.ENCRYPT,
            file = part,
            displayName = part.name,
            bytes = 128,
            sourceName = "Photos.zip",
            volumeCount = 3,
            parts = listOf(part),
        )

        val label = shareLabel(result)

        assertEquals(part.name, label.title)
        assertFalse(label.title.contains("Photos"))
        assertFalse(result.file.name.contains("Photos"))
    }
}
