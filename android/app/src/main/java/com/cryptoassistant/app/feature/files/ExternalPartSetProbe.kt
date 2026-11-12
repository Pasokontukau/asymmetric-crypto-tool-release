package com.cryptoassistant.app.feature.files

import crypto.message.FileFormatHeader
import crypto.volume.VolumeCryptoEngine
import crypto.volume.VolumeInfo
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.PrivateKey

/**
 * 只读 VOLU 元数据的外部卷探测，不读 payload。
 *
 * <p>用于解密页分组：普通 `.aegix` 单文件会返回 null（不是 VOLU 外部卷），
 * 新单卷包/多卷包返回已校验的 [VolumeInfo]；调用方再把多卷交给
 * `VolumeSetPlan` 做缺卷/重卷/混包判定。</p>
 */
internal object ExternalPartSetProbe {

    /**
     * 读取一个文件的 VOLU 元数据。
     *
     * @return 非 VOLU 文件（例如普通 `.aegix` 单文件）返回 null
     * @throws Exception 是 VOLU 但版本/元数据/接收方不匹配时抛出，由调用方决定是否视为不可读
     */
    fun readInfo(open: () -> InputStream, privateKey: PrivateKey): VolumeInfo? =
        open().use { raw ->
            val input = BufferedInputStream(raw, 4)
            input.mark(4)
            val magic = readInt(input)
            if (magic != FileFormatHeader.MAGIC) return null
            input.reset()
            VolumeCryptoEngine.readInfo(input, privateKey)
        }

    private fun readInt(input: InputStream): Int {
        val bytes = ByteArray(4)
        var read = 0
        while (read < 4) {
            val count = input.read(bytes, read, 4 - read)
            if (count < 0) return 0
            if (count > 0) read += count
        }
        return ByteBuffer.wrap(bytes).int
    }
}
