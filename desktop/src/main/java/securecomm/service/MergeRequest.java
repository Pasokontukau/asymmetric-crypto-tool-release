package securecomm.service;

import java.io.File;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 把一批外部分卷合并回原始文件的输入；合并本身完成逐卷解密。 */
public final class MergeRequest {

    public final List<File> parts;
    public final PrivateKey privateKey;
    public final List<PublicKey> senderCandidates;
    public final File outputDirectory;
    public final String temporaryName;
    public final AtomicBoolean cancelled;
    public final ProgressSink progress;

    private MergeRequest(List<File> parts, PrivateKey privateKey, List<PublicKey> senderCandidates,
                         File outputDirectory, String temporaryName, AtomicBoolean cancelled,
                         ProgressSink progress) {
        this.parts = parts == null ? Collections.<File>emptyList() : parts;
        this.privateKey = privateKey;
        this.senderCandidates = senderCandidates == null
                ? Collections.<PublicKey>emptyList() : senderCandidates;
        this.outputDirectory = outputDirectory;
        this.temporaryName = temporaryName;
        this.cancelled = cancelled;
        this.progress = progress;
    }

    public static MergeRequest of(List<File> parts, PrivateKey privateKey,
                                  List<PublicKey> senderCandidates, File outputDirectory) {
        return new MergeRequest(parts, privateKey, senderCandidates, outputDirectory, null, null, null);
    }

    public MergeRequest withProgress(ProgressSink sink) {
        return new MergeRequest(parts, privateKey, senderCandidates, outputDirectory,
                temporaryName, cancelled, sink);
    }

    public MergeRequest withCancellation(AtomicBoolean flag) {
        return new MergeRequest(parts, privateKey, senderCandidates, outputDirectory,
                temporaryName, flag, progress);
    }
}
