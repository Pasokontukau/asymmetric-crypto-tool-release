package com.cryptoassistant.app.feature.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTaskResultTest {

    @Test
    fun `legacy result exposes its single file through allFiles`() {
        val file = File("/tmp/secure_file_20261104_120000.aegix")
        val result = FileTaskResult(
            mode = FileTaskMode.ENCRYPT,
            file = file,
            displayName = file.name,
            bytes = 123,
            sourceName = "report.pdf",
        )

        assertFalse(result.isArchive)
        assertEquals(listOf(file), result.allFiles)
        assertEquals(1, result.allFiles.size)
    }

    @Test
    fun `archive result keeps all parts and points file at the first part`() {
        val parts = (1..3).map { File("/tmp/secure_file_20261104_120000_abcdef_vol00$it.aegix") }
        val result = FileTaskResult(
            mode = FileTaskMode.ENCRYPT,
            file = parts.first(),
            displayName = parts.first().name,
            bytes = 900,
            sourceName = "Photos.zip",
            volumeCount = 3,
            isArchive = true,
            archiveFileCount = 7,
            parts = parts,
        )

        assertTrue(result.isArchive)
        assertEquals(3, result.volumeCount)
        assertEquals(7, result.archiveFileCount)
        assertEquals(parts, result.allFiles)
        assertEquals(parts.first(), result.file)
    }

    @Test
    fun `merged decrypt result exposes the source volume count`() {
        val file = File("/tmp/archive.zip")
        val result = FileTaskResult(
            mode = FileTaskMode.DECRYPT,
            file = file,
            displayName = file.name,
            bytes = 900,
            sourceName = "Photos.zip",
            volumeCount = 3,
            contentHashVerified = true,
            isArchive = true,
            mergedPartCount = 3,
        )

        assertTrue(result.isExtractableArchive())
        assertEquals(3, result.mergedPartCount)
    }

    @Test
    fun `package eligibility is limited to encrypt directory or multi selection`() {
        val directory = FileTaskUiState(
            mode = FileTaskMode.ENCRYPT,
            selectionKind = SelectionKind.DIRECTORY,
        )
        assertTrue(directory.packageEligible)
        assertTrue(directory.canPackage)
        assertFalse(directory.copy(packageEnabled = false).canPackage)

        val decryptDirectory = FileTaskUiState(
            mode = FileTaskMode.DECRYPT,
            selectionKind = SelectionKind.DIRECTORY,
        )
        assertFalse(decryptDirectory.packageEligible)

        val singleFile = FileTaskUiState(mode = FileTaskMode.ENCRYPT)
        assertFalse(singleFile.packageEligible)
    }
}
