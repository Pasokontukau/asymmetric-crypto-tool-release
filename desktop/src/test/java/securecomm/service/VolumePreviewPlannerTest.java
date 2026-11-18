package securecomm.service;

import java.io.File;
import java.io.RandomAccessFile;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VolumePreviewPlannerTest {

    private static final long LIMIT = 50_000_000L;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static java.security.PublicKey recipientKey;

    @BeforeClass
    public static void createKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        recipientKey = pair.getPublic();
    }

    private File sparse(String name, long bytes) throws Exception {
        File file = temp.newFile(name);
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(bytes);
        }
        return file;
    }

    private static VolumePreviewPlanner.ArchiveLookup lookup(
            Map<File, String> folderNames, Map<File, String> registered) {
        return new VolumePreviewPlanner.ArchiveLookup() {
            @Override
            public boolean isFolderZip(File file) {
                return folderNames.containsKey(file);
            }

            @Override
            public String folderName(File file) {
                return folderNames.get(file);
            }

            @Override
            public String registeredArchive(File file) {
                return registered.get(file);
            }
        };
    }

    @Test
    public void smallFilesDoNotProduceLines() throws Exception {
        File file = sparse("small.bin", 1024);

        VolumePreviewPlanner.Plan plan = VolumePreviewPlanner.plan(
                Collections.singletonList(file), false, false, "archive_1",
                recipientKey, LIMIT, lookup(Collections.emptyMap(), Collections.emptyMap()));

        assertTrue(plan.isEmpty());
        assertEquals(0, plan.getTotalParts());
        assertFalse(plan.isWarning());
    }

    @Test
    public void packagedFilesProduceOneSummaryLine() throws Exception {
        File first = sparse("a.bin", 40_000_000L);
        File second = sparse("b.bin", 40_000_000L);
        List<File> files = Arrays.asList(first, second);

        VolumePreviewPlanner.Plan plan = VolumePreviewPlanner.plan(
                files, false, true, "archive_20261112_090807",
                recipientKey, LIMIT, lookup(Collections.emptyMap(), Collections.emptyMap()));

        assertEquals(1, plan.getLines().size());
        String line = plan.getLines().get(0);
        assertTrue(line, line.startsWith("2 个文件（打包） → 预计 "));
        assertTrue(line, line.endsWith(" 卷"));
        assertTrue(plan.getTotalParts() >= 2);

        String message = plan.buildMessage("50 MB");
        assertTrue(message, message.startsWith("将按当前设置（50 MB/卷）生成："));
        assertTrue(message, message.endsWith("是否开始加密？"));
        assertTrue(message, message.contains("共 " + plan.getTotalParts() + " 卷。"));
    }

    @Test
    public void skippedEncryptedFilesAreNotCountedInPackagePreview() throws Exception {
        File encrypted = sparse("already.aegix", 40_000_000L);
        File plain = sparse("plain.bin", 80_000_000L);

        VolumePreviewPlanner.Plan plan = VolumePreviewPlanner.plan(
                Arrays.asList(encrypted, plain), true, true, "archive_1",
                recipientKey, LIMIT, lookup(Collections.emptyMap(), Collections.emptyMap()));

        assertEquals(1, plan.getLines().size());
        assertTrue(plan.getLines().get(0),
                plan.getLines().get(0).startsWith("1 个文件（打包） → 预计 "));
    }

    @Test
    public void folderZipUsesRegisteredFolderNameInPreview() throws Exception {
        File zip = sparse("aegix-folder-9.zip", 60_000_000L);
        Map<File, String> folders = new HashMap<File, String>();
        folders.put(zip, "季度 报告");

        VolumePreviewPlanner.Plan plan = VolumePreviewPlanner.plan(
                Collections.singletonList(zip), false, false, "archive_1",
                recipientKey, LIMIT, lookup(folders, Collections.emptyMap()));

        assertEquals(1, plan.getLines().size());
        String line = plan.getLines().get(0);
        assertTrue(line, line.startsWith("季度 报告（文件夹） → "));
        assertTrue(plan.getTotalParts() >= 2);
    }

    @Test
    public void registeredArchiveNameIsUsedForHeaderButFileNameForDisplay() throws Exception {
        File file = sparse("plain.bin", 60_000_000L);
        Map<File, String> registered = new HashMap<File, String>();
        registered.put(file, "registered.zip");

        VolumePreviewPlanner.Plan plan = VolumePreviewPlanner.plan(
                Collections.singletonList(file), false, false, "archive_1",
                recipientKey, LIMIT, lookup(Collections.emptyMap(), registered));

        assertEquals(1, plan.getLines().size());
        String line = plan.getLines().get(0);
        assertTrue(line, line.startsWith("plain.bin → "));
    }

    @Test
    public void warningAppearsAboveTwentyParts() {
        String message = new VolumePreviewPlanner.Plan(
                Collections.singletonList("x → 21 卷"), 21).buildMessage("50 MB");

        assertTrue(message, message.contains("卷数较多，建议增大分卷大小或减少文件。"));
        assertTrue(new VolumePreviewPlanner.Plan(
                Collections.singletonList("x → 20 卷"), 20).buildMessage("50 MB")
                .indexOf("卷数较多") < 0);
    }
}
