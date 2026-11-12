package stegano.service;

import crypto.config.CryptoConfig;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import stegano.carrier.SteganoCarrier;
import stegano.service.SteganoException;

/**
 * 隐写核心：统一非对称接口（对齐 STEGANO-MERGED-PLAN-v3.md §1-3）。
 *
 * <p>载体通过静态块注册（当前支持 PNG/BMP/JPEG）。新增载体（如视频、文件）只需
 * 实现 {@link SteganoCarrier} 并调用 {@link #registerCarrier(SteganoCarrier)} 即可无缝接入。
 */
public class SteganoCore {

    private static final Map<String, SteganoCarrier> carrierMap = new HashMap<>();

    static {
        registerCarrier(new stegano.carrier.ImageSteganoCarrier());
        registerCarrier(new stegano.carrier.JpegSteganoCarrier());
    }

    /**
     * 注册一个新的载体类型（实现 {@link SteganoCarrier}）。
     * 同名扩展名若被新实现覆盖，将替换既有注册，支持未来载体扩展。
     */
    public static void registerCarrier(SteganoCarrier carrier) {
        for (String ext : carrier.supportedExtensions()) {
            carrierMap.put(ext.toLowerCase(), carrier);
        }
    }

    // ================= RSA-OAEP 辅助 =================

    /**
     * 使用联系人公钥加密 32 字节随机数 r -> 固定 256 字节密文 c。
     */
    public static byte[] encryptRngForContact(byte[] r, PublicKey contactPublicKey) throws SteganoException {
        if (r == null || r.length != stegano.model.SteganoConfig.RSA_R_LENGTH) {
            throw new SteganoException("RSA rng 长度异常: 期望 "
                    + stegano.model.SteganoConfig.RSA_R_LENGTH);
        }
        if (contactPublicKey == null) {
            throw new SteganoException("RSA 联系人公钥为空");
        }
        try {
            Cipher c = Cipher.getInstance(CryptoConfig.RSA_CIPHER);
            c.init(Cipher.ENCRYPT_MODE, contactPublicKey);
            byte[] out = c.doFinal(r);
            if (out.length != stegano.model.SteganoConfig.RSA_C_LENGTH) {
                throw new SteganoException("RSA 密文长度异常: 期望 "
                        + stegano.model.SteganoConfig.RSA_C_LENGTH);
            }
            return out;
        } catch (Exception e) {
            throw new SteganoException("RSA-OAEP 加密失败: " + e.getMessage());
        }
    }

    /**
     * 使用自己私钥解密 c -> 得到 32 字节随机数 r。
     */
    public static byte[] decryptRngWithPrivateKey(byte[] c, PrivateKey privateKey) throws SteganoException {
        if (c == null || c.length != stegano.model.SteganoConfig.RSA_C_LENGTH) {
            throw new SteganoException("RSA c 长度异常: 期望 "
                    + stegano.model.SteganoConfig.RSA_C_LENGTH);
        }
        try {
            Cipher cipher = Cipher.getInstance(CryptoConfig.RSA_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            return cipher.doFinal(c);
        } catch (Exception e) {
            throw new SteganoException("RSA-OAEP 解密失败: " + e.getMessage());
        }
    }

    // ================= 统一非对称嵌入/提取 =================

    /**
     * 非对称嵌入：统一调用对应载体的 embedRsa。
     * @param c RSA-2048 OAEP 密文 (256B)
     * @param payload 载荷内容（载体内部会包装为 [2B len | payload] 后 RS 编码）
     * @param carrierData 载体文件原始字节
     * @param carrierFileName 载体文件名（仅用于识别扩展名 -> 匹配对应载体实现）
     * @param contactSalt 联系人专属 salt
     * @param embedRate 嵌入率
     */
    public static byte[] embedRsa(byte[] c, byte[] payload,
                                   byte[] carrierData, String carrierFileName,
                                   byte[] contactSalt, double embedRate) throws SteganoException {
        SteganoCarrier carrier = resolve(carrierFileName);
        if (c == null || c.length != stegano.model.SteganoConfig.RSA_C_LENGTH) {
            throw new SteganoException("RSA c 长度应为 " + stegano.model.SteganoConfig.RSA_C_LENGTH);
        }
        if (contactSalt == null || contactSalt.length < stegano.model.SteganoConfig.CONTACT_SALT_MIN_LENGTH) {
            throw new SteganoException("contactSalt 至少 " + stegano.model.SteganoConfig.CONTACT_SALT_MIN_LENGTH + " 字节");
        }
        return carrier.embedRsa(c, payload, carrierData, contactSalt, embedRate);
    }

    /**
     * 非对称提取 Phase 1：仅提取 RSA 密文 c。
     */
    public static byte[] extractRsaPhase1(byte[] stegoData, String carrierFileName,
                                          byte[] contactSalt, double embedRate) throws SteganoException {
        SteganoCarrier carrier = resolve(carrierFileName);
        if (contactSalt == null || contactSalt.length < stegano.model.SteganoConfig.CONTACT_SALT_MIN_LENGTH) {
            throw new SteganoException("contactSalt 长度不足");
        }
        return carrier.extractRsaPhase1(stegoData, contactSalt, embedRate);
    }

    /**
     * 非对称提取 Phase 2：用 r 派生 seed1 定位载荷区，提取 payload。
     */
    public static byte[] extractRsaPhase2(byte[] stegoData, String carrierFileName,
                                          byte[] contactSalt, byte[] r, double embedRate) throws SteganoException {
        SteganoCarrier carrier = resolve(carrierFileName);
        if (r == null || r.length != stegano.model.SteganoConfig.RSA_R_LENGTH) {
            throw new SteganoException("r 长度应为 " + stegano.model.SteganoConfig.RSA_R_LENGTH);
        }
        return carrier.extractRsaPhase2(stegoData, contactSalt, r, embedRate);
    }

    /**
     * 估算给定载体的有效载荷容量（字节）。用于 UI 展示，非严格约束。
     */
    public static long calculateCapacity(byte[] carrierData, String carrierFileName, double embedRate) {
        SteganoCarrier carrier;
        try {
            carrier = resolve(carrierFileName);
        } catch (SteganoException e) {
            return 0L;
        }
        return carrier.calculateCapacity(carrierData, embedRate);
    }

    // ================= 工具 =================

    /**
     * 包装 payload 为 [2B payloadLen | payload bytes]。
     */
    public static byte[] packageForStegano(byte[] payload) {
        if (payload == null) throw new SteganoException("payload 为空");
        byte[] wrapped = new byte[2 + payload.length];
        wrapped[0] = (byte) ((payload.length >>> 8) & 0xFF);
        wrapped[1] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, wrapped, 2, payload.length);
        return wrapped;
    }

    /** 检查文件扩展名是否受支持 */
    public static boolean isSupported(String fileName) {
        String ext = getExtension(fileName);
        return ext != null && carrierMap.containsKey(ext);
    }

    /** 返回当前所有已注册的扩展名集合（方便 UI 显示） */
    public static java.util.Set<String> registeredExtensions() {
        return carrierMap.keySet();
    }

    private static SteganoCarrier resolve(String fileName) {
        String ext = getExtension(fileName);
        if (ext == null) {
            throw new SteganoException("无法识别文件扩展名: " + fileName);
        }
        SteganoCarrier carrier = carrierMap.get(ext);
        if (carrier == null) {
            throw new SteganoException("不支持的载体格式: " + ext + " (支持: " + carrierMap.keySet() + ")");
        }
        return carrier;
    }

    private static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return null;
        return fileName.substring(dot + 1).toLowerCase();
    }
}
