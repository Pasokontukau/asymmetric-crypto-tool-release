package com.cryptoassistant.app.feature.files

import android.content.Context
import androidx.annotation.StringRes
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.keys.LocalKeyUnavailableException
import crypto.file.FileFormatException
import crypto.file.UnsupportedVolumeFileException
import crypto.file.WrongRecipientException
import crypto.volume.UnsupportedVolumeVersionException
import crypto.volume.VolumeFormatException
import crypto.volume.VolumeIntegrityException
import crypto.volume.VolumePartException
import java.io.IOException

/**
 * 文件任务的稳定错误原因。
 *
 * <p>纯逻辑层只判断“发生了什么”，不再拼中文；界面层用 [messageRes] 取当前语言文案。
 * 未知异常的原文只进日志，不直接展示给用户，避免英文界面出现中文/技术堆栈。</p>
 */
internal enum class FileTaskError(@get:StringRes val messageRes: Int) {
    UNSUPPORTED_VOLUME(R.string.file_error_unsupported_volume),
    VOLUME_VERSION(R.string.file_error_volume_version),
    VOLUME_INTEGRITY(R.string.file_error_volume_integrity),
    VOLUME_FORMAT(R.string.file_error_volume_format),
    WRONG_RECIPIENT(R.string.file_error_wrong_recipient),
    FILE_FORMAT(R.string.file_error_format),
    DIRECTORY(R.string.file_error_directory),
    DIRECTORY_LIMIT(R.string.file_error_directory_limit),
    DIRECTORY_EMPTY(R.string.file_error_directory_empty),
    ARCHIVE_PLAN(R.string.file_error_archive_plan),
    ARCHIVE_SIZE(R.string.file_error_archive_size),
    ARCHIVE_SOURCE(R.string.file_error_archive_source),
    ARCHIVE_LIMIT(R.string.file_error_archive_limit),
    ARCHIVE_BUILD(R.string.file_error_archive_build),
    ARCHIVE_EXTRACT(R.string.file_error_archive_extract),
    ARCHIVE_EXTRACT_UNSAFE(R.string.file_error_archive_unsafe),
    ARCHIVE_EXTRACT_LIMIT(R.string.file_error_archive_extract_limit),
    ARCHIVE_TARGET(R.string.file_error_archive_target),
    KEY_UNAVAILABLE(R.string.file_error_key_unavailable),
    PERMISSION_LOST(R.string.file_error_permission),
    NOT_FOUND(R.string.file_error_not_found),
    NO_SPACE(R.string.file_error_no_space),
    WRITE_DENIED(R.string.file_error_write_denied),
    IO(R.string.file_error_io),
    GENERIC(R.string.file_error_generic),
}

internal fun fileTaskErrorFor(error: Throwable): FileTaskError = when (error) {
    is UnsupportedVolumeFileException -> FileTaskError.UNSUPPORTED_VOLUME
    is UnsupportedVolumeVersionException -> FileTaskError.VOLUME_VERSION
    is VolumeIntegrityException -> FileTaskError.VOLUME_INTEGRITY
    is VolumeFormatException -> FileTaskError.VOLUME_FORMAT
    is WrongRecipientException -> FileTaskError.WRONG_RECIPIENT
    is FileFormatException -> FileTaskError.FILE_FORMAT
    is DirectoryScanException ->
        if (error.isEmpty) FileTaskError.DIRECTORY_EMPTY else FileTaskError.DIRECTORY

    is DirectoryLimitException -> FileTaskError.DIRECTORY_LIMIT
    is ArchiveExtractException -> when (error.reason) {
        ArchiveExtractException.Reason.UNSAFE_PATH,
        ArchiveExtractException.Reason.CONFLICT,
        -> FileTaskError.ARCHIVE_EXTRACT_UNSAFE

        ArchiveExtractException.Reason.LIMIT -> FileTaskError.ARCHIVE_EXTRACT_LIMIT
        ArchiveExtractException.Reason.TARGET -> FileTaskError.ARCHIVE_TARGET
        ArchiveExtractException.Reason.EXTRACT -> FileTaskError.ARCHIVE_EXTRACT
    }

    is ArchiveSinkSkipException -> FileTaskError.ARCHIVE_TARGET
    is ArchiveBuildException -> when {
        error.message.orEmpty().contains("4 GiB") ||
            error.message.orEmpty().contains("99") -> FileTaskError.ARCHIVE_LIMIT

        else -> FileTaskError.ARCHIVE_BUILD
    }

    is VolumePartException -> when (error.code) {
        VolumePartException.Code.INVALID_PLAN -> FileTaskError.ARCHIVE_PLAN
        VolumePartException.Code.SIZE_MISMATCH -> FileTaskError.ARCHIVE_SIZE
        VolumePartException.Code.SOURCE_CHANGED -> FileTaskError.ARCHIVE_SOURCE
        VolumePartException.Code.IO_ERROR -> FileTaskError.IO
    }

    is IllegalArgumentException -> {
        val text = error.message.orEmpty()
        if (text.contains("4 GiB") || text.contains("99")) {
            FileTaskError.ARCHIVE_LIMIT
        } else {
            FileTaskError.ARCHIVE_PLAN
        }
    }

    is LocalKeyUnavailableException -> FileTaskError.KEY_UNAVAILABLE
    is SecurityException -> FileTaskError.PERMISSION_LOST
    is IOException -> {
        val text = error.message.orEmpty()
        when {
            text.contains("ENOENT", ignoreCase = true) ||
                text.contains("No such file", ignoreCase = true) ->
                FileTaskError.NOT_FOUND

            text.contains("ENOSPC", ignoreCase = true) ||
                text.contains("No space", ignoreCase = true) ->
                FileTaskError.NO_SPACE

            text.contains("EPERM", ignoreCase = true) ||
                text.contains("EACCES", ignoreCase = true) ->
                FileTaskError.WRITE_DENIED

            else -> FileTaskError.IO
        }
    }

    else -> FileTaskError.GENERIC
}

internal fun fileTaskMessageFor(context: Context, error: Throwable): String =
    context.getString(fileTaskErrorFor(error).messageRes)
