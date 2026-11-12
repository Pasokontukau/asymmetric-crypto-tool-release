package volumes.service;

import crypto.file.FileCancelledException;
import crypto.message.FileFormatHeader;
import crypto.volume.VolumeCryptoEngine;
import crypto.volume.VolumeDecryptResult;
import crypto.volume.VolumeFormatException;
import crypto.volume.VolumeInfo;
import crypto.volume.VolumeSetPlan;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 桌面端读取 Android 生成的外部加密分卷（每卷一个单卷 VOLU）。
 *
 * <p>本类只在 desktop/ 内实现合并，不修改 android/：先用 {@link VolumeCryptoEngine#readInfo}
 * 读取每卷元数据，再用共享 {@link VolumeSetPlan} 判定缺卷/重卷/混包，齐套后逐卷解密并
 * 写入同一个输出文件，最后校验整包 SHA-256。任何一个失败都不留下半成品。</p>
 *
 * <p>输出格式由元数据 {@code sourceIsArchive} 决定：单文件分卷直接还原原文件；
 * 文件夹/归档分卷还原为 ZIP，是否解压由界面层决定。</p>
 *
 * <p>与 Android app 层的 {@code ArchivePartReader} 保持相同语义，但它是 Kotlin/Android
 * 代码，桌面端不复用、也不为了复用去下沉 Android 源码。</p>
 */
public final class ExternalPartMerger {

    /** 一组待合并分卷的检查结果。 */
    public enum Status {
        /** 齐套，可以合并。 */
        COMPLETE,
        /** 同一集合但缺卷。 */
        INCOMPLETE,
        /** 混入了另一个集合。 */
        MIXED,
        /** 同一卷号出现两次。 */
        DUPLICATE,
        /** 卷号越界。 */
        INVALID_INDEX,
        /** 不是外部分卷集合（可能是单文件 .aegix 或普通文件）。 */
        NOT_A_SET,
        /** 是外部分卷，但 partCount=1，可直接按单卷处理。 */
        SINGLE_PART
    }

    /** 单卷与它的元数据。 */
    public static final class Part {
        private final File file;
        private final VolumeInfo info;

        Part(File file, VolumeInfo info) {
            this.file = file;
            this.info = info;
        }

        public File getFile() {
            return file;
        }

        public VolumeInfo getInfo() {
            return info;
        }
    }

    /** 检查结果；只有 {@link Status#COMPLETE} 时 {@link #getOrderedParts()} 可直接合并。 */
    public static final class Inspection {
        private final Status status;
        private final List<Part> parts;
        private final List<Part> orderedParts;
        private final List<Integer> missingIndexes;
        private final String detail;

        Inspection(
                Status status,
                List<Part> parts,
                List<Part> orderedParts,
                List<Integer> missingIndexes,
                String detail
        ) {
            this.status = status;
            this.parts = Collections.unmodifiableList(parts);
            this.orderedParts = Collections.unmodifiableList(orderedParts);
            this.missingIndexes = Collections.unmodifiableList(missingIndexes);
            this.detail = detail;
        }

        public Status getStatus() {
            return status;
        }

        public List<Part> getParts() {
            return parts;
        }

        public List<Part> getOrderedParts() {
            return orderedParts;
        }

        public List<Integer> getMissingIndexes() {
            return missingIndexes;
        }

        public String getDetail() {
            return detail;
        }

        public boolean isComplete() {
            return status == Status.COMPLETE;
        }
    }

    /** 合并结果。 */
    public static final class MergeResult {
        private final File output;
        private final int partCount;
        private final long archiveSize;
        private final boolean signatureValid;
        private final boolean partNamesVerified;
        private final String archiveName;
        private final boolean sourceIsFolder;
        private final boolean sourceIsArchive;
        private final int sourceFileCount;

        MergeResult(
                File output,
                int partCount,
                long archiveSize,
                boolean signatureValid,
                boolean partNamesVerified,
                String archiveName,
                boolean sourceIsFolder,
                boolean sourceIsArchive,
                int sourceFileCount
        ) {
            this.output = output;
            this.partCount = partCount;
            this.archiveSize = archiveSize;
            this.signatureValid = signatureValid;
            this.partNamesVerified = partNamesVerified;
            this.archiveName = archiveName;
            this.sourceIsFolder = sourceIsFolder;
            this.sourceIsArchive = sourceIsArchive;
            this.sourceFileCount = sourceFileCount;
        }

        public File getOutput() {
            return output;
        }

        public int getPartCount() {
            return partCount;
        }

        public long getArchiveSize() {
            return archiveSize;
        }

        public boolean isSignatureValid() {
            return signatureValid;
        }

        public boolean isPartNamesVerified() {
            return partNamesVerified;
        }

        public String getArchiveName() {
            return archiveName;
        }

        public boolean isSourceIsFolder() {
            return sourceIsFolder;
        }

        /** {@code true} 表示还原结果是 ZIP；{@code false} 表示直接还原原文件。 */
        public boolean isSourceIsArchive() {
            return sourceIsArchive;
        }

        public int getSourceFileCount() {
            return sourceFileCount;
        }
    }

    /** 合并进度：partNumber/partCount 为 1-based，done/total 为整包口径。 */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int partNumber, int partCount, long doneBytes, long totalBytes);
    }

    /** 合并失败（缺卷、混包、整包哈希不符等）。 */
    public static class ExternalMergeException extends Exception {
        private final Status status;

        public ExternalMergeException(Status status, String message) {
            super(message);
            this.status = status;
        }

        public ExternalMergeException(String message) {
            super(message);
            this.status = null;
        }

        public ExternalMergeException(String message, Throwable cause) {
            super(message, cause);
            this.status = null;
        }

        public Status getStatus() {
            return status;
        }
    }

    private static final int READ_BUFFER_BYTES = 64 * 1024;
    private static final int MAX_OUTPUT_NAME_CHARS = 180;

    private ExternalPartMerger() {
    }

    /**
     * 读取每个文件的 VOLU 元数据并判定集合状态。不会读取分卷 payload，也不会写文件。
     *
     * @throws Exception 文件存在但不是可读的 VOLU、或私钥不匹配时抛出
     */
    public static Inspection inspect(List<File> files, PrivateKey privateKey) throws Exception {
        if (privateKey == null) {
            throw new IllegalArgumentException("私钥不能为空");
        }
        if (files == null || files.isEmpty()) {
            return notSet("没有选择文件");
        }

        List<Part> parts = new ArrayList<Part>(files.size());
        for (File file : files) {
            if (file == null || !file.isFile()) {
                throw new IOException("分卷不存在或不是普通文件: " + file);
            }
            if (!FileFormatHeader.checkMagicNumber(file)) {
                return notSet("不是 VOLU 文件: " + file.getName());
            }
            VolumeInfo info;
            try (BufferedInputStream input = new BufferedInputStream(
                    new FileInputStream(file), READ_BUFFER_BYTES)) {
                info = VolumeCryptoEngine.readInfo(input, privateKey);
            } catch (VolumeFormatException error) {
                throw new VolumeFormatException(
                        "分卷元数据不可读: " + file.getName() + " (" + error.getMessage() + ")",
                        error);
            }
            parts.add(new Part(file, info));
        }

        VolumeInfo first = parts.get(0).getInfo();
        if (first.getPartCount() == 1) {
            return new Inspection(
                    Status.SINGLE_PART,
                    parts,
                    parts,
                    Collections.<Integer>emptyList(),
                    "单卷包，可直接解密");
        }

        List<VolumeInfo> infos = new ArrayList<VolumeInfo>(parts.size());
        for (Part part : parts) {
            infos.add(part.getInfo());
        }
        VolumeSetPlan.Result plan = VolumeSetPlan.plan(infos);
        Status status = mapStatus(plan.getStatus());
        List<Part> ordered = new ArrayList<Part>();
        for (VolumeInfo info : plan.getOrderedParts()) {
            for (Part part : parts) {
                if (part.getInfo() == info) {
                    ordered.add(part);
                    break;
                }
            }
        }
        return new Inspection(
                status,
                parts,
                ordered,
                copyMissing(plan.getMissingIndexes()),
                plan.getDetail());
    }

    /**
     * 把齐套的外部分卷合并为一个文件（原文件或 ZIP）。输出写到 [outputDirectory]，
     * 失败或取消时删除临时文件。
     *
     * @param files            用户选择的全部卷，顺序不重要
     * @param privateKey       接收方私钥
     * @param senderCandidates 已知联系人公钥，用于验签；可为空
     * @param outputDirectory  输出目录
     * @param temporaryName    临时文件名（仅用于同一目录内的中间文件）
     * @param listener         进度回调，可为 null
     * @param cancelled        取消标记，可为 null
     */
    public static MergeResult merge(
            List<File> files,
            PrivateKey privateKey,
            List<PublicKey> senderCandidates,
            File outputDirectory,
            String temporaryName,
            ProgressListener listener,
            AtomicBoolean cancelled
    ) throws Exception {
        if (outputDirectory == null) {
            throw new IllegalArgumentException("输出目录不能为空");
        }
        Inspection inspection = inspect(files, privateKey);
        if (!inspection.isComplete()) {
            throw new ExternalMergeException(
                    inspection.getStatus(),
                    inspection.getDetail());
        }
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()
                && !outputDirectory.isDirectory()) {
            throw new IOException("无法创建输出目录: " + outputDirectory);
        }
        if (!outputDirectory.isDirectory()) {
            throw new IOException("输出路径不是目录: " + outputDirectory);
        }

        List<Part> ordered = inspection.getOrderedParts();
        VolumeInfo descriptor = ordered.get(0).getInfo();
        int partCount = descriptor.getPartCount();
        long archiveSize = descriptor.getArchiveSize();
        String archiveSha256 = descriptor.getArchiveSha256();
        boolean sourceIsArchive = descriptor.getSourceIsArchive();
        if (archiveSize <= 0 || archiveSha256 == null
                || archiveSha256.trim().length() != 64) {
            throw new ExternalMergeException(Status.NOT_A_SET, "缺少整包大小或 SHA-256");
        }
        if (ordered.size() != partCount) {
            throw new ExternalMergeException(
                    Status.INCOMPLETE,
                    "分卷数量与元数据不一致: " + ordered.size() + " vs " + partCount);
        }

        File temporary = File.createTempFile(
                "external-merge-",
                sourceIsArchive ? ".zip.tmp" : ".part.tmp",
                outputDirectory);
        if (temporaryName != null && !temporaryName.trim().isEmpty()) {
            File named = new File(outputDirectory, temporaryName);
            if (named.isFile() && !named.delete()) {
                temporary.delete();
                throw new IOException("无法删除旧临时文件: " + named);
            }
            if (temporary.renameTo(named)) {
                temporary = named;
            }
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        boolean success = false;
        long completedBytes = 0L;
        boolean signaturesValid = true;
        boolean partNamesVerified = true;
        try {
            try (OutputStream fileOutput = new BufferedOutputStream(
                    new FileOutputStream(temporary), READ_BUFFER_BYTES)) {
                DigestOutputStream digestOutput = new DigestOutputStream(fileOutput, digest);
                for (int position = 0; position < ordered.size(); position++) {
                    checkCancelled(cancelled);
                    final int partNumber = position + 1;
                    final long baseBytes = completedBytes;
                    Part part = ordered.get(position);
                    crypto.volume.VolumeProgressListener partListener = listener == null
                            ? null
                            : (ignoredPartNumber, ignoredPartCount, processed, total) -> {
                                long mapped = total <= 0
                                        ? processed
                                        : Math.min(processed, total);
                                listener.onProgress(
                                        partNumber,
                                        partCount,
                                        Math.min(archiveSize, baseBytes + mapped),
                                        archiveSize);
                            };
                    if (listener != null) {
                        listener.onProgress(partNumber, partCount, completedBytes, archiveSize);
                    }

                    VolumeDecryptResult result;
                    try (BufferedInputStream input = new BufferedInputStream(
                            new FileInputStream(part.getFile()), READ_BUFFER_BYTES)) {
                        result = VolumeCryptoEngine.decrypt(
                                input,
                                privateKey,
                                senderCandidates == null
                                        ? Collections.<PublicKey>emptyList()
                                        : senderCandidates,
                                digestOutput,
                                partListener,
                                cancelled);
                    }

                    VolumeInfo expected = part.getInfo();
                    if (result.getPlaintextBytes() != expected.getOriginalFileSize()) {
                        throw new ExternalMergeException(
                                "第 " + partNumber + " 卷明文长度不符: 期望 "
                                        + expected.getOriginalFileSize() + "，实际 "
                                        + result.getPlaintextBytes());
                    }
                    if (result.getPartIndex() != expected.getPartIndex()) {
                        throw new ExternalMergeException(
                                "第 " + partNumber + " 卷的卷号与集合元数据不一致");
                    }
                    if (!result.getSetId().equals(expected.getSetId())) {
                        throw new ExternalMergeException(
                                "第 " + partNumber + " 卷属于另一个分卷包");
                    }
                    if (result.getPartCount() != expected.getPartCount()) {
                        throw new ExternalMergeException(
                                "第 " + partNumber + " 卷的总卷数与集合元数据不一致");
                    }

                    completedBytes += result.getPlaintextBytes();
                    signaturesValid = signaturesValid && result.isSignatureValid();
                    partNamesVerified = partNamesVerified && result.isPartNamesVerified();
                }
            }

            checkCancelled(cancelled);
            if (temporary.length() != archiveSize || completedBytes != archiveSize) {
                throw new ExternalMergeException(
                        "合并后的文件长度不符: 期望 " + archiveSize + "，实际 "
                                + temporary.length() + "（明文 " + completedBytes + "）");
            }
            String actualSha = toHex(digest.digest());
            if (!actualSha.equalsIgnoreCase(archiveSha256)) {
                throw new ExternalMergeException("合并后的文件整包校验失败");
            }

            String defaultName = sourceIsArchive ? "archive.zip" : "decrypted.bin";
            File output = uniqueOutput(
                    outputDirectory,
                    descriptor.getArchiveName() == null
                            ? defaultName
                            : descriptor.getArchiveName(),
                    sourceIsArchive);
            Files.move(
                    temporary.toPath(),
                    output.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            success = true;
            return new MergeResult(
                    output,
                    partCount,
                    archiveSize,
                    signaturesValid,
                    partNamesVerified,
                    descriptor.getArchiveName(),
                    descriptor.getArchiveSourceIsFolder(),
                    sourceIsArchive,
                    descriptor.getSourceFileCount());
        } catch (FileCancelledException error) {
            throw new ExternalMergeException("已取消", error);
        } catch (crypto.file.FileFormatException error) {
            // 保持桌面 API 的错误类型统一；底层已说明是哪一卷认证/格式失败。
            throw new ExternalMergeException("分卷解密失败: " + error.getMessage(), error);
        } finally {
            if (!success) {
                temporary.delete();
            }
        }
    }

    private static Inspection notSet(String detail) {
        return new Inspection(
                Status.NOT_A_SET,
                Collections.<Part>emptyList(),
                Collections.<Part>emptyList(),
                Collections.<Integer>emptyList(),
                detail);
    }

    private static Status mapStatus(VolumeSetPlan.Status status) {
        switch (status) {
            case COMPLETE:
                return Status.COMPLETE;
            case INCOMPLETE:
                return Status.INCOMPLETE;
            case MIXED:
                return Status.MIXED;
            case DUPLICATE:
                return Status.DUPLICATE;
            case INVALID_INDEX:
                return Status.INVALID_INDEX;
            case NOT_A_SET:
            default:
                return Status.NOT_A_SET;
        }
    }

    private static List<Integer> copyMissing(List<Integer> missing) {
        if (missing == null || missing.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<Integer>(missing);
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws FileCancelledException {
        if (cancelled != null && cancelled.get()) {
            throw new FileCancelledException("已取消");
        }
    }

    private static File uniqueOutput(
            File directory,
            String requestedName,
            boolean archive
    ) {
        String name = sanitizeFileName(requestedName, archive);
        File candidate = new File(directory, name);
        if (!candidate.exists()) {
            return candidate;
        }
        String base = name;
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            extension = name.substring(dot);
        }
        for (int index = 2; index < 10000; index++) {
            candidate = new File(directory, base + " (" + index + ")" + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(directory, base + "-" + System.currentTimeMillis() + extension);
    }

    /** 桌面文件系统安全名：去掉路径分隔符、控制字符与 Windows 保留字符。 */
    static String sanitizeFileName(String name) {
        return sanitizeFileName(name, true);
    }

    /**
     * @param archive {@code true} 时补 {@code .zip}（文件夹归档）；单文件结果保留原扩展名
     */
    static String sanitizeFileName(String name, boolean archive) {
        String value = name == null ? "" : name.trim();
        String fallback = archive ? "archive.zip" : "decrypted.bin";
        if (value.isEmpty()) {
            value = fallback;
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (Character.isISOControl(c) || c == '/' || c == '\\'
                    || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|') {
                builder.append('_');
            } else {
                builder.append(c);
            }
        }
        value = builder.toString().replaceAll("[. ]+$", "");
        if (value.isEmpty() || ".".equals(value) || "..".equals(value)) {
            value = fallback;
        }
        if (value.length() > MAX_OUTPUT_NAME_CHARS) {
            value = value.substring(0, MAX_OUTPUT_NAME_CHARS);
        }
        if (!archive || value.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return value;
        }
        return value + ".zip";
    }

    private static String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte value : data) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }
}
