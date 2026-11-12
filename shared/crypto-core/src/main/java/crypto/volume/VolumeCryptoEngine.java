package crypto.volume;

import crypto.cipher.Decryptor;
import crypto.cipher.Encryptor;
import crypto.config.CryptoConfig;
import crypto.file.FileCancelledException;
import crypto.file.FileCryptoEngine;
import crypto.file.FileDecryptResult;
import crypto.file.FileFormatException;
import crypto.file.FileProgressListener;
import crypto.file.WrongRecipientException;
import crypto.message.FileFormatHeader;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 桌面端 VOLU 分卷容器的只读解密。
 *
 * <p>容器结构：</p>
 * <pre>
 * [magic 4 = "VOLU"][version 4 = 1]
 * [加密 VolumeInfo 长度 4][加密 VolumeInfo]
 * [本卷：完整 SignedAndEncryptedData]
 * </pre>
 *
 * <p>每一卷都是独立的单卷容器，内部复用 {@link FileCryptoEngine#decrypt}；
 * 本类只负责容器头、VolumeInfo、本卷边界、卷名/SHA-256 校验与进度。
 * 旧的多分卷 VOLU 在元数据解析阶段即被拒绝，不在这里逐卷猜测。</p>
 */
public final class VolumeCryptoEngine {

    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final int MAX_SIGNATURE_BYTES = 10240;
    private static final int MAX_FILE_NAME_IV_BYTES = 256;
    static final int MAX_ENCRYPTED_FILE_NAME_BYTES = 4096;
    private static final int MAX_ENCRYPTED_KEY_BYTES = 1024;
    private static final int MAX_IV_BYTES = 128;
    private static final int MAX_PART_HEADER_BYTES = 16 * 1024;
    private static final int MIN_CIPHERTEXT_BYTES = CryptoConfig.GCM_TAG_LENGTH;
    private static final int READ_BUFFER_BYTES = 64 * 1024;

    private VolumeCryptoEngine() {
    }

    /**
     * 只读取 VOLU 头并解密 VolumeInfo，不读取任何分卷 payload。
     *
     * <p>输入流不会被包装成 BufferedInputStream，避免预读越过元数据边界；调用方负责
     * 流的生命周期。返回的 VolumeInfo 已由 {@link VolumeInfoParser} 做过字段范围与
     * 单卷自洽校验；集合层面的缺卷/重卷/混包由上层分组逻辑完成。</p>
     */
    public static VolumeInfo readInfo(InputStream input, PrivateKey recipientKey) throws Exception {
        if (input == null || recipientKey == null) {
            throw new IllegalArgumentException("输入流和私钥不能为空");
        }

        final int magic;
        final int version;
        try {
            magic = readInt(input);
            version = readInt(input);
        } catch (EOFException error) {
            throw new VolumeFormatException("VOLU 分卷头不完整", error);
        }
        if (magic != FileFormatHeader.MAGIC) {
            throw new VolumeFormatException("不是 VOLU 分卷文件");
        }
        if (version != FileFormatHeader.CURRENT_VERSION) {
            throw new UnsupportedVolumeVersionException(version);
        }

        final int metadataLength;
        try {
            metadataLength = readBoundedLength(input, 1, MAX_METADATA_BYTES, "VolumeInfo 长度");
        } catch (EOFException error) {
            throw new VolumeFormatException("VOLU 元数据长度不完整", error);
        }
        byte[] encryptedMetadata = new byte[metadataLength];
        try {
            readFully(input, encryptedMetadata, 0, metadataLength);
        } catch (EOFException error) {
            throw new VolumeFormatException("VOLU 元数据不完整", error);
        }
        return decryptMetadata(encryptedMetadata, recipientKey);
    }

    /**
     * 解密一个 VOLU 容器，把明文写到 [output]。
     *
     * <p>调用方负责创建/删除输出文件；本方法只在所有硬校验通过后返回。
     * 任一 GCM/结构/卷数/顺序/大小/SHA-256 失败都会抛异常，且不保证输出文件可用。</p>
     */
    public static VolumeDecryptResult decrypt(
            InputStream input,
            PrivateKey recipientKey,
            List<PublicKey> senderCandidates,
            OutputStream output,
            VolumeProgressListener listener,
            AtomicBoolean cancelled
    ) throws Exception {
        if (input == null || recipientKey == null || output == null) {
            throw new IllegalArgumentException("输入流、私钥和输出流不能为空");
        }

        BufferedInputStream source = input instanceof BufferedInputStream
                ? (BufferedInputStream) input
                : new BufferedInputStream(input, READ_BUFFER_BYTES);

        final int magic;
        final int version;
        try {
            magic = readInt(source);
            version = readInt(source);
        } catch (EOFException error) {
            throw new VolumeFormatException("VOLU 分卷头不完整", error);
        }
        if (magic != FileFormatHeader.MAGIC) {
            throw new VolumeFormatException("不是 VOLU 分卷文件");
        }
        if (version != FileFormatHeader.CURRENT_VERSION) {
            throw new UnsupportedVolumeVersionException(version);
        }

        final int metadataLength;
        try {
            metadataLength = readBoundedLength(source, 1, MAX_METADATA_BYTES, "VolumeInfo 长度");
        } catch (EOFException error) {
            throw new VolumeFormatException("VOLU 元数据长度不完整", error);
        }
        byte[] encryptedMetadata = new byte[metadataLength];
        readFully(source, encryptedMetadata, 0, metadataLength);
        VolumeInfo info = decryptMetadata(encryptedMetadata, recipientKey);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        DigestOutputStream digestOutput = new DigestOutputStream(output, digest);

        final int partNumber = 1;
        final int partCount = 1;
        final long expectedPlaintextBytes = info.getOriginalFileSize();
        final long expectedEncryptedSize = info.getEncryptedVolumeSizes().get(0);
        List<PublicKey> candidates = senderCandidates == null
                ? Collections.<PublicKey>emptyList()
                : senderCandidates;

        checkCancelled(cancelled);
        notifyProgress(listener, partNumber, partCount, 0, expectedPlaintextBytes);
        PartHeader header = readPartHeader(
                source,
                partNumber,
                expectedPlaintextBytes,
                expectedEncryptedSize
        );

        final long ciphertextLength = header.ciphertextLength;
        BoundedInputStream bounded = new BoundedInputStream(source, ciphertextLength);
        SequenceInputStream partStream = new SequenceInputStream(
                new ByteArrayInputStream(header.prefixBytes),
                bounded
        );

        FileProgressListener partListener = (phase, processed, total) -> {
            if (listener == null) {
                return;
            }
            long mapped = ciphertextLength == 0
                    ? 0
                    : Math.min(
                            expectedPlaintextBytes,
                            processed * expectedPlaintextBytes / ciphertextLength
                    );
            notifyProgress(
                    listener,
                    partNumber,
                    partCount,
                    mapped,
                    expectedPlaintextBytes
            );
        };

        FileDecryptResult partResult;
        try {
            partResult = FileCryptoEngine.decrypt(
                    partStream,
                    recipientKey,
                    candidates,
                    digestOutput,
                    partListener,
                    cancelled
            );
        } catch (FileCancelledException error) {
            throw error;
        } catch (WrongRecipientException error) {
            throw new WrongRecipientException(
                    "无法用本机私钥解开分卷密钥",
                    error
            );
        } catch (FileFormatException error) {
            throw new VolumeFormatException(
                    "分卷格式或认证失败: " + error.getMessage(),
                    error
            );
        } catch (IOException error) {
            throw new VolumeFormatException("分卷读取失败", error);
        } finally {
            closeQuietly(partStream);
        }

        if (!bounded.isFullyConsumed()) {
            throw new VolumeFormatException("分卷密文长度与声明不一致");
        }
        long plaintextBytes = partResult.getPlaintextBytes();
        if (plaintextBytes != expectedPlaintextBytes) {
            throw new VolumeIntegrityException(
                    "分卷明文大小与元数据不一致: "
                            + plaintextBytes + " vs " + expectedPlaintextBytes
            );
        }

        String partName = partResult.getOriginalFileName();
        boolean partNamesVerified = true;
        if (partName == null) {
            partNamesVerified = false;
        } else if (!expectedPartName(info, 0).equals(partName)) {
            throw new VolumeIntegrityException("分卷文件名与元数据不一致");
        }

        notifyProgress(
                listener,
                partNumber,
                partCount,
                plaintextBytes,
                expectedPlaintextBytes
        );

        if (source.read() != -1) {
            throw new VolumeFormatException("分卷文件末尾有多余数据");
        }

        digestOutput.flush();
        boolean contentHashVerified = false;
        if (info.hasSha256Hash()) {
            String actual = toHex(digest.digest());
            if (!actual.equalsIgnoreCase(info.getSha256Hash())) {
                throw new VolumeIntegrityException("分卷 SHA-256 校验失败，文件内容可能被篡改");
            }
            contentHashVerified = true;
        }

        return new VolumeDecryptResult(
                info.getOriginalFileName(),
                partResult.isSignatureValid(),
                partResult.getMatchedSender(),
                plaintextBytes,
                info.getVolumeCount(),
                contentHashVerified,
                partNamesVerified,
                info.isSourceIsFolder(),
                info.getSetId(),
                info.getPartIndex(),
                info.getPartCount(),
                info.getArchiveName(),
                info.getArchiveSize(),
                info.getArchiveSha256(),
                info.getArchiveSourceIsFolder(),
                info.getSourceIsArchive(),
                info.getSourceFileCount()
        );
    }

    private static VolumeInfo decryptMetadata(byte[] encrypted, PrivateKey recipientKey)
            throws Exception {
        final Encryptor.EncryptedData data;
        try {
            data = Encryptor.EncryptedData.fromBytes(encrypted);
        } catch (RuntimeException error) {
            throw new VolumeFormatException("VolumeInfo 加密数据格式无效", error);
        }
        final byte[] jsonBytes;
        try {
            jsonBytes = Decryptor.decryptData(data, recipientKey);
        } catch (Exception error) {
            throw new WrongRecipientException(
                    "无法用本机私钥解开分卷元数据，可能不是加密给本机密钥的分卷",
                    error
            );
        }
        return VolumeInfoParser.parse(new String(jsonBytes, StandardCharsets.UTF_8));
    }

    /**
     * 读取一个分卷头；返回的 prefix 包含从签名长度到密文长度字段的全部字节，
     * 可直接回放给单文件引擎，密文由 BoundedInputStream 提供。
     */
    private static PartHeader readPartHeader(
            InputStream source,
            int partNumber,
            long expectedPlaintextBytes,
            Long expectedEncryptedSize
    ) throws IOException, VolumeFormatException {
        ByteArrayOutputStream prefix = new ByteArrayOutputStream(512);

        int signatureLength = readBoundedLength(
                source,
                prefix,
                1,
                MAX_SIGNATURE_BYTES,
                "签名长度"
        );
        readAndCollect(source, prefix, signatureLength, "签名");

        int fileNameIvLength = readBoundedLength(
                source,
                prefix,
                0,
                MAX_FILE_NAME_IV_BYTES,
                "文件名 IV 长度"
        );
        readAndCollect(source, prefix, fileNameIvLength, "文件名 IV");

        int encryptedFileNameLength = readBoundedLength(
                source,
                prefix,
                0,
                MAX_ENCRYPTED_FILE_NAME_BYTES,
                "加密文件名长度"
        );
        readAndCollect(source, prefix, encryptedFileNameLength, "加密文件名");

        int encryptedKeyLength = readBoundedLength(
                source,
                prefix,
                1,
                MAX_ENCRYPTED_KEY_BYTES,
                "加密 AES 密钥长度"
        );
        readAndCollect(source, prefix, encryptedKeyLength, "加密 AES 密钥");

        int ivLength = readBoundedLength(source, prefix, 1, MAX_IV_BYTES, "AES IV 长度");
        readAndCollect(source, prefix, ivLength, "AES IV");

        int ciphertextLength = readBoundedLength(
                source,
                prefix,
                MIN_CIPHERTEXT_BYTES,
                Integer.MAX_VALUE,
                "密文长度"
        );

        if (prefix.size() > MAX_PART_HEADER_BYTES) {
            throw new VolumeFormatException(
                    "第 " + partNumber + " 卷头长度超过上限"
            );
        }
        long totalLength;
        try {
            totalLength = Math.addExact((long) prefix.size(), (long) ciphertextLength);
        } catch (ArithmeticException error) {
            throw new VolumeFormatException("第 " + partNumber + " 卷长度溢出", error);
        }
        if (totalLength > Integer.MAX_VALUE) {
            throw new VolumeFormatException("第 " + partNumber + " 卷总长度超出单卷上限");
        }

        if (expectedEncryptedSize != null && expectedEncryptedSize != totalLength) {
            throw new VolumeIntegrityException(
                    "第 " + partNumber + " 卷加密大小与元数据不一致: "
                            + totalLength + " vs " + expectedEncryptedSize
            );
        }
        if (expectedPlaintextBytes <= 0) {
            throw new VolumeFormatException("第 " + partNumber + " 卷明文大小无效");
        }
        return new PartHeader(prefix.toByteArray(), ciphertextLength, totalLength);
    }

    private static int readBoundedLength(
            InputStream source,
            ByteArrayOutputStream prefix,
            int min,
            int max,
            String field
    ) throws IOException, VolumeFormatException {
        byte[] bytes = new byte[4];
        try {
            readFully(source, bytes, 0, 4);
        } catch (EOFException error) {
            throw new VolumeFormatException("分卷头缺少" + field, error);
        }
        prefix.write(bytes, 0, 4);
        int value = ByteBuffer.wrap(bytes).getInt();
        if (value < min || value > max) {
            throw new VolumeFormatException(
                    "分卷头 " + field + " 超出范围: " + value
            );
        }
        return value;
    }

    private static int readBoundedLength(
            InputStream source,
            int min,
            int max,
            String field
    ) throws IOException, VolumeFormatException {
        byte[] bytes = new byte[4];
        readFully(source, bytes, 0, 4);
        int value = ByteBuffer.wrap(bytes).getInt();
        if (value < min || value > max) {
            throw new VolumeFormatException(field + " 超出范围: " + value);
        }
        return value;
    }

    private static void readAndCollect(
            InputStream source,
            ByteArrayOutputStream prefix,
            int length,
            String field
    ) throws IOException, VolumeFormatException {
        if (length == 0) {
            return;
        }
        if (length > MAX_PART_HEADER_BYTES) {
            throw new VolumeFormatException("分卷头 " + field + " 过长");
        }
        byte[] bytes = new byte[length];
        try {
            readFully(source, bytes, 0, length);
        } catch (EOFException error) {
            throw new VolumeFormatException("分卷头 " + field + " 不完整", error);
        }
        prefix.write(bytes, 0, length);
    }

    private static String expectedPartName(VolumeInfo info, int zeroBasedIndex) {
        return info.getOriginalFileName() + ".part" + (zeroBasedIndex + 1);
    }

    private static void notifyProgress(
            VolumeProgressListener listener,
            int partNumber,
            int partCount,
            long processed,
            long total
    ) {
        if (listener != null) {
            listener.onVolumeProgress(partNumber, partCount, processed, total);
        }
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws FileCancelledException {
        if (cancelled != null && cancelled.get()) {
            throw new FileCancelledException("已取消");
        }
    }

    private static int readInt(InputStream input) throws IOException {
        byte[] bytes = new byte[4];
        readFully(input, bytes, 0, 4);
        return ByteBuffer.wrap(bytes).getInt();
    }

    private static void readFully(InputStream input, byte[] buffer, int offset, int length)
            throws IOException {
        int read = 0;
        while (read < length) {
            int count = input.read(buffer, offset + read, length - read);
            if (count < 0) {
                throw new EOFException("输入流提前结束");
            }
            if (count == 0) {
                continue;
            }
            read += count;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // 上层负责真正的输入流生命周期。
        }
    }

    private static final class PartHeader {
        final byte[] prefixBytes;
        final int ciphertextLength;
        final long totalLength;

        PartHeader(byte[] prefixBytes, int ciphertextLength, long totalLength) {
            this.prefixBytes = prefixBytes;
            this.ciphertextLength = ciphertextLength;
            this.totalLength = totalLength;
        }
    }
}
