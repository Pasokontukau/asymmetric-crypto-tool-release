package app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 版本检查的解析与比较是纯函数，这里把它们钉住；联网部分不测（要用真实 GitHub）。 */
public class UpdateCheckTest {

    @Test
    public void parsesTagFromReleasesJson() {
        String json = "{\"tag_name\":\"v1.4.2\",\"name\":\"1.4.2\",\"draft\":false}";
        assertEquals("v1.4.2", UpdateCheck.parseTag(json));
    }

    @Test
    public void missingOrUnusableTagIsNotParsed() {
        assertNull(UpdateCheck.parseTag("{}"));
        assertNull(UpdateCheck.parseTag("{\"tag_name\":\"\"}"));
        assertNull(UpdateCheck.parseTag("not json"));
        assertNull(UpdateCheck.parseTag(""));
        assertNull(UpdateCheck.parseTag(null));
    }

    @Test
    public void newerPatchMinorAndMajorAreDetected() {
        assertTrue(UpdateCheck.isNewer("v1.4.2", "1.4.1"));
        assertTrue(UpdateCheck.isNewer("1.5", "1.4.9"));
        assertTrue(UpdateCheck.isNewer("2.0.0", "1.9.9"));
        assertTrue(UpdateCheck.isNewer("1.4.10", "1.4.9"));
    }

    @Test
    public void sameOrOlderVersionIsNotNewer() {
        assertFalse(UpdateCheck.isNewer("1.4.1", "1.4.1"));
        assertFalse(UpdateCheck.isNewer("v1.4.1", "1.4.1"));
        assertFalse(UpdateCheck.isNewer("1.4.0", "1.4.1"));
        assertFalse(UpdateCheck.isNewer("1.3.9", "1.4.0"));
    }

    @Test
    public void nonNumericSuffixAndShortVersionsCompareNumerically() {
        assertEquals(0, UpdateCheck.compare("1.4-beta", "1.4.0"));
        assertTrue(UpdateCheck.isNewer("1.4.1-beta", "1.4.0"));
        assertFalse(UpdateCheck.isNewer("1.4", "1.4.0"));
        assertEquals(0, UpdateCheck.compare("", "0"));
    }
}
