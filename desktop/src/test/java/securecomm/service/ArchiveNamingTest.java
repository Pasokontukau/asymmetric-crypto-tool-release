package securecomm.service;

import java.time.LocalDateTime;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ArchiveNamingTest {

    @Test
    public void rootNameUsesFixedPrefixAndTimestamp() {
        assertEquals("archive_20261112_090807",
                ArchiveNaming.newArchiveRootName(LocalDateTime.of(2026, 11, 12, 9, 8, 7)));
        assertEquals("archive_", ArchiveNaming.ARCHIVE_PREFIX);
    }

    @Test
    public void archiveNamePrefersFolderZipThenRegisteredThenFileName() {
        assertEquals("季度 报告.zip",
                ArchiveNaming.archiveNameFor(true, "季度 报告", "ignored.zip", "aegix-folder-1.zip"));
        assertEquals("registered.zip",
                ArchiveNaming.archiveNameFor(false, null, "registered.zip", "plain.txt"));
        assertEquals("plain.txt",
                ArchiveNaming.archiveNameFor(false, null, null, "plain.txt"));
    }

    @Test
    public void displayNameMarksFolderArchives() {
        assertEquals("季度 报告（文件夹）",
                ArchiveNaming.displayNameFor(true, "季度 报告", "aegix-folder-1.zip"));
        assertEquals("plain.txt", ArchiveNaming.displayNameFor(false, null, "plain.txt"));
    }
}
