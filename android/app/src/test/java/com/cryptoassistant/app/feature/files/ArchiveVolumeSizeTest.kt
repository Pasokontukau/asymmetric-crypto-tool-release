package com.cryptoassistant.app.feature.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 单卷上限五档与旧 DataStore 值归一。 */
class ArchiveVolumeSizeTest {

    @Test
    fun `five tiers use exact decimal bytes`() {
        assertEquals(
            listOf(50_000_000L, 100_000_000L, 500_000_000L, 1_000_000_000L, 2_000_000_000L),
            ArchiveVolumeSize.entries.map { it.bytes },
        )
    }

    @Test
    fun `default is five hundred megabytes`() {
        assertEquals(ArchiveVolumeSize.MB_500, ArchiveVolumeSize.DEFAULT)
    }

    @Test
    fun `legacy twenty and two hundred megabytes fall back to default`() {
        assertEquals(
            ArchiveVolumeSize.MB_500,
            ArchiveVolumeSize.normalizeBytes(20L * 1024L * 1024L),
        )
        assertEquals(
            ArchiveVolumeSize.MB_500,
            ArchiveVolumeSize.normalizeBytes(200L * 1024L * 1024L),
        )
        assertEquals(ArchiveVolumeSize.MB_500, ArchiveVolumeSize.normalizeBytes(0L))
        assertEquals(ArchiveVolumeSize.MB_500, ArchiveVolumeSize.normalizeBytes(null))
    }

    @Test
    fun `legacy fifty one hundred and five hundred are kept`() {
        assertEquals(ArchiveVolumeSize.MB_50, ArchiveVolumeSize.normalizeBytes(50_000_000L))
        assertEquals(ArchiveVolumeSize.MB_100, ArchiveVolumeSize.normalizeBytes(100_000_000L))
        assertEquals(ArchiveVolumeSize.MB_500, ArchiveVolumeSize.normalizeBytes(500_000_000L))
        assertEquals(
            ArchiveVolumeSize.MB_50,
            ArchiveVolumeSize.normalizeBytes(50L * 1024L * 1024L),
        )
        assertEquals(
            ArchiveVolumeSize.MB_100,
            ArchiveVolumeSize.normalizeBytes(100L * 1024L * 1024L),
        )
        assertEquals(
            ArchiveVolumeSize.MB_500,
            ArchiveVolumeSize.normalizeBytes(500L * 1024L * 1024L),
        )
    }

    @Test
    fun `one and two gigabyte legacy values are kept`() {
        assertEquals(ArchiveVolumeSize.GB_1, ArchiveVolumeSize.normalizeBytes(1_000_000_000L))
        assertEquals(ArchiveVolumeSize.GB_2, ArchiveVolumeSize.normalizeBytes(2_000_000_000L))
        assertEquals(
            ArchiveVolumeSize.GB_1,
            ArchiveVolumeSize.normalizeBytes(1024L * 1024L * 1024L),
        )
        assertEquals(
            ArchiveVolumeSize.GB_2,
            ArchiveVolumeSize.normalizeBytes(2L * 1024L * 1024L * 1024L),
        )
    }

    @Test
    fun `stored names and numbers are normalized`() {
        assertEquals(ArchiveVolumeSize.MB_50, ArchiveVolumeSize.fromStored("MB_50"))
        assertEquals(
            ArchiveVolumeSize.GB_2,
            ArchiveVolumeSize.fromStored("2000000000"),
        )
        assertEquals(ArchiveVolumeSize.MB_500, ArchiveVolumeSize.fromStored("MB_20"))
        assertEquals(ArchiveVolumeSize.MB_500, ArchiveVolumeSize.fromStored("MB_200"))
        assertEquals(ArchiveVolumeSize.MB_500, ArchiveVolumeSize.fromStored("OFF"))
        assertEquals(ArchiveVolumeSize.MB_500, ArchiveVolumeSize.fromStored("broken"))
        assertEquals(ArchiveVolumeSize.MB_500, ArchiveVolumeSize.fromStored(null))
    }

    @Test
    fun `there is no off tier`() {
        assertFalse(ArchiveVolumeSize.entries.any { it.name == "OFF" })
    }
}
