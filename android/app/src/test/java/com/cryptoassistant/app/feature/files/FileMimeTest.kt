package com.cryptoassistant.app.feature.files

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 明文外发的 MIME 推断：本地补丁表 → 系统给的类型 → octet-stream。
 * `.aegix` 永不参与猜测，避免把密文当成"可打开"的文件；`.enc` 已不是本应用的后缀。
 */
class FileMimeTest {

    @Test
    fun `local table covers the extensions MimeTypeMap often misses`() {
        assertEquals("text/markdown", FileMime.infer("笔记.md", null))
        assertEquals("text/plain", FileMime.infer("run.log", null))
        assertEquals("application/json", FileMime.infer("data.json", "application/octet-stream"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            FileMime.infer("合同.docx", null),
        )
    }

    @Test
    fun `common media types resolve`() {
        assertEquals("image/jpeg", FileMime.infer("photo.jpg", "application/octet-stream"))
        assertEquals("image/png", FileMime.infer("屏幕截图.PNG", null))
        assertEquals("application/pdf", FileMime.infer("说明书.pdf", null))
        assertEquals("video/mp4", FileMime.infer("clip.mp4", null))
    }

    @Test
    fun `system guess is used when the table has no entry`() {
        assertEquals("application/x-custom", FileMime.infer("blob.zzz", "application/x-custom"))
    }

    @Test
    fun `unknown plaintext falls back to octet stream`() {
        assertEquals(FileMime.FALLBACK, FileMime.infer("blob.bin", null))
        assertEquals(FileMime.FALLBACK, FileMime.infer("README", null))
        assertEquals(FileMime.FALLBACK, FileMime.infer(null, ""))
    }

    @Test
    fun `aegix files are never guessed as openable`() {
        assertEquals(FileMime.FALLBACK, FileMime.infer("vault.aegix", "application/zip"))
        assertEquals(FileMime.FALLBACK, FileMime.infer("vault.AEGIX", null))
    }
}
