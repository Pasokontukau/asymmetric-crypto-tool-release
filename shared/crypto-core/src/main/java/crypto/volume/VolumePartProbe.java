package crypto.volume;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.PrivateKey;

/**
 * 只读 VOLU 元数据的探针。
 *
 * <p>用于在多选/入口判型时先知道这是旧 VOLU、新单卷包还是多卷包，不读取任何
 * 分卷 payload，也不做整包解密。真正的缺卷/重卷/混包校验需要收集全部文件后由
 * 上层集合逻辑完成；本类只返回单卷自洽的 VolumeInfo。</p>
 *
 * <p>返回的 VolumeInfo 已包含 {@code setId/partIndex/partCount/archive*} 字段；
 * 旧 VOLU 的 {@link VolumeInfo#isExternalPart()} 为 false，相关 getter 为 null。</p>
 */
public final class VolumePartProbe {

    private VolumePartProbe() {
    }

    /**
     * 探测一个文件。流只读到加密 VolumeInfo 结束，不读取 payload。
     */
    public static VolumeInfo probe(File file, PrivateKey recipientKey) throws Exception {
        if (file == null || !file.isFile()) {
            throw new VolumeFormatException("VOLU 文件不存在或不是普通文件");
        }
        // 不能用 BufferedInputStream：预读会越过元数据边界。
        try (InputStream input = new FileInputStream(file)) {
            return probe(input, recipientKey);
        }
    }

    /**
     * 探测一个输入流；本方法不关闭调用方的流。
     */
    public static VolumeInfo probe(InputStream input, PrivateKey recipientKey) throws Exception {
        return VolumeCryptoEngine.readInfo(input, recipientKey);
    }
}
