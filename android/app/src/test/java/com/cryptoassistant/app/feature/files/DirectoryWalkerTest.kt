package com.cryptoassistant.app.feature.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 目录递归扫描的纯逻辑测试：不依赖 Android，用内存里的目录树覆盖
 * 递归、排序、解密过滤、上限与循环目录等边界。
 */
class DirectoryWalkerTest {

    private fun collect(
        tree: Map<String, List<DirectoryEntry>>,
        mode: FileTaskMode = FileTaskMode.ENCRYPT,
        rootId: String = "root",
        maxFiles: Int = DirectoryWalker.MAX_FILES,
        maxTotalBytes: Long = DirectoryWalker.MAX_TOTAL_BYTES,
        maxEntries: Int = DirectoryWalker.MAX_ENTRIES,
        maxDepth: Int = DirectoryWalker.MAX_DEPTH,
    ): DirectoryScanResult = DirectoryWalker.collect(
        rootDocumentId = rootId,
        mode = mode,
        listChildren = { tree[it].orEmpty() },
        maxFiles = maxFiles,
        maxTotalBytes = maxTotalBytes,
        maxEntries = maxEntries,
        maxDepth = maxDepth,
    )

    @Test
    fun `recurses and returns path-sorted relative paths`() {
        val tree = mapOf(
            "root" to listOf(
                DirectoryEntry("dir", "b-dir", isDirectory = true),
                DirectoryEntry("a", "a.txt", isDirectory = false, size = 10),
                DirectoryEntry("c", "c.txt", isDirectory = false, size = 20),
            ),
            "dir" to listOf(
                DirectoryEntry("z", "z.txt", isDirectory = false, size = 5),
                DirectoryEntry("a2", "a.txt", isDirectory = false, size = 7),
            ),
        )

        val result = collect(tree)

        assertEquals(
            listOf("a.txt", "b-dir/a.txt", "b-dir/z.txt", "c.txt"),
            result.files.map { it.relativePath },
        )
        assertEquals(listOf(10L, 7L, 5L, 20L), result.files.map { it.size })
        assertEquals(0, result.skippedNonMatching)
    }

    @Test
    fun `decrypt mode collects only aegix files and counts skipped files`() {
        val tree = mapOf(
            "root" to listOf(
                DirectoryEntry("a", "a.ENC", isDirectory = false, size = 1),
                DirectoryEntry("b", "b.txt", isDirectory = false, size = 2),
                DirectoryEntry("d", "sub", isDirectory = true),
            ),
            "d" to listOf(
                DirectoryEntry("c", "c.aegix", isDirectory = false, size = 3),
                DirectoryEntry("e", "e.enc", isDirectory = false, size = 4),
                DirectoryEntry("f", "photo.png", isDirectory = false, size = 5),
            ),
        )

        val result = collect(tree, mode = FileTaskMode.DECRYPT)

        assertEquals(
            listOf("sub/c.aegix"),
            result.files.map { it.relativePath },
        )
        assertEquals(4, result.skippedNonMatching)
    }

    @Test
    fun `encrypt scan collects directories without any file descendant`() {
        val tree = mapOf(
            "root" to listOf(
                DirectoryEntry("a", "keep", isDirectory = true),
                DirectoryEntry("empty", "empty", isDirectory = true),
                DirectoryEntry("nested", "nested", isDirectory = true),
            ),
            "a" to listOf(DirectoryEntry("f", "file.txt", isDirectory = false, size = 1)),
            "empty" to emptyList(),
            "nested" to listOf(
                DirectoryEntry("leaf", "leaf", isDirectory = true),
            ),
            "leaf" to emptyList(),
        )

        val result = collect(tree)

        assertEquals(listOf("empty", "nested", "nested/leaf"), result.emptyDirectories)
    }

    @Test
    fun `empty directory is an explicit error`() {
        try {
            collect(mapOf("root" to emptyList()))
            fail("空目录应当直接报错，而不是返回空队列")
        } catch (error: DirectoryScanException) {
            assertTrue(error.message.orEmpty().contains("没有可处理"))
        }
    }

    @Test
    fun `decrypt directory without encrypted files explains the filter`() {
        val tree = mapOf(
            "root" to listOf(
                DirectoryEntry("a", "a.txt", isDirectory = false, size = 1),
            ),
        )
        try {
            collect(tree, mode = FileTaskMode.DECRYPT)
            fail("没有 .aegix 时应当提示")
        } catch (error: DirectoryScanException) {
            assertTrue(error.message.orEmpty().contains("没有找到 .aegix"))
        }
    }

    @Test
    fun `file count limit is enforced before enqueueing the next file`() {
        val tree = mapOf(
            "root" to (1..3).map {
                DirectoryEntry("f$it", "file$it.txt", isDirectory = false, size = 1)
            },
        )
        try {
            collect(tree, maxFiles = 2)
            fail("超过文件数上限应当报错")
        } catch (error: DirectoryLimitException) {
            assertTrue(error.message.orEmpty().contains("2 个"))
        }
    }

    @Test
    fun `total known bytes limit is enforced`() {
        val tree = mapOf(
            "root" to listOf(
                DirectoryEntry("a", "a.bin", isDirectory = false, size = 6),
                DirectoryEntry("b", "b.bin", isDirectory = false, size = 5),
            ),
        )
        try {
            collect(tree, maxTotalBytes = 10)
            fail("超过总大小上限应当报错")
        } catch (error: DirectoryLimitException) {
            assertTrue(error.message.orEmpty().contains("GB"))
        }
    }

    @Test
    fun `unknown sizes do not trip the byte limit`() {
        val tree = mapOf(
            "root" to listOf(
                DirectoryEntry("a", "a.bin", isDirectory = false, size = null),
                DirectoryEntry("b", "b.bin", isDirectory = false, size = null),
            ),
        )

        val result = collect(tree, maxTotalBytes = 1)

        assertEquals(2, result.files.size)
        assertEquals(listOf(null, null), result.files.map { it.size })
    }

    @Test
    fun `cyclic directory ids do not recurse forever`() {
        val tree = mapOf(
            "root" to listOf(DirectoryEntry("d", "d", isDirectory = true)),
            "d" to listOf(
                DirectoryEntry("root", "back-to-root", isDirectory = true),
                DirectoryEntry("f", "file.txt", isDirectory = false, size = 1),
            ),
        )

        val result = collect(tree)

        assertEquals(listOf("d/file.txt"), result.files.map { it.relativePath })
    }

    @Test
    fun `entry limit and depth limit are explicit failures`() {
        val wide = mapOf(
            "root" to (1..3).map {
                DirectoryEntry("f$it", "file$it.txt", isDirectory = false, size = 1)
            },
        )
        try {
            collect(wide, maxEntries = 2)
            fail("超过条目数上限应当报错")
        } catch (error: DirectoryLimitException) {
            assertTrue(error.message.orEmpty().contains("2 个"))
        }

        val deep = mapOf(
            "root" to listOf(DirectoryEntry("d1", "only-dir", isDirectory = true)),
            "d1" to listOf(DirectoryEntry("d2", "deeper", isDirectory = true)),
            "d2" to listOf(DirectoryEntry("f", "file.txt", isDirectory = false, size = 1)),
        )
        try {
            collect(deep, maxDepth = 1)
            fail("超过目录深度上限应当报错")
        } catch (error: DirectoryLimitException) {
            assertTrue(error.message.orEmpty().contains("层级"))
        }
    }
}
