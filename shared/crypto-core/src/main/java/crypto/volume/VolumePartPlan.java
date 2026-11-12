package crypto.volume;

import crypto.file.FileCryptoEngine;

/**
 * 外部分卷的纯预算计划。
 *
 * <p>输入是源字节数（ZIP 或单个原文件）和最终单卷上限（包含 VOLU 头与单文件容器头），
 * 输出每卷的明文偏移、明文长度和最终文件上界。本类不读取文件、不生成密钥、不写任何内容，
 * 因此可以在 app 层预先判断卷数/大小，也可以被写入器复用。</p>
 *
 * <p>预算规则与修订 4 附录 B 一致：先预留 4 KiB 给 VOLU 头与单文件容器头，
 * 剩余部分作为每卷明文容量；所有卷共用同一容量，最后一卷取余数。</p>
 */
public final class VolumePartPlan {

    /** VOLU 头 + FileCryptoEngine 容器头的总预留；实际写入后还要逐卷校验。 */
    public static final int HEADER_RESERVE_BYTES = 4 * 1024;

    /** 多文件 ZIP 总量上限：4 GiB；单文件外部分卷使用 {@link #createForFile}，不套这个上限。 */
    public static final long MAX_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024;

    /** 外部分卷的格式/附件数量上限。 */
    public static final int MAX_PARTS = VolumeInfo.MAX_EXTERNAL_PARTS;

    public static final int MAX_PART_NAME_BYTES = 4096;
    public static final int MIN_RSA_BITS = 2048;
    public static final int MAX_RSA_BITS = 8192;

    private final long archiveBytes;
    private final long volumeLimitBytes;
    private final long partCapacityBytes;
    private final int partCount;
    private final int partNameBytes;
    private final int rsaBits;

    private VolumePartPlan(
            long archiveBytes,
            long volumeLimitBytes,
            long partCapacityBytes,
            int partCount,
            int partNameBytes,
            int rsaBits
    ) {
        this.archiveBytes = archiveBytes;
        this.volumeLimitBytes = volumeLimitBytes;
        this.partCapacityBytes = partCapacityBytes;
        this.partCount = partCount;
        this.partNameBytes = partNameBytes;
        this.rsaBits = rsaBits;
    }

    /**
     * 建 ZIP 归档计划。所有长度单位为字节；卷号为 1-based。
     *
     * @param archiveBytes     ZIP 总字节数，必须 1..4 GiB
     * @param volumeLimitBytes 最终单卷上限（含头部），必须大于 4 KiB
     * @param partNameBytes    FileCryptoEngine 内部名 UTF-8 字节数，仅用于边界校验
     * @param rsaBits          接收方/签名 RSA 模数位数，仅用于边界校验
     */
    public static VolumePartPlan create(
            long archiveBytes,
            long volumeLimitBytes,
            int partNameBytes,
            int rsaBits
    ) {
        if (archiveBytes > MAX_ARCHIVE_BYTES) {
            throw new IllegalArgumentException("ZIP 超过 4 GiB 上限");
        }
        return createInternal(
                archiveBytes,
                volumeLimitBytes,
                partNameBytes,
                rsaBits,
                "ZIP");
    }

    /**
     * 建单个原文件的外部分卷计划。
     *
     * <p>总大小不设 4 GiB 固定上限，实际约束仍是每卷明文容量不超过单文件引擎上限、
     * 且卷数不超过 {@link #MAX_PARTS}。因此可处理约 2 GB × 99 卷的文件，具体上限随
     * 用户选择的单卷大小变化。</p>
     *
     * @param fileBytes        原文件总字节数，必须大于 0
     * @param volumeLimitBytes 最终单卷上限（含头部），必须大于 4 KiB
     * @param partNameBytes    FileCryptoEngine 内部名 UTF-8 字节数，仅用于边界校验
     * @param rsaBits          接收方/签名 RSA 模数位数，仅用于边界校验
     */
    public static VolumePartPlan createForFile(
            long fileBytes,
            long volumeLimitBytes,
            int partNameBytes,
            int rsaBits
    ) {
        return createInternal(
                fileBytes,
                volumeLimitBytes,
                partNameBytes,
                rsaBits,
                "文件");
    }

    private static VolumePartPlan createInternal(
            long archiveBytes,
            long volumeLimitBytes,
            int partNameBytes,
            int rsaBits,
            String sourceLabel
    ) {
        if (archiveBytes <= 0) {
            throw new IllegalArgumentException(sourceLabel + "大小必须大于 0");
        }
        if (volumeLimitBytes <= HEADER_RESERVE_BYTES) {
            throw new IllegalArgumentException("单卷上限必须大于 4 KiB 头部预留");
        }
        if (partNameBytes < 0 || partNameBytes > MAX_PART_NAME_BYTES) {
            throw new IllegalArgumentException("分卷内部名长度超出范围");
        }
        if (rsaBits < MIN_RSA_BITS || rsaBits > MAX_RSA_BITS) {
            throw new IllegalArgumentException("RSA 模数位数超出范围");
        }

        long capacity = volumeLimitBytes - HEADER_RESERVE_BYTES;
        if (capacity > FileCryptoEngine.MAX_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("单卷明文容量超过单文件引擎上限");
        }

        // 先减 1 再整除，避免 archiveBytes 恰好是容量整数倍时多算一个空卷。
        long count = ((archiveBytes - 1) / capacity) + 1;
        if (count > MAX_PARTS) {
            throw new IllegalArgumentException(
                    "分卷数量超过 " + MAX_PARTS + "，请增大单卷上限");
        }
        return new VolumePartPlan(
                archiveBytes,
                volumeLimitBytes,
                capacity,
                (int) count,
                partNameBytes,
                rsaBits
        );
    }

    public long getArchiveBytes() {
        return archiveBytes;
    }

    public long getVolumeLimitBytes() {
        return volumeLimitBytes;
    }

    public long getPartCapacityBytes() {
        return partCapacityBytes;
    }

    public int getPartCount() {
        return partCount;
    }

    public int getPartNameBytes() {
        return partNameBytes;
    }

    public int getRsaBits() {
        return rsaBits;
    }

    /** 第 [partNumber] 卷（1-based）在源文件中的起始偏移。 */
    public long getPartOffset(int partNumber) {
        checkPartNumber(partNumber);
        return (long) (partNumber - 1) * partCapacityBytes;
    }

    /** 第 [partNumber] 卷（1-based）的明文字节数；最后一卷可能小于容量。 */
    public long getPartPlaintextBytes(int partNumber) {
        checkPartNumber(partNumber);
        long offset = (long) (partNumber - 1) * partCapacityBytes;
        return Math.min(partCapacityBytes, archiveBytes - offset);
    }

    /**
     * 第 [partNumber] 卷最终文件大小的上界：明文 + 4 KiB 预留。
     * 写入器应按实际 VOLU 头/容器头重新计算并校验 file.length()。
     */
    public long getExpectedFinalBytes(int partNumber) {
        checkPartNumber(partNumber);
        return getPartPlaintextBytes(partNumber) + HEADER_RESERVE_BYTES;
    }

    private void checkPartNumber(int partNumber) {
        if (partNumber < 1 || partNumber > partCount) {
            throw new IllegalArgumentException("分卷号超出范围: " + partNumber);
        }
    }
}
