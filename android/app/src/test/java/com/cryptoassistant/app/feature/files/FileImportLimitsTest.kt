package com.cryptoassistant.app.feature.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileImportLimitsTest {

    @Test
    fun `file count limit is inclusive at 200`() {
        assertFalse(FileImportLimits.isTooManyFiles(200))
        assertTrue(FileImportLimits.isTooManyFiles(201))
    }

    @Test
    fun `known total size limit handles unknown and overflow values`() {
        val limit = FileImportLimits.MAX_TOTAL_BYTES
        assertFalse(FileImportLimits.exceedsTotalBytes(0, null))
        assertFalse(FileImportLimits.exceedsTotalBytes(0, -1))
        assertFalse(FileImportLimits.exceedsTotalBytes(limit - 1, 1))
        assertTrue(FileImportLimits.exceedsTotalBytes(limit, 1))
        assertTrue(FileImportLimits.exceedsTotalBytes(0, Long.MAX_VALUE))
    }
}
