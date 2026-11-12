package com.cryptoassistant.app.feature.common

import com.cryptoassistant.app.data.keys.LocalKeyUnavailableException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 球路径与胶囊路径共用同一份解密失败原因（T18）。
 *
 * 这里锁住契约：预期内的密码学拒绝必须说明“不是发给本机密钥/内容被改”；
 * 本机私钥不可用按“未创建 / 解封失败”区分；其他异常只能落到通用兜底，
 * 不再把异常原文或类名带进界面。
 */
class DecryptErrorTextTest {

    @Test
    fun `bad padding explains wrong recipient or tampering`() {
        assertEquals(
            DecryptError.WRONG_RECIPIENT,
            decryptErrorFor(BadPaddingException("padding error")),
        )
    }

    @Test
    fun `illegal block size uses the same explanation`() {
        assertEquals(
            DecryptError.WRONG_RECIPIENT,
            decryptErrorFor(IllegalBlockSizeException("bad block")),
        )
    }

    @Test
    fun `missing identity is distinguished from unwrap failure`() {
        assertEquals(
            DecryptError.NO_IDENTITY,
            decryptErrorFor(LocalKeyUnavailableException(missing = true)),
        )
        assertEquals(
            DecryptError.PRIVATE_KEY,
            decryptErrorFor(LocalKeyUnavailableException(missing = false)),
        )
    }

    @Test
    fun `other errors fall back to the generic hint`() {
        assertEquals(
            DecryptError.GENERIC,
            decryptErrorFor(IllegalStateException("无法解封本机私钥")),
        )
        assertEquals(
            DecryptError.GENERIC,
            decryptErrorFor(RuntimeException()),
        )
    }
}
