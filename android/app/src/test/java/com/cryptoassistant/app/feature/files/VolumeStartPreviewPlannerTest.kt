package com.cryptoassistant.app.feature.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeStartPreviewPlannerTest {

    private val label = "50 MB"

    @Test
    fun `single file under the limit has no preview`() {
        assertNull(
            VolumeStartPreviewPlanner.singleFile(
                fileName = "photo.jpg",
                fileBytes = ArchiveVolumeSize.MB_50.bytes,
                volumeLimitBytes = ArchiveVolumeSize.MB_50.bytes,
                volumeLimitLabel = label,
            ),
        )
    }

    @Test
    fun `single file over the limit reports exact parts`() {
        val preview = VolumeStartPreviewPlanner.singleFile(
            fileName = "video.mp4",
            fileBytes = 120_000_000,
            volumeLimitBytes = ArchiveVolumeSize.MB_50.bytes,
            volumeLimitLabel = label,
        )!!
        assertEquals(3, preview.totalParts)
        assertEquals("video.mp4", preview.lines.single().name)
        assertFalse(preview.lines.single().packaged)
        assertFalse(preview.tooMany)
    }

    @Test
    fun `packaged selection under the limit has no preview`() {
        assertNull(
            VolumeStartPreviewPlanner.archive(
                displayName = "3 个文件",
                sourceBytes = 10_000_000,
                fileCount = 3,
                archiveRootName = "archive",
                volumeLimitBytes = ArchiveVolumeSize.MB_50.bytes,
                volumeLimitLabel = label,
            ),
        )
    }

    @Test
    fun `packaged selection over the limit is marked as an estimate`() {
        val preview = VolumeStartPreviewPlanner.archive(
            displayName = "3 个文件",
            sourceBytes = 120_000_000,
            fileCount = 3,
            archiveRootName = "archive",
            volumeLimitBytes = ArchiveVolumeSize.MB_50.bytes,
            volumeLimitLabel = label,
        )!!
        assertEquals(3, preview.totalParts)
        assertTrue(preview.lines.single().packaged)
        assertEquals("50 MB", preview.volumeLimitLabel)
    }

    @Test
    fun `more than twenty volumes is flagged`() {
        val preview = VolumeStartPreviewPlanner.archive(
            displayName = "many",
            sourceBytes = ArchivePartPolicy.MAX_ZIP_BYTES - 1_000_000,
            fileCount = 100,
            archiveRootName = "archive",
            volumeLimitBytes = ArchiveVolumeSize.MB_100.bytes,
            volumeLimitLabel = "100 MB",
        )!!
        assertTrue(preview.totalParts > 20)
        assertTrue(preview.tooMany)
    }

    @Test
    fun `estimated archive over four GiB is rejected before packing`() {
        assertThrows(IllegalArgumentException::class.java) {
            VolumeStartPreviewPlanner.archive(
                displayName = "too large",
                sourceBytes = ArchivePartPolicy.MAX_ZIP_BYTES,
                fileCount = 1,
                archiveRootName = "archive",
                volumeLimitBytes = ArchiveVolumeSize.GB_2.bytes,
                volumeLimitLabel = "2 GB",
            )
        }
    }
}
