package securecomm.service;

import java.io.File;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一次单文件/单卷解密的输入；发送方公钥候选列表用于验签。 */
public final class DecryptRequest {

    public final File source;
    public final PrivateKey privateKey;
    public final List<PublicKey> senderCandidates;
    public final File outputDirectory;
    public final AtomicBoolean cancelled;
    public final ProgressSink progress;

    private DecryptRequest(File source, PrivateKey privateKey, List<PublicKey> senderCandidates,
                           File outputDirectory, AtomicBoolean cancelled, ProgressSink progress) {
        this.source = source;
        this.privateKey = privateKey;
        this.senderCandidates = senderCandidates == null
                ? Collections.<PublicKey>emptyList() : senderCandidates;
        this.outputDirectory = outputDirectory;
        this.cancelled = cancelled;
        this.progress = progress;
    }

    public static DecryptRequest singleFile(
            File source,
            PrivateKey privateKey,
            List<PublicKey> senderCandidates,
            File outputDirectory
    ) {
        return new DecryptRequest(source, privateKey, senderCandidates, outputDirectory, null, null);
    }

    public DecryptRequest withProgress(ProgressSink sink) {
        return new DecryptRequest(source, privateKey, senderCandidates, outputDirectory, cancelled, sink);
    }

    public DecryptRequest withCancellation(AtomicBoolean flag) {
        return new DecryptRequest(source, privateKey, senderCandidates, outputDirectory, flag, progress);
    }
}
