package com.cryptoassistant.app.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 解析与比较是纯函数，这里把它们钉住；网络路径见 `UpdateCheckHttpTest`，联网隔离见 `NetworkIsolationTest`。 */
class UpdateCheckTest {

    @Test
    fun `parses tag_name from a releases json body`() {
        val json = """
            {
              "url": "https://api.github.com/repos/x/y/releases/1",
              "tag_name": "v1.4.2",
              "name": "1.4.2",
              "draft": false
            }
        """.trimIndent()
        assertEquals("v1.4.2", UpdateCheck.parseTag(json))
    }

    @Test
    fun `missing or unusable tag is not parsed`() {
        assertNull(UpdateCheck.parseTag("{}"))
        assertNull(UpdateCheck.parseTag("""{"tag_name": ""}"""))
        assertNull(UpdateCheck.parseTag("not json"))
        assertNull(UpdateCheck.parseTag(""))
    }

    @Test
    fun `newer patch, minor and major are detected`() {
        assertTrue(UpdateCheck.isNewer("v1.4.2", "1.4.1"))
        assertTrue(UpdateCheck.isNewer("1.5", "1.4.9"))
        assertTrue(UpdateCheck.isNewer("2.0.0", "1.9.9"))
        assertTrue(UpdateCheck.isNewer("1.4.10", "1.4.9"))
    }

    @Test
    fun `same or older version is not newer`() {
        assertFalse(UpdateCheck.isNewer("1.4.1", "1.4.1"))
        assertFalse(UpdateCheck.isNewer("v1.4.1", "1.4.1"))
        assertFalse(UpdateCheck.isNewer("1.4.0", "1.4.1"))
        assertFalse(UpdateCheck.isNewer("1.3.9", "1.4.0"))
    }

    @Test
    fun `non numeric suffix and short versions compare numerically`() {
        assertEquals(0, UpdateCheck.compare("1.4-beta", "1.4.0"))
        assertTrue(UpdateCheck.isNewer("1.4.1-beta", "1.4.0"))
        assertFalse(UpdateCheck.isNewer("1.4", "1.4.0"))
        assertEquals(0, UpdateCheck.compare("", "0"))
    }
}
