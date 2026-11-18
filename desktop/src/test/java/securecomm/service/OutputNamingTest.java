package securecomm.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import org.junit.Test;

/** 基础名格式、重名策略与进度文案截断。 */
public class OutputNamingTest {

    @Test
    public void baseNameUsesFixedTimestampFormat() {
        String name = OutputNaming.encryptedBaseName(LocalDateTime.of(2026, 11, 12, 15, 30, 0));
        assertEquals("secure_file_20261112_153000", name);
    }

    @Test
    public void uniqueNameStartsWithoutSequenceThenAddsTwo() throws Exception {
        File dir = Files.createTempDirectory("aegix-output-").toFile();
        String base = OutputNaming.encryptedBaseName(LocalDateTime.of(2026, 11, 12, 15, 30, 0));

        String first = OutputNaming.uniqueEncryptedName(dir, base);
        assertEquals("secure_file_20261112_153000.aegix", first);
        assertTrue(new File(dir, first).createNewFile());

        String second = OutputNaming.uniqueEncryptedName(dir, base);
        assertEquals("secure_file_20261112_153000_2.aegix", second);
    }

    @Test
    public void displayNameTruncatesAtTwentyChars() {
        assertEquals("short.txt", OutputNaming.truncateForDisplay("short.txt", 20));
        String longName = "abcdefghijklmnopqrstuvwxyz.txt";
        assertEquals("abcdefghijklmnopq...", OutputNaming.truncateForDisplay(longName, 20));
        assertEquals(20, OutputNaming.truncateForDisplay(longName, 20).length());
        assertNull(OutputNaming.truncateForDisplay(null, 20));
    }
}
