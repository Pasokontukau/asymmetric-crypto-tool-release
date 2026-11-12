package crypto.volume;

import crypto.cipher.Encryptor;
import crypto.config.CryptoConfig;
import crypto.file.FileCryptoEngine;
import crypto.file.FileProgressListener;
import crypto.key.KeyUtils;
import crypto.message.FileFormatHeader;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 把源文件（ZIP 或单个原文件）的一个切片写成独立的单卷 VOLU。
 *
 * <p>每卷都是完整的 VOLU：12 字节头 + 加密 VolumeInfo + 一个
 * {@link FileCryptoEngine} 单文件容器。因此每一卷都能单独交给
 * {@link VolumeCryptoEngine#decrypt} 解密；多卷顺序由 VolumeInfo 的
 * {@code partIndex/partCount} 决定，而不是外层文件名。</p>
 *
 * <p>写入分两步：先把内部单文件容器写到同目录临时文件，得到准确的
 * {@code encryptedVolumeSizes} 和本卷 SHA-256；再写 VOLU 头与元数据，最后把
 * 内部容器复制到最终临时文件并原子重命名。失败时本方法只负责删除自己创建的
 * 临时文件；调用方仍应清理整批已经成功写出的分卷。</p>
 *
 * <p>不新增依赖、不把整个源文件或整卷读入内存；两条切片流分别用于签名和加密。
 * 普通 {@code File} 走兼容重载，SAF 等无路径来源通过 {@link VolumePartSource} 提供
 * 从偏移开始的流。</p>
 */
public final class VolumePartWriter {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final int MAX_ENCRYPTED_INNER_NAME_BYTES =
            VolumeCryptoEngine.MAX_ENCRYPTED_FILE_NAME_BYTES - CryptoConfig.GCM_TAG_LENGTH;
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final String TEMP_PREFIX = "volpart-";

    private VolumePartWriter() {
    }

    /**
     * 写入一个外部分卷。
     *
     * @param sourceFile           源文件（ZIP 或单个原文件）；长度必须等于 {@code plan.getArchiveBytes()}
     * @param plan                 由 {@link VolumePartPlan#create} 或 {@link VolumePartPlan#createForFile} 生成的计划
     * @param partIndex            1-based 卷号
     * @param setId                同一批分卷共享的随机 ID
     * @param archiveName          合并后的叶子文件名，如 {@code Photos.zip} 或 {@code movie.mkv}
     * @param archiveSha256        整个源文件的 SHA-256 小写/大写 hex
     * @param archiveSourceIsFolder 原始来源是否为文件夹
     * @param sourceFileCount      ZIP 内文件条目数（单文件为 1）
     * @param outputFile           最终 `.aegix` 路径；调用方负责命名
     * @param signingKey           发送方私钥
     * @param recipientKey         接收方公钥
     * @param listener             进度回调，可为 null
     * @param cancelled            取消标记，可为 null
     * @return 成功写出的最终文件
     */
    public static File writePart(
            File sourceFile,
            VolumePartPlan plan,
            int partIndex,
            String setId,
            String archiveName,
            String archiveSha256,
            boolean archiveSourceIsFolder,
            int sourceFileCount,
            File outputFile,
            PrivateKey signingKey,
            PublicKey recipientKey,
            FileProgressListener listener,
            AtomicBoolean cancelled
    ) throws Exception {
        return writePart(
                sourceFile,
                plan,
                partIndex,
                setId,
                archiveName,
                archiveSha256,
                archiveSourceIsFolder,
                true,
                sourceFileCount,
                outputFile,
                signingKey,
                recipientKey,
                listener,
                cancelled);
    }

    /**
     * 写入一个外部分卷，并显式指定源是否为归档 ZIP。
     *
     * <p>{@code sourceIsArchive=false} 表示源是单个普通文件，接收端合并后应直接得到
     * 原文件，而不是解压 ZIP。此时要求 {@code sourceFileCount=1} 且
     * {@code archiveSourceIsFolder=false}。</p>
     */
    public static File writePart(
            File sourceFile,
            VolumePartPlan plan,
            int partIndex,
            String setId,
            String archiveName,
            String archiveSha256,
            boolean archiveSourceIsFolder,
            boolean sourceIsArchive,
            int sourceFileCount,
            File outputFile,
            PrivateKey signingKey,
            PublicKey recipientKey,
            FileProgressListener listener,
            AtomicBoolean cancelled
    ) throws Exception {
        validateSourceFile(sourceFile);
        return writePartFromSource(
                fileSource(sourceFile),
                plan,
                partIndex,
                setId,
                archiveName,
                archiveSha256,
                archiveSourceIsFolder,
                sourceIsArchive,
                sourceFileCount,
                outputFile,
                signingKey,
                recipientKey,
                listener,
                cancelled);
    }

    /**
     * 写入一个外部分卷，源由 {@link VolumePartSource} 提供。
     *
     * <p>Android 的文件页使用这个重载直接读 SAF 流，不落一份完整临时文件。</p>
     */
    public static File writePartFromSource(
            VolumePartSource source,
            VolumePartPlan plan,
            int partIndex,
            String setId,
            String archiveName,
            String archiveSha256,
            boolean archiveSourceIsFolder,
            boolean sourceIsArchive,
            int sourceFileCount,
            File outputFile,
            PrivateKey signingKey,
            PublicKey recipientKey,
            FileProgressListener listener,
            AtomicBoolean cancelled
    ) throws Exception {
        validateArguments(
                source,
                plan,
                partIndex,
                setId,
                archiveName,
                archiveSha256,
                archiveSourceIsFolder,
                sourceIsArchive,
                sourceFileCount,
                outputFile,
                signingKey,
                recipientKey
        );
        checkCancelled(cancelled);

        final long sourceLength = source.length();
        final long sourceModified = source.lastModified();
        if (sourceLength != plan.getArchiveBytes()) {
            throw new VolumePartException(
                    VolumePartException.Code.SIZE_MISMATCH,
                    "源文件长度与分卷计划不一致: " + sourceLength + " vs " + plan.getArchiveBytes()
            );
        }

        final int partCount = plan.getPartCount();
        final long partPlaintextBytes = plan.getPartPlaintextBytes(partIndex);
        if (partPlaintextBytes <= 0) {
            throw new VolumePartException(
                    VolumePartException.Code.INVALID_PLAN,
                    "本卷明文长度必须大于 0"
            );
        }

        final String partName = partCount == 1
                ? archiveName
                : archiveName + ".part" + String.format(Locale.ROOT, "%03d", partIndex);
        final String innerName = partName + ".part1";
        if (partName.length() > VolumeInfo.MAX_FILE_NAME_CHARS) {
            throw new VolumePartException(
                    VolumePartException.Code.INVALID_PLAN,
                    "本卷逻辑名超过 " + VolumeInfo.MAX_FILE_NAME_CHARS + " 字符"
            );
        }
        if (innerName.getBytes(StandardCharsets.UTF_8).length
                > MAX_ENCRYPTED_INNER_NAME_BYTES) {
            throw new VolumePartException(
                    VolumePartException.Code.INVALID_PLAN,
                    "本卷内部文件名过长"
            );
        }

        final File absoluteOutput = outputFile.getAbsoluteFile();
        final File parent = absoluteOutput.getParentFile();
        if (parent == null) {
            throw new VolumePartException(
                    VolumePartException.Code.INVALID_PLAN,
                    "输出路径没有父目录"
            );
        }
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new VolumePartException(
                    VolumePartException.Code.IO_ERROR,
                    "无法创建输出目录: " + parent
            );
        }
        if (!parent.isDirectory()) {
            throw new VolumePartException(
                    VolumePartException.Code.IO_ERROR,
                    "输出父路径不是目录: " + parent
            );
        }
        if (absoluteOutput.exists()) {
            throw new VolumePartException(
                    VolumePartException.Code.INVALID_PLAN,
                    "输出文件已存在: " + absoluteOutput
            );
        }

        File innerFile = null;
        File tempFile = null;
        boolean success = false;
        try {
            innerFile = File.createTempFile(TEMP_PREFIX, ".inner.tmp", parent);
            tempFile = File.createTempFile(TEMP_PREFIX, ".part.tmp", parent);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final long partOffset = plan.getPartOffset(partIndex);
            try (InputStream signatureInput = new DigestInputStream(
                         new SliceInputStream(source, partOffset, partPlaintextBytes),
                         digest);
                 InputStream plaintextInput = new SliceInputStream(
                         source, partOffset, partPlaintextBytes);
                 OutputStream innerOutput = new BufferedOutputStream(
                         new FileOutputStream(innerFile), BUFFER_SIZE)) {
                FileCryptoEngine.encrypt(
                        signatureInput,
                        plaintextInput,
                        partPlaintextBytes,
                        innerName,
                        signingKey,
                        recipientKey,
                        innerOutput,
                        listener,
                        cancelled
                );
            }

            final long innerLength = innerFile.length();
            if (innerLength <= 0 || innerLength > Integer.MAX_VALUE) {
                throw new VolumePartException(
                        VolumePartException.Code.SIZE_MISMATCH,
                        "内部单文件容器长度超出范围: " + innerLength
                );
            }
            final String partSha256 = toHex(digest.digest());

            final VolumeInfo info = new VolumeInfo(
                    partName,
                    partPlaintextBytes,
                    1,
                    partPlaintextBytes,
                    partCount == 1 && archiveSourceIsFolder,
                    partSha256,
                    new Date().toString(),
                    Collections.singletonList(innerLength),
                    setId,
                    partIndex,
                    partCount,
                    archiveName,
                    plan.getArchiveBytes(),
                    archiveSha256.toLowerCase(Locale.ROOT),
                    archiveSourceIsFolder,
                    sourceIsArchive,
                    sourceFileCount
            );
            final byte[] metadataJson = info.toJson().getBytes(StandardCharsets.UTF_8);
            if (metadataJson.length > MAX_METADATA_BYTES
                    || metadataJson.length > VolumeInfoParser.MAX_JSON_CHARS) {
                throw new VolumePartException(
                        VolumePartException.Code.SIZE_MISMATCH,
                        "VolumeInfo 元数据超过 1 MiB"
                );
            }
            final byte[] encryptedMetadata = Encryptor.encryptData(metadataJson, recipientKey)
                    .toBytes();
            if (encryptedMetadata.length > MAX_METADATA_BYTES) {
                throw new VolumePartException(
                        VolumePartException.Code.SIZE_MISMATCH,
                        "加密后的 VolumeInfo 超过 1 MiB"
                );
            }

            final long expectedLength;
            try {
                expectedLength = Math.addExact(
                        (long) FileFormatHeader.HEADER_SIZE,
                        (long) encryptedMetadata.length + innerLength
                );
            } catch (ArithmeticException error) {
                throw new VolumePartException(
                        VolumePartException.Code.SIZE_MISMATCH,
                        "分卷长度溢出",
                        error
                );
            }

            try (OutputStream output = new BufferedOutputStream(
                    new FileOutputStream(tempFile), BUFFER_SIZE)) {
                output.write(KeyUtils.intToBytes(FileFormatHeader.MAGIC));
                output.write(KeyUtils.intToBytes(FileFormatHeader.CURRENT_VERSION));
                output.write(KeyUtils.intToBytes(encryptedMetadata.length));
                output.write(encryptedMetadata);
                copyFile(innerFile, output);
            }

            if (tempFile.length() != expectedLength) {
                throw new VolumePartException(
                        VolumePartException.Code.SIZE_MISMATCH,
                        "分卷实际长度与预期不一致: " + tempFile.length() + " vs " + expectedLength
                );
            }
            if (tempFile.length() > plan.getVolumeLimitBytes()) {
                throw new VolumePartException(
                        VolumePartException.Code.SIZE_MISMATCH,
                        "分卷最终大小超过所选上限: " + tempFile.length()
                                + " > " + plan.getVolumeLimitBytes()
                );
            }
            if (source.length() != sourceLength
                    || (sourceModified >= 0 && source.lastModified() != sourceModified)) {
                throw new VolumePartException(
                        VolumePartException.Code.SOURCE_CHANGED,
                        "源文件在分卷写入期间被修改"
                );
            }
            if (!tempFile.renameTo(absoluteOutput)) {
                throw new VolumePartException(
                        VolumePartException.Code.IO_ERROR,
                        "无法重命名分卷临时文件: " + absoluteOutput
                );
            }
            success = true;
            return absoluteOutput;
        } finally {
            deleteQuietly(innerFile);
            if (!success) {
                deleteQuietly(tempFile);
            }
        }
    }

    private static void validateArguments(
            VolumePartSource source,
            VolumePartPlan plan,
            int partIndex,
            String setId,
            String archiveName,
            String archiveSha256,
            boolean archiveSourceIsFolder,
            boolean sourceIsArchive,
            int sourceFileCount,
            File outputFile,
            PrivateKey signingKey,
            PublicKey recipientKey
    ) throws VolumePartException {
        if (source == null) {
            throw new VolumePartException(
                    VolumePartException.Code.INVALID_PLAN,
                    "源文件不能为空"
            );
        }
        if (plan == null) {
            throw new VolumePartException(
                    VolumePartException.Code.INVALID_PLAN,
                    "分卷计划不能为空"
            );
        }
        if (partIndex < 1 || partIndex > plan.getPartCount()) {
            throw new VolumePartException(
                    VolumePartException.Code.INVALID_PLAN,
                    "卷号超出计划范围: " + partIndex
            );
        }
        if (outputFile == null) {
            throw new VolumePartException(
                    VolumePartException.Code.INVALID_PLAN,
                    "输出文件不能为空"
            );
        }
        if (signingKey == null || recipientKey == null) {
            throw new VolumePartException(
                    VolumePartException.Code.INVALID_PLAN,
                    "签名私钥和接收方公钥不能为空"
            );
        }
        if (setId == null || setId.isEmpty()
                || setId.length() > VolumeInfo.MAX_SET_ID_CHARS) {
            throw new VolumePartException(
                    VolumePartException.Code.INVALID_PLAN,
                    "setId 为空或超过长度上限"
            );
        }
        for (int index = 0; index < setId.length(); index++) {
            if (Character.isISOControl(setId.charAt(index))) {
                throw new VolumePartException(
                        VolumePartException.Code.INVALID_PLAN,
                        "setId 含控制字符"
                );
            }
        }
        if (archiveName == null || archiveName.isEmpty()
                || ".".equals(archiveName) || "..".equals(archiveName)
                || archiveName.length() > VolumeInfo.MAX_FILE_NAME_CHARS) {
            throw new VolumePartException(
                    VolumePartException.Code.INVALID_PLAN,
                    "archiveName 为空、非法或过长"
            );
        }
        for (int index = 0; index < archiveName.length(); index++) {
            char value = archiveName.charAt(index);
            if (Character.isISOControl(value) || value == '/' || value == '\\') {
                throw new VolumePartException(
                        VolumePartException.Code.INVALID_PLAN,
                        "archiveName 含路径分隔符或控制字符"
                );
            }
        }
        if (archiveSha256 == null || !SHA256_PATTERN.matcher(archiveSha256).matches()) {
            throw new VolumePartException(
                    VolumePartException.Code.INVALID_PLAN,
                    "archiveSha256 必须是 64 位十六进制"
            );
        }
        if (sourceFileCount < 0 || sourceFileCount > VolumeInfo.MAX_SOURCE_FILE_COUNT) {
            throw new VolumePartException(
                    VolumePartException.Code.INVALID_PLAN,
                    "sourceFileCount 超出范围"
            );
        }
        if (!sourceIsArchive) {
            if (sourceFileCount != 1) {
                throw new VolumePartException(
                        VolumePartException.Code.INVALID_PLAN,
                        "单文件外部分卷的 sourceFileCount 必须为 1"
                );
            }
            if (archiveSourceIsFolder) {
                throw new VolumePartException(
                        VolumePartException.Code.INVALID_PLAN,
                        "单文件外部分卷不能标记为文件夹来源"
                );
            }
        }
    }

    private static void validateSourceFile(File sourceFile) throws VolumePartException {
        if (sourceFile == null || !sourceFile.isFile()) {
            throw new VolumePartException(
                    VolumePartException.Code.INVALID_PLAN,
                    "源文件不存在或不是普通文件"
            );
        }
    }

    private static VolumePartSource fileSource(final File file) {
        return new VolumePartSource() {
            @Override
            public long length() {
                return file.length();
            }

            @Override
            public long lastModified() {
                return file.lastModified();
            }

            @Override
            public InputStream openAt(long offset) throws IOException {
                FileInputStream raw = new FileInputStream(file);
                try {
                    raw.getChannel().position(offset);
                } catch (IOException error) {
                    try {
                        raw.close();
                    } catch (IOException ignored) {
                        // 保留定位失败。
                    }
                    throw error;
                }
                return raw;
            }
        };
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws IOException {
        if (cancelled != null && cancelled.get()) {
            throw new crypto.file.FileCancelledException("分卷写入已取消");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new crypto.file.FileCancelledException("分卷写入已取消");
        }
    }

    private static void copyFile(File source, OutputStream output) throws IOException {
        try (InputStream input = new BufferedInputStream(
                new FileInputStream(source), BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                output.write(buffer, 0, read);
            }
        }
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

    private static String toHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = digits[value >>> 4];
            result[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(result);
    }

    /** 只读源文件的 [offset, offset+length) 切片；提前 EOF 视为源文件不一致。 */
    private static final class SliceInputStream extends InputStream {

        private final BufferedInputStream input;
        private long remaining;

        SliceInputStream(VolumePartSource source, long offset, long length) throws IOException {
            if (offset < 0 || length < 0) {
                throw new IllegalArgumentException("切片偏移和长度不能为负");
            }
            input = new BufferedInputStream(source.openAt(offset), BUFFER_SIZE);
            remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int value = input.read();
            if (value < 0) {
                throw new EOFException("源文件在分卷切片结束前提前结束");
            }
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (remaining == 0) {
                return -1;
            }
            int toRead = (int) Math.min((long) length, remaining);
            int read = input.read(buffer, offset, toRead);
            if (read < 0) {
                throw new EOFException("源文件在分卷切片结束前提前结束");
            }
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min((long) input.available(), remaining);
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }
}
