package com.cryptoassistant.app.feature.files

import java.io.File
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 密文命名：`secure_file_<yyyyMMdd_HHmmss>.aegix`，
 * 可见文件名里不出现原文件名与后缀（它们只加密存在文件头里）。
 */
class FileTaskStorageNamingTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val sample = LocalDateTime.of(2026, 10, 24, 9, 5, 7)

    @Test
    fun `base name follows the desktop timestamp convention`() {
        assertEquals("secure_file_20261024_090507", FileTaskStorage.encryptedFileBaseName(sample))
        assertEquals("archive_20261024_090507", FileTaskStorage.archiveBaseName(sample))
    }

    @Test
    fun `leading dot is preserved for archive members`() {
        assertEquals(".gitignore", FileTaskStorage.sanitizeFileName(".gitignore", "file"))
        assertEquals(".nomedia", FileTaskStorage.sanitizeFileName(".nomedia", "file"))
        assertEquals("name", FileTaskStorage.sanitizeFileName("name. ", "file"))
    }

    @Test
    fun `output name carries no original name or extension`() {
        val directory = temporary.newFolder()
        val name = FileTaskStorage.encryptedOutputName(directory, sample)

        assertEquals("secure_file_20261024_090507.aegix", name)
        assertFalse(name.contains("工资表"))
        assertFalse(name.contains(".docx"))
    }

    @Test
    fun `duplicate names get a numeric suffix instead of overwriting`() {
        val directory = temporary.newFolder()
        File(directory, "secure_file_20261024_090507.aegix").writeText("first")

        assertEquals(
            "secure_file_20261024_090507_2.aegix",
            FileTaskStorage.encryptedOutputName(directory, sample),
        )

        File(directory, "secure_file_20261024_090507_2.aegix").writeText("second")
        assertEquals(
            "secure_file_20261024_090507_3.aegix",
            FileTaskStorage.encryptedOutputName(directory, sample),
        )
    }

    @Test
    fun `reserved names keep batch outputs distinct across task directories`() {
        val directory = temporary.newFolder()
        // 批量任务每个文件写进不同目录，文件系统里看不到别的目录已经用过这个名字，
        // 所以调用方用 reservedNames 传递本批已用名。
        val used = mutableSetOf<String>()
        val first = FileTaskStorage.encryptedOutputName(directory, sample, used)
        used += first

        val second = FileTaskStorage.encryptedOutputName(directory, sample, used)
        used += second

        assertEquals("secure_file_20261024_090507.aegix", first)
        assertEquals("secure_file_20261024_090507_2.aegix", second)
        assertEquals(2, used.size)
    }

    @Test
    fun `original file name is still what gets stored in the header`() {
        // 头部里的名字由 FileCryptoEngine 加密保存，所以带后缀的原名必须原样传进去；
        // 这里锁定“落盘名”和“头部名”是两件互不影响的事。
        val directory = temporary.newFolder()
        val storedName = "季度汇报.docx"
        assertEquals("季度汇报.docx", FileTaskStorage.sanitizeFileName(storedName, "file"))
        assertFalse(FileTaskStorage.encryptedOutputName(directory, sample).contains(storedName))
    }
}
