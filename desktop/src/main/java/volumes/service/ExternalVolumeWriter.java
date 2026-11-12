package volumes.service;

import crypto.file.FileCancelledException;
import crypto.volume.VolumePartPlan;
import crypto.volume.VolumePartWriter;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 桌面端的大文件外部分卷写入器。
 *
 * <p>复用共享内核的 {@link VolumePartWriter}，写出的每一卷与 Android 当前外部分卷
 * 完全同格式。普通文件直接按卷切片，不再套一层只能容纳约 2 GB 的单文件容器，
 * 也不再包装成 ZIP；文件夹仍先打包成临时 ZIP，再切片。接收端按
 * {@code sourceIsArchive} 决定“还原原文件”还是“还原 ZIP 后解压”。</p>
 *
 * <p>单卷上限由调用方传入；桌面端从「设置 → 文件处理 → 分卷大小」读取，
 * 默认 500 MB，与 Android 默认档一致。</p>
 */
public final class ExternalVolumeWriter {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String EXTENSION = ".aegix";
    /** 保守的内部名后缀上界：`archive.zip.part999.part1`。 */
    private static final String PART_NAME_SUFFIX = ".part999.part1";
    private static final int MAX_OUTPUT_ATTEMPTS = 10_000;

    private ExternalVolumeWriter() {
    }

    /**
     * 把来源写成当前外部分卷。
     *
     * <p>普通文件按原文件切片，合并后直接得到原文件；文件夹来源仍是 ZIP，
     * 合并后再由调用方解压。</p>
     *
     * @param sourceFile       普通文件，或文件夹打包后的临时 ZIP
     * @param sourceIsZip      {@code true} 表示 sourceFile 是文件夹 ZIP；{@code false} 表示普通文件
     * @param archiveName      接收端合并后的文件名：普通文件为原文件名，文件夹为 {@code Folder.zip}
     * @param sourceIsFolder   原始来源是否为文件夹
     * @param sourceFileCount  ZIP 内文件条目数；null 时从 ZIP 读取
     * @param outputDirectory  分卷输出目录
     * @param outputBaseName   不含扩展名的输出基名
     * @param signingKey       发送方私钥
     * @param recipientKey     接收方公钥
     * @param volumeLimitBytes 单卷最终大小上限（含 VOLU/容器头）
     * @param cancelled        取消标记，可为 null
     * @return 已写出的分卷，按卷号升序
     */
    public static List<File> write(
            File sourceFile,
            boolean sourceIsZip,
            String archiveName,
            boolean sourceIsFolder,
            Integer sourceFileCount,
            File outputDirectory,
            String outputBaseName,
            PrivateKey signingKey,
            PublicKey recipientKey,
            long volumeLimitBytes,
            AtomicBoolean cancelled
    ) throws Exception {
        if (sourceFile == null || !sourceFile.isFile()) {
            throw new IOException("来源文件不存在或不是普通文件");
        }
        if (outputDirectory == null) {
            throw new IOException("输出目录不能为空");
        }
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()
                && !outputDirectory.isDirectory()) {
            throw new IOException("无法创建输出目录: " + outputDirectory);
        }
        if (!outputDirectory.isDirectory()) {
            throw new IOException("输出路径不是目录: " + outputDirectory);
        }
        checkCancelled(cancelled);

        List<File> writtenParts = new ArrayList<File>();
        boolean success = false;
        try {
            int fileCount = sourceIsZip
                    ? (sourceFileCount == null ? countZipEntries(sourceFile) : sourceFileCount)
                    : 1;
            String archiveSha256 = sha256(sourceFile, cancelled);
            VolumePartPlan plan = createPlan(
                    sourceFile, sourceIsZip, archiveName, recipientKey, volumeLimitBytes);

            String setId = UUID.randomUUID().toString();
            List<File> outputs = allocateOutputs(
                    outputDirectory, outputBaseName, setId, plan.getPartCount());
            for (int partIndex = 1; partIndex <= plan.getPartCount(); partIndex++) {
                checkCancelled(cancelled);
                File output = outputs.get(partIndex - 1);
                File written = VolumePartWriter.writePart(
                        sourceFile,
                        plan,
                        partIndex,
                        setId,
                        archiveName,
                        archiveSha256,
                        sourceIsFolder,
                        sourceIsZip,
                        fileCount,
                        output,
                        signingKey,
                        recipientKey,
                        null,
                        cancelled);
                writtenParts.add(written);
            }
            success = true;
            return Collections.unmodifiableList(writtenParts);
        } finally {
            if (!success) {
                for (File part : writtenParts) {
                    deleteQuietly(part);
                }
            }
        }
    }

    /**
     * 预估一个来源会生成多少卷，不读取文件内容、不写盘。
     *
     * <p>与 {@link #write} 复用同一套预算规则，避免“预览显示 2 卷、实际写 3 卷”。
     * 来源超过 99 卷时抛出 {@link IllegalArgumentException}，由界面提示增大分卷大小。</p>
     */
    public static int previewPartCount(
            File sourceFile,
            boolean sourceIsZip,
            String archiveName,
            PublicKey recipientKey,
            long volumeLimitBytes
    ) throws IOException {
        return createPlan(
                sourceFile, sourceIsZip, archiveName, recipientKey, volumeLimitBytes)
                .getPartCount();
    }

    /**
     * 预览“多个普通文件打包成一个 ZIP”的卷数；ZIP 尚未生成，使用保守上界。
     */
    public static int previewPartCountForBytes(
            long sourceBytes,
            int fileCount,
            boolean sourceIsZip,
            String archiveName,
            PublicKey recipientKey,
            long volumeLimitBytes
    ) throws IOException {
        if (archiveName == null || archiveName.isEmpty()) {
            throw new IOException("归档名不能为空");
        }
        if (recipientKey == null) {
            throw new IOException("接收方公钥不能为空");
        }
        int partNameBytes = (archiveName + PART_NAME_SUFFIX).getBytes("UTF-8").length;
        int rsaBits = recipientKey instanceof RSAPublicKey
                ? ((RSAPublicKey) recipientKey).getModulus().bitLength()
                : 4096;
        long estimatedZipBytes = sourceBytes + Math.max(0, fileCount) * 512L + 512L;
        return sourceIsZip
                ? VolumePartPlan.create(
                        estimatedZipBytes,
                        volumeLimitBytes,
                        partNameBytes,
                        rsaBits).getPartCount()
                : VolumePartPlan.createForFile(
                        estimatedZipBytes,
                        volumeLimitBytes,
                        partNameBytes,
                        rsaBits).getPartCount();
    }

    /** 写入与预览共用的分卷预算；只读文件长度，不读内容。 */
    private static VolumePartPlan createPlan(
            File sourceFile,
            boolean sourceIsZip,
            String archiveName,
            PublicKey recipientKey,
            long volumeLimitBytes
    ) throws IOException {
        if (sourceFile == null || !sourceFile.isFile()) {
            throw new IOException("来源文件不存在或不是普通文件");
        }
        if (archiveName == null || archiveName.isEmpty()) {
            throw new IOException("归档名不能为空");
        }
        if (recipientKey == null) {
            throw new IOException("接收方公钥不能为空");
        }
        int partNameBytes = (archiveName + PART_NAME_SUFFIX)
                .getBytes("UTF-8").length;
        int rsaBits = recipientKey instanceof RSAPublicKey
                ? ((RSAPublicKey) recipientKey).getModulus().bitLength()
                : 4096;
        return sourceIsZip
                ? VolumePartPlan.create(
                        sourceFile.length(),
                        volumeLimitBytes,
                        partNameBytes,
                        rsaBits)
                : VolumePartPlan.createForFile(
                        sourceFile.length(),
                        volumeLimitBytes,
                        partNameBytes,
                        rsaBits);
    }

    private static int countZipEntries(File zipFile) throws IOException {
        int count = 0;
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String sha256(File file, AtomicBoolean cancelled) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = new BufferedInputStream(
                new FileInputStream(file), BUFFER_SIZE)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                checkCancelled(cancelled);
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    /**
     * 为一组分卷分配不冲突的输出名。单卷沿用
     * {@code secure_file_<ts>.aegix}；多卷带集合短码与 1-based 卷号。
     */
    private static List<File> allocateOutputs(
            File directory,
            String baseName,
            String setId,
            int partCount
    ) throws IOException {
        String setCode = setId.substring(0, 6).toLowerCase(Locale.ROOT);
        for (int attempt = 0; attempt < MAX_OUTPUT_ATTEMPTS; attempt++) {
            String candidateBase = attempt == 0
                    ? baseName
                    : baseName + "_" + attempt;
            List<File> candidates = new ArrayList<File>(partCount);
            boolean conflict = false;
            for (int partIndex = 1; partIndex <= partCount; partIndex++) {
                String name;
                if (partCount == 1) {
                    name = candidateBase + EXTENSION;
                } else {
                    name = candidateBase + "_" + setCode + "_vol"
                            + String.format(Locale.ROOT, "%03d", partIndex)
                            + EXTENSION;
                }
                File candidate = new File(directory, name);
                if (candidate.exists()) {
                    conflict = true;
                    break;
                }
                candidates.add(candidate);
            }
            if (!conflict) {
                return candidates;
            }
        }
        throw new IOException("无法分配不冲突的分卷文件名: " + baseName);
    }

    private static void checkCancelled(AtomicBoolean cancelled)
            throws FileCancelledException {
        if (cancelled != null && cancelled.get()) {
            throw new FileCancelledException("大文件分卷已取消");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new FileCancelledException("大文件分卷已取消");
        }
    }

    private static String toHex(byte[] data) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[data.length * 2];
        for (int index = 0; index < data.length; index++) {
            int value = data[index] & 0xff;
            result[index * 2] = digits[value >>> 4];
            result[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(result);
    }

    private static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            if (file.exists() && !file.delete() && file.exists()) {
                file.deleteOnExit();
            }
        } catch (RuntimeException ignored) {
            // 清理失败不能覆盖原始异常。
        }
    }
}
