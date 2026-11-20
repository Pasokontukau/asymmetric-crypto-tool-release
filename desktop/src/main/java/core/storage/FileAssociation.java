package core.storage;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import core.util.LogManager;
import java.io.File;

/**
 * 把 {@code .aegix} 关联到本程序，让用户双击加密文件就能打开它。
 *
 * <p>只写当前用户的注册表（{@code HKCU\Software\Classes}），不需要管理员权限，
 * 也不复制或移动任何文件；便携版同样可用。卸载程序不会自动清理这些键，
 * 用户可在设置页取消关联。</p>
 *
 * <p>写入走 JNA 的 Win32 API，而不是 {@code reg.exe}：命令行里的引号会被
 * reg.exe 二次解析，含空格路径的打开命令必然失败。</p>
 */
public final class FileAssociation {

    public static final String EXTENSION = ".aegix";
    public static final String PROG_ID = "CryptoAssistant.aegix";
    public static final String TYPE_NAME = "Aegix 加密文件";

    private static final String EXT_KEY = "Software\\Classes\\" + EXTENSION;
    private static final String PROG_KEY = "Software\\Classes\\" + PROG_ID;

    private FileAssociation() {
    }

    /** 只有 Windows 有这套注册表关联。 */
    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 安装版（jpackage）自带 exe：直接调它。 */
    public static String buildExeCommand(String exePath) {
        return quote(exePath) + " \"%1\"";
    }

    /** 开发/绿色版：用 javaw 跑 fat jar。 */
    public static String buildJarCommand(String javaExe, String jarPath) {
        return quote(javaExe) + " -jar " + quote(jarPath) + " \"%1\"";
    }

    /** 当前运行方式对应的「打开命令」；拿不到可执行体时返回 null。 */
    public static String currentOpenCommand() {
        String exe = installedExe();
        if (exe != null) {
            return buildExeCommand(exe);
        }
        String jar = currentJar();
        String javaExe = javaExe();
        if (jar != null && javaExe != null) {
            return buildJarCommand(javaExe, jar);
        }
        return null;
    }

    /** 安装版 exe 路径（依赖 jpackage 的 {@code -Dapp.home}）。 */
    public static String installedExe() {
        String home = System.getProperty("app.home");
        if (home == null || home.isEmpty()) {
            return null;
        }
        File exe = new File(home, "CryptoAssistant.exe");
        return exe.isFile() ? exe.getAbsolutePath() : null;
    }

    public static boolean isAssociated() {
        if (!isSupported()) {
            return false;
        }
        try {
            return PROG_ID.equals(Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_CURRENT_USER, EXT_KEY, ""));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 写入关联；返回是否全部成功。 */
    public static boolean install() {
        if (!isSupported()) {
            return false;
        }
        String command = currentOpenCommand();
        if (command == null) {
            LogManager.warning("无法确定可执行文件路径，跳过 .aegix 关联");
            return false;
        }
        try {
            setDefault(EXT_KEY, PROG_ID);
            setDefault(PROG_KEY, TYPE_NAME);
            setDefault(PROG_KEY + "\\DefaultIcon", iconValue());
            setDefault(PROG_KEY + "\\shell\\open\\command", command);
            LogManager.info("已关联 " + EXTENSION + "：" + command);
            return true;
        } catch (RuntimeException e) {
            LogManager.error("写入 .aegix 关联失败", e);
            return false;
        }
    }

    /** 移除关联；返回是否删除成功（本来就没有也算成功）。 */
    public static boolean uninstall() {
        if (!isSupported()) {
            return false;
        }
        boolean ok = deleteTree(PROG_KEY);
        ok &= deleteTree(EXT_KEY);
        if (ok) {
            LogManager.info("已取消 " + EXTENSION + " 关联");
        }
        return ok;
    }

    private static void setDefault(String key, String value) {
        Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, key);
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "", value);
    }

    private static boolean deleteTree(String key) {
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, key)) {
                // jna-platform 5.14 没有递归删除，逐级先删子项
                for (String sub : Advapi32Util.registryGetKeys(WinReg.HKEY_CURRENT_USER, key)) {
                    deleteTree(key + "\\" + sub);
                }
                Advapi32Util.registryDeleteKey(WinReg.HKEY_CURRENT_USER, key);
            }
            return true;
        } catch (RuntimeException e) {
            LogManager.error("删除注册表项失败：" + key, e);
            return false;
        }
    }

    private static String iconValue() {
        String exe = installedExe();
        if (exe != null) {
            return quote(exe) + ",0";
        }
        String java = javaExe();
        return quote(java == null ? "javaw.exe" : java) + ",0";
    }

    private static String javaExe() {
        String home = System.getProperty("java.home");
        if (home == null) {
            return null;
        }
        File exe = new File(new File(home, "bin"), "javaw.exe");
        if (!exe.isFile()) {
            exe = new File(new File(home, "bin"), "java.exe");
        }
        return exe.isFile() ? exe.getAbsolutePath() : null;
    }

    private static String currentJar() {
        try {
            java.net.URL location = FileAssociation.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            File file = new File(location.toURI());
            if (file.isFile() && file.getName().toLowerCase().endsWith(".jar")) {
                return file.getAbsolutePath();
            }
        } catch (Exception e) {
            LogManager.warning("无法定位当前 jar", e);
        }
        return null;
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }
}
