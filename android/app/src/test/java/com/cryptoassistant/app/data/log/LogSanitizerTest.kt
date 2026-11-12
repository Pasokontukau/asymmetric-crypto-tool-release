package com.cryptoassistant.app.data.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {

    @Test
    fun `safe structured fields are unchanged`() {
        assertEquals(
            "scheme=content mime=application/octet-stream read=1 persist=0",
            LogSanitizer.sanitize(
                "scheme=content mime=application/octet-stream read=1 persist=0",
            ),
        )
        assertEquals("mime=*/* stream=2", LogSanitizer.sanitize("mime=*/* stream=2"))
    }

    @Test
    fun `uris and absolute paths are removed`() {
        val sanitized = LogSanitizer.sanitize(
            "failed /storage/emulated/0/secret.docx " +
                "content://com.example/secret and C:\\Users\\me\\secret.txt",
        )
        assertFalse(sanitized.orEmpty().contains("secret"))
        assertTrue(sanitized.orEmpty().contains("<path>"))
        assertTrue(sanitized.orEmpty().contains("<uri>"))
    }

    @Test
    fun `long base64 and pem are redacted`() {
        val base64 = "A".repeat(80)
        assertEquals("<redacted>", LogSanitizer.sanitize(base64))
        val pem = "-----BEGIN PRIVATE KEY-----\n$base64\n-----END PRIVATE KEY-----"
        assertEquals("<redacted>", LogSanitizer.sanitize(pem))
    }

    @Test
    fun `control characters are flattened and length is capped`() {
        val sanitized = LogSanitizer.sanitize("line1\n\tline2\r\nline3", maxChars = 20)
        assertEquals("line1 line2 line3", sanitized)
        assertEquals(
            20,
            LogSanitizer.sanitize("word ".repeat(100), maxChars = 20)?.length,
        )
    }

    @Test
    fun `blank messages are omitted`() {
        assertNull(LogSanitizer.sanitize(null))
        assertNull(LogSanitizer.sanitize("   \n\t "))
    }
}
