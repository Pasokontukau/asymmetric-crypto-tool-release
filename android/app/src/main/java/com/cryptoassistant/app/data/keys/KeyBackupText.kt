package com.cryptoassistant.app.data.keys

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * 密钥备份文件（`.txt`）的读取与轻量识别。
 *
 * <p>导出的是 `aegix-key-bundle` 的 `Properties` 文本。这里只判断
 * 「能不能当 UTF-8 文本读」以及「是不是完整备份」；真正的口令校验和
 * 公私钥一致性检查仍由 [KeyRepository.importIdentity] 完成。</p>
 *
 * <p>文件来自 SAF，可能被用户选错、被编辑器改成 UTF-16/BOM、或根本不是文本。
 * 这些情况必须在读入内存前就拒绝，避免把二进制文件当备份解析。</p>
 */
object KeyBackupText {

    /** 备份文本只有几 KB；超过这个上限的一定不是本应用的备份。 */
    const val MAX_BYTES = 256 * 1024

    /** 识别结果只用于界面提示与预填名称，不代表导入一定成功。 */
    sealed interface Kind {
        data class Bundle(val name: String, val keySize: Int) : Kind

        data class Invalid(val reason: KeyError, val detail: String? = null) : Kind
    }

    /**
     * 把 SAF 读出的字节解码成备份文本。
     *
     * <p>只接受严格 UTF-8；Windows 记事本保存的 UTF-8 BOM 会被去掉。
     * 超过 [MAX_BYTES]、空文件、包含 NUL 的二进制内容都会被拒绝。</p>
     */
    fun decode(bytes: ByteArray): Result<String> {
        if (bytes.isEmpty()) {
            return Result.failure(KeyOperationException(KeyError.BACKUP_EMPTY))
        }
        if (bytes.size > MAX_BYTES) {
            return Result.failure(
                KeyOperationException(
                    KeyError.BACKUP_TOO_LARGE,
                    "bytes=${bytes.size}",
                ),
            )
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return Result.failure(KeyOperationException(KeyError.BACKUP_NOT_UTF8))
        }
        val cleaned = text.removePrefix(BOM)
        if (cleaned.isBlank()) {
            return Result.failure(KeyOperationException(KeyError.BACKUP_EMPTY))
        }
        if (cleaned.contains('\u0000')) {
            return Result.failure(KeyOperationException(KeyError.BACKUP_BINARY))
        }
        return Result.success(cleaned)
    }

    /**
     * 判断文本是不是当前完整备份。
     *
     * <p>这里不校验口令，因此 [Kind.Bundle] 也可能因为口令错误而导入失败；
     * [Kind.Invalid] 则是结构性错误，导入前就能给用户明确提示。</p>
     */
    fun inspect(text: String): Kind {
        val cleaned = text.removePrefix(BOM).trim()
        if (cleaned.isEmpty()) return Kind.Invalid(KeyError.BACKUP_CONTENT_EMPTY)
        if (!cleaned.contains("format=")) {
            return Kind.Invalid(KeyError.BACKUP_NOT_OURS)
        }
        return try {
            val bundle = KeyBundle.decode(cleaned)
            if (bundle == null) {
                Kind.Invalid(KeyError.BACKUP_UNKNOWN_FORMAT)
            } else {
                Kind.Bundle(bundle.identity.displayName, bundle.identity.keySize)
            }
        } catch (error: IllegalArgumentException) {
            Kind.Invalid(KeyError.BACKUP_FORMAT, error.message)
        }
    }

    /**
     * 从流里最多读 [limit] 字节；超过后立刻停止，避免把大文件整个读进内存。
     * 返回的字节数可能等于 `limit + 1`，交给 [decode] 判定是否超限。
     */
    fun readAtMost(stream: InputStream, limit: Int = MAX_BYTES): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) {
                throw KeyOperationException(
                    KeyError.BACKUP_TOO_LARGE,
                    "bytes>$limit",
                )
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private const val BOM = "\uFEFF"
}
