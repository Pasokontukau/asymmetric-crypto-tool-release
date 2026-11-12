package com.cryptoassistant.app.data.log

import com.cryptoassistant.app.feature.files.DirectoryLimitException
import com.cryptoassistant.app.feature.files.DirectoryScanException
import crypto.file.FileCancelledException
import crypto.file.FileFormatException
import crypto.file.UnsupportedVolumeFileException
import crypto.file.WrongRecipientException
import crypto.volume.UnsupportedVolumeVersionException
import crypto.volume.VolumeFormatException
import crypto.volume.VolumeIntegrityException
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志计划 §5.7 的错误类别映射与 §5.3 的粗粒度字段。
 *
 * 类别决定 WARN/ERROR：可预期的用户/文件/权限拒绝必须是 WARN，
 * 未知异常才允许 ERROR；顺序错了（子类先被父类吃掉）就会误判等级。
 */
class LogErrorKindTest {

    @Test
    fun `specific subclasses win over their parents`() {
        // 这四类都继承 FileFormatException/IOException，必须先命中自己的类别。
        assertEquals(
            LogErrorKind.WRONG_RECIPIENT,
            LogErrorKind.of(WrongRecipientException("not mine")),
        )
        assertEquals(
            LogErrorKind.INTEGRITY,
            LogErrorKind.of(VolumeIntegrityException("bad hash")),
        )
        assertEquals(
            LogErrorKind.VOLUME,
            LogErrorKind.of(VolumeFormatException("bad volume")),
        )
        assertEquals(
            LogErrorKind.VOLUME,
            LogErrorKind.of(UnsupportedVolumeVersionException(3)),
        )
        assertEquals(
            LogErrorKind.FORMAT,
            LogErrorKind.of(UnsupportedVolumeFileException("bad header")),
        )
        assertEquals(LogErrorKind.FORMAT, LogErrorKind.of(FileFormatException("bad")))
    }

    @Test
    fun `cancellation is a cancellation not an io error`() {
        assertEquals(LogErrorKind.CANCELLED, LogErrorKind.of(FileCancelledException("stop")))
        assertEquals(LogErrorKind.CANCELLED, LogErrorKind.of(CancellationException("stop")))
    }

    @Test
    fun `permission limit and scan errors are expected`() {
        assertEquals(LogErrorKind.PERMISSION, LogErrorKind.of(SecurityException("denied")))
        assertEquals(LogErrorKind.LIMIT, LogErrorKind.of(DirectoryLimitException("too many")))
        assertEquals(LogErrorKind.SCAN, LogErrorKind.of(DirectoryScanException("unreadable")))
        assertTrue(LogErrorKind.isExpected(SecurityException("denied")))
        assertTrue(LogErrorKind.isExpected(DirectoryLimitException("too many")))
        assertTrue(LogErrorKind.isExpected(DirectoryScanException("unreadable")))
    }

    @Test
    fun `io and crypto rejections are expected but state failures are not`() {
        assertTrue(LogErrorKind.isExpected(IOException("ENOENT")))
        assertTrue(LogErrorKind.isExpected(IllegalArgumentException("no text")))
        assertTrue(LogErrorKind.isExpected(GeneralSecurityException("bad tag")))

        val state = IllegalStateException("尚未创建本机密钥")
        assertEquals(LogErrorKind.STATE, LogErrorKind.of(state))
        assertFalse("内部状态失败必须留在 ERROR", LogErrorKind.isExpected(state))
    }

    @Test
    fun `unknown exceptions stay errors and never carry the message`() {
        val error = RuntimeException("failed /sdcard/secret.docx")
        assertEquals(LogErrorKind.UNKNOWN, LogErrorKind.of(error))
        assertFalse(LogErrorKind.isExpected(error))

        val detail = LogErrorKind.detail(error)
        assertTrue(detail.startsWith("kind=unknown "))
        assertTrue(detail.contains("class=RuntimeException"))
        assertFalse("detail 不能带原始 message", detail.contains("secret.docx"))
    }

    @Test
    fun `length buckets do not leak exact lengths`() {
        assertEquals("0", LogFields.lengthBucket(0))
        assertEquals("1-16", LogFields.lengthBucket(1))
        assertEquals("1-16", LogFields.lengthBucket(16))
        assertEquals("17-64", LogFields.lengthBucket(17))
        assertEquals("17-64", LogFields.lengthBucket(64))
        assertEquals("65-256", LogFields.lengthBucket(65))
        assertEquals("65-256", LogFields.lengthBucket(256))
        assertEquals(">256", LogFields.lengthBucket(257))
    }

    @Test
    fun `mime families are coarse and never keep the full raw string`() {
        assertEquals("octet-stream", LogFields.mimeFamily("application/octet-stream"))
        assertEquals("zip", LogFields.mimeFamily("application/zip"))
        assertEquals("image", LogFields.mimeFamily("image/png"))
        assertEquals("text", LogFields.mimeFamily("text/plain"))
        assertEquals("encrypted", LogFields.mimeFamily("application/vnd.aegix"))
        assertEquals("other", LogFields.mimeFamily("application/x-weird"))
        assertEquals("unknown", LogFields.mimeFamily(null))
        assertEquals("unknown", LogFields.mimeFamily("  "))
        assertEquals("image", LogFields.mimeFamily("IMAGE/PNG"))
    }
}
