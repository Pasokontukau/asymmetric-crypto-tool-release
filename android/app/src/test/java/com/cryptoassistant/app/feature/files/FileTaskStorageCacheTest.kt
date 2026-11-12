package com.cryptoassistant.app.feature.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 设置页“清除缓存”的纯函数：统计不得跟随租约文件虚增，全清必须包括活跃租约，
 * 删除失败必须如实返回，且只能动 `file-tasks/` 的直接子项。
 */
class FileTaskStorageCacheTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `missing or empty root reports zero`() {
        val missing = File(temporary.root, "missing")
        assertEquals(FileTaskCacheUsage(), FileTaskStorage.inspect(missing))

        val empty = temporary.newFolder(FileTaskStorage.DIRECTORY_NAME)
        assertEquals(FileTaskCacheUsage(), FileTaskStorage.inspect(empty))
        assertEquals(0, FileTaskStorage.clearAll(empty).failedEntries)
        assertTrue(empty.isDirectory)
    }

    @Test
    fun `nested files and task directories are counted`() {
        val root = temporary.newFolder(FileTaskStorage.DIRECTORY_NAME)
        val first = File(root, "first").apply {
            mkdirs()
            File(this, "a.bin").writeBytes(ByteArray(3))
            File(this, "nested").apply {
                mkdirs()
                File(this, "b.bin").writeBytes(ByteArray(5))
            }
        }
        val second = File(root, "second").apply {
            mkdirs()
            File(this, "c.bin").writeBytes(ByteArray(7))
        }

        val usage = FileTaskStorage.inspect(root)

        assertEquals(15L, usage.bytes)
        assertEquals(3, usage.files)
        assertEquals(2, usage.directories)
        assertEquals(0, usage.activeLeases)
        assertTrue(first.isDirectory)
        assertTrue(second.isDirectory)
    }

    @Test
    fun `lease files do not inflate file count or bytes`() {
        val root = temporary.newFolder(FileTaskStorage.DIRECTORY_NAME)
        val task = File(root, "task").apply {
            mkdirs()
            File(this, "payload.bin").writeBytes(ByteArray(11))
            File(this, ".export-lease").writeText("not-a-real-timestamp")
        }

        val usage = FileTaskStorage.inspect(root)

        assertEquals(11L, usage.bytes)
        assertEquals(1, usage.files)
        assertEquals(1, usage.directories)
        assertEquals(0, usage.activeLeases)
        assertTrue(File(task, "payload.bin").isFile)
    }

    @Test
    fun `active and expired leases are counted separately`() {
        val root = temporary.newFolder(FileTaskStorage.DIRECTORY_NAME)
        val active = File(root, "active").apply { mkdirs() }
        val expired = File(root, "expired").apply { mkdirs() }
        FileTaskStorage.markExported(active)
        FileTaskStorage.markExported(expired, now = 0L)

        val usage = FileTaskStorage.inspect(root)

        assertEquals(2, usage.directories)
        assertEquals(1, usage.activeLeases)
    }

    @Test
    fun `clearAll removes active leases and loose files but keeps root and siblings`() {
        val root = temporary.newFolder(FileTaskStorage.DIRECTORY_NAME)
        val exported = File(root, "exported").apply {
            mkdirs()
            File(this, "plain.txt").writeText("secret")
        }
        FileTaskStorage.markExported(exported)
        File(root, "loose.bin").writeBytes(ByteArray(2))
        val sibling = File(temporary.root, "outside").apply {
            mkdirs()
            File(this, "keep.txt").writeText("keep")
        }

        val result = FileTaskStorage.clearAll(root)

        assertEquals(1, result.deletedDirectories)
        assertEquals(1, result.deletedFiles)
        assertEquals(0, result.failedEntries)
        assertEquals(FileTaskCacheUsage(), result.remaining)
        assertTrue(root.isDirectory)
        assertFalse(exported.exists())
        assertFalse(File(root, "loose.bin").exists())
        assertTrue(sibling.isDirectory)
        assertTrue(File(sibling, "keep.txt").isFile)
    }

    @Test
    fun `partial deletion failure is reported and leaves the entry in place`() {
        val root = temporary.newFolder(FileTaskStorage.DIRECTORY_NAME)
        val ok = File(root, "ok").apply {
            mkdirs()
            File(this, "a.bin").writeBytes(ByteArray(4))
        }
        val stuck = File(root, "stuck").apply {
            mkdirs()
            File(this, "b.bin").writeBytes(ByteArray(6))
        }

        val result = FileTaskStorage.clearAll(root) { child ->
            if (child.name == "stuck") false else FileTaskStorage.deleteRecursively(child)
        }

        assertEquals(1, result.deletedDirectories)
        assertEquals(1, result.failedEntries)
        assertFalse(ok.exists())
        assertTrue(stuck.isDirectory)
        assertTrue(File(stuck, "b.bin").isFile)
        assertEquals(6L, result.remaining.bytes)
        assertEquals(1, result.remaining.directories)
    }

    @Test
    fun `deleteRecursively reports a failed directory delete`() {
        val root = temporary.newFolder("delete-test")
        // File 可被测试子类覆盖 delete()，模拟系统拒绝删除目录的情况；
        // 子项已删掉、目录仍在，返回值必须是 false，而不是“尝试过就算成功”。
        val locked = object : File(root, "locked") {
            override fun delete(): Boolean = false
        }
        locked.mkdirs()
        File(locked, "child.txt").writeText("x")

        assertFalse(FileTaskStorage.deleteRecursively(locked))
        assertTrue(locked.isDirectory)
        assertFalse(File(locked, "child.txt").exists())
    }

    @Test
    fun `deleteRecursively treats an already deleted path as success`() {
        val root = temporary.newFolder("delete-idempotent")
        val directory = File(root, "gone").apply { mkdirs() }

        assertTrue(FileTaskStorage.deleteRecursively(directory))
        assertFalse(directory.exists())
        // 清理重试时路径可能已被其他清理删掉；幂等成功，不当作失败。
        assertTrue(FileTaskStorage.deleteRecursively(directory))
    }
}
