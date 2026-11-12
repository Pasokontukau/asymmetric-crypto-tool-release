package com.cryptoassistant.app.feature.files

import crypto.file.FileCancelledException
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P4 安全解压的 JVM 门禁。
 *
 * <p>覆盖修订 3 §5.7 的关键约束：路径穿越/盘符/反斜杠/控制字符拒绝、重复与
 * 文件目录冲突预检、空目录/0 字节/Unicode、目标重名 `_2` 不覆盖、取消/失败只清理
 * 本次创建项、provider 单条拒绝跳过。真实 SAF provider 差异留给 V17 设备路径。</p>
 */
class ArchiveExtractorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private data class EntrySpec(
        val name: String,
        val content: ByteArray? = null,
    )

    private fun zip(vararg specs: EntrySpec): File {
        val file = temporaryFolder.newFile("archive-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            specs.forEach { spec ->
                zip.putNextEntry(ZipEntry(spec.name))
                spec.content?.let { zip.write(it) }
                zip.closeEntry()
            }
        }
        return file
    }

    private fun extract(zip: File, target: File, cancel: AtomicBoolean = AtomicBoolean(false)) =
        ArchiveExtractor.extract(
            zipFile = zip,
            sink = FileArchiveSink(target),
            cancel = cancel,
        )

    @Test
    fun `extracts nested empty zero byte and unicode entries`() {
        val zip = zip(
            EntrySpec("Photos/"),
            EntrySpec("Photos/a.txt", "hello".toByteArray()),
            EntrySpec("Photos/sub/"),
            EntrySpec("Photos/sub/b.txt", "world".toByteArray()),
            EntrySpec("Photos/empty/"),
            EntrySpec("Photos/zero.bin", ByteArray(0)),
            EntrySpec("Photos/中文🙂.txt", "你好".toByteArray()),
        )
        val target = temporaryFolder.newFolder("target")

        val result = extract(zip, target)

        assertEquals(4, result.files)
        assertEquals(3, result.directories)
        assertEquals(0, result.renamed)
        assertEquals(0, result.skipped)
        assertEquals(5 + 5 + 0 + 6L, result.bytes)
        assertEquals("world", File(target, "Photos/sub/b.txt").readText())
        assertTrue(File(target, "Photos/empty").isDirectory)
        assertTrue(File(target, "Photos/zero.bin").length() == 0L)
        assertTrue(File(target, "Photos/中文🙂.txt").readText() == "你好")
    }

    @Test
    fun `rejects path traversal absolute drive backslash control and empty segments`() {
        val unsafeNames = listOf(
            "../evil.txt",
            "a/../b.txt",
            "/absolute.txt",
            "C:/evil.txt",
            "a\\b.txt",
            "a//b.txt",
            "a/\u0001bad.txt",
        )
        unsafeNames.forEach { name ->
            val target = temporaryFolder.newFolder("unsafe-${System.nanoTime()}")
            val zip = zip(EntrySpec(name, "x".toByteArray()))
            val error = assertThrows(ArchiveExtractException::class.java) {
                extract(zip, target)
            }
            assertEquals(
                "entry=$name",
                ArchiveExtractException.Reason.UNSAFE_PATH,
                error.reason,
            )
            assertTrue(
                "unsafe entry must not write anything: $name",
                target.listFiles().isNullOrEmpty(),
            )
        }
    }

    @Test
    fun `rejects too many path segments`() {
        val name = (1..33).joinToString("/") { "d$it" } + "/file.txt"
        val target = temporaryFolder.newFolder("segments")
        val error = assertThrows(ArchiveExtractException::class.java) {
            extract(zip(EntrySpec(name, "x".toByteArray())), target)
        }
        assertEquals(ArchiveExtractException.Reason.UNSAFE_PATH, error.reason)
    }

    @Test
    fun `rejects duplicate file entries and file directory conflicts before writing`() {
        // ZipOutputStream 拒绝完全同名条目，这里用大小写不同的同名路径验证
        // 大小写不敏感目标上的重复判定。
        val duplicate = zip(
            EntrySpec("root/", null),
            EntrySpec("root/a.txt", "1".toByteArray()),
            EntrySpec("root/A.TXT", "2".toByteArray()),
        )
        val target1 = temporaryFolder.newFolder("duplicate")
        val duplicateError = assertThrows(ArchiveExtractException::class.java) {
            extract(duplicate, target1)
        }
        assertEquals(ArchiveExtractException.Reason.CONFLICT, duplicateError.reason)
        assertTrue(target1.listFiles().isNullOrEmpty())

        val conflict = zip(
            EntrySpec("root/", null),
            EntrySpec("root/a.txt", "1".toByteArray()),
            EntrySpec("root/a.txt/", null),
        )
        val target2 = temporaryFolder.newFolder("conflict")
        val conflictError = assertThrows(ArchiveExtractException::class.java) {
            extract(conflict, target2)
        }
        assertEquals(ArchiveExtractException.Reason.CONFLICT, conflictError.reason)
        assertTrue(target2.listFiles().isNullOrEmpty())
    }

    @Test
    fun `keeps existing destination files and appends _2 for duplicate names`() {
        val target = temporaryFolder.newFolder("existing")
        File(target, "Photos").mkdirs()
        File(target, "Photos/a.txt").writeText("old")
        File(target, "other.txt").writeText("keep")

        val result = extract(
            zip(
                EntrySpec("Photos/"),
                EntrySpec("Photos/a.txt", "new".toByteArray()),
            ),
            target,
        )

        assertEquals(1, result.files)
        assertEquals(1, result.directories)
        assertEquals(1, result.renamed)
        assertEquals("old", File(target, "Photos/a.txt").readText())
        assertEquals("new", File(target, "Photos/a_2.txt").readText())
        assertEquals("keep", File(target, "other.txt").readText())
    }

    @Test
    fun `cancels and deletes only entries created in this run`() {
        val target = temporaryFolder.newFolder("cancel")
        File(target, "keep.txt").writeText("keep")
        val cancel = AtomicBoolean(false)

        val error = assertThrows(FileCancelledException::class.java) {
            ArchiveExtractor.extract(
                zipFile = zip(
                    EntrySpec("Photos/"),
                    EntrySpec("Photos/a.txt", "one".toByteArray()),
                    EntrySpec("Photos/b.txt", "two".toByteArray()),
                ),
                sink = FileArchiveSink(target),
                onProgress = { progress ->
                    if (progress.filesDone >= 1) cancel.set(true)
                },
                cancel = cancel,
            )
        }
        assertTrue(error.message.orEmpty().contains("取消"))
        assertEquals(
            setOf("keep.txt"),
            target.listFiles().orEmpty().map { it.name }.toSet(),
        )
        assertEquals("keep", File(target, "keep.txt").readText())
        assertFalse(File(target, "Photos").exists())
    }

    @Test
    fun `skips entries refused by the destination provider`() {
        val target = temporaryFolder.newFolder("skip")
        val delegate = FileArchiveSink(target)
        val sink = object : ArchiveSink {
            override fun list(parentPath: String) = delegate.list(parentPath)
            override fun createDirectory(parentPath: String, name: String) =
                delegate.createDirectory(parentPath, name)

            override fun createFile(parentPath: String, name: String): SinkFile {
                if (name == "b.txt") throw ArchiveSinkSkipException("provider refused")
                return delegate.createFile(parentPath, name)
            }

            override fun delete(path: String) = delegate.delete(path)
        }

        val result = ArchiveExtractor.extract(
            zipFile = zip(
                EntrySpec("Photos/"),
                EntrySpec("Photos/a.txt", "one".toByteArray()),
                EntrySpec("Photos/b.txt", "two".toByteArray()),
            ),
            sink = sink,
        )

        assertEquals(1, result.files)
        assertEquals(1, result.skipped)
        assertTrue(File(target, "Photos/a.txt").exists())
        assertFalse(File(target, "Photos/b.txt").exists())
    }

    @Test
    fun `random mutations never write outside the destination`() {
        val original = zip(
            EntrySpec("Photos/", null),
            EntrySpec("Photos/a.txt", "alpha".toByteArray()),
            EntrySpec("Photos/sub/", null),
            EntrySpec("Photos/sub/b.txt", ByteArray(64) { it.toByte() }),
            EntrySpec("Photos/中文.txt", "你好".toByteArray()),
        ).readBytes()
        val random = Random(20261111L)

        repeat(40) { round ->
            val mutated = when (random.nextInt(3)) {
                0 -> original.copyOf().also { bytes ->
                    bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte()
                }
                1 -> original.copyOf().also { bytes ->
                    repeat(1 + random.nextInt(4)) {
                        bytes[random.nextInt(bytes.size)] = 0
                    }
                }
                2 -> original.copyOf(random.nextInt(original.size))
                else -> error("unreachable")
            }

            val sandbox = temporaryFolder.newFolder("mutation-$round")
            val target = File(sandbox, "out").apply { mkdirs() }
            val zipFile = File(sandbox, "mutated.zip").apply { writeBytes(mutated) }

            val result = try {
                extract(zipFile, target)
            } catch (expected: Exception) {
                // 结构损坏/安全拒绝都允许；关键是不得写出目标目录。
                null
            }

            // sandbox 只能包含 out（以及我们自己的输入 zip）。
            val unexpected = sandbox.listFiles().orEmpty()
                .filter { it.name != "out" && it.name != "mutated.zip" }
            assertTrue(
                "第 $round 轮在目标目录外写入了 ${unexpected.map { it.name }}",
                unexpected.isEmpty(),
            )
            target.walkTopDown().forEach { file ->
                assertTrue(
                    "第 $round 轮路径越界: ${file.absolutePath}",
                    file.canonicalPath.startsWith(target.canonicalPath + File.separator) ||
                        file.canonicalPath == target.canonicalPath,
                )
            }
            if (result != null) {
                assertTrue(result.files <= ArchiveExtractor.MAX_ENTRIES)
                assertTrue(result.bytes <= ArchiveExtractor.MAX_TOTAL_BYTES)
                assertTrue(
                    "成功解压的实际文件数不能超过上限",
                    target.walkTopDown().count { it.isFile } <= ArchiveExtractor.MAX_ENTRIES,
                )
            }
        }
    }

    @Test
    fun `rejects archives with more than the entry limit`() {
        val specs = (1..ArchiveExtractor.MAX_ENTRIES + 1).map { index ->
            EntrySpec("root/$index.txt", null)
        }
        val target = temporaryFolder.newFolder("limit")
        val zipFile = zip(*specs.toTypedArray())

        val error = assertThrows(ArchiveExtractException::class.java) {
            extract(zipFile, target)
        }
        assertEquals(ArchiveExtractException.Reason.LIMIT, error.reason)
        assertTrue(target.listFiles().isNullOrEmpty())
    }
}
