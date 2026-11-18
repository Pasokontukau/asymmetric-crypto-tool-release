package securecomm.controller;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FileSelectionControllerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final List<File> discarded = new ArrayList<File>();

    private FileSelectionController controller(File... temporaryArchives) {
        List<File> archives = Arrays.asList(temporaryArchives);
        return new FileSelectionController(discarded::add, archives::contains);
    }

    @Test
    public void planAddSplitsPlainFilesFoldersMissingAndDuplicates() throws IOException {
        File existing = temp.newFile("existing.txt");
        File plain = temp.newFile("plain.txt");
        File folder = temp.newFolder("folder");
        File missing = new File(temp.getRoot(), "nope.txt");

        FileSelectionController controller = controller();
        controller.add(existing);

        FileSelectionController.AddPlan plan = controller.planAdd(
                new File[]{plain, folder, missing, plain, existing, null});

        assertEquals(Arrays.asList(plain), plan.getPlainFiles());
        assertEquals(Arrays.asList(folder), plan.getFolders());
        assertEquals(Arrays.asList(missing), plan.getMissing());
        assertEquals(Arrays.asList(plain, existing), plan.getDuplicates());
        assertTrue(plan.hasWork());
    }

    @Test
    public void addIsIdempotentAndKeepsOrder() throws IOException {
        File first = temp.newFile("first.txt");
        File second = temp.newFile("second.txt");

        FileSelectionController controller = controller();

        assertTrue(controller.add(first));
        assertTrue(controller.add(second));
        assertFalse(controller.add(first));
        assertFalse(controller.add(null));

        assertEquals(Arrays.asList(first, second), controller.files());
        assertEquals(2, controller.size());
        assertTrue(controller.contains(first));
    }

    @Test
    public void removeByIndicesHandlesUnsortedIndicesAndDiscardsOnlyRemoved() throws IOException {
        File first = temp.newFile("first.txt");
        File second = temp.newFile("second.txt");
        File third = temp.newFile("third.txt");
        FileSelectionController controller = controller(second);

        controller.add(first);
        controller.add(second);
        controller.add(third);

        List<File> removed = controller.removeByIndices(new int[]{2, 1, 99, -1});

        assertEquals(Arrays.asList(third, second), removed);
        assertEquals(Arrays.asList(first), controller.files());
        assertEquals(Arrays.asList(third, second), discarded);
    }

    @Test
    public void removeTemporaryArchivesKeepsPlainFiles() throws IOException {
        File archive = temp.newFile("aegix-folder-1.zip");
        File plain = temp.newFile("plain.txt");
        FileSelectionController controller = controller(archive);

        controller.add(archive);
        controller.add(plain);

        assertEquals(Arrays.asList(archive), controller.removeTemporaryArchives());
        assertEquals(Arrays.asList(plain), controller.files());
        assertEquals(Arrays.asList(archive), discarded);
    }

    @Test
    public void canPackageNeedsAtLeastTwoPlainFilesAndNoFolderOrArchive() throws IOException {
        File one = temp.newFile("one.txt");
        File two = temp.newFile("two.txt");
        File archive = temp.newFile("aegix-archive-1.zip");
        File folder = temp.newFolder("folder");

        FileSelectionController controller = controller(archive);
        assertFalse(controller.canPackage());

        controller.add(one);
        assertFalse(controller.canPackage());

        controller.add(two);
        assertTrue(controller.canPackage());

        controller.add(folder);
        assertFalse(controller.canPackage());

        FileSelectionController withArchive = controller(archive);
        withArchive.add(one);
        withArchive.add(two);
        withArchive.add(archive);
        assertFalse(withArchive.canPackage());
    }

    @Test
    public void describeFormatsSizeNameAndPath() throws IOException {
        File file = temp.newFile("report.txt");
        Files.write(file.toPath(), new byte[1536]);

        FileSelectionController.ItemView view = controller().describe(file);

        assertEquals("report.txt", view.getDisplayName());
        assertEquals("1.5 KB", view.getSizeText());
        assertEquals(temp.getRoot().getAbsolutePath(), view.getPath());
        assertFalse(view.isFolder());
        assertNull(controller().describe(null));
    }

    @Test
    public void validateBatchReportsWhichPrerequisiteIsMissing() {
        FileSelectionController controller = controller();

        assertEquals(FileSelectionController.BatchBlocker.NO_KEY_PAIR,
                controller.validateBatch(null, "alice"));
        assertEquals(FileSelectionController.BatchBlocker.NO_KEY_PAIR,
                controller.validateBatch("", "alice"));
        assertEquals(FileSelectionController.BatchBlocker.NO_RECIPIENT,
                controller.validateBatch("my-key", "选择联系人"));
        assertEquals(FileSelectionController.BatchBlocker.NO_RECIPIENT,
                controller.validateBatch("my-key", null));
        assertEquals(FileSelectionController.BatchBlocker.NONE,
                controller.validateBatch("my-key", "联系人 - alice"));
    }
}
