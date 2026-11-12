package com.cryptoassistant.app.feature.files

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 导出租约：交给其他应用打开过的结果，在 TTL 内不能被启动清理删掉；
 * 过期后仍然要回收；没有租约的目录照旧删除。删除失败也不能抛（冷启动不能被带崩）。
 */
class FileTaskStorageLeaseTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `directory with an active export lease survives cold start cleanup`() {
        val root = temporary.newFolder(FileTaskStorage.DIRECTORY_NAME)
        val exported = File(root, "exported").apply {
            mkdirs()
            File(this, "明文.txt").writeText("secret")
        }
        FileTaskStorage.markExported(exported)

        val stale = File(root, "stale").apply {
            mkdirs()
            File(this, "old.enc").writeText("ciphertext")
        }

        FileTaskStorage.cleanupStale(root)

        assertTrue(exported.isDirectory)
        assertTrue(File(exported, "明文.txt").isFile)
        assertFalse(stale.exists())
    }

    @Test
    fun `expired lease is cleaned up on the next start`() {
        val root = temporary.newFolder(FileTaskStorage.DIRECTORY_NAME)
        val expired = File(root, "expired").apply { mkdirs() }
        // 租约时间戳取 0，加上 TTL 后仍早于现在，等价于「很久以前导出过」。
        FileTaskStorage.markExported(expired, now = 0L)

        assertFalse(FileTaskStorage.hasActiveLease(expired))
        FileTaskStorage.cleanupStale(root)
        assertFalse(expired.exists())
    }

    @Test
    fun `lease expires exactly after the configured ttl`() {
        val directory = temporary.newFolder("task")
        val exportedAt = 1_000_000L
        FileTaskStorage.markExported(directory, now = exportedAt)

        assertTrue(FileTaskStorage.hasActiveLease(directory, now = exportedAt))
        assertTrue(
            FileTaskStorage.hasActiveLease(
                directory,
                now = exportedAt + FileTaskStorage.EXPORT_LEASE_MILLIS - 1,
            ),
        )
        assertFalse(
            FileTaskStorage.hasActiveLease(
                directory,
                now = exportedAt + FileTaskStorage.EXPORT_LEASE_MILLIS,
            ),
        )
    }

    @Test
    fun `loose files and unreadable leases are treated as stale`() {
        val root = temporary.newFolder(FileTaskStorage.DIRECTORY_NAME)
        val brokenLease = File(root, "broken").apply {
            mkdirs()
            File(this, ".export-lease").writeText("not-a-number")
        }
        File(root, "loose-file").writeText("x")

        FileTaskStorage.cleanupStale(root)

        assertFalse(brokenLease.exists())
        assertFalse(File(root, "loose-file").exists())
    }
}
