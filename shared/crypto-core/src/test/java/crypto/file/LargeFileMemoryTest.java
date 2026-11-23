package crypto.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * G-01：100 MB 文件在 96 MB 堆下完成加密与解密。
 *
 * <p>与 {@code largeFile_streamsWithBoundedMemory} 的区别：那条依赖测试 JVM 的堆配置，
 * 这条把堆显式压到 96 MB（文件 100 MB），只有真正的流式实现才能过。若实现改成“整段读入内存”
 * 会在这里稳定 OOM。</p>
 */
public class LargeFileMemoryTest {

    @Test
    public void hundredMegabytesFitInNinetySixMegabytesOfHeap() throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + "java";
        String classpath = System.getProperty("java.class.path", "");
        assertTrue("测试类路径为空，无法启动子进程", classpath != null && !classpath.isEmpty());

        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-Xmx96m");
        command.add("-cp");
        command.add(classpath);
        command.add(MemoryProbe.class.getName());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        boolean finished = process.waitFor(240, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertTrue("子进程超时", finished);
        assertEquals("受限堆下的 100 MB 流程失败:\n" + output, 0, process.exitValue());
        assertTrue("缺少成功标记:\n" + output, output.toString().contains("MEMORY_PROBE_OK"));
    }
}
