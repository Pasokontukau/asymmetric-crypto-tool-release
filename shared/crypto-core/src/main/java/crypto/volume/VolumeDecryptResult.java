package crypto.volume;

import java.security.PublicKey;

/**
 * VOLU 外部分卷解密结果。
 *
 * <p>当前每一卷都是独立单卷容器，签名有效表示该卷签名匹配任一候选公钥；
 * GCM/结构/数量/SHA-256 失败不会返回本对象，而是抛异常。</p>
 */
public final class VolumeDecryptResult {

    private final String originalFileName;
    private final boolean signatureValid;
    private final PublicKey matchedSender;
    private final long plaintextBytes;
    private final int volumeCount;
    private final boolean contentHashVerified;
    private final boolean partNamesVerified;
    private final boolean sourceIsFolder;

    private final String setId;
    private final int partIndex;
    private final int partCount;
    private final String archiveName;
    private final long archiveSize;
    private final String archiveSha256;
    private final boolean archiveSourceIsFolder;
    private final boolean sourceIsArchive;
    private final int sourceFileCount;

    public VolumeDecryptResult(
            String originalFileName,
            boolean signatureValid,
            PublicKey matchedSender,
            long plaintextBytes,
            int volumeCount,
            boolean contentHashVerified,
            boolean partNamesVerified,
            boolean sourceIsFolder,
            String setId,
            int partIndex,
            int partCount,
            String archiveName,
            long archiveSize,
            String archiveSha256,
            boolean archiveSourceIsFolder,
            boolean sourceIsArchive,
            int sourceFileCount
    ) {
        this.originalFileName = originalFileName;
        this.signatureValid = signatureValid;
        this.matchedSender = matchedSender;
        this.plaintextBytes = plaintextBytes;
        this.volumeCount = volumeCount;
        this.contentHashVerified = contentHashVerified;
        this.partNamesVerified = partNamesVerified;
        this.sourceIsFolder = sourceIsFolder;
        this.setId = setId;
        this.partIndex = partIndex;
        this.partCount = partCount;
        this.archiveName = archiveName;
        this.archiveSize = archiveSize;
        this.archiveSha256 = archiveSha256;
        this.archiveSourceIsFolder = archiveSourceIsFolder;
        this.sourceIsArchive = sourceIsArchive;
        this.sourceFileCount = sourceFileCount;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public boolean isSignatureValid() {
        return signatureValid;
    }

    public PublicKey getMatchedSender() {
        return matchedSender;
    }

    public long getPlaintextBytes() {
        return plaintextBytes;
    }

    public int getVolumeCount() {
        return volumeCount;
    }

    /** VolumeInfo 有 SHA-256 且与解密输出一致时为 true。 */
    public boolean isContentHashVerified() {
        return contentHashVerified;
    }

    /** 解密出的内部文件名是否与 {@code originalFileName.part1} 一致。 */
    public boolean isPartNamesVerified() {
        return partNamesVerified;
    }

    public boolean isSourceIsFolder() {
        return sourceIsFolder;
    }

    public String getSetId() {
        return setId;
    }

    public int getPartIndex() {
        return partIndex;
    }

    public int getPartCount() {
        return partCount;
    }

    public String getArchiveName() {
        return archiveName;
    }

    public long getArchiveSize() {
        return archiveSize;
    }

    public String getArchiveSha256() {
        return archiveSha256;
    }

    public boolean getArchiveSourceIsFolder() {
        return archiveSourceIsFolder;
    }

    public boolean getSourceIsArchive() {
        return sourceIsArchive;
    }

    public int getSourceFileCount() {
        return sourceFileCount;
    }
}
