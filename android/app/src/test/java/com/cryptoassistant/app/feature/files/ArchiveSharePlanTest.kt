package com.cryptoassistant.app.feature.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveSharePlanTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun part(name: String, bytes: Int): File =
        File(temporary.root, name).apply { writeBytes(ByteArray(bytes) { it.toByte() }) }

    private fun result(parts: List<File>): FileTaskResult = FileTaskResult(
        mode = FileTaskMode.ENCRYPT,
        file = parts.first(),
        displayName = parts.first().name,
        bytes = parts.sumOf { it.length() },
        sourceName = "Photos.zip",
        volumeCount = parts.size,
        isArchive = true,
        archiveFileCount = 2,
        parts = parts,
    )

    @Test
    fun `single volume keeps one part and octet stream mime`() {
        val single = part("secure_file_20261104_120000.aegix", 12)
        val plan = ArchiveSharePlan.from(result(listOf(single)))

        assertFalse(plan.isMultiVolume)
        assertEquals(1, plan.partCount)
        assertEquals(ArchiveSharePlan.MIME_TYPE, plan.mimeType)
        assertEquals(listOf(single), plan.files)
        assertEquals(12L, plan.totalBytes)
        assertEquals(1, plan.parts.single().partIndex)
    }

    @Test
    fun `multi volume sorts by file name part index not list order`() {
        val third = part("secure_file_20261104_120000_abcdef_vol003.aegix", 3)
        val first = part("secure_file_20261104_120000_abcdef_vol001.aegix", 1)
        val second = part("secure_file_20261104_120000_abcdef_vol002.aegix", 2)

        val plan = ArchiveSharePlan.from(result(listOf(third, first, second)))

        assertTrue(plan.isMultiVolume)
        assertEquals(3, plan.partCount)
        assertEquals(listOf(1, 2, 3), plan.parts.map { it.partIndex })
        assertEquals(listOf(first, second, third), plan.files)
        assertEquals(6L, plan.totalBytes)
    }

    @Test
    fun `missing part suffix falls back to stable list position`() {
        val first = part("first.aegix", 1)
        val second = part("second.aegix", 2)

        val plan = ArchiveSharePlan.fromFiles(listOf(first, second))

        assertEquals(listOf(1, 2), plan.parts.map { it.partIndex })
        assertEquals(listOf(first, second), plan.files)
    }

    @Test
    fun `empty archive has no parts to share`() {
        val plan = ArchiveSharePlan.fromFiles(emptyList())

        assertTrue(plan.isEmpty)
        assertFalse(plan.isMultiVolume)
        assertEquals(0, plan.partCount)
        assertEquals(0L, plan.totalBytes)
    }

    @Test
    fun `duplicate save names get numeric suffix before extension`() {
        val used = mutableSetOf("secure_file_vol001.aegix")

        assertEquals(
            "secure_file_vol001_2.aegix",
            ArchiveSharePlan.uniqueName("secure_file_vol001.aegix", used),
        )
        assertEquals(
            "secure_file_vol001_3.aegix",
            ArchiveSharePlan.uniqueName("secure_file_vol001.aegix", used),
        )
        assertEquals(
            ".gitignore_2",
            ArchiveSharePlan.uniqueName(".gitignore", mutableSetOf(".gitignore")),
        )
    }
}
