package app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

/** 命令行里只收已存在的文件路径，忽略选项与空参数。 */
public class OpenFilesArgsTest {

    @Test
    public void keepsExistingFilesOnly() throws IOException {
        Path dir = Files.createTempDirectory("aegix-open-files");
        try {
            Path exists = Files.createFile(dir.resolve("demo.aegix"));
            String missing = dir.resolve("missing.aegix").toString();

            List<File> files = AppMain.parseOpenFiles(new String[] {exists.toString(), missing});

            assertEquals(1, files.size());
            assertEquals(exists.toFile().getAbsolutePath(), files.get(0).getAbsolutePath());
        } finally {
            Files.deleteIfExists(dir.resolve("demo.aegix"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    public void ignoresOptionsAndEmptyArguments() {
        List<File> files = AppMain.parseOpenFiles(new String[] {"-Dfoo=bar", "", null, "--help"});
        assertTrue(files.isEmpty());
    }

    @Test
    public void toleratesNullArgs() {
        assertTrue(AppMain.parseOpenFiles(null).isEmpty());
    }
}
