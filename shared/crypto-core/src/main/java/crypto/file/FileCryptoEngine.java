package crypto.file;

import core.model.SignedAndEncryptedData;
import crypto.cipher.Encryptor;
import crypto.cipher.OaepCompat;
import crypto.config.CryptoConfig;
import crypto.key.KeyUtils;
import crypto.message.FileFormatHeader;
import crypto.provider.SecurityProvider;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * 单文件 {@code .aegix} 的流式加解密。
 *
 * <p>线格式与桌面端 {@code SignedAndEncryptedData} 完全一致：</p>
 *
 * <pre>
 * [签名长度][签名]
 * [文件名 IV 长度][文件名 IV]
 * [加密文件名长度][加密文件名]
 * [RSA 加密的 AES 密钥长度][RSA 加密的 AES 密钥]
 * [AES IV 长度][AES IV]
 * [密文长度][AES-GCM 密文 + 16 字节 tag]
 * </pre>
 *
 * <p>加密与解密都分块进行，不把整个文件读进内存；加密为了对原文签名会顺序读取输入两次。
 * 输出流由调用方管理，失败时由调用方删除临时文件。</p>
 *
 * <p>当前只支持这种单文件容器；VOLU 分卷容器会明确抛出
 * {@link UnsupportedVolumeFileException}，不会误解析。</p>
 */
public final class FileCryptoEngine {

    /** 单文件格式的密文长度是 32 位有符号整数，文件大小必须给 GCM tag 留出空间。 */
    public static final long MAX_PLAINTEXT_BYTES = Integer.MAX_VALUE - CryptoConfig.GCM_TAG_LENGTH;

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long PROGRESS_STEP_BYTES = 512 * 1024;
    private static final int MAX_ENCRYPTED_KEY_BYTES = 1024;
    private static final int MAX_IV_BYTES = 128;
    private static final String AES_ALGORITHM = CryptoConfig.AES_ALGORITHM;
    private static final int GCM_TAG_BITS = CryptoConfig.GCM_TAG_LENGTH * 8;

    private FileCryptoEngine() {
    }

    /**
     * 加密一个文件。
     *
     * @param signatureInput  第一遍读取，用于对原文签名
     * @param plaintextInput  第二遍读取，用于 AES-GCM 加密
     * @param plaintextSize   原文字节数（必须准确；操作期间文件变化会失败）
     * @param originalFileName 原始文件名，加密后放进文件头
     * @param signingKey      发送方私钥
     * @param recipientKey    接收方公钥
     * @param output          密文输出流（调用方负责关闭/删除临时文件）
     * @param listener        进度回调，可为 null
     * @param cancelled       取消标记，可为 null
     */
    public static void encrypt(
            InputStream signatureInput,
            InputStream plaintextInput,
            long plaintextSize,
            String originalFileName,
            PrivateKey signingKey,
            PublicKey recipientKey,
            OutputStream output,
            FileProgressListener listener,
            AtomicBoolean cancelled
    ) throws Exception {
        if (signatureInput == null || plaintextInput == null) {
            throw new IllegalArgumentException("输入流不能为空");
        }
        if (signingKey == null || recipientKey == null || output == null) {
            throw new IllegalArgumentException("密钥和输出流不能为空");
        }
        if (plaintextSize < 0) {
            throw new IllegalArgumentException("无法确定文件大小");
        }
        if (plaintextSize > MAX_PLAINTEXT_BYTES) {
            throw new FileFormatException("文件超过 2GB，单文件格式无法容纳");
        }

        byte[] signature = signPlaintext(
                signatureInput, plaintextSize, signingKey, listener, cancelled);
        checkCancelled(cancelled);

        SecretKey aesKey = Encryptor.generateAesKey();
        byte[] aesKeyBytes = aesKey.getEncoded();
        if (aesKeyBytes == null) {
            throw new FileFormatException("无法导出 AES 密钥");
        }
        try {
            byte[] iv = new byte[CryptoConfig.GCM_IV_LENGTH];
            SecurityProvider.REGULAR_RANDOM.nextBytes(iv);

            byte[] fileNameIv = new byte[0];
            byte[] encryptedFileName = new byte[0];
            if (originalFileName != null && !originalFileName.isEmpty()) {
                fileNameIv = new byte[CryptoConfig.GCM_IV_LENGTH];
                SecurityProvider.REGULAR_RANDOM.nextBytes(fileNameIv);
                Cipher nameCipher = Cipher.getInstance(AES_ALGORITHM);
                nameCipher.init(
                        Cipher.ENCRYPT_MODE,
                        aesKey,
                        new GCMParameterSpec(GCM_TAG_BITS, fileNameIv));
                encryptedFileName = nameCipher.doFinal(
                        originalFileName.getBytes(StandardCharsets.UTF_8));
            }

            byte[] encryptedAesKey = OaepCompat.encryptCipher(recipientKey).doFinal(aesKeyBytes);
            long ciphertextLength = plaintextSize + CryptoConfig.GCM_TAG_LENGTH;
            if (ciphertextLength > Integer.MAX_VALUE) {
                throw new FileFormatException("文件超过单文件格式上限");
            }

            writeInt(output, signature.length);
            output.write(signature);
            writeInt(output, fileNameIv.length);
            output.write(fileNameIv);
            writeInt(output, encryptedFileName.length);
            output.write(encryptedFileName);
            writeInt(output, encryptedAesKey.length);
            output.write(encryptedAesKey);
            writeInt(output, iv.length);
            output.write(iv);
            writeInt(output, (int) ciphertextLength);

            // Android 的 Conscrypt 会在 Cipher.update() 里缓存整段 GCM 密文，大文件直接 OOM。
            // BouncyCastle 的 GCMBlockCipher 按块输出，线格式仍是标准 AES-256-GCM。
            GCMBlockCipher cipher = new GCMBlockCipher(new AESEngine());
            cipher.init(
                    true,
                    new AEADParameters(
                            new KeyParameter(aesKeyBytes), GCM_TAG_BITS, iv));

            byte[] buffer = new byte[BUFFER_SIZE];
            byte[] cipherBuffer = new byte[BUFFER_SIZE + CryptoConfig.GCM_TAG_LENGTH];
            long processed = 0;
            long encryptedBytes = 0;
            long lastReported = -1;
            while (true) {
                checkCancelled(cancelled);
                int read = plaintextInput.read(buffer);
                if (read == -1) break;
                if (read == 0) continue;
                processed += read;
                if (processed > plaintextSize) {
                    throw new FileFormatException("文件大小在加密过程中发生变化");
                }
                int produced = cipher.processBytes(buffer, 0, read, cipherBuffer, 0);
                if (produced > 0) {
                    output.write(cipherBuffer, 0, produced);
                    encryptedBytes += produced;
                }
                lastReported = report(
                        listener, FileProgressListener.Phase.ENCRYPTING,
                        processed, plaintextSize, lastReported);
            }
            if (processed != plaintextSize) {
                throw new FileFormatException("文件大小在加密过程中发生变化");
            }
            int tail;
            try {
                tail = cipher.doFinal(cipherBuffer, 0);
            } catch (InvalidCipherTextException error) {
                throw new FileFormatException("加密失败：无法生成 GCM 认证标签", error);
            }
            if (tail > 0) {
                output.write(cipherBuffer, 0, tail);
                encryptedBytes += tail;
            }
            if (encryptedBytes != ciphertextLength) {
                throw new FileFormatException("加密输出长度不一致");
            }
            output.flush();
        } finally {
            Arrays.fill(aesKeyBytes, (byte) 0);
        }
    }

    /**
     * 解密一个单文件 .aegix。
     *
     * <p>调用方应提供接收方私钥和所有可能的发送方公钥（本机 + 联系人）；签名只用于标识
     * 发送者，GCM 认证失败会抛异常。签名未匹配任何公钥时返回
     * {@code signatureValid=false}，明文仍然写出，由 UI 显示“发送者未识别”。</p>
     */
    public static FileDecryptResult decrypt(
            InputStream input,
            PrivateKey recipientKey,
            List<PublicKey> senderCandidates,
            OutputStream output,
            FileProgressListener listener,
            AtomicBoolean cancelled
    ) throws Exception {
        if (input == null || recipientKey == null || output == null) {
            throw new IllegalArgumentException("输入流、私钥和输出流不能为空");
        }

        BufferedInputStream buffered = input instanceof BufferedInputStream
                ? (BufferedInputStream) input
                : new BufferedInputStream(input, BUFFER_SIZE);
        PushbackInputStream source = new PushbackInputStream(buffered, 4);
        byte[] magicBytes = new byte[4];
        readFully(source, magicBytes, 0, 4);
        int magic = ByteBuffer.wrap(magicBytes).getInt();
        if (magic == FileFormatHeader.MAGIC) {
            throw new UnsupportedVolumeFileException(
                    "检测到 VOLU 外部卷，当前入口只支持单文件 .aegix");
        }
        source.unread(magicBytes);

        SignedAndEncryptedData.StreamHeader header;
        try {
            header = SignedAndEncryptedData.readHeaderFromStream(source);
        } catch (FileFormatException error) {
            throw error;
        } catch (IOException error) {
            throw new FileFormatException(
                    "文件头不完整，或这不是本工具生成的单文件 .aegix", error);
        }

        int encryptedKeyLength = readInt(source);
        if (encryptedKeyLength <= 0 || encryptedKeyLength > MAX_ENCRYPTED_KEY_BYTES) {
            throw new FileFormatException("文件密钥长度无效");
        }
        byte[] encryptedKey = new byte[encryptedKeyLength];
        readFully(source, encryptedKey, 0, encryptedKeyLength);

        int ivLength = readInt(source);
        if (ivLength <= 0 || ivLength > MAX_IV_BYTES) {
            throw new FileFormatException("AES IV 长度无效");
        }
        byte[] iv = new byte[ivLength];
        readFully(source, iv, 0, ivLength);

        int ciphertextLength = readInt(source);
        if (ciphertextLength < CryptoConfig.GCM_TAG_LENGTH) {
            throw new FileFormatException("密文长度无效");
        }

        checkCancelled(cancelled);

        byte[] aesKeyBytes;
        try {
            aesKeyBytes = OaepCompat.decryptCipher(recipientKey).doFinal(encryptedKey);
        } catch (Exception error) {
            throw new WrongRecipientException(
                    "无法用本机私钥解开文件密钥，可能不是加密给本机密钥的文件", error);
        }
        if (aesKeyBytes.length != CryptoConfig.AES_KEY_SIZE / 8) {
            Arrays.fill(aesKeyBytes, (byte) 0);
            throw new FileFormatException("解开的 AES 密钥长度无效");
        }

        SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");
        try {
            String originalFileName = decryptFileName(
                    header.encryptedFileName, header.fileNameIv, aesKey);

            GCMBlockCipher cipher = new GCMBlockCipher(new AESEngine());
            cipher.init(
                    false,
                    new AEADParameters(
                            new KeyParameter(aesKeyBytes), GCM_TAG_BITS, iv));

            List<PublicKey> candidates = new ArrayList<PublicKey>();
            List<Signature> verifiers = new ArrayList<Signature>();
            if (senderCandidates != null) {
                for (PublicKey candidate : senderCandidates) {
                    if (candidate == null) continue;
                    try {
                        Signature verifier = Signature.getInstance(CryptoConfig.SIGNATURE_ALGORITHM);
                        verifier.initVerify(candidate);
                        candidates.add(candidate);
                        verifiers.add(verifier);
                    } catch (Exception ignored) {
                        // 单个候选公钥无效不影响解密，只是无法匹配它。
                    }
                }
            }

            byte[] buffer = new byte[BUFFER_SIZE];
            byte[] cipherBuffer = new byte[BUFFER_SIZE + CryptoConfig.GCM_TAG_LENGTH];
            long remaining = ciphertextLength;
            long processed = 0;
            long plaintextBytes = 0;
            long lastReported = -1;
            while (remaining > 0) {
                checkCancelled(cancelled);
                int toRead = (int) Math.min(buffer.length, remaining);
                readFully(source, buffer, 0, toRead);
                int produced = cipher.processBytes(buffer, 0, toRead, cipherBuffer, 0);
                if (produced > 0) {
                    output.write(cipherBuffer, 0, produced);
                    plaintextBytes += produced;
                    updateVerifiers(verifiers, cipherBuffer, produced);
                }
                remaining -= toRead;
                processed += toRead;
                lastReported = report(
                        listener, FileProgressListener.Phase.DECRYPTING,
                        processed, ciphertextLength, lastReported);
            }
            int tail;
            try {
                tail = cipher.doFinal(cipherBuffer, 0);
            } catch (InvalidCipherTextException error) {
                throw new FileFormatException("文件认证失败，可能已损坏或被篡改", error);
            }
            if (tail > 0) {
                output.write(cipherBuffer, 0, tail);
                plaintextBytes += tail;
                updateVerifiers(verifiers, cipherBuffer, tail);
            }
            output.flush();

            boolean signatureValid = false;
            PublicKey matchedSender = null;
            for (int i = 0; i < verifiers.size(); i++) {
                try {
                    if (verifiers.get(i).verify(header.signature)) {
                        signatureValid = true;
                        matchedSender = candidates.get(i);
                        break;
                    }
                } catch (SignatureException ignored) {
                    // 某个公钥对不上（例如长度不匹配）时继续尝试下一个。
                }
            }
            return new FileDecryptResult(
                    originalFileName, signatureValid, matchedSender, plaintextBytes);
        } finally {
            Arrays.fill(aesKeyBytes, (byte) 0);
        }
    }

    private static byte[] signPlaintext(
            InputStream input,
            long plaintextSize,
            PrivateKey signingKey,
            FileProgressListener listener,
            AtomicBoolean cancelled
    ) throws Exception {
        Signature signer = Signature.getInstance(CryptoConfig.SIGNATURE_ALGORITHM);
        signer.initSign(signingKey);
        byte[] buffer = new byte[BUFFER_SIZE];
        long processed = 0;
        long lastReported = -1;
        while (true) {
            checkCancelled(cancelled);
            int read = input.read(buffer);
            if (read == -1) break;
            if (read == 0) continue;
            processed += read;
            if (processed > plaintextSize) {
                throw new FileFormatException("文件大小在签名过程中发生变化");
            }
            signer.update(buffer, 0, read);
            lastReported = report(
                    listener, FileProgressListener.Phase.SIGNING,
                    processed, plaintextSize, lastReported);
        }
        if (processed != plaintextSize) {
            throw new FileFormatException("文件大小在签名过程中发生变化");
        }
        return signer.sign();
    }

    /** 文件名用文件自己的 AES 密钥加密；这是当前唯一写入的格式。 */
    private static String decryptFileName(
            byte[] encryptedFileName,
            byte[] fileNameIv,
            SecretKey fileAesKey
    ) {
        if (encryptedFileName == null || encryptedFileName.length <= 0 || fileNameIv == null) {
            return null;
        }
        try {
            Cipher nameCipher = Cipher.getInstance(AES_ALGORITHM);
            nameCipher.init(
                    Cipher.DECRYPT_MODE,
                    fileAesKey,
                    new GCMParameterSpec(GCM_TAG_BITS, fileNameIv));
            return new String(
                    nameCipher.doFinal(encryptedFileName),
                    StandardCharsets.UTF_8);
        } catch (Exception error) {
            return null;
        }
    }

    private static void updateVerifiers(
            List<Signature> verifiers, byte[] chunk, int length
    ) throws SignatureException {
        for (Signature verifier : verifiers) {
            verifier.update(chunk, 0, length);
        }
    }

    private static long report(
            FileProgressListener listener,
            FileProgressListener.Phase phase,
            long processed,
            long total,
            long lastReported
    ) {
        if (listener == null) return lastReported;
        if (processed == lastReported) return lastReported;
        if (processed == total || lastReported < 0 ||
                processed - lastReported >= PROGRESS_STEP_BYTES) {
            listener.onProgress(phase, processed, total);
            return processed;
        }
        return lastReported;
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws FileCancelledException {
        if (cancelled != null && cancelled.get()) {
            throw new FileCancelledException("文件操作已取消");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new FileCancelledException("文件操作已取消");
        }
    }

    private static void writeInt(OutputStream output, int value) throws IOException {
        output.write(KeyUtils.intToBytes(value));
    }

    private static int readInt(InputStream input) throws IOException {
        byte[] bytes = new byte[4];
        readFully(input, bytes, 0, 4);
        return ByteBuffer.wrap(bytes).getInt();
    }

    private static void readFully(InputStream input, byte[] buffer, int offset, int length)
            throws IOException {
        int total = 0;
        while (total < length) {
            int read = input.read(buffer, offset + total, length - total);
            if (read == -1) {
                throw new FileFormatException("文件被截断或长度字段不正确");
            }
            if (read == 0) continue;
            total += read;
        }
    }
}
