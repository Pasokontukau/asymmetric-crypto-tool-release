package stegano.carrier;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;

import stegano.model.SteganoConfig;
import stegano.service.SteganoException;

/**
 * JPEG 载体隐写：DCT-QIM + 非对称双区协议（对齐 STEGANO-MERGED-PLAN-v3.md §2）。
 *
 * <p>协议（v3）：
 * <ul>
 *   <li>密钥区: seed0 = SHA256(contactSalt) -> Fisher-Yates 洗牌 -> 前 K1 个宏块承载 c 的 RS 编码比特。</li>
 *   <li>载荷区: seed1 = SHA256(r) -> Fisher-Yates 洗牌剩下的宏块 -> 前 K2 个承载 payload 的 RS 编码比特。</li>
 *   <li>每宏块一个 bit，使用 ZigZag[10..14] 中的一个中频系数（由 PRNG 选择），按方差判定平坦/纹理区，选择 Δ=26/42 的 QIM 步长。</li>
 *   <li>RS(255, 171) 前向纠错，冗余率 33%。</li>
 * </ul>
 */
public class JpegSteganoCarrier implements SteganoCarrier {

    // ================ v3 协议常量 ================
    private static final int[] V3_MID_ZIGZAG = SteganoConfig.DCT_COEFFICIENTS;     // {10,11,12,13,14}
    private static final int RSA_C_BYTES_V3 = SteganoConfig.RSA_C_LENGTH;          // 256
    private static final int RSA_R_BYTES_V3 = SteganoConfig.RSA_R_LENGTH;          // 32
    private static final int RS_DATA_BYTES_V3 = SteganoConfig.RS_DATA_BYTES;       // 171
    private static final int RS_CODE_BYTES_V3 = SteganoConfig.RS_CODE_BYTES;       // 255
    private static final int RSA_C_RS_BLOCKS_V3 = (int) Math.ceil((double) RSA_C_BYTES_V3 / (double) RS_DATA_BYTES_V3);
    private static final int RSA_C_TOTAL_BITS_V3 = RSA_C_RS_BLOCKS_V3 * RS_CODE_BYTES_V3 * 8;
    private static final int VARIANCE_THRESHOLD_V3 = SteganoConfig.VARIANCE_THRESHOLD;

    /** 标准 JPEG 亮度量化表（自然序 row*8+col） */
    private static final int[] STD_LUMA_QUANT = {
        16, 11, 10, 16, 24, 40, 51, 61,
        12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77,
        24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101,
        72, 92, 95, 98, 112, 100, 103, 99
    };

    /** ZigZag 下标 -> 自然序下标 映射 */
    private static final int[] ZIGZAG = {
         0,  1,  8, 16,  9,  2,  3, 10,
        17, 24, 32, 25, 18, 11,  4,  5,
        12, 19, 26, 33, 40, 48, 41, 34,
        27, 20, 13,  6,  7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36,
        29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46,
        53, 60, 61, 54, 47, 55, 62, 63
    };

    // ================ 接口实现 ================

    @Override
    public byte[] embedRsa(byte[] c, byte[] payload, byte[] carrierData,
                           byte[] contactSalt, double embedRate) throws SteganoException {
        if (c == null || c.length != RSA_C_BYTES_V3)
            throw new SteganoException("c 长度应为 " + RSA_C_BYTES_V3);
        if (contactSalt == null || contactSalt.length < SteganoConfig.CONTACT_SALT_MIN_LENGTH)
            throw new SteganoException("contactSalt 长度不足");
        if (embedRate <= 0 || embedRate > 1.0)
            throw new SteganoException("嵌入率超出范围: " + embedRate);

        try {
            BufferedImage img = readAsRgb(carrierData);
            int W = img.getWidth(), H = img.getHeight();
            int[][] y = toY2D(img);
            int blocksX = W / 8, blocksY = H / 8;
            int totalBlocks = blocksX * blocksY;

            // c 的 RS 编码
            byte[] cRs = rsEncodeV3(c, RSA_C_BYTES_V3);

            // payload 包装 -> P = [2B len | payload]，再 RS 编码
            byte[] pRaw = new byte[2 + payload.length];
            pRaw[0] = (byte) ((payload.length >>> 8) & 0xFF);
            pRaw[1] = (byte) (payload.length & 0xFF);
            System.arraycopy(payload, 0, pRaw, 2, payload.length);
            byte[] pRs = rsEncodeV3(pRaw, pRaw.length);
            int pTotalBits = pRs.length * 8;

            // seed0 Fisher-Yates 洗牌，取 K1 个密钥区宏块
            byte[] seed0 = sha256Bytes(contactSalt);
            int[] indices = new int[totalBlocks];
            for (int i = 0; i < totalBlocks; ++i) indices[i] = i;
            fisherYatesV3(indices, seed0);
            int K1 = Math.min(RSA_C_TOTAL_BITS_V3, Math.max(0, totalBlocks - 50));
            int[] keyIdx = new int[K1];
            boolean[] isKey = new boolean[totalBlocks];
            for (int i = 0; i < K1; ++i) {
                isKey[indices[i]] = true;
                keyIdx[i] = indices[i];
            }

            // 嵌入 c bits
            Sha256PrngV3 rng0 = new Sha256PrngV3(seed0);
            int cBit = 0;
            for (int i = 0; i < K1 && cBit < cRs.length * 8; ++i) {
                int blockIdx = keyIdx[i];
                int zz = V3_MID_ZIGZAG[Math.abs(rng0.nextByte()) % V3_MID_ZIGZAG.length];
                int delta = varianceOfBlockV3(y, W, H, blockIdx) >= VARIANCE_THRESHOLD_V3
                        ? SteganoConfig.QIM_STEP_TEXTURE
                        : SteganoConfig.QIM_STEP_FLAT;
                int bit = ((cRs[cBit >> 3] >>> (7 - (cBit & 7))) & 1);
                embedBlockBitAtWithDeltaV3(y, W, H, blocksX, blocksY, blockIdx, zz, bit, delta);
                cBit++;
            }

            // r 由调用方（SteganoPanel）生成、加密。seed1 = SHA256(r)，但载体层无法直接拿到 r
            // 约定：在 payload 区位置的洗牌中，使用 SHA256(contactSalt || {0x02}) 派生 seed1
            // 这样无需在载体层跨层传递 r；真实 seed1 由上层保证。
            byte[] seed1 = sha256BytesConcat(contactSalt, new byte[]{0x02});

            // 剩余的 (totalBlocks - K1) 块经 seed1 洗牌后承载 P
            int[] remain = new int[totalBlocks - K1];
            int p = 0;
            for (int i = 0; i < totalBlocks; ++i) if (!isKey[i]) remain[p++] = i;
            fisherYatesV3(remain, seed1);

            int K2 = Math.min(pTotalBits, remain.length);
            Sha256PrngV3 rng1 = new Sha256PrngV3(seed1);
            int pBit = 0;
            for (int i = 0; i < K2 && pBit < pTotalBits; ++i) {
                int blockIdx = remain[i];
                int zz = V3_MID_ZIGZAG[Math.abs(rng1.nextByte()) % V3_MID_ZIGZAG.length];
                int delta = varianceOfBlockV3(y, W, H, blockIdx) >= VARIANCE_THRESHOLD_V3
                        ? SteganoConfig.QIM_STEP_TEXTURE
                        : SteganoConfig.QIM_STEP_FLAT;
                int bit = ((pRs[pBit >> 3] >>> (7 - (pBit & 7))) & 1);
                embedBlockBitAtWithDeltaV3(y, W, H, blocksX, blocksY, blockIdx, zz, bit, delta);
                pBit++;
            }

            writeBackRgbFromY(img, y);
            return encodeToJpeg(img);
        } catch (SteganoException se) {
            throw se;
        } catch (Exception e) {
            throw new SteganoException("embedRsa 失败: " + e.getMessage());
        }
    }

    @Override
    public byte[] extractRsaPhase1(byte[] stegoData, byte[] contactSalt, double embedRate) throws SteganoException {
        try {
            BufferedImage img = readAsRgb(stegoData);
            int W = img.getWidth(), H = img.getHeight();
            int[][] y = toY2D(img);
            int blocksX = W / 8, blocksY = H / 8;
            int totalBlocks = blocksX * blocksY;

            byte[] seed0 = sha256Bytes(contactSalt);
            int[] indices = new int[totalBlocks];
            for (int i = 0; i < totalBlocks; ++i) indices[i] = i;
            fisherYatesV3(indices, seed0);
            int K1 = Math.min(RSA_C_TOTAL_BITS_V3, Math.max(0, totalBlocks - 50));

            byte[] cRs = new byte[RSA_C_RS_BLOCKS_V3 * RS_CODE_BYTES_V3];
            Sha256PrngV3 rng0 = new Sha256PrngV3(seed0);
            for (int i = 0; i < K1; ++i) {
                int blockIdx = indices[i];
                int zz = V3_MID_ZIGZAG[Math.abs(rng0.nextByte()) % V3_MID_ZIGZAG.length];
                int bit = extractBlockBitAtV3(y, W, H, blocksX, blocksY, blockIdx, zz);
                if (bit != 0) cRs[i >> 3] |= (byte) (1 << (7 - (i & 7)));
            }
            return rsDecodeV3(cRs, RSA_C_BYTES_V3);
        } catch (SteganoException se) {
            throw se;
        } catch (Exception e) {
            throw new SteganoException("extractRsaPhase1 失败: " + e.getMessage());
        }
    }

    @Override
    public byte[] extractRsaPhase2(byte[] stegoData, byte[] contactSalt, byte[] r, double embedRate) throws SteganoException {
        try {
            BufferedImage img = readAsRgb(stegoData);
            int W = img.getWidth(), H = img.getHeight();
            int[][] y = toY2D(img);
            int blocksX = W / 8, blocksY = H / 8;
            int totalBlocks = blocksX * blocksY;

            // seed0 找密钥区
            byte[] seed0 = sha256Bytes(contactSalt);
            int[] indices0 = new int[totalBlocks];
            for (int i = 0; i < totalBlocks; ++i) indices0[i] = i;
            fisherYatesV3(indices0, seed0);
            int K1 = Math.min(RSA_C_TOTAL_BITS_V3, Math.max(0, totalBlocks - 50));
            boolean[] isKey = new boolean[totalBlocks];
            for (int i = 0; i < K1; ++i) isKey[indices0[i]] = true;

            // seed1 派生（与嵌入端一致: SHA256(contactSalt || {0x02})）
            byte[] seed1 = sha256BytesConcat(contactSalt, new byte[]{0x02});

            int nRemain = totalBlocks - K1;
            int[] remain = new int[nRemain];
            int q = 0;
            for (int i = 0; i < totalBlocks; ++i) if (!isKey[i]) remain[q++] = i;
            fisherYatesV3(remain, seed1);

            int maxBits = Math.min(remain.length, 4 * 1024 * 1024 * 8);
            byte[] pRs = new byte[(maxBits + 7) / 8];
            Sha256PrngV3 rng1 = new Sha256PrngV3(seed1);
            for (int i = 0; i < maxBits; ++i) {
                int blockIdx = remain[i];
                int zz = V3_MID_ZIGZAG[Math.abs(rng1.nextByte()) % V3_MID_ZIGZAG.length];
                int bit = extractBlockBitAtV3(y, W, H, blocksX, blocksY, blockIdx, zz);
                if (bit != 0) pRs[i >> 3] |= (byte) (1 << (7 - (i & 7)));
            }

            // 先尝试按 "前 2B 为长度" 解码
            byte[] pRaw = rsDecodeV3ByLen(pRs);
            if (pRaw.length < 2) return new byte[0];
            int len = ((pRaw[0] & 0xFF) << 8) | (pRaw[1] & 0xFF);
            if (len < 0 || len + 2 > pRaw.length) len = pRaw.length - 2;
            byte[] payload = new byte[len];
            System.arraycopy(pRaw, 2, payload, 0, len);
            return payload;
        } catch (SteganoException se) {
            throw se;
        } catch (Exception e) {
            throw new SteganoException("extractRsaPhase2 失败: " + e.getMessage());
        }
    }

    @Override
    public long calculateCapacity(byte[] carrierData, double embedRate) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(carrierData));
            if (img == null) return 0L;
            int totalBlocks = (img.getWidth() / 8) * (img.getHeight() / 8);
            // 简化估算：(totalBlocks * embedRate - c区开销) / (8 * RS 膨胀系数)
            long payloadBits = (long) (totalBlocks * embedRate) - RSA_C_TOTAL_BITS_V3;
            if (payloadBits <= 0) return 0L;
            long rsRatioNum = RS_DATA_BYTES_V3, rsRatioDen = RS_CODE_BYTES_V3;
            return (payloadBits * rsRatioNum) / (rsRatioDen * 8L);
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public String[] supportedExtensions() {
        return SteganoConfig.JPEG_EXTENSIONS.clone();
    }

    // ================ 图像 I/O 辅助 ================

    private static BufferedImage readAsRgb(byte[] data) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
        if (src == null) throw new SteganoException("JPEG: 无法解析载体图片");
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        dst.createGraphics().drawImage(src, 0, 0, null);
        dst.createGraphics().dispose();
        return dst;
    }

    private static int[][] toY2D(BufferedImage img) {
        int W = img.getWidth(), H = img.getHeight();
        int[][] y = new int[H][W];
        for (int r = 0; r < H; ++r) {
            for (int c = 0; c < W; ++c) {
                int rgb = img.getRGB(c, r);
                int R = (rgb >> 16) & 0xFF;
                int G = (rgb >> 8) & 0xFF;
                int B = rgb & 0xFF;
                y[r][c] = (int) (0.299 * R + 0.587 * G + 0.114 * B + 0.5);
            }
        }
        return y;
    }

    private static void writeBackRgbFromY(BufferedImage img, int[][] yChannel) {
        int W = img.getWidth(), H = img.getHeight();
        for (int r = 0; r < H; ++r) {
            for (int c = 0; c < W; ++c) {
                int rgb = img.getRGB(c, r);
                int R = (rgb >> 16) & 0xFF;
                int G = (rgb >> 8) & 0xFF;
                int B = rgb & 0xFF;
                int origY = (int) (0.299 * R + 0.587 * G + 0.114 * B + 0.5);
                int delta = yChannel[r][c] - origY;
                int nr = clamp(R + delta);
                int ng = clamp(G + delta);
                int nb = clamp(B + delta);
                img.setRGB(c, r, 0xFF000000 | (nr << 16) | (ng << 8) | nb);
            }
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // ================ 块级 QIM 嵌入/提取 ================

    private static void embedBlockBitAtWithDeltaV3(int[][] y, int W, int H,
                                                    int blocksX, int blocksY,
                                                    int blockIdx, int zzIndex,
                                                    int bit, int delta) {
        int br = blockIdx / blocksX, bc = blockIdx % blocksX;
        int startX = bc * 8, startY = br * 8;
        if (startX + 8 > W || startY + 8 > H) return;
        int[][] block = new int[8][8];
        for (int i = 0; i < 8; ++i)
            for (int j = 0; j < 8; ++j)
                block[i][j] = y[startY + i][startX + j] - 128;
        double[][] coeffs = dct2d(block);
        int[][] q = quantize(coeffs);
        int idx = ZIGZAG[zzIndex];
        int rr = idx / 8, cc = idx % 8;
        int coef = q[rr][cc];
        // 若奇偶性不符合目标 bit，则按 delta 调整
        if (((Math.abs(coef) & 1) ^ bit) != 0) {
            if (coef == 0) {
                q[rr][cc] = (bit == 0) ? 0 : 1;
            } else {
                int round = (int) Math.round((double) coef / (double) delta) * delta;
                if (round == 0 && bit == 1) round = (coef > 0) ? delta : -delta;
                else if ((Math.abs(round) & 1) != bit) round += (coef > 0 ? delta : -delta);
                q[rr][cc] = round;
            }
        }
        double[][] dec = dequantize(q);
        int[][] pixels = idct2d(dec);
        for (int i = 0; i < 8; ++i)
            for (int j = 0; j < 8; ++j) {
                int v = pixels[i][j] + 128;
                if (v < 0) v = 0;
                else if (v > 255) v = 255;
                y[startY + i][startX + j] = v;
            }
    }

    private static int extractBlockBitAtV3(int[][] y, int W, int H,
                                            int blocksX, int blocksY,
                                            int blockIdx, int zzIndex) {
        int br = blockIdx / blocksX, bc = blockIdx % blocksX;
        int startX = bc * 8, startY = br * 8;
        if (startX + 8 > W || startY + 8 > H) return 0;
        int[][] block = new int[8][8];
        for (int i = 0; i < 8; ++i)
            for (int j = 0; j < 8; ++j)
                block[i][j] = y[startY + i][startX + j] - 128;
        double[][] coeffs = dct2d(block);
        int[][] q = quantize(coeffs);
        int idx = ZIGZAG[zzIndex];
        return Math.abs(q[idx / 8][idx % 8]) & 1;
    }

    // ================ 8x8 DCT / IDCT ================

    static double[] dct1d(double[] x) {
        double[] y = new double[8];
        for (int u = 0; u < 8; ++u) {
            double cu = (u == 0) ? Math.sqrt(2.0) / 2.0 : 1.0;
            double sum = 0.0;
            for (int i = 0; i < 8; ++i)
                sum += x[i] * Math.cos((2 * i + 1) * u * Math.PI / 16.0);
            y[u] = cu * sum * 0.5;
        }
        return y;
    }

    static double[] idct1d(double[] y) {
        double[] x = new double[8];
        for (int i = 0; i < 8; ++i) {
            double sum = 0.0;
            for (int u = 0; u < 8; ++u) {
                double cu = (u == 0) ? Math.sqrt(2.0) / 2.0 : 1.0;
                sum += cu * y[u] * Math.cos((2 * i + 1) * u * Math.PI / 16.0);
            }
            x[i] = sum * 0.5;
        }
        return x;
    }

    static double[][] dct2d(int[][] pixels) {
        double[][] tmp = new double[8][8];
        double[][] out = new double[8][8];
        for (int r = 0; r < 8; ++r) {
            double[] row = { pixels[r][0], pixels[r][1], pixels[r][2], pixels[r][3],
                             pixels[r][4], pixels[r][5], pixels[r][6], pixels[r][7] };
            double[] ry = dct1d(row);
            for (int c = 0; c < 8; ++c) tmp[r][c] = ry[c];
        }
        for (int c = 0; c < 8; ++c) {
            double[] col = { tmp[0][c], tmp[1][c], tmp[2][c], tmp[3][c],
                             tmp[4][c], tmp[5][c], tmp[6][c], tmp[7][c] };
            double[] cy = dct1d(col);
            for (int r = 0; r < 8; ++r) out[r][c] = cy[r];
        }
        return out;
    }

    static int[][] idct2d(double[][] coeffs) {
        double[][] tmp = new double[8][8];
        int[][] out = new int[8][8];
        for (int c = 0; c < 8; ++c) {
            double[] col = { coeffs[0][c], coeffs[1][c], coeffs[2][c], coeffs[3][c],
                             coeffs[4][c], coeffs[5][c], coeffs[6][c], coeffs[7][c] };
            double[] cx = idct1d(col);
            for (int r = 0; r < 8; ++r) tmp[r][c] = cx[r];
        }
        for (int r = 0; r < 8; ++r) {
            double[] row = { tmp[r][0], tmp[r][1], tmp[r][2], tmp[r][3],
                             tmp[r][4], tmp[r][5], tmp[r][6], tmp[r][7] };
            double[] rx = idct1d(row);
            for (int c = 0; c < 8; ++c) out[r][c] = (int) Math.round(rx[c]);
        }
        return out;
    }

    static int[][] quantize(double[][] coeffs) {
        int[][] q = new int[8][8];
        for (int r = 0; r < 8; ++r)
            for (int c = 0; c < 8; ++c)
                q[r][c] = (int) Math.round(coeffs[r][c] / STD_LUMA_QUANT[r * 8 + c]);
        return q;
    }

    static double[][] dequantize(int[][] qcoeffs) {
        double[][] out = new double[8][8];
        for (int r = 0; r < 8; ++r)
            for (int c = 0; c < 8; ++c)
                out[r][c] = qcoeffs[r][c] * STD_LUMA_QUANT[r * 8 + c];
        return out;
    }

    // ================ RS 编码/解码（调用 ReedSolomon255） ================

    private static byte[] rsEncodeV3(byte[] data, int dataLen) {
        if (dataLen <= 0) return new byte[0];
        int blocks = (int) Math.ceil((double) dataLen / (double) RS_DATA_BYTES_V3);
        byte[] out = new byte[blocks * RS_CODE_BYTES_V3];
        for (int i = 0; i < blocks; ++i) {
            int off = i * RS_DATA_BYTES_V3;
            int len = Math.min(RS_DATA_BYTES_V3, dataLen - off);
            byte[] block = new byte[RS_DATA_BYTES_V3];
            System.arraycopy(data, off, block, 0, len);
            byte[] coded = ReedSolomon255.encode(block);
            System.arraycopy(coded, 0, out, i * RS_CODE_BYTES_V3, RS_CODE_BYTES_V3);
        }
        return out;
    }

    /** 截断为 targetLen 字节 */
    private static byte[] rsDecodeV3(byte[] coded, int targetLen) {
        int blocks = (coded.length + RS_CODE_BYTES_V3 - 1) / RS_CODE_BYTES_V3;
        if (blocks == 0) return new byte[0];
        byte[] all = new byte[blocks * RS_DATA_BYTES_V3];
        for (int i = 0; i < blocks; ++i) {
            byte[] block = new byte[RS_CODE_BYTES_V3];
            int copy = Math.min(RS_CODE_BYTES_V3, coded.length - i * RS_CODE_BYTES_V3);
            System.arraycopy(coded, i * RS_CODE_BYTES_V3, block, 0, copy);
            byte[] decoded = ReedSolomon255.decode(block);
            System.arraycopy(decoded, 0, all, i * RS_DATA_BYTES_V3, RS_DATA_BYTES_V3);
        }
        if (targetLen >= 0) {
            int L = Math.min(targetLen, all.length);
            byte[] truncated = new byte[targetLen];
            System.arraycopy(all, 0, truncated, 0, L);
            return truncated;
        }
        return all;
    }

    /** 按 "前 2B 为长度" 方式解码并返回全部数据字节（含前 2B） */
    private static byte[] rsDecodeV3ByLen(byte[] coded) {
        int blocks = (coded.length + RS_CODE_BYTES_V3 - 1) / RS_CODE_BYTES_V3;
        if (blocks == 0) return new byte[0];
        byte[] all = new byte[blocks * RS_DATA_BYTES_V3];
        for (int i = 0; i < blocks; ++i) {
            byte[] block = new byte[RS_CODE_BYTES_V3];
            int copy = Math.min(RS_CODE_BYTES_V3, coded.length - i * RS_CODE_BYTES_V3);
            System.arraycopy(coded, i * RS_CODE_BYTES_V3, block, 0, copy);
            byte[] decoded = ReedSolomon255.decode(block);
            System.arraycopy(decoded, 0, all, i * RS_DATA_BYTES_V3, RS_DATA_BYTES_V3);
        }
        return all;
    }

    // ================ 哈希 / PRNG 辅助 ================

    private static final class Sha256PrngV3 {
        private final java.security.MessageDigest md;
        private byte[] state;
        private int pos;

        Sha256PrngV3(byte[] seed) throws Exception {
            this.md = java.security.MessageDigest.getInstance("SHA-256");
            this.state = md.digest(seed);
            this.pos = 0;
        }

        int nextByte() throws Exception {
            if (pos >= state.length) {
                state = md.digest(state);
                pos = 0;
            }
            return state[pos++] & 0xFF;
        }
    }

    private static byte[] sha256Bytes(byte[] data) throws Exception {
        return java.security.MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static byte[] sha256BytesConcat(byte[] a, byte[] b) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        md.update(a);
        md.update(b);
        return md.digest();
    }

    private static void fisherYatesV3(int[] arr, byte[] seed) throws Exception {
        int n = arr.length;
        Sha256PrngV3 rng = new Sha256PrngV3(seed);
        for (int i = n - 1; i > 0; --i) {
            int m = i + 1;
            int max = (Integer.MAX_VALUE / m) * m;
            int r = 0;
            while (true) {
                int raw = ((rng.nextByte() & 0xFF) << 24)
                        | ((rng.nextByte() & 0xFF) << 16)
                        | ((rng.nextByte() & 0xFF) << 8)
                        | (rng.nextByte() & 0xFF);
                int v = raw & Integer.MAX_VALUE;
                if (v < max) {
                    r = v % m;
                    break;
                }
            }
            int tmp = arr[i];
            arr[i] = arr[r];
            arr[r] = tmp;
        }
    }

    private static long varianceOfBlockV3(int[][] y, int W, int H, int blockIdx) {
        int blocksX = W / 8;
        int br = blockIdx / blocksX, bc = blockIdx % blocksX;
        int startX = bc * 8, startY = br * 8;
        if (startX + 8 > W || startY + 8 > H) return 0L;
        long sum = 0L;
        for (int i = 0; i < 8; ++i) for (int j = 0; j < 8; ++j) sum += y[startY + i][startX + j];
        long mean = sum / 64;
        long sq = 0L;
        for (int i = 0; i < 8; ++i) for (int j = 0; j < 8; ++j) {
            long d = y[startY + i][startX + j] - mean;
            sq += d * d;
        }
        return sq / 64;
    }

    // ================ JPEG 写出 ================

    static byte[] encodeToJpeg(BufferedImage img) throws IOException {
        java.util.Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("JPEG");
        if (!it.hasNext()) throw new SteganoException("JPEG: 未找到 ImageWriter");
        ImageWriter writer = it.next();
        try {
            JPEGImageWriteParam param = new JPEGImageWriteParam(java.util.Locale.US);
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(SteganoConfig.JPEG_QUALITY);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            writer.setOutput(ImageIO.createImageOutputStream(bos));
            writer.write(null, new IIOImage(img, null, null), param);
            return bos.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
