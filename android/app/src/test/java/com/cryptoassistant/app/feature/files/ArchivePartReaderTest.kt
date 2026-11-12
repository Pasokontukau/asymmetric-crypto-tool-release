package com.cryptoassistant.app.feature.files

import crypto.file.FileCancelledException
import crypto.volume.VolumeCryptoEngine
import crypto.volume.VolumeInfo
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 外部加密分卷的合并：齐套还原、整包哈希、缺卷/乱序/篡改/取消清理。 */
class ArchivePartReaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val now = LocalDateTime.of(2026, 11, 5, 9, 30, 0)

    private fun source(path: String, content: String): ArchiveSource =
        ArchiveSource(path, content.toByteArray().size.toLong()) {
            ByteArrayInputStream(content.toByteArray())
        }

    private fun encryptParts(
        sources: List<ArchiveSource>,
        limit: Long,
        rootName: String,
    ): Pair<FileTaskResult, KeyPair> {
        val keys = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        val result = ArchiveEncryptionPipeline.encrypt(
            sources = sources,
            rootName = rootName,
            emptyDirectories = emptyList(),
            sourceIsFolder = false,
            privateKey = keys.private,
            recipient = keys.public,
            recipientName = null,
            directory = temporaryFolder.newFolder("parts"),
            archiveRoot = temporaryFolder.root,
            taskId = "merge-${System.nanoTime()}",
            volumeLimitBytes = limit,
            onArchivePhase = {},
            onArchiveProgress = { _, _ -> },
            onVolumeProgress = { _, _, _, _ -> },
            cancel = AtomicBoolean(false),
            now = now,
        )
        return result to keys
    }

    private fun input(part: File, keys: KeyPair): ExternalPartInput {
        val info: VolumeInfo = part.inputStream().use { stream ->
            VolumeCryptoEngine.readInfo(stream, keys.private)
        }
        return ExternalPartInput(info, part.name) { part.inputStream() }
    }

    private fun assertNoOutput(directory: File) {
        assertTrue(
            "失败/取消不得留下合并结果：${directory.listFiles()?.map { it.name }}",
            directory.listFiles().isNullOrEmpty(),
        )
    }

    @Test
    fun `merges all parts into the original zip and verifies whole archive`() {
        val limit = crypto.volume.VolumePartPlan.HEADER_RESERVE_BYTES + 128L
        val (result, keys) = encryptParts(
            sources = listOf(
                source("one.txt", "a".repeat(2_000)),
                source("two.txt", "b".repeat(2_000)),
            ),
            limit = limit,
            rootName = "Photos",
        )
        assertTrue("应生成多卷", (result.volumeCount ?: 0) > 1)

        val inputs = result.parts.map { input(it, keys) }
        val directory = temporaryFolder.newFolder("merged")
        val merged = ArchivePartReader.merge(
            parts = inputs,
            descriptor = inputs.first().info,
            privateKey = keys.private,
            senderCandidates = listOf(keys.public),
            directory = directory,
            temporaryName = "merged.zip",
            onProgress = { _, _, _, _ -> },
            cancel = AtomicBoolean(false),
        )

        assertEquals(FileTaskMode.DECRYPT, merged.mode)
        assertTrue(merged.isArchive)
        assertEquals(inputs.first().info.partCount, merged.volumeCount)
        assertEquals(true, merged.contentHashVerified)
        assertEquals(true, merged.signatureValid)
        assertEquals(true, merged.partNamesVerified)
        assertEquals(inputs.first().info.sourceFileCount, merged.archiveFileCount)
        assertEquals("Photos.zip", merged.displayName)
        assertEquals(directory.listFiles()?.size, 1)

        ZipFile(merged.file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(names.contains("Photos/one.txt"))
            assertTrue(names.contains("Photos/two.txt"))
        }
    }

    @Test
    fun `volume order from filenames does not matter but wrong order fails the whole hash`() {
        val limit = crypto.volume.VolumePartPlan.HEADER_RESERVE_BYTES + 128L
        val (result, keys) = encryptParts(
            sources = listOf(
                source("one.txt", "a".repeat(2_000)),
                source("two.txt", "b".repeat(2_000)),
            ),
            limit = limit,
            rootName = "Photos",
        )
        val shuffled = result.parts.reversed().map { input(it, keys) }
        val directory = temporaryFolder.newFolder("shuffled")

        assertThrows(ArchiveMergeException::class.java) {
            ArchivePartReader.merge(
                parts = shuffled,
                descriptor = shuffled.first().info,
                privateKey = keys.private,
                senderCandidates = listOf(keys.public),
                directory = directory,
                temporaryName = "merged.zip",
                onProgress = { _, _, _, _ -> },
                cancel = AtomicBoolean(false),
            )
        }
        assertNoOutput(directory)
    }

    @Test
    fun `missing part is rejected before writing`() {
        val limit = crypto.volume.VolumePartPlan.HEADER_RESERVE_BYTES + 128L
        val (result, keys) = encryptParts(
            sources = listOf(
                source("one.txt", "a".repeat(2_000)),
                source("two.txt", "b".repeat(2_000)),
            ),
            limit = limit,
            rootName = "Photos",
        )
        val inputs = result.parts.map { input(it, keys) }
        val directory = temporaryFolder.newFolder("missing")

        assertThrows(IllegalArgumentException::class.java) {
            ArchivePartReader.merge(
                parts = inputs.dropLast(1),
                descriptor = inputs.first().info,
                privateKey = keys.private,
                senderCandidates = listOf(keys.public),
                directory = directory,
                temporaryName = "merged.zip",
                onProgress = { _, _, _, _ -> },
                cancel = AtomicBoolean(false),
            )
        }
        assertNoOutput(directory)
    }

    @Test
    fun `duplicate volume index is rejected`() {
        val limit = crypto.volume.VolumePartPlan.HEADER_RESERVE_BYTES + 128L
        val (result, keys) = encryptParts(
            sources = listOf(
                source("one.txt", "a".repeat(2_000)),
                source("two.txt", "b".repeat(2_000)),
            ),
            limit = limit,
            rootName = "Photos",
        )
        val inputs = result.parts.map { input(it, keys) }
        val directory = temporaryFolder.newFolder("duplicate")
        val duplicated = listOf(inputs.first()) + inputs.dropLast(1)

        assertThrows(IllegalArgumentException::class.java) {
            ArchivePartReader.merge(
                parts = duplicated,
                descriptor = inputs.first().info,
                privateKey = keys.private,
                senderCandidates = listOf(keys.public),
                directory = directory,
                temporaryName = "merged.zip",
                onProgress = { _, _, _, _ -> },
                cancel = AtomicBoolean(false),
            )
        }
        assertNoOutput(directory)
    }

    @Test
    fun `tampered part fails integrity and leaves no output`() {
        val (result, keys) = encryptParts(
            sources = listOf(source("one.txt", "a".repeat(500))),
            limit = ArchiveVolumeSize.MB_500.bytes,
            rootName = "Photos",
        )
        val original = result.parts.first()
        val tampered = File(temporaryFolder.root, "tampered.aegix")
        val bytes = original.readBytes()
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 0xFF).toByte()
        tampered.writeBytes(bytes)

        val directory = temporaryFolder.newFolder("tampered")
        assertThrows(Exception::class.java) {
            ArchivePartReader.merge(
                parts = listOf(input(tampered, keys)),
                descriptor = input(original, keys).info,
                privateKey = keys.private,
                senderCandidates = listOf(keys.public),
                directory = directory,
                temporaryName = "merged.zip",
                onProgress = { _, _, _, _ -> },
                cancel = AtomicBoolean(false),
            )
        }
        assertNoOutput(directory)
    }

    @Test
    fun `cancel deletes the partial zip`() {
        val limit = crypto.volume.VolumePartPlan.HEADER_RESERVE_BYTES + 128L
        val (result, keys) = encryptParts(
            sources = listOf(
                source("one.txt", "a".repeat(2_000)),
                source("two.txt", "b".repeat(2_000)),
            ),
            limit = limit,
            rootName = "Photos",
        )
        val inputs = result.parts.map { input(it, keys) }
        val directory = temporaryFolder.newFolder("cancelled")

        assertThrows(FileCancelledException::class.java) {
            ArchivePartReader.merge(
                parts = inputs,
                descriptor = inputs.first().info,
                privateKey = keys.private,
                senderCandidates = listOf(keys.public),
                directory = directory,
                temporaryName = "merged.zip",
                onProgress = { _, _, _, _ -> },
                cancel = AtomicBoolean(true),
            )
        }
        assertNoOutput(directory)
    }

    @Test
    fun `single volume package merges as one part`() {
        val (result, keys) = encryptParts(
            sources = listOf(source("only.txt", "hello")),
            limit = ArchiveVolumeSize.MB_500.bytes,
            rootName = "Photos",
        )
        assertEquals(1, result.parts.size)
        val inputs = result.parts.map { input(it, keys) }
        assertEquals(1, inputs.first().info.partCount)

        val directory = temporaryFolder.newFolder("single-merge")
        val merged = ArchivePartReader.merge(
            parts = inputs,
            descriptor = inputs.first().info,
            privateKey = keys.private,
            senderCandidates = listOf(keys.public),
            directory = directory,
            temporaryName = "merged.zip",
            onProgress = { _, _, _, _ -> },
            cancel = AtomicBoolean(false),
        )
        assertNotNull(merged.file)
        assertTrue(merged.file.length() > 0)
        assertEquals(1, merged.volumeCount)
    }
}
