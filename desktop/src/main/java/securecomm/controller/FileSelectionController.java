package securecomm.controller;

import core.util.FolderZipUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import securecomm.service.FileTypeClassifier;

/**
 * 文件选择的模型与规则（DR-3 步骤 3.1）：加入、去重、删除、判型、可打包判断与批量前置检查。
 *
 * <p>不碰 Swing，也不自己弹窗：删除临时归档的动作、是否为临时归档的判断都由面板注入，
 * controller 只管“该不该调用”。这样同一套规则可以直接写测试。</p>
 */
public final class FileSelectionController {

    /** 批量加/解密的拦路原因；文案由面板决定。 */
    public enum BatchBlocker {
        NONE,
        NO_KEY_PAIR,
        NO_RECIPIENT
    }

    /** 一次加入请求的分流结果。 */
    public static final class AddPlan {

        private final List<File> plainFiles;
        private final List<File> folders;
        private final List<File> missing;
        private final List<File> duplicates;

        AddPlan(List<File> plainFiles, List<File> folders, List<File> missing, List<File> duplicates) {
            this.plainFiles = Collections.unmodifiableList(plainFiles);
            this.folders = Collections.unmodifiableList(folders);
            this.missing = Collections.unmodifiableList(missing);
            this.duplicates = Collections.unmodifiableList(duplicates);
        }

        public List<File> getPlainFiles() {
            return plainFiles;
        }

        public List<File> getFolders() {
            return folders;
        }

        public List<File> getMissing() {
            return missing;
        }

        public List<File> getDuplicates() {
            return duplicates;
        }

        public boolean hasWork() {
            return !plainFiles.isEmpty() || !folders.isEmpty();
        }
    }

    /** 单个条目的展示信息，面板只负责把它放进标签。 */
    public static final class ItemView {

        private final File file;
        private final FileTypeClassifier.Kind kind;
        private final String displayName;
        private final String sizeText;
        private final String path;

        ItemView(File file, FileTypeClassifier.Kind kind, String displayName,
                 String sizeText, String path) {
            this.file = file;
            this.kind = kind;
            this.displayName = displayName;
            this.sizeText = sizeText;
            this.path = path;
        }

        public File getFile() {
            return file;
        }

        public FileTypeClassifier.Kind getKind() {
            return kind;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getSizeText() {
            return sizeText;
        }

        public String getPath() {
            return path;
        }

        public boolean isFolder() {
            return kind == FileTypeClassifier.Kind.FOLDER;
        }
    }

    private final List<File> files = new ArrayList<File>();
    private final Consumer<File> discardHook;
    private final Predicate<File> temporaryArchive;

    /**
     * @param discardHook       条目被移出选择时的清理（面板传临时归档的 unregister + 删除）
     * @param temporaryArchive  “是否临时归档”的判断（面板传 {@code TempFileManager::isZipFile}）
     */
    public FileSelectionController(Consumer<File> discardHook, Predicate<File> temporaryArchive) {
        this.discardHook = discardHook == null ? file -> { } : discardHook;
        this.temporaryArchive = temporaryArchive == null ? file -> false : temporaryArchive;
    }

    /** 只读视图；修改必须走本类方法，避免界面模型与选择模型不同步。 */
    public List<File> files() {
        return Collections.unmodifiableList(files);
    }

    public int size() {
        return files.size();
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    public boolean contains(File file) {
        return files.contains(file);
    }

    public void clear() {
        files.clear();
    }

    /** 加入一个条目；已存在时返回 {@code false}，保持原顺序。 */
    public boolean add(File file) {
        if (file == null || files.contains(file)) {
            return false;
        }
        files.add(file);
        return true;
    }

    /**
     * 把一次选择/拖入拆成普通文件、文件夹、缺失与重复。
     * 缺失项保持原来的“静默跳过”行为，由面板决定是否提示。
     */
    public AddPlan planAdd(File[] incoming) {
        List<File> plainFiles = new ArrayList<File>();
        List<File> folders = new ArrayList<File>();
        List<File> missing = new ArrayList<File>();
        List<File> duplicates = new ArrayList<File>();
        Set<File> seen = new LinkedHashSet<File>(files);

        if (incoming != null) {
            for (File file : incoming) {
                if (file == null || !file.exists()) {
                    if (file != null) {
                        missing.add(file);
                    }
                    continue;
                }
                if (!seen.add(file)) {
                    duplicates.add(file);
                    continue;
                }
                if (file.isDirectory()) {
                    folders.add(file);
                } else {
                    plainFiles.add(file);
                }
            }
        }
        return new AddPlan(plainFiles, folders, missing, duplicates);
    }

    /** 按界面索引删除（索引可以乱序）；被删条目逐个交给 discardHook。 */
    public List<File> removeByIndices(int[] indices) {
        List<File> removed = new ArrayList<File>();
        if (indices == null) {
            return removed;
        }
        List<Integer> sorted = new ArrayList<Integer>();
        for (int index : indices) {
            if (index >= 0 && index < files.size() && !sorted.contains(index)) {
                sorted.add(index);
            }
        }
        Collections.sort(sorted, Collections.reverseOrder());
        for (int index : sorted) {
            File file = files.remove(index);
            removed.add(file);
            discardHook.accept(file);
        }
        return removed;
    }

    /** 批量收尾：移除所有临时归档（文件夹 ZIP 等）并清理；新选择保留。 */
    public List<File> removeTemporaryArchives() {
        List<File> removed = new ArrayList<File>();
        for (int i = files.size() - 1; i >= 0; i--) {
            File file = files.get(i);
            if (temporaryArchive.test(file)) {
                files.remove(i);
                removed.add(file);
                discardHook.accept(file);
            }
        }
        return removed;
    }

    /**
     * 能否“打包成一个 ZIP”：含文件夹或临时归档时不行；至少两个普通文件才行。
     * 与 DR-1 之前的判断逐条等价。
     */
    public boolean canPackage() {
        int plainFiles = 0;
        for (File file : files) {
            if (file.isDirectory() || temporaryArchive.test(file)) {
                return false;
            }
            if (file.isFile()) {
                plainFiles++;
            }
        }
        return plainFiles >= 2;
    }

    /** 单条目的展示字段；{@code file == null} 时返回 {@code null}。 */
    public ItemView describe(File file) {
        if (file == null) {
            return null;
        }
        FileTypeClassifier.Kind kind = FileTypeClassifier.classify(file);
        long bytes = file.isDirectory()
                ? FolderZipUtils.calculateFolderSize(file)
                : file.length();
        File parent = file.getAbsoluteFile().getParentFile();
        return new ItemView(
                file,
                kind,
                file.getName(),
                formatSize(bytes),
                parent == null ? "" : parent.getAbsolutePath());
    }

    /** 批量操作前置条件；联系人最后使用时间等副作用仍由面板处理。 */
    public BatchBlocker validateBatch(String keyPairName, String otherSelection) {
        if (keyPairName == null || keyPairName.isEmpty()) {
            return BatchBlocker.NO_KEY_PAIR;
        }
        if (otherSelection == null || otherSelection.equals("选择联系人")) {
            return BatchBlocker.NO_RECIPIENT;
        }
        return BatchBlocker.NONE;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
