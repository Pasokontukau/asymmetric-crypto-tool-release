package crypto.volume;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** VolumeSetPlan 的集合判定：齐套、缺卷、重卷、混包、越界与单卷。 */
public class VolumeSetPlanTest {

    private static final String SET = "set-abc123";
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static VolumeInfo part(int index, int count) {
        return part(SET, index, count, "photos.zip", SHA);
    }

    private static VolumeInfo part(String setId, int index, int count, String archiveName, String sha) {
        return new VolumeInfo(
                archiveName + ".part" + String.format("%03d", index),
                1024L,
                1,
                1024L,
                false,
                SHA,
                "2026-11-05T00:00:00Z",
                Collections.singletonList(2048L),
                setId,
                index,
                count,
                archiveName,
                count == 1 ? 1024L : 4096L,
                sha,
                false,
                true,
                3
        );
    }

    private static VolumeInfo filePart(int index, int count) {
        return new VolumeInfo(
                "movie.mkv.part" + String.format("%03d", index),
                1024L,
                1,
                1024L,
                false,
                SHA,
                "2026-11-07T00:00:00Z",
                Collections.singletonList(2048L),
                SET,
                index,
                count,
                "photos.zip",
                count == 1 ? 1024L : 4096L,
                SHA,
                false,
                false,
                1
        );
    }

    @Test
    public void rawFileSetIsOrderedAndComplete() {
        VolumeSetPlan.Result result = VolumeSetPlan.plan(
                Arrays.asList(filePart(2, 2), filePart(1, 2)));
        assertEquals(VolumeSetPlan.Status.COMPLETE, result.getStatus());
        assertFalse(result.getOrderedParts().get(0).getSourceIsArchive());
        assertTrue(result.getOrderedParts().get(0).isFilePartSet());
    }

    @Test
    public void mixingFilePartsAndArchivePartsIsRejected() {
        VolumeSetPlan.Result result = VolumeSetPlan.plan(
                Arrays.asList(part(1, 2), filePart(2, 2)));
        assertEquals(VolumeSetPlan.Status.MIXED, result.getStatus());
        assertTrue(result.getDetail().contains("来源类型"));
    }

    @Test
    public void singlePartIsNotASet() {
        assertEquals(VolumeSetPlan.Status.NOT_A_SET,
                VolumeSetPlan.plan(Collections.singletonList(part(1, 1))).getStatus());
        assertFalse(VolumeSetPlan.isMultiPart(part(1, 1)));
    }

    @Test
    public void emptyInputIsNotASet() {
        assertEquals(VolumeSetPlan.Status.NOT_A_SET,
                VolumeSetPlan.plan(Collections.<VolumeInfo>emptyList()).getStatus());
        assertEquals(VolumeSetPlan.Status.NOT_A_SET, VolumeSetPlan.plan(null).getStatus());
    }

    @Test
    public void completeSetIsOrderedByPartIndex() {
        // 故意打乱顺序：文件名与选择顺序都不可信；partIndex 是 1-based。
        VolumeSetPlan.Result result = VolumeSetPlan.plan(Arrays.asList(part(3, 3), part(1, 3), part(2, 3)));
        assertEquals(VolumeSetPlan.Status.COMPLETE, result.getStatus());
        assertTrue(result.isComplete());
        List<VolumeInfo> ordered = result.getOrderedParts();
        assertEquals(3, ordered.size());
        assertEquals(1, ordered.get(0).getPartIndex());
        assertEquals(2, ordered.get(1).getPartIndex());
        assertEquals(3, ordered.get(2).getPartIndex());
        assertTrue(result.getMissingIndexes().isEmpty());
    }

    @Test
    public void missingMiddlePartReportsItsIndex() {
        VolumeSetPlan.Result result = VolumeSetPlan.plan(Arrays.asList(part(1, 3), part(3, 3)));
        assertEquals(VolumeSetPlan.Status.INCOMPLETE, result.getStatus());
        assertEquals(Collections.singletonList(2), result.getMissingIndexes());
        assertEquals(2, result.getOrderedParts().size());
        assertTrue(result.getDetail().contains("第2卷"));
    }

    @Test
    public void missingMultiplePartsAreSorted() {
        VolumeSetPlan.Result result = VolumeSetPlan.plan(Arrays.asList(part(3, 5), part(5, 5)));
        assertEquals(VolumeSetPlan.Status.INCOMPLETE, result.getStatus());
        assertEquals(Arrays.asList(1, 2, 4), result.getMissingIndexes());
    }

    @Test
    public void duplicatePartIndexIsRejected() {
        VolumeSetPlan.Result result = VolumeSetPlan.plan(Arrays.asList(part(1, 3), part(1, 3), part(2, 3)));
        assertEquals(VolumeSetPlan.Status.DUPLICATE, result.getStatus());
        assertTrue(result.getDetail().contains("第 1 卷"));
    }

    @Test
    public void mixedSetIdsAreRejected() {
        VolumeSetPlan.Result result = VolumeSetPlan.plan(Arrays.asList(part(1, 2), part("other-set", 2, 2, "photos.zip", SHA)));
        assertEquals(VolumeSetPlan.Status.MIXED, result.getStatus());
        assertTrue(result.getDetail().contains("集合 ID"));
    }

    @Test
    public void mixedArchiveShaIsRejected() {
        VolumeSetPlan.Result result = VolumeSetPlan.plan(Arrays.asList(
                part(1, 2),
                part(SET, 2, 2, "photos.zip", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")));
        assertEquals(VolumeSetPlan.Status.MIXED, result.getStatus());
        assertTrue(result.getDetail().contains("SHA-256"));
    }

    @Test
    public void mixedArchiveNameIsRejected() {
        VolumeSetPlan.Result result = VolumeSetPlan.plan(Arrays.asList(
                part(1, 2),
                part(SET, 2, 2, "other.zip", SHA)));
        assertEquals(VolumeSetPlan.Status.MIXED, result.getStatus());
        assertTrue(result.getDetail().contains("包名"));
    }

    @Test
    public void mixedPartCountIsRejected() {
        VolumeSetPlan.Result result = VolumeSetPlan.plan(Arrays.asList(part(1, 3), part(2, 2)));
        assertEquals(VolumeSetPlan.Status.MIXED, result.getStatus());
        assertTrue(result.getDetail().contains("卷数"));
    }

    @Test
    public void outOfRangeIndexIsRejected() {
        assertEquals(VolumeSetPlan.Status.INVALID_INDEX,
                VolumeSetPlan.plan(Arrays.asList(part(1, 2), part(3, 2))).getStatus());
        assertEquals(VolumeSetPlan.Status.INVALID_INDEX,
                VolumeSetPlan.plan(Arrays.asList(part(1, 2), part(0, 2))).getStatus());
        assertEquals(VolumeSetPlan.Status.INVALID_INDEX,
                VolumeSetPlan.plan(Arrays.asList(part(1, 2), part(-1, 2))).getStatus());
    }

    @Test
    public void receivedCountIgnoresDuplicatesAndSingles() {
        assertEquals(2, VolumeSetPlan.receivedCount(Arrays.asList(
                part(1, 3), part(1, 3), part(3, 3), part(1, 1))));
        assertEquals(0, VolumeSetPlan.receivedCount(null));
    }

    @Test
    public void twoPartSetIsSupported() {
        VolumeSetPlan.Result result = VolumeSetPlan.plan(Arrays.asList(part(2, 2), part(1, 2)));
        assertEquals(VolumeSetPlan.Status.COMPLETE, result.getStatus());
        assertEquals(2, result.getOrderedParts().size());
    }
}
