package securecomm.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

/** 判型只回答"是什么"，口径与现有 .aegix / 分卷命名保持一致。 */
public class FileTypeClassifierTest {

    @Test
    public void singleEncryptedFile() throws Exception {
        File dir = Files.createTempDirectory("aegix-classify-").toFile();
        File file = new File(dir, "secure_file_20260101_000000.aegix");
        assertTrue(file.createNewFile());

        assertEquals(FileTypeClassifier.Kind.ENCRYPTED_SINGLE, FileTypeClassifier.classify(file));
    }

    @Test
    public void externalPartFile() throws Exception {
        File dir = Files.createTempDirectory("aegix-classify-").toFile();
        File file = new File(dir, "secure_file_20260101_000000_ab12cd_vol002.aegix");
        assertTrue(file.createNewFile());

        assertEquals(FileTypeClassifier.Kind.EXTERNAL_PART, FileTypeClassifier.classify(file));
        assertTrue(FileTypeClassifier.isExternalPartName(file.getName()));
    }

    @Test
    public void plainTextFieldIsPlainFile() throws Exception {
        File dir = Files.createTempDirectory("aegix-classify-").toFile();
        File file = new File(dir, "note.txt");
        assertTrue(file.createNewFile());

        assertEquals(FileTypeClassifier.Kind.PLAIN_FILE, FileTypeClassifier.classify(file));
        assertFalse(FileTypeClassifier.isEncryptedFileName(file.getName()));
    }

    @Test
    public void folderIsFolder() throws Exception {
        File dir = Files.createTempDirectory("aegix-classify-").toFile();
        assertEquals(FileTypeClassifier.Kind.FOLDER, FileTypeClassifier.classify(dir));
    }

    @Test
    public void missingPathIsMissing() throws Exception {
        File dir = Files.createTempDirectory("aegix-classify-").toFile();
        assertEquals(FileTypeClassifier.Kind.MISSING,
                FileTypeClassifier.classify(new File(dir, "nope.aegix")));
        assertEquals(FileTypeClassifier.Kind.MISSING, FileTypeClassifier.classify(null));
    }

    @Test
    public void extensionCheckIgnoresCase() {
        assertTrue(FileTypeClassifier.isEncryptedFileName("X.AEGIX"));
        assertTrue(FileTypeClassifier.isEncryptedFileName("x.aegix"));
        assertFalse(FileTypeClassifier.isEncryptedFileName("x.aegix.tmp"));
        assertFalse(FileTypeClassifier.isEncryptedFileName(null));
    }

    @Test
    public void partNameRequiresThreeDigitIndex() {
        assertTrue(FileTypeClassifier.isExternalPartName("a_ab12cd_vol001.aegix"));
        assertFalse(FileTypeClassifier.isExternalPartName("a_ab12cd_vol01.aegix"));
        assertFalse(FileTypeClassifier.isExternalPartName("secure_file_20260101_000000.aegix"));
    }
}
