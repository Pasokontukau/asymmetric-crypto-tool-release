package stegano.service;














public class SteganoLsbUtils {

    /**
     * 获取 24-bit RGB 值中指定通道的 LSB。
     * @param rgb 24-bit RGB 值 (0xRRGGBB)
     * @param channel 通道索引: 0=R, 1=G, 2=B
     * @return 0 或 1
     */
    public static int getLsb(int rgb, int channel) {
        int shift = (2 - channel) * 8; // R=16, G=8, B=0
        return (rgb >> shift) & 1;
    }

    /**
     * 设置 24-bit RGB 值中指定通道的 LSB。
     * @param rgb 原始 RGB 值
     * @param channel 通道索引: 0=R, 1=G, 2=B
     * @param bit 要设置的位 (0 或 1)
     * @return 修改后的 RGB 值
     */
    public static int setLsb(int rgb, int channel, int bit) {
        int shift = (2 - channel) * 8;
        int mask = ~(1 << shift);
        return (rgb & mask) | ((bit & 1) << shift);
    }

    /** 将字节数组转换为位数组 */
    public static int[] bytesToBits(byte[] data) {
        int[] bits = new int[data.length * 8];
        for (int i = 0; i < data.length; i++) {
            for (int j = 0; j < 8; j++) {
                bits[i * 8 + j] = (data[i] >> (7 - j)) & 1;
            }
        }
        return bits;
    }

    /** 将位数组转换为字节数组 */
    public static byte[] bitsToBytes(int[] bits) {
        int byteCount = bits.length / 8;
        byte[] bytes = new byte[byteCount];
        for (int i = 0; i < byteCount; i++) {
            int b = 0;
            for (int j = 0; j < 8; j++) {
                b = (b << 1) | (bits[i * 8 + j] & 1);
            }
            bytes[i] = (byte) b;
        }
        return bytes;
    }
}