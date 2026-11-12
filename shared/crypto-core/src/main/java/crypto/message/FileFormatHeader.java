package crypto.message;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public class FileFormatHeader {
    
    /** 魔数：用于识别分卷加密文件 */
    public static final int MAGIC = 0x564F4C55; // "VOLU"
    
    /** 当前版本号 */
    public static final int CURRENT_VERSION = 1;
    
    /** 头部总大小（字节） */
    public static final int HEADER_SIZE = 12;
    
    private final int magic;
    private final int version;
    private final int algorithmId;
    
    public FileFormatHeader(int version, int algorithmId) {
        this.magic = MAGIC;
        this.version = version;
        this.algorithmId = algorithmId;
    }
    
    public FileFormatHeader(int version) {
        this(version, 0);
    }
    
    private FileFormatHeader(int magic, int version, int algorithmId) {
        this.magic = magic;
        this.version = version;
        this.algorithmId = algorithmId;
    }
    
    public int getMagic() { return magic; }
    public int getVersion() { return version; }
    public int getAlgorithmId() { return algorithmId; }
    
    /**
     * 检查是否是有效的魔数
     */
    public boolean isValidMagic() {
        return magic == MAGIC;
    }
    
    /**
     * 将头部序列化为字节数组
     */
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        buffer.putInt(magic);
        buffer.putInt(version);
        buffer.putInt(algorithmId);
        return buffer.array();
    }
    
    /**
     * 将头部写入输出流
     */
    public void writeTo(OutputStream out) throws IOException {
        out.write(toBytes());
    }
    
    /**
     * 从输入流读取头部
     */
    public static FileFormatHeader readFrom(InputStream in) throws IOException {
        byte[] headerBytes = new byte[HEADER_SIZE];
        int read = in.read(headerBytes);
        if (read < HEADER_SIZE) {
            throw new IOException("文件格式错误：头部数据不完整");
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(headerBytes);
        int magic = buffer.getInt();
        int version = buffer.getInt();
        int algorithmId = buffer.getInt();
        
        return new FileFormatHeader(magic, version, algorithmId);
    }
    
    /**
     * 检查文件是否有有效的魔数
     */
    public static boolean checkMagicNumber(File file) throws IOException {
        if (!file.exists() || file.length() < HEADER_SIZE) {
            return false;
        }
        
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] magicBytes = new byte[4];
            int read = fis.read(magicBytes);
            if (read < 4) {
                return false;
            }
            int magic = ByteBuffer.wrap(magicBytes).getInt();
            return magic == MAGIC;
        }
    }
}