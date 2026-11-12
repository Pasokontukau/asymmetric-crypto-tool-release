package stegano.model;

import crypto.config.CryptoConfig;
import stegano.model.SteganoConfig;

public final class SteganoConfig {

    // --- 嵌入率 ---
    /** 默认嵌入率，保持 LSB 分布自然 */
    public static final double DEFAULT_EMBED_RATE = 0.20;
    /** 最大嵌入率，超过此值可能被统计检测 */
    public static final double MAX_EMBED_RATE = 0.30;

    // --- 像素配置 ---
    /** 嵌入通道数（R/G/B） */
    public static final int CHANNELS = 3;

    // --- PBKDF2 参数 ---
    /** 迭代次数，与 CryptoConfig 保持一致 */
    public static final int PBKDF2_ITERATIONS = 65536;
    /** 种子长度（字节），256-bit */
    public static final int SEED_LENGTH = 32;
    /** 盐值长度（字节），128-bit */
    public static final int SALT_LENGTH = 16;

    // --- 文件头 ---
    /** 载荷长度字段占用字节数 */
    public static final int HEADER_LENGTH_BYTES = 4;
    /** Phase 2+ header 版本字节（1 = 标识 Phase 2） */
    public static final int HEADER_VERSION_BYTE = 0x02;
    /** Phase 2: header 中 payload 校验和占用字节数（Fletcher-16） */
    public static final int HEADER_CHECKSUM_BYTES = 2;
    /** Phase 2: Fletcher-16 校验和魔数, 用于校验密码正确性 */
    public static final int CHECKSUM_MAGIC = 0xA5C3;

    // --- 支持的文件格式 ---
    public static final String[] SUPPORTED_IMAGE_EXTENSIONS = {"png", "bmp"};

    // --- JPEG DCT-QIM (Phase 1+) ---
    /** JPEG 载体支持的扩展名（小写，不含点） */
    public static final String[] JPEG_EXTENSIONS = {"jpg", "jpeg"};
    /** Phase 1 兼容：单系数索引 */
    public static final int QIM_COEFF_INDEX = 9;
    /** Phase 2：3 个中频系数的 ZigZag 下标，用于多数投票 */
    public static final int[] QIM_ZIGZAG_INDICES = {8, 16, 24};
    /** Phase 2：3 个系数中 bit 为 1 的阈值（> 半数即为 1） */
    public static final int QIM_MAJORITY_THRESHOLD = (QIM_ZIGZAG_INDICES.length / 2) + 1;
    /** Phase 1 QIM 步长（单位：量化系数的绝对值） */
    public static final int QIM_STEP = 16;
    /** 写回 JPEG 的压缩质量（越大文件越大，保真越高） */
    public static final float JPEG_QUALITY = 0.80f;
    /** 8x8 块大小 */
    public static final int BLOCK_SIZE = 8;
    /** JPEG 像素电平位移量（-128） */
    public static final int LEVEL_SHIFT = 128;

    // --- Phase 2 非对称隐写（对齐 STEGANO-MERGED-PLAN-v3.md §1-3） ---
    /** 5 个中频系数的 ZigZag 下标（v3 §2.3） */
    public static final int[] DCT_COEFFICIENTS = {10, 11, 12, 13, 14};
    /** 平坦区 Δ（v3 §2.4） */
    public static final int QIM_STEP_FLAT = 26;
    /** 纹理区 Δ（v3 §2.4） */
    public static final int QIM_STEP_TEXTURE = 42;
    /** 平坦/纹理判定阈值（8x8 宏块像素方差） */
    public static final int VARIANCE_THRESHOLD = 50;
    /** RS 数据字节数（v3 §2.5） */
    public static final int RS_DATA_BYTES = 171;
    /** RS 码字字节数（v3 §2.5） */
    public static final int RS_CODE_BYTES = 255;
    /** RSA-2048 OAEP 密文长度（固定 256B，v3 §1.2） */
    public static final int RSA_C_LENGTH = 256;
    /** RSA 随机数 r 长度（32B，v3 §3.3） */
    public static final int RSA_R_LENGTH = 32;
    /** 联系人 salt 最小长度（16B，v3 §3.7） */
    public static final int CONTACT_SALT_MIN_LENGTH = 16;

    // --- 缓冲区 ---
    public static final int BUFFER_SIZE = 8192;

    private SteganoConfig() {
    }
}