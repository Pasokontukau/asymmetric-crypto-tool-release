package com.cryptoassistant.app.feature.files

import crypto.volume.VolumePartPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivePartPolicyTest {

    private val fourGiB = 4L * 1024 * 1024 * 1024

    @Test
    fun `five tiers produce 86 43 9 5 3 parts at four GiB`() {
        val expected = listOf(
            ArchiveVolumeSize.MB_50.bytes to 86,
            ArchiveVolumeSize.MB_100.bytes to 43,
            ArchiveVolumeSize.MB_500.bytes to 9,
            ArchiveVolumeSize.GB_1.bytes to 5,
            ArchiveVolumeSize.GB_2.bytes to 3,
        )
        expected.forEach { (limit, count) ->
            val plan = ArchivePartPolicy.plan(fourGiB, limit, "Photos.zip")
            assertEquals("limit=$limit", count, plan.partCount)
            for (part in 1..plan.partCount) {
                assertTrue(
                    "part $part must fit limit=$limit",
                    plan.getExpectedFinalBytes(part) <= limit,
                )
            }
        }
    }

    @Test
    fun `parts cover the whole archive and the last part takes the remainder`() {
        val limit = ArchiveVolumeSize.MB_50.bytes
        val plan = ArchivePartPolicy.plan(fourGiB, limit, "Photos.zip")
        var covered = 0L
        for (part in 1..plan.partCount) {
            covered += plan.getPartPlaintextBytes(part)
        }
        assertEquals(fourGiB, covered)
        assertEquals(
            fourGiB % plan.partCapacityBytes,
            plan.getPartPlaintextBytes(plan.partCount),
        )
    }

    @Test
    fun `exact capacity does not create an empty tail part`() {
        val limit = VolumePartPlan.HEADER_RESERVE_BYTES + 50L
        assertEquals(1, ArchivePartPolicy.partCount(50, limit, "a.zip"))
        assertEquals(2, ArchivePartPolicy.partCount(51, limit, "a.zip"))
        assertEquals(2, ArchivePartPolicy.partCount(100, limit, "a.zip"))
        assertEquals(50L, ArchivePartPolicy.plan(100, limit, "a.zip").getPartPlaintextBytes(2))
    }

    @Test
    fun `ninety nine parts allowed and one hundred rejected`() {
        val limit = VolumePartPlan.HEADER_RESERVE_BYTES + 1L
        assertEquals(99, ArchivePartPolicy.partCount(99, limit, "a.zip"))
        val error = assertThrows(IllegalArgumentException::class.java) {
            ArchivePartPolicy.partCount(100, limit, "a.zip")
        }
        assertTrue(error.message.orEmpty().contains("99"))
    }

    @Test
    fun `four GiB accepted and four GiB plus one rejected`() {
        assertEquals(fourGiB, ArchivePartPolicy.MAX_ZIP_BYTES)
        assertEquals(3, ArchivePartPolicy.partCount(fourGiB, ArchiveVolumeSize.GB_2.bytes, "a.zip"))
        assertThrows(IllegalArgumentException::class.java) {
            ArchivePartPolicy.partCount(fourGiB + 1, ArchiveVolumeSize.GB_2.bytes, "a.zip")
        }
    }

    @Test
    fun `set code is first six characters lowercased`() {
        assertEquals("abcdef", ArchivePartPolicy.setCode("ABCDEF0123456789"))
        assertEquals("a1b2c3", ArchivePartPolicy.setCode("a1b2c3d4"))
        assertThrows(IllegalArgumentException::class.java) {
            ArchivePartPolicy.setCode("abc")
        }
    }

    @Test
    fun `single and multi part names follow the external convention`() {
        val base = "secure_file_20261104_120000"
        val setId = "abcdef0123456789"

        assertEquals(
            "secure_file_20261104_120000.aegix",
            ArchivePartPolicy.outputName(base, setId, 1, 1),
        )
        assertEquals(
            "secure_file_20261104_120000_abcdef_vol001.aegix",
            ArchivePartPolicy.outputName(base, setId, 1, 3),
        )
        assertEquals(
            "secure_file_20261104_120000_abcdef_vol003.aegix",
            ArchivePartPolicy.outputName(base, setId, 3, 3),
        )
        assertEquals(
            listOf(
                "secure_file_20261104_120000_abcdef_vol001.aegix",
                "secure_file_20261104_120000_abcdef_vol002.aegix",
                "secure_file_20261104_120000_abcdef_vol003.aegix",
            ),
            ArchivePartPolicy.outputNames(base, setId, 3),
        )
    }

    @Test
    fun `plan reports name bytes and final size bounds`() {
        val plan = ArchivePartPolicy.plan(1_000, ArchiveVolumeSize.MB_50.bytes, "Photos.zip")
        assertEquals("Photos.zip".length + ".part999.part1".length, plan.partNameBytes)
        assertTrue(plan.getExpectedFinalBytes(1) <= ArchiveVolumeSize.MB_50.bytes)
        assertEquals(plan.getPartPlaintextBytes(1) + VolumePartPlan.HEADER_RESERVE_BYTES,
            plan.getExpectedFinalBytes(1))
    }
}
