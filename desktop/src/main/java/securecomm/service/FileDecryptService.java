package securecomm.service;

import core.util.FolderZipUtils;
import crypto.file.FileCryptoEngine;
import crypto.file.FileDecryptResult;
import crypto.file.FileProgressListener;
import crypto.message.FileFormatHeader;
import crypto.volume.VolumeCryptoEngine;
import crypto.volume.VolumeDecryptResult;
import crypto.volume.VolumeInfo;
import crypto.volume.VolumeProgressListener;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 桌面文件页的解密执行体。
 *
 * <p>按文件头魔数分流：`VOLU` 走 {@link VolumeCryptoEngine}，其余走
 * {@link FileCryptoEngine}。单文件 `.aegix` 与当前外部分卷共用同一套共享内核。</p>
 *
 * <p>多卷外部分卷不能单独解密：本类在读到 `partCount > 1` 时抛出
 * {@link ExternalPartsException}，由 UI 引导用户去「分卷合并」页。所有输出先写临时文件，
 * 失败或取消时删除；单卷外部分卷按 Android 语义原样输出 ZIP。</p>
 */
public final class FileDecryptService {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_OUTPUT_NAME_CHARS = 180;

    private FileDecryptService() {
    }

    /** 解密结果；[output] 可能是普通文件，也可能是解包后的目录。 */
    public static final class Result {
        private final File output;
        private final boolean folder;
        private final boolean signatureValid;
        private final String originalFileName;
        private final int volumeCount;
        private final boolean contentHashVerified;
        private final boolean externalPart;
        private final String sha256Hash;

        Result(
                File output,
                boolean folder,
                boolean signatureValid,
                String originalFileName,
                int volumeCount,
                boolean contentHashVerified,
                boolean externalPart,
                String sha256Hash
        ) {
            this.output = output;
            this.folder = folder;
            this.signatureValid = signatureValid;
            this.originalFileName = originalFileName;
            this.volumeCount = volumeCount;
            this.contentHashVerified = contentHashVerified;
            this.externalPart = externalPart;
            this.sha256Hash = sha256Hash;
        }

        public File getOutput() {
            return output;
        }

        public boolean isFolder() {
            return folder;
        }

        public boolean isSignatureValid() {
            return signatureValid;
        }

        public String getOriginalFileName() {
            return originalFileName;
        }

        public int getVolumeCount() {
            return volumeCount;
        }

        public boolean isContentHashVerified() {
            return contentHashVerified;
        }

        public boolean isExternalPart() {
            return externalPart;
        }

        public String getSha256Hash() {
            return sha256Hash;
        }
    }

    /** 选中了多卷外部包中的一卷；必须去「分卷合并」页处理整组。 */
    public static final class ExternalPartsException extends Exception {
        private final Integer partIndex;
        private final int partCount;

        ExternalPartsException(Integer partIndex, int partCount) {
            super(message(partIndex, partCount));
            this.partIndex = partIndex;
            this.partCount = partCount;
        }

        public Integer getPartIndex() {
            return partIndex;
        }

        public int getPartCount() {
            return partCount;
        }

        private static String message(Integer partIndex, int partCount) {
            String index = partIndex == null ? "未知" : String.valueOf(partIndex);
            return "这是外部分卷第 " + index + "/" + partCount
                    + " 卷，不能单独解密；请到「分卷合并」页选择全部 "
                    + partCount + " 卷后合并为 ZIP。";
        }
    }

    /**
     * 解密一个文件。
     *
     * @param input           密文文件
     * @param privateKey      接收方私钥
     * @param senderCandidates 已知发送方公钥，用于验签；可为空
     * @param outputDirectory 输出目录
     * @param singleListener  单文件进度，可为 null
     * @param volumeListener  VOLU 进度，可为 null
     * @param cancelled       取消标记，可为 null
     */
    public static Result decrypt(
            File input,
            PrivateKey privateKey,
            List<PublicKey> senderCandidates,
            File outputDirectory,
            FileProgressListener singleListener,
            VolumeProgressListener volumeListener,
            AtomicBoolean cancelled
    ) throws Exception {
        if (input == null || !input.isFile()) {
            throw new IOException("输入文件不存在或不是普通文件");
        }
        if (privateKey == null) {
            throw new IllegalArgumentException("私钥不能为空");
        }
        if (outputDirectory == null) {
            throw new IllegalArgumentException("输出目录不能为空");
        }
        if (!outputDirectory.exists()
                && !outputDirectory.mkdirs()
                && !outputDirectory.isDirectory()) {
            throw new IOException("无法创建输出目录: " + outputDirectory);
        }
        if (!outputDirectory.isDirectory()) {
            throw new IOException("输出路径不是目录: " + outputDirectory);
        }

        List<PublicKey> senders = senderCandidates == null
                ? Collections.<PublicKey>emptyList()
                : senderCandidates;
        if (readMagic(input) == FileFormatHeader.MAGIC) {
            return decryptVolume(
                    input, privateKey, senders, outputDirectory, volumeListener, cancelled);
        }
        return decryptSingle(
                input, privateKey, senders, outputDirectory, singleListener, cancelled);
    }

    private static Result decryptSingle(
            File input,
            PrivateKey privateKey,
            List<PublicKey> senderCandidates,
            File outputDirectory,
            FileProgressListener listener,
            AtomicBoolean cancelled
    ) throws Exception {
        File temporary = File.createTempFile("aegix-decrypt-", ".tmp", outputDirectory);
        boolean success = false;
        try {
            FileDecryptResult result;
            try (InputStream stream = new BufferedInputStream(
                         new FileInputStream(input), BUFFER_SIZE);
                 OutputStream output = new BufferedOutputStream(
                         new FileOutputStream(temporary), BUFFER_SIZE)) {
                result = FileCryptoEngine.decrypt(
                        stream,
                        privateKey,
                        senderCandidates,
                        output,
                        listener,
                        cancelled);
            }

            String originalName = result.getOriginalFileName();
            if (isFolderMarker(originalName)) {
                File folder = restoreFolder(
                        temporary,
                        outputDirectory,
                        stripSuffix(originalName, ".folder"));
                FolderZipUtils.safeDelete(temporary);
                success = true;
                return new Result(
                        folder,
                        true,
                        result.isSignatureValid(),
                        originalName,
                        0,
                        false,
                        false,
                        null);
            }

            File output = uniqueFile(
                    outputDirectory,
                    originalName,
                    fallbackName(input, ".aegix"));
            move(temporary, output);
            success = true;
            return new Result(
                    output,
                    false,
                    result.isSignatureValid(),
                    originalName == null ? output.getName() : originalName,
                    0,
                    false,
                    false,
                    null);
        } finally {
            if (!success) {
                FolderZipUtils.safeDelete(temporary);
            }
        }
    }

    private static Result decryptVolume(
            File input,
            PrivateKey privateKey,
            List<PublicKey> senderCandidates,
            File outputDirectory,
            VolumeProgressListener listener,
            AtomicBoolean cancelled
    ) throws Exception {
        VolumeInfo info;
        try (InputStream stream = new BufferedInputStream(
                new FileInputStream(input), BUFFER_SIZE)) {
            info = VolumeCryptoEngine.readInfo(stream, privateKey);
        }

        if (info.getPartCount() > 1) {
            throw new ExternalPartsException(info.getPartIndex(), info.getPartCount());
        }

        File temporary = File.createTempFile("volu-decrypt-", ".tmp", outputDirectory);
        boolean success = false;
        try {
            VolumeDecryptResult result;
            try (InputStream stream = new BufferedInputStream(
                         new FileInputStream(input), BUFFER_SIZE);
                 OutputStream output = new BufferedOutputStream(
                         new FileOutputStream(temporary), BUFFER_SIZE)) {
                result = VolumeCryptoEngine.decrypt(
                        stream,
                        privateKey,
                        senderCandidates,
                        output,
                        listener,
                        cancelled);
            }
            if (temporary.length() != result.getPlaintextBytes()) {
                throw new IOException("解密输出长度与结果不一致");
            }

            String requestedName = firstNonBlank(
                    result.getArchiveName(), result.getOriginalFileName());
            File output = uniqueFile(
                    outputDirectory,
                    requestedName,
                    fallbackName(input, ".aegix"));
            move(temporary, output);
            success = true;
            return new Result(
                    output,
                    false,
                    result.isSignatureValid(),
                    requestedName == null ? output.getName() : requestedName,
                    result.getVolumeCount(),
                    result.isContentHashVerified(),
                    true,
                    info.getSha256Hash());
        } finally {
            if (!success) {
                FolderZipUtils.safeDelete(temporary);
            }
        }
    }

    /**
     * 把文件夹 ZIP 还原成以原文件夹名命名的目录，内容直接放在该目录下。
     *
     * <p>文件夹 ZIP 自带一层根目录（{@code 文件夹名/...}）。如果直接解进
     * {@code output/文件夹名}，会得到 {@code output/文件夹名/文件夹名/...} 的双层目录。
     * 这里先解到同盘临时目录，再把这唯一根目录搬到最终位置；没有单一根目录的
     * 旧/异常 ZIP 则把顶层条目逐个搬进去，仍不产生额外层级。</p>
     */
    private static File restoreFolder(
            File zip,
            File outputDirectory,
            String requestedName
    ) throws Exception {
        File staging = Files.createTempDirectory(
                outputDirectory.toPath(), "aegix-unzip-").toFile();
        File target = null;
        try {
            FolderZipUtils.unzipFolder(zip, staging, null);
            target = uniqueDirectory(outputDirectory, requestedName);
            File[] top = staging.listFiles();
            if (top != null && top.length == 1 && top[0].isDirectory()) {
                Files.move(top[0].toPath(), target.toPath());
            } else {
                if (!target.mkdirs() && !target.isDirectory()) {
                    throw new IOException("无法创建解压目录: " + target);
                }
                if (top != null) {
                    for (File child : top) {
                        Files.move(child.toPath(), new File(target, child.getName()).toPath());
                    }
                }
            }
            return target;
        } catch (Exception error) {
            if (target != null) {
                FolderZipUtils.safeDelete(target);
            }
            throw error;
        } finally {
            FolderZipUtils.safeDelete(staging);
        }
    }

    private static int readMagic(File input) throws IOException {
        byte[] magic = new byte[4];
        try (InputStream stream = new FileInputStream(input)) {
            int read = 0;
            while (read < 4) {
                int count = stream.read(magic, read, 4 - read);
                if (count < 0) {
                    return 0;
                }
                if (count > 0) {
                    read += count;
                }
            }
        }
        return ((magic[0] & 0xff) << 24)
                | ((magic[1] & 0xff) << 16)
                | ((magic[2] & 0xff) << 8)
                | (magic[3] & 0xff);
    }

    private static void move(File temporary, File output) throws IOException {
        Files.move(
                temporary.toPath(),
                output.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    /** 只保留叶子名，并替换 Windows 不允许的字符；防止解密文件名带路径。 */
    static String safeFileName(String name, String fallback) {
        String value = name == null ? "" : name.trim();
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
            int dot = value.lastIndexOf('.');
            if (dot > 0 && dot > MAX_OUTPUT_NAME_CHARS - 16) {
                String extension = value.substring(dot);
                value = value.substring(0, MAX_OUTPUT_NAME_CHARS - extension.length())
                        + extension;
            } else {
                value = value.substring(0, MAX_OUTPUT_NAME_CHARS);
            }
        }
        return value;
    }

    private static File uniqueFile(File directory, String requestedName, String fallback) {
        String name = safeFileName(requestedName, fallback);
        File candidate = new File(directory, name);
        if (!candidate.exists()) {
            return candidate;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int index = 2; index < 10000; index++) {
            candidate = new File(directory, base + " (" + index + ")" + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(directory, base + "-" + System.currentTimeMillis() + extension);
    }

    private static File uniqueDirectory(File directory, String requestedName) {
        String name = safeFileName(requestedName, "decrypted");
        File candidate = new File(directory, name);
        if (!candidate.exists()) {
            return candidate;
        }
        for (int index = 2; index < 10000; index++) {
            candidate = new File(directory, name + " (" + index + ")");
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(directory, name + "-" + System.currentTimeMillis());
    }

    private static boolean isFolderMarker(String name) {
        return name != null
                && name.toLowerCase(Locale.ROOT).endsWith(".folder");
    }

    private static String stripSuffix(String name, String suffix) {
        if (name == null || name.trim().isEmpty()) {
            return "decrypted";
        }
        String value = name.trim();
        if (value.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT))) {
            value = value.substring(0, value.length() - suffix.length());
        }
        return value.isEmpty() ? "decrypted" : value;
    }

    private static String fallbackName(File input, String encryptedExtension) {
        String name = input.getName();
        if (name.toLowerCase(Locale.ROOT).endsWith(
                encryptedExtension.toLowerCase(Locale.ROOT))) {
            name = name.substring(0, name.length() - encryptedExtension.length());
        }
        return name.trim().isEmpty() ? "decrypted.bin" : name;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first;
        }
        return second;
    }
}
