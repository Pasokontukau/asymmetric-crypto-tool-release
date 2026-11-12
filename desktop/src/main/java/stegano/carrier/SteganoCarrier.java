package stegano.carrier;

import stegano.service.SteganoException;

/**
 * 统一的载体隐写接口（对齐 STEGANO-MERGED-PLAN-v3.md 的非对称协议）。
 *
 * <p>协议概述：<br>
 *   1. 发送方生成 32 字节随机数 r；<br>
 *   2. c = RSA-OAEP(contactPublicKey, r)，长度固定为 RSA_C_LENGTH；<br>
 *   3. seed0 = SHA256(contactSalt) → 定位密钥区，承载 c 的 RS 编码比特；<br>
 *   4. seed1 = SHA256(r) → 定位载荷区，承载 payload 的 RS 编码比特；<br>
 *   5. 接收方用 contactSalt 提取 c → 用自己私钥解密得 r → seed1 提取 payload。
 *
 * <p>载体的具体实现决定密钥区/载荷区的粒度与比特嵌入方式：<br>
 *   - JPEG: 8×8 宏块 → 5 个中频 DCT 系数之一 + QIM；<br>
 *   - PNG/BMP: 像素通道 LSB；<br>
 *   - 视频/文件等未来载体: 实现本接口即可无缝接入。
 */
public interface SteganoCarrier {

    /**
     * 非对称嵌入：将 c（RSA 密文）和 payload 按 v3 双区协议嵌入载体。
     *
     * @param c            RSA-2048 OAEP 密文，固定长度 RSA_C_LENGTH
     * @param payload      载荷内容（实现内部会包装为 [2B len | payload] 后 RS 编码）
     * @param carrierData  载体原始字节
     * @param contactSalt  联系人专属 salt，>= CONTACT_SALT_MIN_LENGTH
     * @param embedRate    嵌入率
     * @return 含密载体字节
     */
    byte[] embedRsa(byte[] c, byte[] payload,
                    byte[] carrierData, byte[] contactSalt,
                    double embedRate) throws SteganoException;

    /**
     * 非对称提取 Phase 1：仅从密钥区提取 RSA 密文 c。
     * 依赖 contactSalt 派生 seed0。
     */
    byte[] extractRsaPhase1(byte[] stegoData, byte[] contactSalt,
                            double embedRate) throws SteganoException;

    /**
     * 非对称提取 Phase 2：用 r（由 c 解密得到）派生 seed1，定位载荷区，提取 payload。
     */
    byte[] extractRsaPhase2(byte[] stegoData, byte[] contactSalt,
                            byte[] r, double embedRate) throws SteganoException;

    /**
     * 估算载体可承载的最大 payload 字节数（仅 payload，不含 c、RS 开销等的估计）。
     * UI 用它来展示容量。
     */
    long calculateCapacity(byte[] carrierData, double embedRate);

    /**
     * 返回该载体支持的文件扩展名（小写，不含点）。
     * 例: {@code {"png", "bmp"}} / {@code {"jpg", "jpeg"}}。
     */
    String[] supportedExtensions();
}
