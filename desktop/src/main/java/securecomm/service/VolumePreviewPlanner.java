package securecomm.service;

import java.io.File;
import java.io.IOException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import volumes.service.ExternalVolumeWriter;

/**
 * 分卷预览的纯计算（DR-3 步骤 3.2）：给定文件与分卷上限，算出每一行提示与总卷数。
 *
 * <p>不读文件内容、不弹窗、不访问设置；分卷上限与公钥由调用方传入，文件夹 ZIP 的登记信息
 * 通过 {@link ArchiveLookup} 注入。面板只负责把结果放进对话框。</p>
 */
public final class VolumePreviewPlanner {

    /** 文件夹 ZIP 的登记信息；面板用 {@code TempFileManager} 实现，测试可传假实现。 */
    public interface ArchiveLookup {

        boolean isFolderZip(File file);

        String folderName(File file);

        String registeredArchive(File file);
    }

    /** 预览结果：逐行说明 + 总卷数。 */
    public static final class Plan {

        private final List<String> lines;
        private final int totalParts;

        Plan(List<String> lines, int totalParts) {
            this.lines = Collections.unmodifiableList(lines);
            this.totalParts = totalParts;
        }

        public boolean isEmpty() {
            return lines.isEmpty();
        }

        public List<String> getLines() {
            return lines;
        }

        public int getTotalParts() {
            return totalParts;
        }

        /** 卷数超过 20 时给警告图标与额外建议。 */
        public boolean isWarning() {
            return totalParts > 20;
        }

        /** 组装对话框正文；文案与迁移前逐字一致。 */
        public String buildMessage(String volumeLimitLabel) {
            StringBuilder message = new StringBuilder();
            message.append("将按当前设置（")
                    .append(volumeLimitLabel)
                    .append("/卷）生成：\n\n");
            for (String line : lines) {
                message.append("• ").append(line).append('\n');
            }
            message.append("\n共 ").append(totalParts).append(" 卷。");
            if (isWarning()) {
                message.append("\n\n卷数较多，建议增大分卷大小或减少文件。");
            }
            message.append("\n\n是否开始加密？");
            return message.toString();
        }
    }

    private VolumePreviewPlanner() {
    }

    /**
     * @param files            选中的文件
     * @param skipEncrypted    是否跳过 {@code .aegix}
     * @param packageFiles     是否打包成一个 ZIP（此时逐文件预览不生效）
     * @param archiveRootName  打包时的归档根名
     * @param recipientKey     接收方公钥（影响每卷可用字节）
     * @param volumeLimitBytes 单卷上限
     * @param lookup           文件夹 ZIP 登记信息
     */
    public static Plan plan(
            List<File> files,
            boolean skipEncrypted,
            boolean packageFiles,
            String archiveRootName,
            PublicKey recipientKey,
            long volumeLimitBytes,
            ArchiveLookup lookup
    ) throws IOException {
        List<String> lines = new ArrayList<String>();
        int totalParts = 0;
        List<File> sources = files == null ? Collections.<File>emptyList() : files;

        if (packageFiles) {
            long sourceBytes = 0L;
            int count = 0;
            for (File file : sources) {
                if (skipEncrypted && FileTypeClassifier.isEncryptedFileName(file.getName())) {
                    continue;
                }
                sourceBytes += file.length();
                count++;
            }
            int partCount = ExternalVolumeWriter.previewPartCountForBytes(
                    sourceBytes,
                    count,
                    true,
                    archiveRootName + ".zip",
                    recipientKey,
                    volumeLimitBytes);
            if (partCount > 1) {
                lines.add(count + " 个文件（打包） → 预计 " + partCount + " 卷");
                totalParts += partCount;
            }
            return new Plan(lines, totalParts);
        }

        for (File file : sources) {
            if (skipEncrypted && FileTypeClassifier.isEncryptedFileName(file.getName())) {
                continue;
            }
            if (file.length() <= volumeLimitBytes) {
                continue;
            }

            boolean folderZip = lookup.isFolderZip(file);
            String registeredArchive = lookup.registeredArchive(file);
            boolean sourceIsZip = folderZip || registeredArchive != null;
            String folderName = folderZip ? lookup.folderName(file) : null;
            String archiveName = ArchiveNaming.archiveNameFor(
                    folderZip, folderName, registeredArchive, file.getName());

            int partCount = ExternalVolumeWriter.previewPartCount(
                    file, sourceIsZip, archiveName, recipientKey, volumeLimitBytes);
            if (partCount > 1) {
                lines.add(ArchiveNaming.displayNameFor(folderZip, folderName, file.getName())
                        + " → " + partCount + " 卷");
                totalParts += partCount;
            }
        }
        return new Plan(lines, totalParts);
    }
}
