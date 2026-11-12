package interop;

import core.model.SignedAndEncryptedData;
import crypto.cipher.Decryptor;
import crypto.cipher.Encryptor;
import crypto.file.FileCryptoEngine;
import crypto.key.KeyPairManager;
import crypto.key.KeyUtils;
import crypto.message.FileFormatHeader;
import crypto.message.SecureMessage;
import crypto.signature.SignatureManager;
import crypto.volume.VolumePartPlan;
import crypto.volume.VolumePartWriter;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 用桌面端 crypto 源码实现的小型 CLI，供互操作测试使用。
 *
 * <p>编译时直接使用 {@code src/main/java} 中的 Encryptor/Decryptor/SecureMessage/
 * SignedAndEncryptedData 等源文件（逐字复制），只把日志/监控替换为桩实现。</p>
 */
public final class InteropCli {

    public static final String PLAINTEXT = "桌面端 → Android 互操作测试：RSA + AES-GCM + SHA256withRSA ✅ 2026-10-20";
    public static final String PRIVATE_KEY_PASSWORD = "interop-test-password";
    // 桌面端 KeyUtils.checkRSAKeyStrengthDetail 将 2048 位视为“不通过”（尽管其提示是建议 4096），
    // 因此夹具使用 4096 位，保持与桌面端 KeyPairManager 的实际行为一致。
    private static final int KEY_SIZE = 4096;

    private InteropCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        String command = args[0];
        switch (command) {
            case "generate":
                requireArgs(args, 2);
                generate(Paths.get(args[1]));
                break;
            case "decrypt-with-fixture":
                requireArgs(args, 3);
                decryptWithFixture(Paths.get(args[1]), Paths.get(args[2]));
                break;
            case "decrypt-key-with-fixture":
                requireArgs(args, 2);
                decryptPrivateKeyWithFixture(Paths.get(args[1]));
                break;
            case "decrypt-key":
                requireArgs(args, 3);
                decryptPrivateKey(Paths.get(args[1]), args[2]);
                break;
            case "encrypt-file":
                requireArgs(args, 4);
                encryptFile(Paths.get(args[1]), Paths.get(args[2]), Paths.get(args[3]));
                break;
            case "decrypt-file":
                requireArgs(args, 4);
                decryptFile(Paths.get(args[1]), Paths.get(args[2]), Paths.get(args[3]));
                break;
            case "generate-external":
                requireArgs(args, 2);
                generateExternal(Paths.get(args[1]));
                break;
            case "generate-single":
                requireArgs(args, 2);
                generateSingle(Paths.get(args[1]));
                break;
            default:
                usage();
                System.exit(2);
        }
    }

    private static void usage() {
        System.err.println("usage:");
        System.err.println("  generate <fixture.properties>");
        System.err.println("  decrypt-with-fixture <fixture.properties> <ciphertext.b64>");
        System.err.println("  decrypt-key-with-fixture <fixture.properties>");
        System.err.println("  decrypt-key <encryptedFile.b64> <password>");
        System.err.println("  encrypt-file <fixture.properties> <plain.bin> <out.aegix>");
        System.err.println("  decrypt-file <fixture.properties> <in.aegix> <plain.bin>");
        System.err.println("  generate-external <outputDir>");
        System.err.println("  generate-single <outputDir>");
    }

    private static void requireArgs(String[] args, int expected) {
        if (args.length < expected) {
            usage();
            System.exit(2);
        }
    }

    private static void generate(Path output) throws Exception {
        KeyPair sender = KeyPairManager.generateKeyPair(KEY_SIZE);
        KeyPair receiver = KeyPairManager.generateKeyPair(KEY_SIZE);

        SignedAndEncryptedData data = SecureMessage.signAndEncryptText(
                PLAINTEXT,
                sender.getPrivate(),
                receiver.getPublic());
        byte[] encryptedPrivateKeyFile = KeyPairManager.encryptPrivateKey(
                receiver.getPrivate().getEncoded(),
                PRIVATE_KEY_PASSWORD.toCharArray());

        Properties properties = new Properties();
        properties.setProperty("format.version", "1");
        properties.setProperty("generated.by", "desktop src/main/java crypto core via desktop-bridge");
        properties.setProperty("plaintext.b64", b64(PLAINTEXT.getBytes(StandardCharsets.UTF_8)));
        properties.setProperty("sender.public.x509.b64", b64(sender.getPublic().getEncoded()));
        properties.setProperty("sender.private.pkcs8.b64", b64(sender.getPrivate().getEncoded()));
        properties.setProperty("receiver.public.x509.b64", b64(receiver.getPublic().getEncoded()));
        properties.setProperty("receiver.private.pkcs8.b64", b64(receiver.getPrivate().getEncoded()));
        properties.setProperty("desktop.signedEncrypted.b64", b64(data.toBytes()));
        properties.setProperty("receiver.privateKey.encryptedFile.b64", b64(encryptedPrivateKeyFile));
        properties.setProperty("privateKey.password", PRIVATE_KEY_PASSWORD);

        Files.createDirectories(output.getParent());
        try (FileOutputStream out = new FileOutputStream(output.toFile())) {
            properties.store(out, "Desktop -> Android interop fixture (test keys only)");
        }
        System.out.println("FIXTURE_WRITTEN=" + output.toAbsolutePath());
        System.out.println("DESKTOP_CIPHERTEXT_BYTES=" + data.toBytes().length);
        System.out.println("DESKTOP_CIPHERTEXT_SHA256=" + sha256(data.toBytes()));
    }

    private static void decryptWithFixture(Path fixture, Path ciphertextFile) throws Exception {
        Properties properties = load(fixture);
        byte[] ciphertext = Base64.getDecoder().decode(
                new String(Files.readAllBytes(ciphertextFile), StandardCharsets.US_ASCII).trim());
        PublicKey senderPublic = KeyUtils.getPublicKeyFromBytes(
                Base64.getDecoder().decode(properties.getProperty("sender.public.x509.b64")));
        PrivateKey receiverPrivate = KeyUtils.getPrivateKeyFromBytes(
                Base64.getDecoder().decode(properties.getProperty("receiver.private.pkcs8.b64")));

        SignedAndEncryptedData data = SignedAndEncryptedData.fromBytes(ciphertext);
        SecureMessage.DecryptAndVerifyResult result = SecureMessage.decryptAndVerify(
                data,
                senderPublic,
                receiverPrivate);

        System.out.println("SIGNATURE_VALID=" + result.isSignatureValid());
        System.out.println("PLAINTEXT_B64=" + b64(result.getPlaintext()));
        if (!result.isSignatureValid()) {
            System.err.println("签名验证失败");
            System.exit(3);
        }
    }

    private static void decryptPrivateKeyWithFixture(Path fixture) throws Exception {
        Properties properties = load(fixture);
        byte[] encryptedFile = Base64.getDecoder().decode(
                properties.getProperty("receiver.privateKey.encryptedFile.b64"));
        char[] password = properties.getProperty("privateKey.password").toCharArray();
        byte[] decrypted = KeyPairManager.decryptPrivateKey(encryptedFile, password);
        byte[] expected = Base64.getDecoder().decode(
                properties.getProperty("receiver.private.pkcs8.b64"));
        boolean matches = java.util.Arrays.equals(decrypted, expected);
        boolean passwordVerified = KeyPairManager.verifyPassword(encryptedFile, password);
        System.out.println("PRIVATE_KEY_MATCH=" + matches);
        System.out.println("PASSWORD_VERIFIED=" + passwordVerified);
        java.util.Arrays.fill(decrypted, (byte) 0);
        java.util.Arrays.fill(password, '\0');
        if (!matches || !passwordVerified) {
            System.exit(3);
        }
    }

    private static void decryptPrivateKey(Path encryptedFileB64, String passwordText) throws Exception {
        byte[] encryptedFile = Base64.getDecoder().decode(
                new String(Files.readAllBytes(encryptedFileB64), StandardCharsets.US_ASCII).trim());
        char[] password = passwordText.toCharArray();
        byte[] decrypted = KeyPairManager.decryptPrivateKey(encryptedFile, password);
        boolean verified = KeyPairManager.verifyPassword(encryptedFile, password);
        System.out.println("PASSWORD_VERIFIED=" + verified);
        System.out.println("PRIVATE_KEY_B64=" + b64(decrypted));
        java.util.Arrays.fill(decrypted, (byte) 0);
        java.util.Arrays.fill(password, '\0');
        if (!verified) {
            System.exit(3);
        }
    }

    /** 用桌面端代码加密一个普通二进制文件（单文件 SignedAndEncryptedData 格式）。 */
    private static void encryptFile(Path fixturePath, Path input, Path output) throws Exception {
        Properties properties = load(fixturePath);
        PrivateKey senderPrivate = KeyUtils.getPrivateKeyFromBytes(
                Base64.getDecoder().decode(properties.getProperty("sender.private.pkcs8.b64")));
        PublicKey receiverPublic = KeyUtils.getPublicKeyFromBytes(
                Base64.getDecoder().decode(properties.getProperty("receiver.public.x509.b64")));

        byte[] data = Files.readAllBytes(input);
        byte[] signature = SignatureManager.signBytes(data, senderPrivate);
        Encryptor.EncryptResult encrypted = Encryptor.encryptDataWithKey(data, receiverPublic);
        SignedAndEncryptedData signed = SignedAndEncryptedData.createWithEncryptedFileName(
                signature,
                encrypted.encryptedData,
                input.getFileName().toString(),
                encrypted.aesKey);
        byte[] encoded = signed.toBytes();
        Files.write(output, encoded);

        System.out.println("ENCRYPTED_BYTES=" + encoded.length);
        System.out.println("PLAINTEXT_SHA256=" + sha256(data));
    }

    /** 用桌面端代码解密单文件 .aegix，并验证签名与原始文件名。 */
    private static void decryptFile(Path fixturePath, Path input, Path output) throws Exception {
        Properties properties = load(fixturePath);
        PrivateKey receiverPrivate = KeyUtils.getPrivateKeyFromBytes(
                Base64.getDecoder().decode(properties.getProperty("receiver.private.pkcs8.b64")));
        PublicKey senderPublic = KeyUtils.getPublicKeyFromBytes(
                Base64.getDecoder().decode(properties.getProperty("sender.public.x509.b64")));

        byte[] data = Files.readAllBytes(input);
        SignedAndEncryptedData signed = SignedAndEncryptedData.fromBytes(data);
        byte[] plaintext = Decryptor.decryptData(signed.getEncryptedData(), receiverPrivate);
        boolean signatureValid = SignatureManager.verifyBytes(
                plaintext, signed.getSignature(), senderPublic);
        String originalFileName = signed.hasOriginalFileName()
                ? signed.getOriginalFileName(receiverPrivate)
                : null;

        Files.write(output, plaintext);
        System.out.println("SIGNATURE_VALID=" + signatureValid);
        System.out.println("PLAINTEXT_BYTES=" + plaintext.length);
        System.out.println("PLAINTEXT_SHA256=" + sha256(plaintext));
        System.out.println("ORIGINAL_NAME_B64=" + b64(
                originalFileName == null
                        ? new byte[0]
                        : originalFileName.getBytes(StandardCharsets.UTF_8)));
        if (!signatureValid) {
            System.exit(3);
        }
    }

    /**
     * 生成当前外部分卷格式的固定夹具：多卷 ZIP + 单卷文件夹包。
     *
     * <p>每次重新生成都会换一套公开测试密钥；输出只用于测试，不包含任何真实数据。</p>
     */
    private static void generateExternal(Path outputDir) throws Exception {
        KeyPair sender = KeyPairManager.generateKeyPair(KEY_SIZE);
        KeyPair recipient = KeyPairManager.generateKeyPair(KEY_SIZE);
        PrivateKey senderPrivate = sender.getPrivate();
        PublicKey senderPublic = sender.getPublic();
        PrivateKey recipientPrivate = recipient.getPrivate();
        PublicKey recipientPublic = recipient.getPublic();

        Files.createDirectories(outputDir);

        String setId = "2f7c1a9e-5b3d-4c6f-8a0e-2b4d6c8f1a33";
        String archiveName = "Photos.zip";
        byte[] archive = zipBytes(new String[][]{
                {"Photos/one.txt", "fixture one: " + repeat('a', 700)},
                {"Photos/two.txt", "fixture two: " + repeat('b', 700)},
                {"Photos/three.txt", "fixture three: " + repeat('c', 300)},
        });
        Path archivePath = outputDir.resolve("Photos.zip");
        Files.write(archivePath, archive);

        VolumePartPlan plan = VolumePartPlan.create(
                archive.length,
                5_000L,
                archiveName.getBytes(StandardCharsets.UTF_8).length + 16,
                4096
        );
        if (plan.getPartCount() != 3) {
            throw new IllegalStateException("外部夹具预期 3 卷，实际 " + plan.getPartCount());
        }

        List<String> partFiles = new ArrayList<String>();
        List<String> partPlainSizes = new ArrayList<String>();
        for (int index = 1; index <= plan.getPartCount(); index++) {
            String fileName = "secure_file_20261107_120000_2f7c1a_vol"
                    + String.format(Locale.ROOT, "%03d", index)
                    + ".aegix";
            Path output = outputDir.resolve(fileName);
            Files.deleteIfExists(output);
            VolumePartWriter.writePart(
                    archivePath.toFile(),
                    plan,
                    index,
                    setId,
                    archiveName,
                    sha256(archive),
                    false,
                    3,
                    output.toFile(),
                    senderPrivate,
                    recipientPublic,
                    null,
                    new AtomicBoolean(false)
            );
            partFiles.add(fileName);
            partPlainSizes.add(String.valueOf(plan.getPartPlaintextBytes(index)));
        }

        String folderName = "季度 报告.zip";
        byte[] folder = zipBytes(new String[][]{
                {"docs/readme.txt", "folder fixture readme"},
                {"data/values.bin", "folder fixture values " + repeat('d', 200)},
        });
        Path folderPath = outputDir.resolve("folder.zip");
        Files.write(folderPath, folder);

        long singleLimit = 50L * 1_000_000;
        VolumePartPlan singlePlan = VolumePartPlan.create(
                folder.length,
                singleLimit,
                folderName.getBytes(StandardCharsets.UTF_8).length + 16,
                4096
        );
        if (singlePlan.getPartCount() != 1) {
            throw new IllegalStateException("单卷文件夹夹具预期 1 卷，实际 "
                    + singlePlan.getPartCount());
        }
        String singleFile = "secure_file_20261107_120000.aegix";
        Path singleOutput = outputDir.resolve(singleFile);
        Files.deleteIfExists(singleOutput);
        VolumePartWriter.writePart(
                folderPath.toFile(),
                singlePlan,
                1,
                setId,
                folderName,
                sha256(folder),
                true,
                2,
                singleOutput.toFile(),
                senderPrivate,
                recipientPublic,
                null,
                new AtomicBoolean(false)
        );

        Properties properties = new Properties();
        properties.setProperty("fixture.version", "1");
        properties.setProperty(
                "fixture.generated.by",
                "shared/crypto-core VolumePartWriter via desktop-bridge"
        );
        properties.setProperty("fixture.keys.bits", "4096");
        properties.setProperty("sender.private.pkcs8.b64", b64(senderPrivate.getEncoded()));
        properties.setProperty("sender.public.x509.b64", b64(senderPublic.getEncoded()));
        properties.setProperty("recipient.private.pkcs8.b64", b64(recipientPrivate.getEncoded()));
        properties.setProperty("recipient.public.x509.b64", b64(recipientPublic.getEncoded()));

        properties.setProperty("multi.archive.file", "Photos.zip");
        properties.setProperty("multi.archive.name", archiveName);
        properties.setProperty("multi.archive.size", String.valueOf(archive.length));
        properties.setProperty("multi.archive.sha256", sha256(archive));
        properties.setProperty("multi.archive.sourceIsFolder", "false");
        properties.setProperty("multi.archive.sourceFileCount", "3");
        properties.setProperty("multi.set.id", setId);
        properties.setProperty("multi.set.code", "2f7c1a");
        properties.setProperty("multi.part.count", String.valueOf(plan.getPartCount()));
        properties.setProperty("multi.part.limit", "5000");
        properties.setProperty("multi.part.files", String.join(",", partFiles));
        properties.setProperty("multi.part.plain.sizes", String.join(",", partPlainSizes));

        properties.setProperty("single.archive.file", "folder.zip");
        properties.setProperty("single.archive.name", folderName);
        properties.setProperty("single.archive.size", String.valueOf(folder.length));
        properties.setProperty("single.archive.sha256", sha256(folder));
        properties.setProperty("single.archive.sourceIsFolder", "true");
        properties.setProperty("single.archive.sourceFileCount", "2");
        properties.setProperty("single.part.count", "1");
        properties.setProperty("single.part.limit", String.valueOf(singleLimit));
        properties.setProperty("single.part.file", singleFile);
        properties.setProperty("single.part.plain.sizes", String.valueOf(folder.length));

        Path propertiesPath = outputDir.resolve("external-part-fixture.properties");
        try (FileOutputStream out = new FileOutputStream(propertiesPath.toFile())) {
            properties.store(out, "Current external-part format fixtures (test keys only)");
        }

        System.out.println("EXTERNAL_FIXTURE_DIR=" + outputDir.toAbsolutePath());
        System.out.println("EXTERNAL_MULTI_PARTS=" + plan.getPartCount());
        System.out.println("EXTERNAL_MULTI_SHA256=" + sha256(archive));
        System.out.println("EXTERNAL_SINGLE_SHA256=" + sha256(folder));
    }

    /**
     * 生成当前单文件 {@code .aegix} 格式的固定夹具。
     *
     * <p>明文以 Base64 存在属性里，不额外落一个明文文件；密钥只用于测试。单文件容器没有
     * 版本字段，夹具钉住的是头部布局、签名长度与解密后的字节。</p>
     */
    private static void generateSingle(Path outputDir) throws Exception {
        KeyPair sender = KeyPairManager.generateKeyPair(KEY_SIZE);
        KeyPair recipient = KeyPairManager.generateKeyPair(KEY_SIZE);

        Files.createDirectories(outputDir);

        String originalName = "季度 报告.txt";
        byte[] plaintext = ("aegix single-file fixture v1\n"
                + "固定夹具，只用于测试，不得用于真实数据。\n"
                + repeat('x', 600)).getBytes(StandardCharsets.UTF_8);

        Path plainPath = Files.createTempFile("aegix-single-fixture-", ".bin");
        Files.write(plainPath, plaintext);
        String fileName = "secure_file_20261111_120000.aegix";
        Path cipherPath = outputDir.resolve(fileName);
        Files.deleteIfExists(cipherPath);
        try (InputStream signatureInput = Files.newInputStream(plainPath);
             InputStream plaintextInput = Files.newInputStream(plainPath);
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(cipherPath))) {
            FileCryptoEngine.encrypt(
                    signatureInput,
                    plaintextInput,
                    plaintext.length,
                    originalName,
                    sender.getPrivate(),
                    recipient.getPublic(),
                    output,
                    null,
                    new AtomicBoolean(false)
            );
        } finally {
            Files.deleteIfExists(plainPath);
        }
        byte[] cipher = Files.readAllBytes(cipherPath);

        Properties properties = new Properties();
        properties.setProperty("fixture.version", "1");
        properties.setProperty(
                "fixture.generated.by",
                "shared/crypto-core FileCryptoEngine via desktop-bridge"
        );
        properties.setProperty("fixture.keys.bits", String.valueOf(KEY_SIZE));
        properties.setProperty("sender.private.pkcs8.b64", b64(sender.getPrivate().getEncoded()));
        properties.setProperty("sender.public.x509.b64", b64(sender.getPublic().getEncoded()));
        properties.setProperty("recipient.private.pkcs8.b64", b64(recipient.getPrivate().getEncoded()));
        properties.setProperty("recipient.public.x509.b64", b64(recipient.getPublic().getEncoded()));

        properties.setProperty("plain.name", originalName);
        properties.setProperty("plain.size", String.valueOf(plaintext.length));
        properties.setProperty("plain.sha256", sha256(plaintext));
        properties.setProperty("plain.base64", b64(plaintext));

        properties.setProperty("cipher.file", fileName);
        properties.setProperty("cipher.size", String.valueOf(cipher.length));
        properties.setProperty("cipher.sha256", sha256(cipher));
        properties.setProperty("header.signature.bytes", String.valueOf(KEY_SIZE / 8));
        properties.setProperty("container.version", "none");

        Path propertiesPath = outputDir.resolve("single-file-fixture.properties");
        try (FileOutputStream out = new FileOutputStream(propertiesPath.toFile())) {
            properties.store(out, "Current single-file .aegix fixture (test keys only)");
        }

        System.out.println("SINGLE_FIXTURE_DIR=" + outputDir.toAbsolutePath());
        System.out.println("SINGLE_FIXTURE_FILE=" + fileName);
        System.out.println("SINGLE_FIXTURE_SHA256=" + sha256(cipher));
        System.out.println("SINGLE_PLAINTEXT_SHA256=" + sha256(plaintext));
    }

    private static byte[] zipBytes(String[][] entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            // 夹具需要可控的字节数；压缩会让重复文本缩到 1 卷，失去多卷样本的意义。
            zip.setLevel(Deflater.NO_COMPRESSION);
            for (String[] entry : entries) {
                ZipEntry zipEntry = new ZipEntry(entry[0]);
                zipEntry.setTime(0L);
                zip.putNextEntry(zipEntry);
                zip.write(entry[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static Properties load(Path fixture) throws Exception {
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(fixture.toFile())) {
            properties.load(in);
        }
        return properties;
    }

    private static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private static String sha256(byte[] data) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest(data)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @SuppressWarnings("unused")
    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
