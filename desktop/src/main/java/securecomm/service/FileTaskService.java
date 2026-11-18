package securecomm.service;

import java.io.File;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.List;

/**
 * 文件操作的编排边界（DR-2）：把“选路径、判型、命名、分卷、解密、合卷”的调用顺序收在一处，
 * 界面只剩“收集参数 + 展示结果”。可预期失败用结果对象表达，不跨层裸抛运行时异常。
 */
public interface FileTaskService {

    /** 加密一个文件；超过阈值自动写外部加密分卷。 */
    EncryptOutcome encrypt(EncryptRequest request);

    /** 解密一个单文件容器或单卷外部包。 */
    DecryptOutcome decrypt(DecryptRequest request);

    /**
     * 批量解密前的分流：没有分卷就直接逐个解密；全是分卷则检查后按齐套/缺卷/拒绝分流。
     * 齐套时**会写盘**——合并出原始文件（合并本身完成逐卷解密），产物是最终文件，
     * 不需要二次解密，也没有临时文件要清理；缺卷/重卷/混包/混选返回不可继续。
     */
    BatchDecryptPreparation routeBatchDecrypt(List<File> selection, PrivateKey privateKey,
                                                List<PublicKey> senderCandidates,
                                                File outputDirectory, ProgressSink progress,
                                                java.util.concurrent.atomic.AtomicBoolean cancelled);

    /** 检查一批外部分卷的状态并给出下一步动作；只读元数据，不合并、不写盘。 */
    MergeInspection inspectParts(List<File> parts, PrivateKey privateKey);

    /** 合并一批外部分卷：逐卷解密并还原原始文件（文件夹来源还原为 ZIP）。 */
    MergeOutcome mergeParts(MergeRequest request);
}
