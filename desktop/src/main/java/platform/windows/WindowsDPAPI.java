package platform.windows;

import com.sun.jna.Memory;
import com.sun.jna.platform.win32.Crypt32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinCrypt;
import core.util.LogManager;
import java.lang.RuntimeException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class WindowsDPAPI {
    
    private static final String SERVICE_NAME = "AsymmetricEncryptionTool";
    
    /**
     * 检查是否是 Windows 系统
     */
    public static boolean isWindows() {
        String osName = System.getProperty("os.name");
        return osName != null && osName.toLowerCase().startsWith("windows");
    }
    
    // 缓存可用性检查结果
    private static volatile Boolean isAvailableCache = null;
    
    /**
     * 检查是否可以使用 Windows DPAPI
     */
    public static boolean isAvailable() {
        if (!isWindows()) {
            return false;
        }
        
        // 检查缓存
        if (isAvailableCache != null) {
            return isAvailableCache;
        }
        
        try {
            // 1. 尝试加载相关类
            Class.forName("com.sun.jna.platform.win32.Crypt32");
            
            // 2. 尝试一个简单的加密/解密测试
            byte[] testData = new byte[] { (byte) 0x01, (byte) 0x02, (byte) 0x03 };
            byte[] encrypted = encrypt(testData);
            byte[] decrypted = decrypt(encrypted);
            
            // 验证结果
            boolean testPassed = java.util.Arrays.equals(testData, decrypted);
            
            isAvailableCache = testPassed;
            
            if (!testPassed) {
                LogManager.warning("Windows DPAPI 测试失败（加解密不一致）");
            }
            
            return testPassed;
        } catch (Throwable e) {
            LogManager.warning("Windows DPAPI 不可用", e);
            isAvailableCache = false;
            return false;
        }
    }
    
    /**
     * 清除可用性检查缓存
     */
    public static void clearAvailableCache() {
        isAvailableCache = null;
    }
    
    /**
     * 使用 Windows DPAPI 加密数据
     * @param data 要加密的数据
     * @return 加密后的数据
     */
    public static byte[] encrypt(byte[] data) throws Exception {
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        
        // 准备输入数据结构
        WinCrypt.DATA_BLOB dataIn = new WinCrypt.DATA_BLOB();
        dataIn.cbData = data.length;
        dataIn.pbData = new Memory(data.length);
        dataIn.pbData.write(0, data, 0, data.length);
        
        // 准备输出数据结构
        WinCrypt.DATA_BLOB dataOut = new WinCrypt.DATA_BLOB();
        
        // 使用 CryptProtectData 加密
        boolean success = Crypt32.INSTANCE.CryptProtectData(
            dataIn,               // 输入数据
            SERVICE_NAME,         // 描述
            null,                 // 额外熵
            null,                 // 保留
            null,                 // 提示参数
            WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,  // 禁止显示UI
            dataOut);             // 输出
        
        if (!success) {
            int err = Kernel32.INSTANCE.GetLastError();
            throw new RuntimeException("DPAPI 加密失败，错误码: " + err);
        }
        
        // 复制输出数据
        byte[] result = dataOut.pbData.getByteArray(0, dataOut.cbData);
        
        // 释放 Windows 分配的内存
        Kernel32.INSTANCE.LocalFree(dataOut.pbData);
        
        LogManager.debug("DPAPI 加密成功，数据长度: " + result.length);
        
        return result;
    }
    
    /**
     * 使用 Windows DPAPI 解密数据
     * @param encryptedData 加密的数据
     * @return 解密后的数据
     */
    public static byte[] decrypt(byte[] encryptedData) throws Exception {
        if (encryptedData == null || encryptedData.length == 0) {
            return new byte[0];
        }
        
        // 准备输入数据结构
        WinCrypt.DATA_BLOB dataIn = new WinCrypt.DATA_BLOB();
        dataIn.cbData = encryptedData.length;
        dataIn.pbData = new Memory(encryptedData.length);
        dataIn.pbData.write(0, encryptedData, 0, encryptedData.length);
        
        // 准备输出数据结构
        WinCrypt.DATA_BLOB dataOut = new WinCrypt.DATA_BLOB();
        
        // 使用 CryptUnprotectData 解密
        boolean success = Crypt32.INSTANCE.CryptUnprotectData(
            dataIn,               // 输入数据
            null,                 // 描述（不关心）
            null,                 // 额外熵
            null,                 // 保留
            null,                 // 提示参数
            WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,  // 禁止显示UI
            dataOut);             // 输出
        
        if (!success) {
            int err = Kernel32.INSTANCE.GetLastError();
            throw new RuntimeException("DPAPI 解密失败，错误码: " + err);
        }
        
        // 复制输出数据
        byte[] result = dataOut.pbData.getByteArray(0, dataOut.cbData);
        
        // 释放 Windows 分配的内存
        Kernel32.INSTANCE.LocalFree(dataOut.pbData);
        
        LogManager.debug("DPAPI 解密成功，数据长度: " + result.length);
        
        return result;
    }
    
    /**
     * 加密字符串
     */
    public static byte[] encryptString(String str) throws Exception {
        return encrypt(str.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * 解密字符串
     */
    public static String decryptString(byte[] encrypted) throws Exception {
        byte[] decrypted = decrypt(encrypted);
        return new String(decrypted, StandardCharsets.UTF_8);
    }
}
