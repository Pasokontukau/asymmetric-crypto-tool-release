package com.cryptoassistant.app.feature.files

import crypto.file.FileCancelledException
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveBuilderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun source(path: String, content: ByteArray): ArchiveSource =
        ArchiveSource(path, content.size.toLong()) { ByteArrayInputStream(content) }

    private fun source(path: String, content: String): ArchiveSource =
        source(path, content.toByteArray(Charsets.UTF_8))

    private fun zipNames(file: File): List<String> = ZipFile(file).use { zip ->
        zip.entries().asSequence().map { it.name }.toList()
    }

    private fun zipEntryText(file: File, name: String): String = ZipFile(file).use { zip ->
        zip.getInputStream(zip.getEntry(name)).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `builds sanitized zip with empty directory leading dot and case dedupe`() {
        val output = File(temporaryFolder.root, "archive.zip")
        val result = ArchiveBuilder.build(
            ArchiveBuildRequest(
                output = output,
                rootName = "Photos.zip",
                sources = listOf(
                    source("a.txt", "A"),
                    source("dir/.gitignore", ".gitignore"),
                    source("dir/A.txt", "A"),
                    source("dir/a.txt", "a"),
                    source("unicode/中文.txt", "中"),
                    source("zero.bin", ByteArray(0)),
                ),
                emptyDirectories = listOf("empty"),
                sourceIsFolder = true,
                volumeLimitBytes = 4_096L + 200L,
                packedAtMillis = 1_700_000_000_000L,
            ),
        )

        assertEquals("Photos.zip", result.archiveName)
        assertEquals(6, result.sourceFileCount)
        assertEquals(output.length(), result.archiveSize)
        assertEquals(sha256(output.readBytes()), result.archiveSha256)

        assertEquals(
            listOf(
                "Photos/",
                "Photos/empty/",
                "Photos/a.txt",
                "Photos/dir/.gitignore",
                "Photos/dir/A.txt",
                "Photos/dir/a_2.txt",
                "Photos/unicode/中文.txt",
                "Photos/zero.bin",
            ),
            zipNames(output),
        )
        assertEquals(".gitignore", zipEntryText(output, "Photos/dir/.gitignore"))
        assertTrue(zipNames(output).none { it.contains("..") || it.contains('\\') })

        assertEquals(result.plan.partCount, result.partSha256.size)
        val bytes = output.readBytes()
        var offset = 0
        result.partSha256.forEachIndexed { index, expected ->
            val length = result.plan.getPartPlaintextBytes(index + 1).toInt()
            assertEquals(
                expected,
                sha256(bytes.copyOfRange(offset, offset + length)),
            )
            offset += length
        }
        assertEquals(bytes.size, offset)
    }

    @Test
    fun `multi selection preserves selection order`() {
        val output = File(temporaryFolder.root, "multi.zip")
        val root = "archive_20261104_120000"
        val result = ArchiveBuilder.build(
            ArchiveBuildRequest(
                output = output,
                rootName = root,
                sources = listOf(
                    source("b.txt", "b"),
                    source("a.txt", "a"),
                ),
                sourceIsFolder = false,
                volumeLimitBytes = ArchiveVolumeSize.MB_500.bytes,
            ),
        )

        assertEquals("$root.zip", result.archiveName)
        assertEquals(listOf("$root/", "$root/b.txt", "$root/a.txt"), zipNames(output))
        assertEquals(1, result.plan.partCount)
    }

    @Test
    fun `sanitizes traversal control characters and trailing dots`() {
        val output = File(temporaryFolder.root, "safe.zip")
        ArchiveBuilder.build(
            ArchiveBuildRequest(
                output = output,
                rootName = "Photos",
                sources = listOf(
                    source("../secret.txt", "x"),
                    source("dir/\u0000bad.txt", "y"),
                    source("name. ", "z"),
                    source(" dir/ .hidden.txt", "h"),
                ),
                sourceIsFolder = true,
                volumeLimitBytes = ArchiveVolumeSize.MB_500.bytes,
            ),
        )

        val names = zipNames(output)
        assertTrue(names.contains("Photos/_/secret.txt"))
        assertTrue(names.contains("Photos/dir/_bad.txt"))
        assertTrue(names.contains("Photos/dir/.hidden.txt"))
        assertTrue(names.contains("Photos/name"))
        assertTrue(names.none { it.contains("..") || it.contains('\u0000') })
    }

    @Test
    fun `unknown source size is allowed`() {
        val output = File(temporaryFolder.root, "unknown.zip")
        val sources = listOf(
            ArchiveSource("unknown.bin", null) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        )
        val result = ArchiveBuilder.build(
            ArchiveBuildRequest(
                output = output,
                rootName = "files",
                sources = sources,
                sourceIsFolder = true,
                volumeLimitBytes = ArchiveVolumeSize.MB_500.bytes,
            ),
        )
        assertEquals(1, result.sourceFileCount)
        assertEquals(listOf("files/", "files/unknown.bin"), zipNames(output))
    }

    @Test
    fun `cancel deletes incomplete output and reports cancellation`() {
        val output = File(temporaryFolder.root, "cancelled.zip")
        assertThrows(FileCancelledException::class.java) {
            ArchiveBuilder.build(
                ArchiveBuildRequest(
                    output = output,
                    rootName = "files",
                    sources = listOf(source("a.txt", "a")),
                    sourceIsFolder = true,
                    volumeLimitBytes = ArchiveVolumeSize.MB_500.bytes,
                ),
                cancelled = AtomicBoolean(true),
            )
        }
        assertFalse(output.exists())
    }

    @Test
    fun `cancel after first file deletes partial zip`() {
        val output = File(temporaryFolder.root, "partial-cancel.zip")
        val cancelled = AtomicBoolean(false)
        assertThrows(FileCancelledException::class.java) {
            ArchiveBuilder.build(
                ArchiveBuildRequest(
                    output = output,
                    rootName = "files",
                    sources = listOf(
                        source("a.txt", "a".repeat(1_000)),
                        source("b.txt", "b".repeat(1_000)),
                    ),
                    sourceIsFolder = true,
                    volumeLimitBytes = ArchiveVolumeSize.MB_500.bytes,
                ),
                cancelled = cancelled,
                onProgress = { _, filesWritten ->
                    if (filesWritten == 1) cancelled.set(true)
                },
            )
        }
        assertFalse(output.exists())
    }

    @Test
    fun `existing output is rejected without overwriting it`() {
        val output = File(temporaryFolder.root, "existing.zip")
        output.writeText("keep")
        assertThrows(ArchiveBuildException::class.java) {
            ArchiveBuilder.build(
                ArchiveBuildRequest(
                    output = output,
                    rootName = "files",
                    sources = listOf(source("a.txt", "a")),
                    sourceIsFolder = true,
                    volumeLimitBytes = ArchiveVolumeSize.MB_500.bytes,
                ),
            )
        }
        assertEquals("keep", output.readText())
    }
}

class ArchiveWorkspaceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `creates task directory and deletes only that task`() {
        val root = temporaryFolder.newFolder("files")
        val task = ArchiveWorkspace.create(root, "task-1")
        val other = ArchiveWorkspace.create(root, "task-2")
        assertTrue(task.isDirectory)
        assertEquals(File(task, ArchiveWorkspace.ZIP_NAME), ArchiveWorkspace.zipFile(root, "task-1"))

        File(task, ArchiveWorkspace.ZIP_NAME).writeText("zip")
        File(other, ArchiveWorkspace.ZIP_NAME).writeText("other")
        assertTrue(ArchiveWorkspace.delete(root, "task-1"))
        assertFalse(task.exists())
        assertTrue(other.exists())
    }

    @Test
    fun `cold cleanup removes all archive work children`() {
        val root = temporaryFolder.newFolder("files")
        val first = ArchiveWorkspace.create(root, "a")
        val second = ArchiveWorkspace.create(root, "b")
        File(first, ArchiveWorkspace.ZIP_NAME).writeText("a")
        File(second, ArchiveWorkspace.ZIP_NAME).writeText("b")

        assertEquals(2, ArchiveWorkspace.cleanup(root))
        assertFalse(first.exists())
        assertFalse(second.exists())
        assertTrue(File(root, ArchiveWorkspace.DIRECTORY_NAME).isDirectory)
    }

    @Test
    fun `task id cannot escape archive work`() {
        val root = temporaryFolder.newFolder("files")
        val task = ArchiveWorkspace.create(root, "../escape")
        val base = File(root, ArchiveWorkspace.DIRECTORY_NAME).canonicalFile
        assertTrue(task.canonicalFile.toPath().startsWith(base.toPath()))
    }
}
