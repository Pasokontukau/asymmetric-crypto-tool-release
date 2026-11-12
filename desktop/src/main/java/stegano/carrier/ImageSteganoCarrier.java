package stegano.carrier;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import javax.imageio.ImageIO;
import stegano.carrier.SteganoCarrier;
import stegano.model.SteganoConfig;
import stegano.service.SteganoException;

/**
 * PNG/BMP 图像载体：LSB 域的非对称隐写（对齐 STEGANO-MERGED-PLAN-v3.md）。
 *
 * <p>协议（v3）：
 * <ul>
 *   <li>粒度：像素通道（RGB 共 3 通道/像素）。每个通道的最低位（LSB）承载 1 bit。</li>
 *   <li>seed0 = SHA256(contactSalt) -> Fisher-Yates 洗牌所有通道索引 -> 前 K1 个通道承载 c 的 RS 编码比特。</li>
 *   <li>seed1 = SHA256(contactSalt || {0x02}) -> Fisher-Yates 洗牌剩余通道 -> 承载 payload 的 RS 编码比特。</li>
 *   <li>payload 包装：[2B payloadLen | payload bytes]，RS(255, 171) 分块编码。</li>
 * </ul>
 */
public class ImageSteganoCarrier implements SteganoCarrier {

    private static final int CHANNELS = 3;
    private static final int RSA_C_BYTES_V3 = SteganoConfig.RSA_C_LENGTH;
    private static final int RSA_R_BYTES_V3 = SteganoConfig.RSA_R_LENGTH;
    private static final int RS_DATA_BYTES_V3 = SteganoConfig.RS_DATA_BYTES;
    private static final int RS_CODE_BYTES_V3 = SteganoConfig.RS_CODE_BYTES;
    private static final int RSA_C_RS_BLOCKS_V3 = (int) Math.ceil((double) RSA_C_BYTES_V3 / (double) RS_DATA_BYTES_V3);
    private static final int RSA_C_TOTAL_BITS_V3 = RSA_C_RS_BLOCKS_V3 * RS_CODE_BYTES_V3 * 8;

    // ================ 接口实现 ================

    @Override
    public byte[] embedRsa(byte[] c, byte[] payload,
                           byte[] carrierData, byte[] contactSalt,
                           double embedRate) throws SteganoException {
        if (embedRate <= 0 || embedRate > 1.0)
            throw new SteganoException("LSB: 嵌入率超出范围: " + embedRate);
        if (c == null || c.length != RSA_C_BYTES_V3)
            throw new SteganoException("LSB: c 长度应为 " + RSA_C_BYTES_V3);
        if (contactSalt == null || contactSalt.length < SteganoConfig.CONTACT_SALT_MIN_LENGTH)
            throw new SteganoException("LSB: contactSalt 长度不足");

        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(carrierData));
            if (img == null) throw new SteganoException("LSB: 无法解析载体图片");

            int W = img.getWidth(), H = img.getHeight();
            int totalChannels = W * H * CHANNELS;

            // c 的 RS 编码
            byte[] cRs = rsEncodeV3(c, RSA_C_BYTES_V3);
            int cRsBits = cRs.length * 8;

            // K1 = min(c_RS_bits, totalChannels * embedRate * 0.5)
            int keyChannelBudget = (int) (totalChannels * embedRate * 0.5);
            int K1 = Math.min(cRsBits, keyChannelBudget);
            if (K1 < cRsBits)
                throw new SteganoException("LSB: 载体容量不足以承载 RSA 密文（请增大图片或 embedRate）");

            // seed0 Fisher-Yates 洗牌，取前 K1 个通道
            byte[] seed0 = sha256Bytes(contactSalt);
            int[] allChannels = new int[totalChannels];
            for (int i = 0; i < totalChannels; i++) allChannels[i] = i;
            fisherYatesV3(allChannels, seed0);

            boolean[] isKey = new boolean[totalChannels];
            for (int i = 0; i < K1; i++) isKey[allChannels[i]] = true;

            // 嵌入 c 的 bits
            for (int i = 0; i < cRsBits; i++) {
                int chIdx = allChannels[i];
                int bit = ((cRs[i >> 3] >>> (7 - (i & 7))) & 1);
                setChannelLsb(img, W, chIdx, bit);
            }

            // payload 包装 -> [2B len | payload] -> RS 编码
            byte[] pRaw = new byte[2 + payload.length];
            pRaw[0] = (byte) ((payload.length >>> 8) & 0xFF);
            pRaw[1] = (byte) (payload.length & 0xFF);
            System.arraycopy(payload, 0, pRaw, 2, payload.length);
            byte[] pRs = rsEncodeV3(pRaw, pRaw.length);
            int pRsBits = pRs.length * 8;

            // seed1 = SHA256(contactSalt || {0x02})
            byte[] seed1 = sha256BytesConcat(contactSalt, new byte[]{0x02});

            // 剩余通道
            int remaining = totalChannels - K1;
            int[] remainIdx = new int[remaining];
            int p = 0;
            for (int i = 0; i < totalChannels; i++)
                if (!isKey[i]) remainIdx[p++] = i;
            fisherYatesV3(remainIdx, seed1);

            int payloadBudget = (int) (totalChannels * embedRate) - K1;
            int K2 = Math.min(pRsBits, Math.max(0, payloadBudget));
            if (K2 < pRsBits)
                throw new SteganoException("LSB: 载体容量不足以承载 payload（请增大图片或 embedRate）");

            for (int i = 0; i < pRsBits; i++) {
                int chIdx = remainIdx[i];
                int bit = ((pRs[i >> 3] >>> (7 - (i & 7))) & 1);
                setChannelLsb(img, W, chIdx, bit);
            }

            // 写出 PNG
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", bos);
            return bos.toByteArray();

        } catch (SteganoException se) {
            throw se;
        } catch (Exception e) {
            throw new SteganoException("LSB: 嵌入失败: " + e.getMessage());
        }
    }

    @Override
    public byte[] extractRsaPhase1(byte[] stegoData, byte[] contactSalt,
                                   double embedRate) throws SteganoException {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(stegoData));
            if (img == null) throw new SteganoException("LSB: 无法解析含密图片");
            int W = img.getWidth(), H = img.getHeight();
            int totalChannels = W * H * CHANNELS;

            byte[] seed0 = sha256Bytes(contactSalt);
            int[] allChannels = new int[totalChannels];
            for (int i = 0; i < totalChannels; i++) allChannels[i] = i;
            fisherYatesV3(allChannels, seed0);

            int keyChannelBudget = (int) (totalChannels * embedRate * 0.5);
            int cRsBytes = RSA_C_RS_BLOCKS_V3 * RS_CODE_BYTES_V3;
            int cRsBits = cRsBytes * 8;
            int K1 = Math.min(cRsBits, keyChannelBudget);
            if (K1 < cRsBits)
                throw new SteganoException("LSB: 无法定位密钥区（embedRate 或载体容量不足）");

            byte[] cRs = new byte[cRsBytes];
            for (int i = 0; i < cRsBits; i++) {
                int chIdx = allChannels[i];
                int bit = getChannelLsb(img, W, chIdx);
                if (bit != 0) cRs[i >> 3] |= (byte) (1 << (7 - (i & 7)));
            }
            return rsDecodeV3(cRs, RSA_C_BYTES_V3);
        } catch (SteganoException se) {
            throw se;
        } catch (Exception e) {
            throw new SteganoException("LSB: Phase1 提取失败: " + e.getMessage());
        }
    }

    @Override
    public byte[] extractRsaPhase2(byte[] stegoData, byte[] contactSalt,
                                   byte[] r, double embedRate) throws SteganoException {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(stegoData));
            if (img == null) throw new SteganoException("LSB: 无法解析含密图片");
            int W = img.getWidth(), H = img.getHeight();
            int totalChannels = W * H * CHANNELS;

            // seed0 洗牌 -> 标记 key 区
            byte[] seed0 = sha256Bytes(contactSalt);
            int[] allChannels = new int[totalChannels];
            for (int i = 0; i < totalChannels; i++) allChannels[i] = i;
            fisherYatesV3(allChannels, seed0);

            int cRsBytes = RSA_C_RS_BLOCKS_V3 * RS_CODE_BYTES_V3;
            int cRsBits = cRsBytes * 8;
            int keyChannelBudget = (int) (totalChannels * embedRate * 0.5);
            int K1 = Math.min(cRsBits, keyChannelBudget);

            boolean[] isKey = new boolean[totalChannels];
            for (int i = 0; i < K1; i++) isKey[allChannels[i]] = true;

            int remaining = totalChannels - K1;
            int[] remainIdx = new int[remaining];
            int p = 0;
            for (int i = 0; i < totalChannels; i++)
                if (!isKey[i]) remainIdx[p++] = i;

            // seed1 = SHA256(contactSalt || {0x02})
            byte[] seed1 = sha256BytesConcat(contactSalt, new byte[]{0x02});
            fisherYatesV3(remainIdx, seed1);

            int payloadBudget = (int) (totalChannels * embedRate) - K1;
            if (payloadBudget < 16)
                throw new SteganoException("LSB: 载体容量不足以为 payload 提取");

            // 读 payload 区 bits: 先取整 block 个 bit
            int blocksToRead = (int) Math.ceil((double) payloadBudget / (8 * RS_CODE_BYTES_V3));
            if (blocksToRead < 1) blocksToRead = 1;
            int totalBits = blocksToRead * RS_CODE_BYTES_V3 * 8;
            if (totalBits > remainIdx.length) totalBits = remainIdx.length;

            byte[] pRs = new byte[(totalBits + 7) / 8];
            for (int i = 0; i < totalBits; i++) {
                int chIdx = remainIdx[i];
                int bit = getChannelLsb(img, W, chIdx);
                if (bit != 0) pRs[i >> 3] |= (byte) (1 << (7 - (i & 7)));
            }

            // 解码: 先按整个 payload 解码，再看前 2B 长度
            byte[] pRaw = rsDecodeV3(pRs, -1);
            if (pRaw.length < 2) return new byte[0];
            int len = ((pRaw[0] & 0xFF) << 8) | (pRaw[1] & 0xFF);
            if (len < 0 || len + 2 > pRaw.length) len = pRaw.length - 2;
            byte[] payload = new byte[len];
            System.arraycopy(pRaw, 2, payload, 0, len);
            return payload;
        } catch (SteganoException se) {
            throw se;
        } catch (Exception e) {
            throw new SteganoException("LSB: Phase2 提取失败: " + e.getMessage());
        }
    }

    @Override
    public long calculateCapacity(byte[] carrierData, double embedRate) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(carrierData));
            if (img == null) return 0L;
            long totalChannels = (long) img.getWidth() * img.getHeight() * CHANNELS;
            long payloadBits = (long) (totalChannels * embedRate) - RSA_C_TOTAL_BITS_V3;
            if (payloadBits <= 0) return 0L;
            long rsRatioNum = RS_DATA_BYTES_V3, rsRatioDen = RS_CODE_BYTES_V3;
            return (payloadBits * rsRatioNum) / (rsRatioDen * 8L);
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public String[] supportedExtensions() {
        return SteganoConfig.SUPPORTED_IMAGE_EXTENSIONS.clone();
    }

    // ================ 像素通道 LSB 操作 ================

    private static void setChannelLsb(BufferedImage img, int W, int channelIdx, int bit) {
        int pixelIdx = channelIdx / CHANNELS;
        int ch = channelIdx % CHANNELS;
        int x = pixelIdx % W;
        int y = pixelIdx / W;
        int rgb = img.getRGB(x, y);
        int shift;
        if (ch == 0) shift = 16;        // R
        else if (ch == 1) shift = 8;   // G
        else shift = 0;                  // B
        int mask = ~(1 << shift);
        int cleared = rgb & mask;
        int newVal = cleared | ((bit & 1) << shift);
        img.setRGB(x, y, newVal);
    }

    private static int getChannelLsb(BufferedImage img, int W, int channelIdx) {
        int pixelIdx = channelIdx / CHANNELS;
        int ch = channelIdx % CHANNELS;
        int x = pixelIdx % W;
        int y = pixelIdx / W;
        int rgb = img.getRGB(x, y);
        int shift = (ch == 0) ? 16 : (ch == 1 ? 8 : 0);
        return (rgb >>> shift) & 1;
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
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static byte[] sha256BytesConcat(byte[] a, byte[] b) throws Exception {
        java.security.MessageDigest md = MessageDigest.getInstance("SHA-256");
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
                if (v < max) { r = v % m; break; }
            }
            int tmp = arr[i]; arr[i] = arr[r]; arr[r] = tmp;
        }
    }

    // ================ RS 编码/解码 ================

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

    /** targetLen > 0 则截断为 targetLen；targetLen < 0 表示返回整包（由上层按前 2B 长度裁剪） */
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
}
