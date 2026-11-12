import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import core.util.FolderZipUtils;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 多文件打包：条目只保留文件名，重名自动加后缀，内容不被改写。 */
public class FolderZipUtilsTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void zipsFilesAndRenamesDuplicateEntryNames() throws Exception {
        File firstDir = temporaryFolder.newFolder("a");
        File secondDir = temporaryFolder.newFolder("b");
        File first = new File(firstDir, "note.txt");
        File second = new File(secondDir, "note.txt");
        byte[] firstBytes = "first".getBytes("UTF-8");
        byte[] secondBytes = "second".getBytes("UTF-8");
        Files.write(first.toPath(), firstBytes);
        Files.write(second.toPath(), secondBytes);

        File zip = temporaryFolder.newFile("batch.zip");
        String rootName = "archive_20261110_120000";
        FolderZipUtils.zipFiles(Arrays.asList(first, second), zip, rootName, null);

        try (ZipFile opened = new ZipFile(zip)) {
            ZipEntry root = opened.getEntry(rootName + "/");
            ZipEntry original = opened.getEntry(rootName + "/note.txt");
            ZipEntry renamed = opened.getEntry(rootName + "/note_2.txt");
            assertNotNull("归档根目录应与 Android 一致", root);
            assertNotNull(original);
            assertNotNull(renamed);
            assertArrayEquals(firstBytes, readAll(opened.getInputStream(original)));
            assertArrayEquals(secondBytes, readAll(opened.getInputStream(renamed)));
            assertEquals(3, opened.size());
        }
    }

    @Test
    public void folderZipPreservesEmptyDirectories() throws Exception {
        File folder = temporaryFolder.newFolder("Docs");
        assertTrue(new File(folder, "empty/sub").mkdirs());
        Files.write(new File(folder, "note.txt").toPath(), "x".getBytes(StandardCharsets.UTF_8));

        File zip = temporaryFolder.newFile("docs.zip");
        FolderZipUtils.zipFolder(folder, zip, null);
        try (ZipFile opened = new ZipFile(zip)) {
            assertNotNull("空目录必须作为独立条目写入", opened.getEntry("Docs/empty/"));
            assertNotNull(opened.getEntry("Docs/empty/sub/"));
        }

        File destination = temporaryFolder.newFolder("unpacked");
        FolderZipUtils.unzipFolder(zip, destination, null);
        assertTrue(new File(destination, "Docs/empty/sub").isDirectory());
    }

    @Test
    public void unzipRenamesExistingFilesInsteadOfOverwriting() throws Exception {
        File folder = temporaryFolder.newFolder("Docs2");
        Files.write(new File(folder, "note.txt").toPath(),
                "new".getBytes(StandardCharsets.UTF_8));
        File zip = temporaryFolder.newFile("docs2.zip");
        FolderZipUtils.zipFolder(folder, zip, null);

        File destination = temporaryFolder.newFolder("existing");
        File existing = new File(destination, "Docs2/note.txt");
        existing.getParentFile().mkdirs();
        Files.write(existing.toPath(), "old".getBytes(StandardCharsets.UTF_8));

        FolderZipUtils.unzipFolder(zip, destination, null);

        assertEquals("已有文件不能被覆盖", "old",
                new String(Files.readAllBytes(existing.toPath()), StandardCharsets.UTF_8));
        assertEquals("new", new String(Files.readAllBytes(
                new File(destination, "Docs2/note_2.txt").toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void unzipRejectsPathTraversalAndLeavesNoFileOutside() throws Exception {
        File zip = temporaryFolder.newFile("evil.zip");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("../evil.txt"));
            output.write("evil".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        File destination = temporaryFolder.newFolder("safe");
        try {
            FolderZipUtils.unzipFolder(zip, destination, null);
            fail("路径穿越应被拒绝");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("绝对路径")
                    || expected.getMessage().contains("相对路径")
                    || expected.getMessage().contains("遍历"));
        }
        assertFalse(new File(temporaryFolder.getRoot(), "evil.txt").exists());
    }

    @Test
    public void unzipRejectsAbsoluteDriveBackslashControlAndDeepPaths() throws Exception {
        StringBuilder deep = new StringBuilder();
        for (int index = 0; index <= 32; index++) {
            deep.append("d").append(index).append('/');
        }
        deep.append("file.txt");

        String[] unsafeNames = {
                "/absolute.txt",
                "C:/evil.txt",
                "a\\b.txt",
                "a/\u0001bad.txt",
                "a//b.txt",
                deep.toString()
        };

        for (int index = 0; index < unsafeNames.length; index++) {
            String name = unsafeNames[index];
            File zip = temporaryFolder.newFile("unsafe-" + index + ".zip");
            try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(zip))) {
                output.putNextEntry(new ZipEntry(name));
                output.write("evil".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }

            File destination = temporaryFolder.newFolder("unsafe-out-" + index);
            try {
                FolderZipUtils.unzipFolder(zip, destination, null);
                fail("恶意条目应被拒绝: " + name);
            } catch (java.io.IOException expected) {
                // 预期：在写任何目标文件前拒绝。
            }
            File[] left = destination.listFiles();
            assertTrue("拒绝后不能留下文件: " + name, left == null || left.length == 0);
        }
    }

    @Test
    public void unzipRejectsTooManyEntries() throws Exception {
        File zip = temporaryFolder.newFile("many.zip");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(zip))) {
            for (int index = 0; index <= FolderZipUtils.MAX_ENTRIES; index++) {
                output.putNextEntry(new ZipEntry("entry-" + index + ".txt"));
                output.closeEntry();
            }
        }

        try {
            FolderZipUtils.unzipFolder(zip, temporaryFolder.newFolder("many-out"), null);
            fail("超过条目上限应被拒绝");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("条目"));
        }
    }

    @Test
    public void randomMutationsNeverWriteOutsideTheDestination() throws Exception {
        File original = temporaryFolder.newFile("mutation-original.zip");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(original))) {
            output.putNextEntry(new ZipEntry("Photos/"));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("Photos/a.txt"));
            output.write("alpha".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("Photos/sub/b.txt"));
            output.write(new byte[64]);
            output.closeEntry();
        }
        byte[] originalBytes = Files.readAllBytes(original.toPath());
        Random random = new Random(20261111L);

        for (int round = 0; round < 40; round++) {
            byte[] mutated;
            switch (random.nextInt(3)) {
                case 0:
                    mutated = originalBytes.clone();
                    mutated[random.nextInt(mutated.length)] = (byte) random.nextInt(256);
                    break;
                case 1:
                    mutated = originalBytes.clone();
                    for (int index = 0; index < 1 + random.nextInt(4); index++) {
                        mutated[random.nextInt(mutated.length)] = 0;
                    }
                    break;
                default:
                    mutated = Arrays.copyOf(originalBytes, random.nextInt(originalBytes.length));
                    break;
            }

            File sandbox = temporaryFolder.newFolder("desktop-mutation-" + round);
            File destination = new File(sandbox, "out");
            assertTrue(destination.mkdirs());
            File zip = new File(sandbox, "mutated.zip");
            Files.write(zip.toPath(), mutated);

            try {
                FolderZipUtils.unzipFolder(zip, destination, null);
            } catch (Exception expected) {
                // 结构损坏/安全拒绝都允许；关键是不得写出目标目录。
            }

            File[] unexpected = sandbox.listFiles((dir, name) ->
                    !name.equals("out") && !name.equals("mutated.zip"));
            assertNotNull(unexpected);
            assertEquals("第 " + round + " 轮在目标目录外写了文件", 0, unexpected.length);

            String root = destination.getCanonicalPath();
            int files = 0;
            Path rootPath = destination.toPath();
            try (java.util.stream.Stream<Path> walk = Files.walk(rootPath)) {
                for (Path path : (Iterable<Path>) walk::iterator) {
                    assertTrue("第 " + round + " 轮路径越界: " + path,
                            path.toFile().getCanonicalPath().startsWith(root + File.separator)
                                    || path.toFile().getCanonicalPath().equals(root));
                    if (Files.isRegularFile(path)) {
                        files++;
                    }
                }
            }
            assertTrue("成功解压的实际文件数不能超过上限", files <= FolderZipUtils.MAX_ENTRIES);
        }
    }

    private static byte[] readAll(InputStream input) throws Exception {
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
            return output.toByteArray();
        }
    }
}
