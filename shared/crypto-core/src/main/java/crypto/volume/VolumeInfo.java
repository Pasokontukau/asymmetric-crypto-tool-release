package crypto.volume;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Collections;
import java.util.List;

/**
 * VOLU 外部分卷的 VolumeInfo（解密后的元数据）。
 *
 * <p>当前只有一种 VOLU 形态：每一卷都是一个独立文件，{@code volumeCount} 固定为 1，
 * 并且一定携带完整的 {@code setId/partIndex/partCount/archive*} 集合字段。
 * {@code sourceIsArchive=true} 表示合并后是 ZIP；{@code false} 表示合并后直接得到
 * 原始单文件。</p>
 *
 * <p>该对象只由 {@link VolumeInfoParser} 在完成字段范围与一致性校验后构造；
 * 元数据本身没有签名，因此调用方不能仅凭这里的文件名/文件夹标志做危险操作。</p>
 */
public final class VolumeInfo {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    public static final int MAX_FILE_NAME_CHARS = 1024;
    public static final int MAX_CREATED_AT_CHARS = 256;
    /** 外部单卷包的最大卷数；与 99 个社交附件上限一致。 */
    public static final int MAX_EXTERNAL_PARTS = 99;
    public static final int MAX_SET_ID_CHARS = 128;
    public static final int MAX_SOURCE_FILE_COUNT = 1_000_000;

    private final String originalFileName;
    private final long originalFileSize;
    private final int volumeCount;
    private final long volumeSize;
    private final boolean sourceIsFolder;
    private final String sha256Hash;
    private final String createdAt;
    private final List<Long> encryptedVolumeSizes;

    private final String setId;
    private final int partIndex;
    private final int partCount;
    private final String archiveName;
    private final long archiveSize;
    private final String archiveSha256;
    private final boolean archiveSourceIsFolder;
    private final boolean sourceIsArchive;
    private final int sourceFileCount;

    VolumeInfo(
            String originalFileName,
            long originalFileSize,
            int volumeCount,
            long volumeSize,
            boolean sourceIsFolder,
            String sha256Hash,
            String createdAt,
            List<Long> encryptedVolumeSizes,
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
        this.originalFileSize = originalFileSize;
        this.volumeCount = volumeCount;
        this.volumeSize = volumeSize;
        this.sourceIsFolder = sourceIsFolder;
        this.sha256Hash = sha256Hash;
        this.createdAt = createdAt;
        this.encryptedVolumeSizes = encryptedVolumeSizes == null
                ? Collections.<Long>emptyList()
                : Collections.unmodifiableList(encryptedVolumeSizes);
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

    public long getOriginalFileSize() {
        return originalFileSize;
    }

    /** 当前格式固定为 1。 */
    public int getVolumeCount() {
        return volumeCount;
    }

    public long getVolumeSize() {
        return volumeSize;
    }

    public boolean isSourceIsFolder() {
        return sourceIsFolder;
    }

    /** 本卷明文的 SHA-256；解析器允许缺省，当前写入器总是写。 */
    public String getSha256Hash() {
        return sha256Hash;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    /** 本卷内部单文件容器的字节数；当前格式恰好一个元素。 */
    public List<Long> getEncryptedVolumeSizes() {
        return encryptedVolumeSizes;
    }

    public boolean hasSha256Hash() {
        return sha256Hash != null && !sha256Hash.isEmpty();
    }

    /** 序列化为 VOLU 元数据 JSON；字段名必须与 {@link VolumeInfoParser} 保持一致。 */
    public String toJson() {
        return GSON.toJson(this);
    }

    public String getSetId() {
        return setId;
    }

    /** 1-based 卷号。 */
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

    /** 当前所有 VOLU 都是外部分卷；保留此方法供调用方表达归档分卷语义。 */
    public boolean isExternalPart() {
        return sourceIsArchive;
    }

    /** 本卷是否属于“单个原文件切片”集合（合并后不是 ZIP）。 */
    public boolean isFilePartSet() {
        return !sourceIsArchive;
    }

    /**
     * 本卷预期明文字节数。
     *
     * <p>当前每卷只装一个内部单文件容器，{@code volumeCount=1}，因此只接受索引 0。</p>
     */
    public long expectedPartPlaintextSize(int index) {
        if (index != 0) {
            throw new IllegalArgumentException("分卷索引超出范围: " + index);
        }
        return originalFileSize;
    }
}
