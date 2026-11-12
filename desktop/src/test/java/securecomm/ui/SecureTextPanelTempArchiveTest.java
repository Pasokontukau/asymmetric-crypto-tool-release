package securecomm.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import org.junit.After;
import org.junit.Test;
import securecomm.ui.util.TempFileManager;

/** 文件夹临时 ZIP 的原名只从内存映射取，不能靠随机临时文件名反推。 */
public class SecureTextPanelTempArchiveTest {

    @After
    public void cleanUp() {
        TempFileManager.cleanAll();
    }

    @Test
    public void folderZipUsesRegisteredFolderName() throws Exception {
        File tempZip = File.createTempFile("aegix-folder-", ".zip");
        tempZip.deleteOnExit();

        TempFileManager.registerFolderZip(tempZip, "季度 报告");

        assertTrue("登记后应识别为文件夹 ZIP", TempFileManager.isFolderZip(tempZip));
        assertEquals("季度 报告", TempFileManager.getFolderName(tempZip));
        assertEquals("文件夹名应来自内存映射", "季度 报告",
                SecureTextPanel.folderNameForZip(tempZip));
        assertFalse("临时文件名不能包含原文件夹名",
                tempZip.getName().contains("季度 报告"));
    }
}
