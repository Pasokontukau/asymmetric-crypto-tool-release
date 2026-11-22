package core.util;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 崩溃测试子进程：创建三种含明文的临时路径后直接 halt。
 *
 * <p>{@code halt} 不执行 shutdown hook，模拟任务管理器结束进程或 JVM 崩溃。</p>
 */
public final class TempCrashFixture {

    private TempCrashFixture() {
    }

    public static void main(String[] args) throws Exception {
        File tempRoot = new File(args[0]);
        File outputDirectory = new File(args[1]);

        File input = new File(tempRoot, "input");
        Files.createDirectories(input.toPath());
        File secret = new File(input, "secret.txt");
        Files.write(secret.toPath(), "TOP SECRET".getBytes(StandardCharsets.UTF_8));

        File zip = TempFileRegistry.createTempFile("aegix-folder-", ".zip", tempRoot);
        FolderZipUtils.zipFolder(input, zip, null);

        File decrypted = TempFileRegistry.createTempFile(
                "aegix-decrypt-", ".tmp", outputDirectory);
        Files.write(decrypted.toPath(), "TOP SECRET".getBytes(StandardCharsets.UTF_8));

        File staging = TempFileRegistry.createTempDirectory("aegix-unzip-", outputDirectory);
        Files.write(new File(staging, "secret.txt").toPath(),
                "TOP SECRET".getBytes(StandardCharsets.UTF_8));

        Runtime.getRuntime().halt(7);
    }
}
