package com.cryptoassistant.app.feature.files

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveProgressTest {

    @Test
    fun `packaging prefers byte progress over file count`() {
        assertEquals(
            0.25f,
            ArchiveProgress.overall(
                phase = ArchivePhase.PACKAGING,
                filesDone = 1,
                filesTotal = 2,
                processed = 25,
                total = 100,
                volumeIndex = null,
                volumeTotal = null,
            ),
            0.0001f,
        )
    }

    @Test
    fun `packaging falls back to file count when bytes are unknown`() {
        assertEquals(
            0.5f,
            ArchiveProgress.overall(
                phase = ArchivePhase.PACKAGING,
                filesDone = 3,
                filesTotal = 6,
                processed = 0,
                total = 0,
                volumeIndex = null,
                volumeTotal = null,
            ),
            0.0001f,
        )
    }

    @Test
    fun `writing combines completed volumes with current volume progress`() {
        assertEquals(
            0.375f,
            ArchiveProgress.overall(
                phase = ArchivePhase.WRITING,
                filesDone = 0,
                filesTotal = 0,
                processed = 50,
                total = 100,
                volumeIndex = 2,
                volumeTotal = 4,
            ),
            0.0001f,
        )
    }

    @Test
    fun `writing without a volume index starts at zero`() {
        assertEquals(
            0f,
            ArchiveProgress.overall(
                phase = ArchivePhase.WRITING,
                filesDone = 0,
                filesTotal = 0,
                processed = 0,
                total = 0,
                volumeIndex = null,
                volumeTotal = 2,
            ),
            0.0001f,
        )
    }

    @Test
    fun `missing phase reports zero`() {
        assertEquals(
            0f,
            ArchiveProgress.overall(
                phase = null,
                filesDone = 10,
                filesTotal = 10,
                processed = 100,
                total = 100,
                volumeIndex = 1,
                volumeTotal = 1,
            ),
            0.0001f,
        )
    }
}
