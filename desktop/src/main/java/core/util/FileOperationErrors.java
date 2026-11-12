package core.util;

import java.nio.file.FileSystemException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

/**
 * 把文件写入失败翻译成用户能处理的提示。
 *
 * <p>Windows 的 {@link java.io.IOException} 消息随系统语言变化，可能直接抛
 * “磁盘空间不足。”或“文件名或扩展名太长。”。界面不应把这类原始消息丢给用户，
 * 也不应只显示一个 IOException 类名。</p>
 */
public final class FileOperationErrors {

    private static final String[] DISK_FULL_MARKERS = {
            "not enough space",
            "no space left",
            "disk full",
            "磁盘空间不足",
            "空间不足",
            "磁盘已满"
    };

    private static final String[] PATH_TOO_LONG_MARKERS = {
            "too long",
            "filename or extension is too long",
            "file name too long",
            "文件名或扩展名太长",
            "路径过长",
            "path too long"
    };

    private static final String[] FILE_IN_USE_MARKERS = {
            "being used by another process",
            "used by another process",
            "sharing violation",
            "另一个程序正在使用",
            "文件正在被其他程序使用",
            "进程无法访问"
    };

    private static final String[] ACCESS_DENIED_MARKERS = {
            "access is denied",
            "access denied",
            "permission denied",
            "拒绝访问",
            "没有权限",
            "无权限"
    };

    private FileOperationErrors() {
    }

    public static boolean isDiskFull(Throwable error) {
        return containsMarker(error, DISK_FULL_MARKERS);
    }

    public static boolean isPathTooLong(Throwable error) {
        return containsMarker(error, PATH_TOO_LONG_MARKERS);
    }

    public static boolean isFileInUse(Throwable error) {
        return containsMarker(error, FILE_IN_USE_MARKERS);
    }

    public static boolean isAccessDenied(Throwable error) {
        return containsMarker(error, ACCESS_DENIED_MARKERS);
    }

    /**
     * 面向用户的失败提示；无法识别的异常保留原始 message，便于排查。
     */
    public static String userMessage(Throwable error) {
        if (isDiskFull(error)) {
            return "磁盘空间不足，请清理磁盘后重试。";
        }
        if (isPathTooLong(error)) {
            return "文件名或路径过长，请缩短文件名或改用更短的输出目录（Windows 单个路径最多 260 个字符）。";
        }
        if (isFileInUse(error)) {
            return "目标文件正在被其他程序使用，请关闭后重试。";
        }
        if (isAccessDenied(error)) {
            return "没有写入权限，请改选可写的输出目录，或检查文件是否被其他程序占用。";
        }
        String detail = error == null ? null : error.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            return "文件操作失败，请检查磁盘和权限后重试。";
        }
        return detail.trim();
    }

    private static boolean containsMarker(Throwable error, String[] markers) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable current = error;
        while (current != null && seen.add(current)) {
            if (containsMarker(current.getMessage(), markers)) {
                return true;
            }
            if (current instanceof FileSystemException) {
                FileSystemException systemException = (FileSystemException) current;
                if (containsMarker(systemException.getReason(), markers)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean containsMarker(String message, String[] markers) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        for (String marker : markers) {
            if (normalized.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
