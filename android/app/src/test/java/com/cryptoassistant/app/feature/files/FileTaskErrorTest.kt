package com.cryptoassistant.app.feature.files

import com.cryptoassistant.app.data.keys.LocalKeyUnavailableException
import crypto.file.FileFormatException
import crypto.file.UnsupportedVolumeFileException
import crypto.file.WrongRecipientException
import crypto.volume.UnsupportedVolumeVersionException
import crypto.volume.VolumeFormatException
import crypto.volume.VolumeIntegrityException
import crypto.volume.VolumePartException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 文件错误原因映射的 JVM 门禁。
 *
 * <p>纯逻辑层只判断异常类型（个别 IO 错误再看系统给出的 errno 文案），
 * 界面层再按 [FileTaskError.messageRes] 取当前语言。这里锁住映射本身，
 * 避免迁移到资源时把某类异常悄悄降级成通用文案。</p>
 */
class FileTaskErrorTest {

    @Test
    fun `crypto core exceptions keep their specific reasons`() {
        assertEquals(
            FileTaskError.UNSUPPORTED_VOLUME,
            fileTaskErrorFor(UnsupportedVolumeFileException("volume")),
        )
        assertEquals(
            FileTaskError.VOLUME_VERSION,
            fileTaskErrorFor(UnsupportedVolumeVersionException(2)),
        )
        assertEquals(
            FileTaskError.VOLUME_INTEGRITY,
            fileTaskErrorFor(VolumeIntegrityException("integrity")),
        )
        assertEquals(
            FileTaskError.VOLUME_FORMAT,
            fileTaskErrorFor(VolumeFormatException("format")),
        )
        assertEquals(
            FileTaskError.WRONG_RECIPIENT,
            fileTaskErrorFor(WrongRecipientException("recipient")),
        )
        assertEquals(
            FileTaskError.FILE_FORMAT,
            fileTaskErrorFor(FileFormatException("format")),
        )
    }

    @Test
    fun `archive plan and source errors keep their specific reasons`() {
        assertEquals(
            FileTaskError.ARCHIVE_PLAN,
            fileTaskErrorFor(
                VolumePartException(VolumePartException.Code.INVALID_PLAN, "plan"),
            ),
        )
        assertEquals(
            FileTaskError.ARCHIVE_SIZE,
            fileTaskErrorFor(
                VolumePartException(VolumePartException.Code.SIZE_MISMATCH, "size"),
            ),
        )
        assertEquals(
            FileTaskError.ARCHIVE_SOURCE,
            fileTaskErrorFor(
                VolumePartException(VolumePartException.Code.SOURCE_CHANGED, "changed"),
            ),
        )
        assertEquals(
            FileTaskError.IO,
            fileTaskErrorFor(
                VolumePartException(VolumePartException.Code.IO_ERROR, "io"),
            ),
        )
        assertEquals(
            FileTaskError.ARCHIVE_LIMIT,
            fileTaskErrorFor(ArchiveBuildException("ZIP 超过 4 GiB 上限")),
        )
        assertEquals(
            FileTaskError.ARCHIVE_BUILD,
            fileTaskErrorFor(ArchiveBuildException("归档输出已存在")),
        )
        assertEquals(
            FileTaskError.ARCHIVE_LIMIT,
            fileTaskErrorFor(IllegalArgumentException("分卷数量超过 99，请增大单卷上限")),
        )
        assertEquals(
            FileTaskError.ARCHIVE_PLAN,
            fileTaskErrorFor(IllegalArgumentException("单卷上限必须大于 4 KiB 头部预留")),
        )
    }

    @Test
    fun `archive extract errors keep their specific reasons`() {
        assertEquals(
            FileTaskError.ARCHIVE_EXTRACT_UNSAFE,
            fileTaskErrorFor(
                ArchiveExtractException(
                    ArchiveExtractException.Reason.UNSAFE_PATH,
                    "unsafe",
                ),
            ),
        )
        assertEquals(
            FileTaskError.ARCHIVE_EXTRACT_UNSAFE,
            fileTaskErrorFor(
                ArchiveExtractException(
                    ArchiveExtractException.Reason.CONFLICT,
                    "conflict",
                ),
            ),
        )
        assertEquals(
            FileTaskError.ARCHIVE_EXTRACT_LIMIT,
            fileTaskErrorFor(
                ArchiveExtractException(ArchiveExtractException.Reason.LIMIT, "limit"),
            ),
        )
        assertEquals(
            FileTaskError.ARCHIVE_TARGET,
            fileTaskErrorFor(
                ArchiveExtractException(ArchiveExtractException.Reason.TARGET, "target"),
            ),
        )
        assertEquals(
            FileTaskError.ARCHIVE_EXTRACT,
            fileTaskErrorFor(
                ArchiveExtractException(ArchiveExtractException.Reason.EXTRACT, "extract"),
            ),
        )
    }

    @Test
    fun `directory errors are separated from read failures and empty folders`() {
        assertEquals(
            FileTaskError.DIRECTORY,
            fileTaskErrorFor(DirectoryScanException("unreadable")),
        )
        assertEquals(
            FileTaskError.DIRECTORY_EMPTY,
            fileTaskErrorFor(DirectoryScanException("empty", isEmpty = true)),
        )
        assertEquals(
            FileTaskError.DIRECTORY_LIMIT,
            fileTaskErrorFor(DirectoryLimitException("too many")),
        )
    }

    @Test
    fun `local key failures map to the key reason`() {
        assertEquals(
            FileTaskError.KEY_UNAVAILABLE,
            fileTaskErrorFor(LocalKeyUnavailableException(missing = true)),
        )
        assertEquals(
            FileTaskError.KEY_UNAVAILABLE,
            fileTaskErrorFor(LocalKeyUnavailableException(missing = false)),
        )
    }

    @Test
    fun `permission failures map to the permission reason`() {
        assertEquals(
            FileTaskError.PERMISSION_LOST,
            fileTaskErrorFor(SecurityException("denied")),
        )
    }

    @Test
    fun `io errors use errno hints before the generic fallback`() {
        assertEquals(
            FileTaskError.NOT_FOUND,
            fileTaskErrorFor(IOException("ENOENT: No such file or directory")),
        )
        assertEquals(
            FileTaskError.NO_SPACE,
            fileTaskErrorFor(IOException("ENOSPC: No space left on device")),
        )
        assertEquals(
            FileTaskError.WRITE_DENIED,
            fileTaskErrorFor(IOException("EPERM: Operation not permitted")),
        )
        assertEquals(
            FileTaskError.WRITE_DENIED,
            fileTaskErrorFor(IOException("EACCES: Permission denied")),
        )
        assertEquals(
            FileTaskError.IO,
            fileTaskErrorFor(IOException("")),
        )
        assertEquals(
            FileTaskError.IO,
            fileTaskErrorFor(IOException("socket closed")),
        )
    }

    @Test
    fun `unknown errors fall back without leaking their message`() {
        assertEquals(
            FileTaskError.GENERIC,
            fileTaskErrorFor(IllegalStateException("boom")),
        )
    }
}
