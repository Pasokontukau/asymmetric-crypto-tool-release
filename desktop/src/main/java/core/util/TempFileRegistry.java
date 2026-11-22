package core.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 应用临时文件的登记器。
 *
 * <p>临时 ZIP、解密中间文件和文件夹暂存目录都可能含明文。正常退出时由 JVM
 * shutdown hook 删除；如果进程被强制结束或崩溃，shutdown hook 不会执行。
 * 本类在创建之前就把目标路径写入 {@code aegix-sessions/} 下的会话清单，并让
 * 会话持有一把进程锁。下次启动时只处理“锁已释放”的旧会话，因此不会误删
 * 另一个正在运行的实例的临时文件。</p>
 *
 * <p>清单只保存临时路径，不保存文件内容或原名。启动清理还会扫描旧版本在
 * {@code java.io.tmpdir} 直接留下的已知前缀，作为没有会话清单时的兜底。</p>
 */
public final class TempFileRegistry {

    private static final String SESSION_DIR_NAME = "aegix-sessions";
    private static final String SESSION_LOCK_NAME = "lock";
    private static final String SESSION_MANIFEST_NAME = "paths";
    private static final String SESSION_PREFIX = "session-";
    private static final String[] MANAGED_PREFIXES = {
            "aegix-folder-",
            "aegix-archive-",
            "aegix-decrypt-",
            "volu-decrypt-",
            "aegix-unzip-",
            "external-merge-",
            "aegix-write-",
            "aegix-temp-"
    };

    private static final Object LOCK = new Object();
    private static Session session;
    private static boolean shutdownHookInstalled;

    static {
        installShutdownHook();
    }

    private TempFileRegistry() {
    }

    /** 创建并登记一个临时文件；登记发生在文件创建之前。 */
    public static File createTempFile(String prefix, String suffix, File directory)
            throws IOException {
        return create(prefix, suffix, directory, false);
    }

    /** 创建并登记一个临时目录；登记发生在目录创建之前。 */
    public static File createTempDirectory(String prefix, File directory) throws IOException {
        return create(prefix, "", directory, true);
    }

    private static File create(String prefix, String suffix, File directory, boolean asDirectory)
            throws IOException {
        if (directory == null) {
            throw new IOException("临时目录不能为空");
        }
        if (prefix == null || prefix.trim().isEmpty()) {
            throw new IOException("临时文件前缀不能为空");
        }
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("无法创建临时目录");
        }
        if (!directory.isDirectory()) {
            throw new IOException("临时路径不是目录");
        }

        for (int attempt = 0; attempt < 8; attempt++) {
            String name = prefix + UUID.randomUUID() + (suffix == null ? "" : suffix);
            Path target = new File(directory, name).toPath().toAbsolutePath().normalize();
            Session current = session();
            if (Files.exists(target)) {
                continue;
            }
            current.record(target);
            try {
                if (asDirectory) {
                    Files.createDirectory(target);
                } else {
                    Files.createFile(target);
                }
                return target.toFile();
            } catch (FileAlreadyExistsException collision) {
                current.remove(target);
            } catch (IOException | RuntimeException error) {
                current.remove(target);
                throw error;
            }
        }
        throw new IOException("无法分配不冲突的临时文件名");
    }

    /** 登记一个已经由调用方创建的临时路径；正常流程优先使用 createTempFile。 */
    public static void track(File file) throws IOException {
        if (file == null) {
            return;
        }
        session().record(normalize(file));
    }

    /** 路径已经不再表示临时文件（已删除或已移动到最终位置）。 */
    public static void untrack(File file) {
        if (file == null) {
            return;
        }
        synchronized (LOCK) {
            if (session != null) {
                session.remove(normalize(file));
            }
        }
    }

    /** 删除临时路径；只有删除成功才移除登记，失败时留给下次启动重试。 */
    public static boolean delete(File file) {
        if (file == null) {
            return true;
        }
        boolean deleted = FolderZipUtils.safeDelete(file);
        if (deleted) {
            untrack(file);
        }
        return deleted;
    }

    /**
     * 把已登记的中间文件改名为调用方指定的临时名；新路径在移动前登记，
     * 这样跨过移动步骤也不会留下未登记的明文。
     */
    public static void moveTracked(File source, File target) throws IOException {
        if (source == null || target == null) {
            throw new IOException("临时文件路径不能为空");
        }
        Session current = session();
        Path from = normalize(source);
        Path to = normalize(target);
        current.record(to);
        try {
            Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException error) {
            current.remove(to);
            throw error;
        }
        current.remove(from);
    }

    /** 删除本进程登记的全部临时路径并结束会话。 */
    public static void cleanAll() {
        synchronized (LOCK) {
            if (session == null) {
                return;
            }
            Session current = session;
            session = null;
            current.cleanAndClose();
        }
    }

    /** 启动时清理上次运行留下的临时路径；返回实际删除的路径数量。 */
    public static int cleanupStale() {
        try {
            String raw = System.getProperty("java.io.tmpdir");
            Path root = Paths.get(raw == null || raw.trim().isEmpty() ? "." : raw)
                    .toAbsolutePath().normalize();
            return cleanupStale(root);
        } catch (RuntimeException ignored) {
            // 临时目录属性异常不能阻塞主窗口启动。
            return 0;
        }
    }

    /**
     * 清理指定临时根下的旧会话和旧版本前缀。可见性包内公开，供崩溃测试使用。
     */
    static int cleanupStale(Path tempRoot) {
        if (tempRoot == null) {
            return 0;
        }
        Path root = tempRoot.toAbsolutePath().normalize();
        Path sessions = root.resolve(SESSION_DIR_NAME);
        int deleted = 0;
        boolean active = false;

        if (Files.isDirectory(sessions)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessions)) {
                for (Path candidate : stream) {
                    if (!Files.isDirectory(candidate)) {
                        continue;
                    }
                    Recovery recovery = recover(candidate);
                    if (recovery == Recovery.ACTIVE) {
                        active = true;
                    } else {
                        deleted += recovery.deleted;
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // 清理失败不能阻塞启动；下次继续尝试。
                return deleted;
            }
            try {
                Files.deleteIfExists(sessions);
            } catch (IOException ignored) {
                // 目录非空或仍被占用时保持原样。
            }
        }

        if (!active) {
            deleted += cleanupLegacyRoot(root);
        }
        return deleted;
    }

    private static int cleanupLegacyRoot(Path root) {
        int deleted = 0;
        File[] entries = root.toFile().listFiles();
        if (entries == null) {
            return 0;
        }
        for (File entry : entries) {
            if (isManagedName(entry.getName()) && FolderZipUtils.safeDelete(entry)) {
                deleted++;
            }
        }
        return deleted;
    }

    private static Recovery recover(Path sessionDir) {
        Path lockPath = sessionDir.resolve(SESSION_LOCK_NAME);
        Path manifest = sessionDir.resolve(SESSION_MANIFEST_NAME);
        RandomAccessFile lockFile = null;
        FileChannel channel = null;
        FileLock lock = null;
        try {
            if (Files.isRegularFile(lockPath)) {
                lockFile = new RandomAccessFile(lockPath.toFile(), "rw");
                channel = lockFile.getChannel();
                lock = channel.tryLock();
                if (lock == null) {
                    return Recovery.ACTIVE;
                }
            }

            int deleted = 0;
            for (Path path : readManifest(manifest)) {
                if (deleteManaged(path)) {
                    deleted++;
                }
            }
            closeQuietly(lock, channel, lockFile);
            lock = null;
            channel = null;
            lockFile = null;
            FolderZipUtils.safeDelete(sessionDir.toFile());
            return Recovery.deleted(deleted);
        } catch (OverlappingFileLockException activeInThisJvm) {
            return Recovery.ACTIVE;
        } catch (IOException | RuntimeException ignored) {
            // 锁或清单读不到时保守跳过，不碰可能仍在使用的文件。
            return Recovery.ACTIVE;
        } finally {
            closeQuietly(lock, channel, lockFile);
        }
    }

    private static List<Path> readManifest(Path manifest) {
        List<Path> paths = new ArrayList<>();
        if (!Files.isRegularFile(manifest)) {
            return paths;
        }
        try {
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                try {
                    String decoded = new String(
                            Base64.getDecoder().decode(line.trim()),
                            StandardCharsets.UTF_8);
                    paths.add(Paths.get(decoded));
                } catch (IllegalArgumentException ignored) {
                    // 损坏或非本类写的行不影响其它条目。
                }
            }
        } catch (IOException ignored) {
            // 清单不可读时只能保守跳过。
        }
        return paths;
    }

    private static boolean deleteManaged(Path path) {
        if (path == null || path.getFileName() == null
                || !isManagedName(path.getFileName().toString())) {
            return true;
        }
        return FolderZipUtils.safeDelete(path.toFile());
    }

    static boolean isManagedName(String name) {
        if (name == null) {
            return false;
        }
        for (String prefix : MANAGED_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static Session session() throws IOException {
        synchronized (LOCK) {
            if (session == null) {
                String raw = System.getProperty("java.io.tmpdir");
                Path root = Paths.get(raw == null || raw.trim().isEmpty() ? "." : raw)
                        .toAbsolutePath().normalize();
                session = Session.create(root);
            }
            return session;
        }
    }

    private static Path normalize(File file) {
        return file.toPath().toAbsolutePath().normalize();
    }

    private static void installShutdownHook() {
        synchronized (LOCK) {
            if (shutdownHookInstalled) {
                return;
            }
            shutdownHookInstalled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(
                    TempFileRegistry::cleanAll, "TempFileRegistry-Shutdown"));
        }
    }

    private static void closeQuietly(FileLock lock, FileChannel channel, RandomAccessFile file) {
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException ignored) {
                // 退出/清理路径不再抛错。
            }
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // 同上。
            }
        }
        if (file != null) {
            try {
                file.close();
            } catch (IOException ignored) {
                // 同上。
            }
        }
    }

    private static final class Recovery {
        static final Recovery ACTIVE = new Recovery(0);

        final int deleted;

        private Recovery(int deleted) {
            this.deleted = deleted;
        }

        static Recovery deleted(int count) {
            return new Recovery(count);
        }
    }

    private static final class Session {
        private final Path directory;
        private final RandomAccessFile lockFile;
        private final FileChannel channel;
        private final FileLock lock;
        private final FileOutputStream manifest;
        private final Set<Path> paths = new LinkedHashSet<>();
        private boolean closed;

        private Session(
                Path directory,
                RandomAccessFile lockFile,
                FileChannel channel,
                FileLock lock,
                FileOutputStream manifest
        ) {
            this.directory = directory;
            this.lockFile = lockFile;
            this.channel = channel;
            this.lock = lock;
            this.manifest = manifest;
        }

        static Session create(Path tempRoot) throws IOException {
            Path sessions = tempRoot.resolve(SESSION_DIR_NAME);
            Files.createDirectories(sessions);
            Path directory = sessions.resolve(SESSION_PREFIX + UUID.randomUUID());
            Files.createDirectory(directory);

            RandomAccessFile lockFile = null;
            FileChannel channel = null;
            FileLock lock = null;
            FileOutputStream manifest = null;
            try {
                Path lockPath = directory.resolve(SESSION_LOCK_NAME);
                lockFile = new RandomAccessFile(lockPath.toFile(), "rw");
                channel = lockFile.getChannel();
                lock = channel.tryLock();
                if (lock == null) {
                    throw new IOException("临时文件会话已被其他实例占用");
                }
                manifest = new FileOutputStream(
                        directory.resolve(SESSION_MANIFEST_NAME).toFile(), true);
                return new Session(directory, lockFile, channel, lock, manifest);
            } catch (IOException | RuntimeException error) {
                TempFileRegistry.closeQuietly(lock, channel, lockFile);
                closeQuietly(manifest);
                FolderZipUtils.safeDelete(directory.toFile());
                throw error;
            }
        }

        synchronized void record(Path path) throws IOException {
            if (closed) {
                throw new IOException("临时文件会话已关闭");
            }
            if (!paths.add(path)) {
                return;
            }
            try {
                String encoded = Base64.getEncoder().encodeToString(
                        path.toString().getBytes(StandardCharsets.UTF_8));
                manifest.write(encoded.getBytes(StandardCharsets.US_ASCII));
                manifest.write('\n');
                manifest.flush();
                manifest.getFD().sync();
            } catch (IOException error) {
                paths.remove(path);
                throw error;
            }
        }

        synchronized void remove(Path path) {
            paths.remove(path);
        }

        synchronized void cleanAndClose() {
            boolean allDeleted = true;
            for (Path path : new ArrayList<>(paths)) {
                if (!deleteManaged(path)) {
                    allDeleted = false;
                }
            }
            paths.clear();
            closeQuietly(manifest);
            closed = true;
            TempFileRegistry.closeQuietly(lock, channel, lockFile);
            if (allDeleted) {
                FolderZipUtils.safeDelete(directory.toFile());
            }
        }

        private static void closeQuietly(FileOutputStream stream) {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // 清理路径不抛错。
                }
            }
        }
    }
}
