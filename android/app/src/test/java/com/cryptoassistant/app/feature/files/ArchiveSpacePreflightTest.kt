package com.cryptoassistant.app.feature.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArchiveSpacePreflightTest {

    @Test
    fun `estimate covers zip and encrypted parts plus headroom`() {
        val source = 100_000_000L
        assertEquals(
            source * 2 + ArchiveSpacePreflight.HEADROOM_BYTES,
            ArchiveSpacePreflight.estimateRequiredBytes(source),
        )
    }

    @Test
    fun `exactly enough space is accepted`() {
        val required = ArchiveSpacePreflight.estimateRequiredBytes(1_000L)
        ArchiveSpacePreflight.check(availableBytes = required, sourceBytes = 1_000L)
    }

    @Test
    fun `less than required space is rejected with both values`() {
        val source = 1_000L
        val required = ArchiveSpacePreflight.estimateRequiredBytes(source)
        val error = assertThrows(ArchiveSpaceException::class.java) {
            ArchiveSpacePreflight.check(availableBytes = required - 1, sourceBytes = source)
        }
        assertEquals(required, error.requiredBytes)
        assertEquals(required - 1, error.availableBytes)
    }

    @Test
    fun `unknown size or unreported space skips preflight`() {
        ArchiveSpacePreflight.check(availableBytes = 1, sourceBytes = null)
        ArchiveSpacePreflight.check(availableBytes = 0, sourceBytes = 1_000L)
        ArchiveSpacePreflight.check(availableBytes = -1, sourceBytes = 1_000L)
    }

    @Test
    fun `overflow saturates instead of wrapping negative`() {
        assertEquals(Long.MAX_VALUE, ArchiveSpacePreflight.estimateRequiredBytes(Long.MAX_VALUE))
    }
}
