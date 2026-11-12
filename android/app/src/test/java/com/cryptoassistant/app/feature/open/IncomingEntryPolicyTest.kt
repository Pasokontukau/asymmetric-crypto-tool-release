package com.cryptoassistant.app.feature.open

import com.cryptoassistant.app.feature.files.FileTaskMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingEntryPolicyTest {

    @Test
    fun `LOW 置信度兜底为加密`() {
        val decision = EntryDecision(mode = null, confidence = EntryConfidence.LOW, reason = "none")
        assertEquals(FileTaskMode.ENCRYPT, IncomingEntryPolicy.modeFor(decision))
    }

    @Test
    fun `后缀命中 aegix 时保持解密`() {
        val decision = EntryDecision(
            mode = FileTaskMode.DECRYPT,
            confidence = EntryConfidence.HIGH,
            reason = "name:aegix",
        )
        assertEquals(FileTaskMode.DECRYPT, IncomingEntryPolicy.modeFor(decision))
    }

    @Test
    fun `只有两侧都非空才算混合批量`() {
        assertFalse(EntryPlan.isMixed(decrypt = 0, encrypt = 3))
        assertFalse(EntryPlan.isMixed(decrypt = 2, encrypt = 0))
        assertTrue(EntryPlan.isMixed(decrypt = 2, encrypt = 3))
    }
}
