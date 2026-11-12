package com.cryptoassistant.app.feature.files

import crypto.file.FileCryptoEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 从 ViewModel 抽出的共享加密预检。
 *
 * 文件页和文件流水线同时使用，所以把既有文案与边界锁在 JVM 测试里，
 * 避免抽取时无意改掉用户看到的提示。异常到文案的映射见 [FileTaskErrorTest]。
 */
class FileTaskRunnerTest {

    @Test
    fun `preflight rejects unknown size`() {
        val nullSize = encryptPreflightError(null)
        val negative = encryptPreflightError(-1)
        assertTrue(nullSize!!.contains("没有提供这个文件的大小"))
        assertEquals(nullSize, negative)
    }

    @Test
    fun `preflight allows exactly the plaintext limit`() {
        assertNull(encryptPreflightError(FileCryptoEngine.MAX_PLAINTEXT_BYTES))
    }

    @Test
    fun `preflight rejects files above the limit`() {
        val message = encryptPreflightError(FileCryptoEngine.MAX_PLAINTEXT_BYTES + 1)
        assertTrue(message!!.contains("2 GB"))
    }
}
