package core.model;

import core.util.IOUtils;
import crypto.cipher.Encryptor;
import crypto.cipher.OaepCompat;
import crypto.config.CryptoConfig;
import crypto.key.KeyUtils;
import crypto.provider.SecurityProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class SignedAndEncryptedData implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final byte[] signature;
    private final Encryptor.EncryptedData encryptedData;
    private final byte[] encryptedFileName; // 加密后的文件名（使用AES-GCM加密）
    private final byte[] fileNameIv; // 文件名加密使用的IV
    
    // 构造函数：文字模式（无文件名）
    public SignedAndEncryptedData(byte[] signature, Encryptor.EncryptedData encryptedData) {
        this(signature, encryptedData, null, null);
    }
    
    // 构造函数：完整模式（可选文件名）
    public SignedAndEncryptedData(byte[] signature, Encryptor.EncryptedData encryptedData, 
                                  byte[] encryptedFileName, byte[] fileNameIv) {
        this.signature = signature;
        this.encryptedData = encryptedData;
        this.encryptedFileName = encryptedFileName;
        this.fileNameIv = fileNameIv;
    }
    
    // 新增类：用于存储从流读取的头部信息
    public static class StreamHeader {
        public final byte[] signature;
        public final byte[] fileNameIv;
        public final byte[] encryptedFileName;
        
        public StreamHeader(byte[] signature, byte[] fileNameIv, byte[] encryptedFileName) {
            this.signature = signature;
            this.fileNameIv = fileNameIv;
            this.encryptedFileName = encryptedFileName;
        }
    }
    
    // 新增方法：写入头部信息到流
    public static void writeHeaderToStream(
        OutputStream out,
        byte[] signature,
        byte[] fileNameIv,
        byte[] encryptedFileName
    ) throws IOException {
        int fileNameIvLength = fileNameIv != null ? fileNameIv.length : 0;
        int encryptedFileNameLength = encryptedFileName != null ? encryptedFileName.length : 0;
        
        // 写入签名
        out.write(KeyUtils.intToBytes(signature.length));
        out.write(signature);
        
        // 写入文件名IV
        out.write(KeyUtils.intToBytes(fileNameIvLength));
        if (fileNameIv != null) {
            out.write(fileNameIv);
        }
        
        // 写入加密文件名
        out.write(KeyUtils.intToBytes(encryptedFileNameLength));
        if (encryptedFileName != null) {
            out.write(encryptedFileName);
        }
        
        out.flush();
    }
    
    // 新增方法：从流读取头部信息
    public static StreamHeader readHeaderFromStream(InputStream in) throws IOException {
        // 读取签名
        byte[] sigLengthBytes = new byte[4];
        IOUtils.readFully(in, sigLengthBytes);
        int sigLength = KeyUtils.bytesToInt(sigLengthBytes, 0);
        if (sigLength <= 0 || sigLength > 10240) { // 安全检查
            throw new IOException("Invalid signature length: " + sigLength);
        }
        byte[] signature = new byte[sigLength];
        IOUtils.readFully(in, signature);
        
        // 读取文件名IV
        byte[] fileNameIvLengthBytes = new byte[4];
        IOUtils.readFully(in, fileNameIvLengthBytes);
        int fileNameIvLength = KeyUtils.bytesToInt(fileNameIvLengthBytes, 0);
        byte[] fileNameIv = null;
        if (fileNameIvLength > 0) {
            if (fileNameIvLength > 256) { // 安全检查
                throw new IOException("Invalid fileName IV length: " + fileNameIvLength);
            }
            fileNameIv = new byte[fileNameIvLength];
            IOUtils.readFully(in, fileNameIv);
        }
        
        // 读取加密文件名
        byte[] encryptedFileNameLengthBytes = new byte[4];
        IOUtils.readFully(in, encryptedFileNameLengthBytes);
        int encryptedFileNameLength = KeyUtils.bytesToInt(encryptedFileNameLengthBytes, 0);
        byte[] encryptedFileName = null;
        if (encryptedFileNameLength > 0) {
            if (encryptedFileNameLength > 4096) { // 安全检查
                throw new IOException("Invalid encrypted fileName length: " + encryptedFileNameLength);
            }
            encryptedFileName = new byte[encryptedFileNameLength];
            IOUtils.readFully(in, encryptedFileName);
        }
        
        return new StreamHeader(signature, fileNameIv, encryptedFileName);
    }
    
    // 辅助方法：完全读取指定长度的字节（委托给 IOUtils）
    
    public byte[] getSignature() {
        return signature;
    }
    
    public Encryptor.EncryptedData getEncryptedData() {
        return encryptedData;
    }
    
    public boolean hasOriginalFileName() {
        return encryptedFileName != null && encryptedFileName.length > 0;
    }
    
    // 获取原始文件名（需要解密密钥）
    public String getOriginalFileName(java.security.PrivateKey privateKey) throws Exception {
        if (encryptedFileName == null || encryptedFileName.length == 0) {
            return null;
        }
        
        // 当前唯一格式：文件名用文件自己的 AES 密钥加密。
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(CryptoConfig.AES_ALGORITHM);
        javax.crypto.spec.GCMParameterSpec gcmSpec = 
            new javax.crypto.spec.GCMParameterSpec(CryptoConfig.GCM_TAG_LENGTH * 8, fileNameIv);
        // 从原加密数据中提取 AES 密钥。
        javax.crypto.SecretKey aesKey = extractAesKeyFromEncryptedData(privateKey);
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, aesKey, gcmSpec);
        byte[] decryptedFileNameBytes = cipher.doFinal(encryptedFileName);
        return new String(decryptedFileNameBytes, StandardCharsets.UTF_8);
    }
    
    // 辅助方法：从加密数据中提取AES密钥
    private javax.crypto.SecretKey extractAesKeyFromEncryptedData(java.security.PrivateKey privateKey) throws Exception {
        // 首先从encryptedData中提取加密的AES密钥
        byte[] encryptedAesKey = encryptedData.getEncryptedAesKey();
        // RSA 解密（显式 OAEP 参数，保持与桌面端一致）
        javax.crypto.Cipher rsaCipher = OaepCompat.decryptCipher(privateKey);
        byte[] aesKeyBytes = rsaCipher.doFinal(encryptedAesKey);
        return new javax.crypto.spec.SecretKeySpec(aesKeyBytes, "AES");
    }
    
    public byte[] toBytes() {
        byte[] encBytes = encryptedData.toBytes();
        
        // 格式: 
        // [签名长度(4字节)][签名数据]
        // [文件名IV长度(4字节)][文件名IV]
        // [加密文件名长度(4字节)][加密文件名]
        // [加密数据]
        int fileNameIvLength = fileNameIv != null ? fileNameIv.length : 0;
        int encryptedFileNameLength = encryptedFileName != null ? encryptedFileName.length : 0;
        
        int totalSize = 4 + signature.length + 
                        4 + fileNameIvLength + 
                        4 + encryptedFileNameLength + 
                        encBytes.length;
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        
        buffer.putInt(signature.length);
        buffer.put(signature);
        buffer.putInt(fileNameIvLength);
        if (fileNameIv != null) {
            buffer.put(fileNameIv);
        }
        buffer.putInt(encryptedFileNameLength);
        if (encryptedFileName != null) {
            buffer.put(encryptedFileName);
        }
        buffer.put(encBytes);
        
        return buffer.array();
    }
    
    public static SignedAndEncryptedData fromBytes(byte[] data) {
        // 1. 基本验证
        if (data == null) {
            throw new IllegalArgumentException("数据不能为空");
        }
        if (data.length < 16) { // 至少需要4(签名长度)+4(IV长度)+4(文件名长度)+至少4(加密数据)
            throw new IllegalArgumentException("数据太短，格式无效: " + data.length + " 字节");
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int currentOffset = 0;
        
        // 2. 签名长度验证
        int sigLength = buffer.getInt();
        currentOffset += 4;
        if (sigLength <= 0) {
            throw new IllegalArgumentException("签名长度不能为负数或零: " + sigLength);
        }
        // 安全上限: RSA 签名一般最多 2048 bits = 256 bytes，给个安全余量 10KB
        if (sigLength > 10240) {
            throw new IllegalArgumentException("签名长度过大: " + sigLength + " 字节");
        }
        if (sigLength > data.length - 12) { // 至少需要4+4+4的头部
            throw new IllegalArgumentException("签名长度超出数据范围");
        }
        
        // 3. 读取签名
        byte[] signature = new byte[sigLength];
        buffer.get(signature);
        currentOffset += sigLength;
        
        // 4. 读取文件名IV
        int fileNameIvLength = buffer.getInt();
        currentOffset += 4;
        byte[] fileNameIv = null;
        if (fileNameIvLength > 0) {
            if (fileNameIvLength > 256) { // IV一般12或16字节，安全限制
                throw new IllegalArgumentException("文件名IV长度过大: " + fileNameIvLength + " 字节");
            }
            fileNameIv = new byte[fileNameIvLength];
            buffer.get(fileNameIv);
            currentOffset += fileNameIvLength;
        }
        
        // 5. 读取加密文件名
        int encryptedFileNameLength = buffer.getInt();
        currentOffset += 4;
        byte[] encryptedFileName = null;
        if (encryptedFileNameLength > 0) {
            if (encryptedFileNameLength > 4096) { // 安全限制
                throw new IllegalArgumentException("加密文件名长度过大: " + encryptedFileNameLength + " 字节");
            }
            encryptedFileName = new byte[encryptedFileNameLength];
            buffer.get(encryptedFileName);
            currentOffset += encryptedFileNameLength;
        }
        
        // 6. 读取加密数据
        int remaining = data.length - currentOffset;
        if (remaining <= 0) {
            throw new IllegalArgumentException("加密数据长度无效: " + remaining);
        }
        byte[] encBytes = new byte[remaining];
        buffer.get(encBytes);
        
        Encryptor.EncryptedData encryptedData = Encryptor.EncryptedData.fromBytes(encBytes);
        
        return new SignedAndEncryptedData(signature, encryptedData, encryptedFileName, fileNameIv);
    }
    
    // 静态辅助方法：创建带加密文件名的 SignedAndEncryptedData
    public static SignedAndEncryptedData createWithEncryptedFileName(
            byte[] signature,
            Encryptor.EncryptedData encryptedData,
            String originalFileName,
            javax.crypto.SecretKey aesKey
    ) throws Exception {
        if (originalFileName == null || originalFileName.isEmpty()) {
            return new SignedAndEncryptedData(signature, encryptedData);
        }
        
        // 加密文件名
        byte[] fileNameBytes = originalFileName.getBytes(StandardCharsets.UTF_8);
        byte[] fileNameIv = new byte[12]; // AES-GCM推荐12字节IV
        SecurityProvider.REGULAR_RANDOM.nextBytes(fileNameIv);
        
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(CryptoConfig.AES_ALGORITHM);
        javax.crypto.spec.GCMParameterSpec gcmSpec = 
            new javax.crypto.spec.GCMParameterSpec(128, fileNameIv);
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, aesKey, gcmSpec);
        byte[] encryptedFileName = cipher.doFinal(fileNameBytes);
        
        return new SignedAndEncryptedData(signature, encryptedData, encryptedFileName, fileNameIv);
    }
}
