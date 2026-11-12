package crypto.volume;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** VolumeInfo JSON 的严格解析与边界校验（只覆盖当前外部分卷形态）。 */
public class VolumeInfoParserTest {

    private static final String HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SET_ID =
            "0123456789abcdef0123456789abcdef";

    private static String validJson() {
        return "{\"originalFileName\":\"Photos.zip.part001\","
                + "\"originalFileSize\":2048,"
                + "\"volumeCount\":1,"
                + "\"volumeSize\":2048,"
                + "\"sourceIsFolder\":false,"
                + "\"sha256Hash\":\"" + HASH + "\","
                + "\"createdAt\":\"2026-11-03 fixture\","
                + "\"encryptedVolumeSizes\":[3067],"
                + "\"setId\":\"" + SET_ID + "\","
                + "\"partIndex\":1,"
                + "\"partCount\":2,"
                + "\"archiveName\":\"Photos.zip\","
                + "\"archiveSize\":4096,"
                + "\"archiveSha256\":\"" + HASH + "\","
                + "\"archiveSourceIsFolder\":true,"
                + "\"sourceIsArchive\":true,"
                + "\"sourceFileCount\":3}";
    }

    @Test
    public void externalPartFieldsAreParsed() throws Exception {
        VolumeInfo info = VolumeInfoParser.parse(validJson());

        assertTrue(info.isExternalPart());
        assertEquals("Photos.zip.part001", info.getOriginalFileName());
        assertEquals(2048L, info.getOriginalFileSize());
        assertEquals(1, info.getVolumeCount());
        assertEquals(2048L, info.getVolumeSize());
        assertFalse(info.isSourceIsFolder());
        assertEquals(SET_ID, info.getSetId());
        assertEquals(1, info.getPartIndex());
        assertEquals(2, info.getPartCount());
        assertEquals("Photos.zip", info.getArchiveName());
        assertEquals(4096L, info.getArchiveSize());
        assertEquals(HASH, info.getArchiveSha256());
        assertTrue(info.getArchiveSourceIsFolder());
        assertTrue(info.getSourceIsArchive());
        assertEquals(3, info.getSourceFileCount());
        assertEquals(1, info.getEncryptedVolumeSizes().size());
        assertEquals(3067L, info.getEncryptedVolumeSizes().get(0).longValue());
    }

    @Test
    public void singlePartPackageIsAccepted() throws Exception {
        String json = validJson()
                .replace("\"partCount\":2", "\"partCount\":1")
                .replace("\"archiveSize\":4096", "\"archiveSize\":2048")
                .replace("\"sourceIsFolder\":false", "\"sourceIsFolder\":true");
        VolumeInfo info = VolumeInfoParser.parse(json);
        assertEquals(1, info.getPartCount());
        assertTrue(info.isSourceIsFolder());
        assertTrue(info.getArchiveSourceIsFolder());
    }

    @Test
    public void partialCollectionFieldsAreRejected() {
        assertFormatError(validJson().replace("\"partIndex\":1,", ""));
        assertFormatError(validJson().replace("\"setId\":\"" + SET_ID + "\",", ""));
        assertFormatError(validJson().replace("\"sourceFileCount\":3", ""));
    }

    @Test
    public void collectionRangesAreStrict() {
        assertFormatError(validJson().replace("\"partCount\":2", "\"partCount\":0"));
        assertFormatError(validJson().replace("\"partCount\":2", "\"partCount\":100"));
        assertFormatError(validJson().replace("\"partIndex\":1", "\"partIndex\":3"));
    }

    private static String rawFileJson() {
        return validJson()
                .replace("\"archiveName\":\"Photos.zip\"", "\"archiveName\":\"movie.mkv\"")
                .replace("\"archiveSourceIsFolder\":true", "\"archiveSourceIsFolder\":false")
                .replace("\"sourceIsArchive\":true", "\"sourceIsArchive\":false")
                .replace("\"sourceFileCount\":3", "\"sourceFileCount\":1");
    }

    @Test
    public void singleFilePartSetIsAccepted() throws Exception {
        VolumeInfo info = VolumeInfoParser.parse(rawFileJson());

        assertFalse(info.getSourceIsArchive());
        assertTrue(info.isFilePartSet());
        assertFalse(info.getArchiveSourceIsFolder());
        assertEquals(1, info.getSourceFileCount());
        assertEquals("movie.mkv", info.getArchiveName());
    }

    @Test
    public void singleFilePartSetMustStaySingleAndNotFolder() {
        assertFormatError(rawFileJson().replace("\"sourceFileCount\":1", "\"sourceFileCount\":2"));
        assertFormatError(rawFileJson().replace(
                "\"archiveSourceIsFolder\":false",
                "\"archiveSourceIsFolder\":true"));
        assertFormatError(rawFileJson().replace(
                "\"partCount\":2",
                "\"partCount\":1")
                .replace("\"archiveSize\":4096", "\"archiveSize\":2048")
                .replace("\"sourceIsFolder\":false", "\"sourceIsFolder\":true"));
    }

    @Test
    public void setIdMustBeUsable() {
        assertFormatError(validJson().replace(
                "\"setId\":\"" + SET_ID + "\"",
                "\"setId\":\"\""));
        assertFormatError(validJson().replace(
                "\"setId\":\"" + SET_ID + "\"",
                "\"setId\":\"\\u0001\""));
    }

    @Test
    public void archiveHashMustBeSha256() {
        assertFormatError(validJson().replace(
                "\"archiveSha256\":\"" + HASH + "\"",
                "\"archiveSha256\":\"abc\""));
    }

    @Test
    public void archiveNameMustBeASafeLeafName() {
        assertFormatError(validJson().replace("\"archiveName\":\"Photos.zip\"", "\"archiveName\":\"../Photos.zip\""));
        assertFormatError(validJson().replace("\"archiveName\":\"Photos.zip\"", "\"archiveName\":\"a/b.zip\""));
        assertFormatError(validJson().replace("\"archiveName\":\"Photos.zip\"", "\"archiveName\":\"\""));
    }

    @Test
    public void sourceFileCountMustBeBounded() {
        assertFormatError(validJson().replace("\"sourceFileCount\":3", "\"sourceFileCount\":-1"));
        assertFormatError(validJson().replace("\"sourceFileCount\":3", "\"sourceFileCount\":1000001"));
    }

    @Test
    public void multiPartMustNotClaimSourceIsFolder() {
        assertFormatError(validJson().replace("\"sourceIsFolder\":false", "\"sourceIsFolder\":true"));
    }

    @Test
    public void singlePartFolderFlagsMustBeConsistent() {
        String json = validJson()
                .replace("\"partCount\":2", "\"partCount\":1")
                .replace("\"archiveSize\":4096", "\"archiveSize\":2048")
                .replace("\"sourceIsFolder\":false", "\"sourceIsFolder\":true")
                .replace("\"archiveSourceIsFolder\":true", "\"archiveSourceIsFolder\":false");
        assertFormatError(json);
    }

    @Test
    public void volumeCountMustBeOneAndSizesMustMatch() {
        assertFormatError(validJson().replace("\"volumeCount\":1", "\"volumeCount\":2"));
        assertFormatError(validJson().replace("\"volumeSize\":2048", "\"volumeSize\":4096"));
        assertFormatError(validJson().replace("\"encryptedVolumeSizes\":[3067]", "\"encryptedVolumeSizes\":[3067,1389]"));
        assertFormatError(validJson().replace("\"encryptedVolumeSizes\":[3067],", ""));
    }

    @Test
    public void archiveSizeMustCoverTheCurrentPart() {
        assertFormatError(validJson().replace("\"archiveSize\":4096", "\"archiveSize\":1024"));
        String single = validJson()
                .replace("\"partCount\":2", "\"partCount\":1")
                .replace("\"archiveSize\":4096", "\"archiveSize\":2049");
        assertFormatError(single);
    }

    @Test
    public void unicodeFileNameIsAccepted() throws Exception {
        String json = validJson().replace("Photos.zip.part001", "季度汇报 2026.bin");
        VolumeInfo info = VolumeInfoParser.parse(json);
        assertEquals("季度汇报 2026.bin", info.getOriginalFileName());
    }

    @Test
    public void unknownFieldsAreIgnored() throws Exception {
        String json = validJson().substring(0, validJson().length() - 1)
                + ",\"future\":{\"a\":[1,2,3]}}";
        VolumeInfo info = VolumeInfoParser.parse(json);
        assertEquals(1, info.getVolumeCount());
    }

    @Test
    public void missingRequiredFieldIsRejected() {
        assertFormatError(validJson().replace("\"volumeCount\":1,", ""));
        assertFormatError(validJson().replace("\"originalFileName\":\"Photos.zip.part001\",", ""));
    }

    @Test
    public void wrongFieldTypeIsRejected() {
        assertFormatError(validJson().replace("\"volumeCount\":1", "\"volumeCount\":\"1\""));
        assertFormatError(validJson().replace("\"sourceIsFolder\":false", "\"sourceIsFolder\":\"false\""));
    }

    @Test
    public void duplicateFieldIsRejected() {
        assertFormatError(validJson().replace("\"volumeCount\":1,", "\"volumeCount\":1,\"volumeCount\":1,"));
    }

    @Test
    public void malformedHashIsRejected() {
        assertFormatError(validJson().replace(HASH, "abc"));
    }

    @Test
    public void trailingContentIsRejected() {
        assertFormatError(validJson() + "{}");
    }

    @Test
    public void deepNestingIsRejected() {
        StringBuilder deep = new StringBuilder("{\"future\":");
        for (int index = 0; index < VolumeInfoParser.MAX_JSON_DEPTH + 2; index++) {
            deep.append("{\"x\":");
        }
        deep.append("1");
        for (int index = 0; index < VolumeInfoParser.MAX_JSON_DEPTH + 2; index++) {
            deep.append('}');
        }
        deep.append('}');
        assertFormatError(deep.toString());
    }

    @Test
    public void emptyOrOversizedJsonIsRejected() {
        assertFormatError("");
        StringBuilder huge = new StringBuilder();
        for (int index = 0; index < VolumeInfoParser.MAX_JSON_CHARS + 1; index++) {
            huge.append('x');
        }
        assertFormatError(huge.toString());
    }

    private static void assertFormatError(String json) {
        try {
            VolumeInfoParser.parse(json);
            fail("应当拒绝非法 VolumeInfo: " + json);
        } catch (VolumeFormatException expected) {
            // 预期
        }
    }
}
