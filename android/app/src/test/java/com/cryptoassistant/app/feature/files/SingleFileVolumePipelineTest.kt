package com.cryptoassistant.app.feature.files

import crypto.file.FileCancelledException
import crypto.volume.VolumeCryptoEngine
import crypto.volume.VolumePartPlan
import crypto.volume.VolumePartSource
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.LocalDateTime
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 单文件直接分卷：不套 ZIP，合并后还原原文件，且仍受整包哈希/签名保护。 */
class SingleFileVolumePipelineTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val now = LocalDateTime.of(2026, 11, 7, 12, 0, 0)

    private fun fileSource(file: File): VolumePartSource = object : VolumePartSource {
        override fun length(): Long = file.length()

        override fun lastModified(): Long = file.lastModified()

        override fun openAt(offset: Long): InputStream {
            val input = FileInputStream(file)
            input.channel.position(offset)
            return input
        }
    }

    private fun keys(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun `slices a file into parts and restores the exact original`() {
        val keys = keys()
        val data = ByteArray(20_000).also { Random(7).nextBytes(it) }
        val source = temporary.newFile("movie.mkv").apply { writeBytes(data) }
        val directory = temporary.newFolder("task")
        val limit = VolumePartPlan.HEADER_RESERVE_BYTES + 512L

        val result = SingleFileVolumePipeline.encrypt(
            sourceName = "movie.mkv",
            source = fileSource(source),
            privateKey = keys.private,
            recipient = keys.public,
            recipientName = "接收方",
            directory = directory,
            volumeLimitBytes = limit,
            onHashProgress = { _, _ -> },
            onVolumeProgress = { _, _, _, _ -> },
            cancel = AtomicBoolean(false),
            now = now,
        )

        assertFalse("单文件分卷不是归档", result.isArchive)
        assertFalse(result.sourceIsFolder)
        assertTrue("小容量应切成多卷", (result.volumeCount ?: 0) > 1)
        assertEquals(result.volumeCount, result.parts.size)
        assertEquals(result.parts.first(), result.file)
        assertEquals("movie.mkv", result.sourceName)
        assertEquals(result.parts.sumOf { it.length() }, result.bytes)
        assertTrue("密文应比原文大", result.bytes > data.size)
        result.parts.forEach { part ->
            assertTrue("每卷不得超过上限", part.length() <= limit)
            assertTrue(part.name.endsWith(".aegix"))
        }

        val inputs = result.parts.map { part ->
            val info = part.inputStream().use { stream ->
                VolumeCryptoEngine.readInfo(stream, keys.private)
            }
            assertFalse(info.sourceIsArchive)
            assertEquals("movie.mkv", info.archiveName)
            assertEquals(1, info.sourceFileCount)
            ExternalPartInput(info, part.name) { part.inputStream() }
        }

        val mergedDirectory = temporary.newFolder("merged")
        val merged = ArchivePartReader.merge(
            parts = inputs,
            descriptor = inputs.first().info,
            privateKey = keys.private,
            senderCandidates = listOf(keys.public),
            directory = mergedDirectory,
            temporaryName = "merged.part",
            onProgress = { _, _, _, _ -> },
            cancel = AtomicBoolean(false),
        )

        assertEquals(FileTaskMode.DECRYPT, merged.mode)
        assertFalse("合并结果应还原原文件而不是 ZIP", merged.isArchive)
        assertFalse(merged.sourceIsFolder)
        assertEquals("movie.mkv", merged.displayName)
        assertEquals("movie.mkv", merged.sourceName)
        assertEquals(result.volumeCount, merged.volumeCount)
        assertEquals(true, merged.contentHashVerified)
        assertEquals(true, merged.signatureValid)
        assertArrayEquals(data, merged.file.readBytes())
    }

    @Test
    fun `cancel before hashing leaves no parts`() {
        val keys = keys()
        val source = temporary.newFile("movie.mkv").apply { writeBytes(ByteArray(4_000)) }
        val directory = temporary.newFolder("cancelled")

        assertThrows(FileCancelledException::class.java) {
            SingleFileVolumePipeline.encrypt(
                sourceName = "movie.mkv",
                source = fileSource(source),
                privateKey = keys.private,
                recipient = keys.public,
                recipientName = null,
                directory = directory,
                volumeLimitBytes = VolumePartPlan.HEADER_RESERVE_BYTES + 512L,
                onHashProgress = { _, _ -> },
                onVolumeProgress = { _, _, _, _ -> },
                cancel = AtomicBoolean(true),
                now = now,
            )
        }

        assertTrue(
            "取消不得留下分卷：${directory.listFiles()?.map { it.name }}",
            directory.listFiles().isNullOrEmpty(),
        )
    }
}
