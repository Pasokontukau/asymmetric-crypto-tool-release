package crypto.volume;

import crypto.file.FileCryptoEngine;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** VolumePartPlan 的纯预算、边界和 99/100 卷测试。 */
public class VolumePartPlanTest {

    private static final long MB = 1_000_000L;
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long[] LIMITS = {
            50L * MB,
            100L * MB,
            500L * MB,
            1_000L * MB,
            2_000L * MB
    };
    private static final int[] EXPECTED_PARTS = {86, 43, 9, 5, 3};

    @Test
    public void fiveTierCountsForFourGiBAndThreePointNineEightGiB() {
        long[] archives = {4L * GIB, 4_273_492_459L};
        for (long archive : archives) {
            for (int index = 0; index < LIMITS.length; index++) {
                VolumePartPlan plan = VolumePartPlan.create(
                        archive, LIMITS[index], 24, 4096);

                assertEquals(EXPECTED_PARTS[index], plan.getPartCount());
                assertEquals(LIMITS[index] - VolumePartPlan.HEADER_RESERVE_BYTES,
                        plan.getPartCapacityBytes());
                assertEquals(0L, plan.getPartOffset(1));
                assertEquals(archive, plan.getPartOffset(plan.getPartCount())
                        + plan.getPartPlaintextBytes(plan.getPartCount()));
                assertPartSizes(plan);
            }
        }
    }

    @Test
    public void archiveThatFitsProducesOnePart() {
        VolumePartPlan plan = VolumePartPlan.create(10L * MB, 50L * MB, 16, 4096);

        assertEquals(1, plan.getPartCount());
        assertEquals(10L * MB, plan.getPartPlaintextBytes(1));
        assertEquals(10L * MB + VolumePartPlan.HEADER_RESERVE_BYTES,
                plan.getExpectedFinalBytes(1));
        assertTrue(plan.getExpectedFinalBytes(1) <= plan.getVolumeLimitBytes());
    }

    @Test
    public void exactMultipleDoesNotCreateAnEmptyTail() {
        long limit = 50L * MB;
        long capacity = limit - VolumePartPlan.HEADER_RESERVE_BYTES;

        VolumePartPlan exact = VolumePartPlan.create(capacity * 2, limit, 24, 4096);
        assertEquals(2, exact.getPartCount());
        assertEquals(capacity, exact.getPartPlaintextBytes(1));
        assertEquals(capacity, exact.getPartPlaintextBytes(2));

        VolumePartPlan plusOne = VolumePartPlan.create(capacity * 2 + 1, limit, 24, 4096);
        assertEquals(3, plusOne.getPartCount());
        assertEquals(1L, plusOne.getPartPlaintextBytes(3));
    }

    @Test
    public void ninetyNinePartsAreAllowedAndOneHundredAreRejected() {
        VolumePartPlan plan = VolumePartPlan.create(99, 4097, 1, 2048);
        assertEquals(99, plan.getPartCount());
        assertEquals(1L, plan.getPartPlaintextBytes(99));
        assertEquals(4097L, plan.getExpectedFinalBytes(99));

        assertRejected(() -> VolumePartPlan.create(100, 4097, 1, 2048));
    }

    @Test
    public void singleFileCanExceedFourGiBWithoutTheArchiveCap() {
        long limit = 500L * MB;
        long capacity = limit - VolumePartPlan.HEADER_RESERVE_BYTES;
        long fileBytes = capacity * 9 + 123L;

        VolumePartPlan plan = VolumePartPlan.createForFile(fileBytes, limit, 24, 4096);
        assertEquals(10, plan.getPartCount());
        assertEquals(fileBytes, plan.getArchiveBytes());
        assertEquals(123L, plan.getPartPlaintextBytes(10));
    }

    @Test
    public void singleFileUsesTheSameNinetyNinePartCeiling() {
        long limit = 4097L;
        VolumePartPlan plan = VolumePartPlan.createForFile(99, limit, 1, 2048);
        assertEquals(99, plan.getPartCount());
        assertRejected(() -> VolumePartPlan.createForFile(100, limit, 1, 2048));
    }

    @Test
    public void fourGiBIsAcceptedAndOneByteMoreIsRejected() {
        VolumePartPlan plan = VolumePartPlan.create(
                VolumePartPlan.MAX_ARCHIVE_BYTES, 500L * MB, 24, 4096);
        assertEquals(9, plan.getPartCount());

        assertRejected(() -> VolumePartPlan.create(
                VolumePartPlan.MAX_ARCHIVE_BYTES + 1, 500L * MB, 24, 4096));
    }

    @Test
    public void twoGigabytePartStaysBelowTheSingleFileEngineLimit() {
        VolumePartPlan plan = VolumePartPlan.create(
                4L * GIB, 2_000L * MB, 24, 4096);

        assertEquals(3, plan.getPartCount());
        assertEquals(1_999_995_904L, plan.getPartCapacityBytes());
        assertTrue(plan.getPartCapacityBytes() < FileCryptoEngine.MAX_PLAINTEXT_BYTES);
    }

    @Test
    public void partCapacityAtTheEngineLimitIsAcceptedButOneByteMoreIsRejected() {
        long limit = FileCryptoEngine.MAX_PLAINTEXT_BYTES + VolumePartPlan.HEADER_RESERVE_BYTES;
        VolumePartPlan plan = VolumePartPlan.create(1, limit, 1, 4096);
        assertEquals(FileCryptoEngine.MAX_PLAINTEXT_BYTES, plan.getPartCapacityBytes());

        assertRejected(() -> VolumePartPlan.create(1, limit + 1, 1, 4096));
    }

    @Test
    public void invalidArgumentsAreRejected() {
        assertRejected(() -> VolumePartPlan.create(0, 50L * MB, 24, 4096));
        assertRejected(() -> VolumePartPlan.create(-1, 50L * MB, 24, 4096));
        assertRejected(() -> VolumePartPlan.create(1, VolumePartPlan.HEADER_RESERVE_BYTES, 24, 4096));
        assertRejected(() -> VolumePartPlan.create(1, 50L * MB, -1, 4096));
        assertRejected(() -> VolumePartPlan.create(1, 50L * MB, 4097, 4096));
        assertRejected(() -> VolumePartPlan.create(1, 50L * MB, 24, 1024));
        assertRejected(() -> VolumePartPlan.create(1, 50L * MB, 24, 16384));
    }

    @Test
    public void partNumbersAreOneBasedAndBounded() {
        VolumePartPlan plan = VolumePartPlan.create(4L * GIB, 50L * MB, 24, 4096);

        assertEquals(0L, plan.getPartOffset(1));
        assertEquals(plan.getPartCapacityBytes(), plan.getPartOffset(2));
        assertRejected(() -> plan.getPartOffset(0));
        assertRejected(() -> plan.getPartOffset(plan.getPartCount() + 1));
        assertRejected(() -> plan.getPartPlaintextBytes(0));
        assertRejected(() -> plan.getExpectedFinalBytes(plan.getPartCount() + 1));
    }

    private static void assertPartSizes(VolumePartPlan plan) {
        long sum = 0;
        for (int part = 1; part <= plan.getPartCount(); part++) {
            long size = plan.getPartPlaintextBytes(part);
            assertTrue("分卷明文必须大于 0", size > 0);
            assertTrue("分卷明文不能超过容量", size <= plan.getPartCapacityBytes());
            assertTrue("最终文件上界不能超过单卷上限",
                    plan.getExpectedFinalBytes(part) <= plan.getVolumeLimitBytes());
            if (part < plan.getPartCount()) {
                assertEquals("除最后一卷外都应填满容量",
                        plan.getPartCapacityBytes(), size);
            }
            sum += size;
        }
        assertEquals(plan.getArchiveBytes(), sum);
    }

    private static void assertRejected(Runnable action) {
        try {
            action.run();
            fail("应当拒绝非法计划");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }
}
