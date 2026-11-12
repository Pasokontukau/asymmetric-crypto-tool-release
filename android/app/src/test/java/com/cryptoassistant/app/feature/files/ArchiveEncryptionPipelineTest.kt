package com.cryptoassistant.app.feature.files

import crypto.file.FileCancelledException
import crypto.volume.VolumeCryptoEngine
import crypto.volume.VolumePartPlan
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveEncryptionPipelineTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val now = LocalDateTime.of(2026, 11, 4, 12, 0, 0)

    private fun source(path: String, content: String): ArchiveSource =
        ArchiveSource(path, content.toByteArray().size.toLong()) {
            ByteArrayInputStream(content.toByteArray())
        }

    private fun encrypt(
        sources: List<ArchiveSource>,
        limit: Long,
        rootName: String,
        sourceIsFolder: Boolean,
        emptyDirectories: List<String> = emptyList(),
    ): Pair<FileTaskResult, KeyPair> {
        val keys = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        val result = ArchiveEncryptionPipeline.encrypt(
            sources = sources,
            rootName = rootName,
            emptyDirectories = emptyDirectories,
            sourceIsFolder = sourceIsFolder,
            privateKey = keys.private,
            recipient = keys.public,
            recipientName = "测试接收方",
            directory = temporary.newFolder("task"),
            archiveRoot = temporary.root,
            taskId = "task-${System.nanoTime()}",
            volumeLimitBytes = limit,
            onArchivePhase = {},
            onArchiveProgress = { _, _ -> },
            onVolumeProgress = { _, _, _, _ -> },
            cancel = AtomicBoolean(false),
            now = now,
        )
        return result to keys
    }

    @Test
    fun `single folder archive keeps one volume and folder semantics`() {
        val (result, keys) = encrypt(
            sources = listOf(
                source("notes.txt", "notes"),
                source("dir/.gitignore", "ignored"),
            ),
            limit = ArchiveVolumeSize.MB_500.bytes,
            rootName = "Photos",
            sourceIsFolder = true,
            emptyDirectories = listOf("empty"),
        )

        assertTrue(result.isArchive)
        assertEquals(1, result.volumeCount)
        assertEquals(1, result.parts.size)
        assertTrue(result.sourceIsFolder)
        assertEquals(2, result.archiveFileCount)
        assertEquals(result.parts.first(), result.file)
        assertEquals("secure_file_20261104_120000.aegix", result.file.name)
        assertEquals(result.parts.sumOf { it.length() }, result.bytes)
        assertTrue(
            "archive-work 必须在成功后清理",
            File(temporary.root, ArchiveWorkspace.DIRECTORY_NAME).listFiles().isNullOrEmpty(),
        )

        val zip = File(temporary.root, "single.zip")
        decryptAll(result, keys, zip)
        ZipFile(zip).use { archive ->
            val names = archive.entries().asSequence().map { it.name }.toList()
            assertTrue(names.contains("Photos/"))
            assertTrue(names.contains("Photos/notes.txt"))
            assertTrue(names.contains("Photos/dir/.gitignore"))
            assertTrue(names.contains("Photos/empty/"))
        }
    }

    @Test
    fun `small volume limit creates multiple volumes that reassemble to the zip`() {
        val limit = VolumePartPlan.HEADER_RESERVE_BYTES + 128L
        val (result, keys) = encrypt(
            sources = listOf(
                source("one.txt", "a".repeat(1_000)),
                source("two.txt", "b".repeat(1_000)),
            ),
            limit = limit,
            rootName = "Photos",
            sourceIsFolder = false,
        )

        assertTrue(result.isArchive)
        assertTrue("128 B 容量应产生多卷", (result.volumeCount ?: 0) > 1)
        assertEquals(result.volumeCount, result.parts.size)
        assertEquals(result.parts.first(), result.file)
        assertEquals(result.parts.sumOf { it.length() }, result.bytes)
        result.parts.forEach { part ->
            assertTrue("每卷不得超过上限", part.length() <= limit)
            assertTrue(part.name.startsWith("secure_file_20261104_120000_"))
            assertTrue(part.name.contains("_vol"))
            assertTrue(part.name.endsWith(".aegix"))
        }
        assertFalse(result.sourceIsFolder)

        val zip = File(temporary.root, "multi.zip")
        decryptAll(result, keys, zip)
        ZipFile(zip).use { archive ->
            val names = archive.entries().asSequence().map { it.name }.toList()
            assertTrue(names.contains("Photos/one.txt"))
            assertTrue(names.contains("Photos/two.txt"))
        }
    }

    @Test
    fun `cancel before zip leaves no parts or archive work`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        val directory = temporary.newFolder("cancelled-task")
        val archiveRoot = temporary.root
        val taskId = "cancelled"

        assertThrows(FileCancelledException::class.java) {
            ArchiveEncryptionPipeline.encrypt(
                sources = listOf(source("one.txt", "one")),
                rootName = "Photos",
                emptyDirectories = emptyList(),
                sourceIsFolder = true,
                privateKey = keys.private,
                recipient = keys.public,
                recipientName = null,
                directory = directory,
                archiveRoot = archiveRoot,
                taskId = taskId,
                volumeLimitBytes = ArchiveVolumeSize.MB_500.bytes,
                onArchivePhase = {},
                onArchiveProgress = { _, _ -> },
                onVolumeProgress = { _, _, _, _ -> },
                cancel = AtomicBoolean(true),
                now = now,
            )
        }

        assertTrue(directory.listFiles().isNullOrEmpty())
        assertTrue(File(archiveRoot, ArchiveWorkspace.DIRECTORY_NAME).listFiles().isNullOrEmpty())
    }

    private fun decryptAll(result: FileTaskResult, keys: KeyPair, output: File) {
        FileOutputStream(output).use { sink ->
            result.parts.forEachIndexed { index, part ->
                part.inputStream().use { input ->
                    val decrypted = VolumeCryptoEngine.decrypt(
                        input,
                        keys.private,
                        listOf(keys.public),
                        sink,
                        null,
                        null,
                    )
                    assertEquals(index + 1, decrypted.partIndex)
                    assertEquals(result.volumeCount, decrypted.partCount)
                    assertEquals(true, decrypted.sourceIsArchive)
                }
            }
        }
    }
}
