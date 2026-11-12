package securecomm.ui.util;

import crypto.key.KeyUtils;

/**
 * 安全文本面板的字符串工具类
 */
public class SecureTextStringUtils {

    private SecureTextStringUtils() {}

    /**
     * 格式化文件大小为用户友好的字符串
     */
    public static String formatFileSizeForUI(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * 尝试将文本解码为 Base64 字节数组
     */
    public static byte[] tryDecodeBase64(String text) {
        if (text == null) return null;
        String stripped = text.replaceAll("\\s+", "");
        if (stripped.length() < 4) return null;
        if (!stripped.matches("[A-Za-z0-9+/=-]+")) return null;
        try {
            return KeyUtils.decodeBase64(stripped);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从异常中提取消息（递归查找 cause）
     */
    public static String getExceptionMessage(Throwable ex) {
        if (ex == null) {
            return "";
        }
        String message = ex.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            return message;
        }
        if (ex.getCause() != null) {
            return getExceptionMessage(ex.getCause());
        }
        return "";
    }

    /**
     * 根据异常返回友好的用户提示
     */
    public static String getUserFriendlyErrorMessage(Throwable ex) {
        String message = getExceptionMessage(ex);

        if (message.contains("无法读取加密数据")) {
            return "读取失败，请检查路径和权限";
        }

        if (message.contains("无法解析密文") || message.contains("Base64") || message.toLowerCase().contains("illegal base64")) {
            return "格式错误，请输入正确的密文格式";
        }

        if (message.contains("数据太短") || message.contains("格式无效") ||
            message.contains("签名长度") || message.contains("超出范围") ||
            message.contains("过大") || message.contains("无效")) {
            return "格式错误，请选择正确文件格式";
        }

        if (message.contains("密钥不匹配")) {
            return "解密失败，密钥对不正确或数据不完整";
        }

        if (message.contains("密文格式不正确")) {
            return "解密失败，加密数据已损坏";
        }

        return "解密失败，详情查看日志";
    }

    /**
     * 将 int 转换为 4 字节的大端字节数组
     */
    public static byte[] intToBytes(int value) {
        return new byte[] {
            (byte) (value >> 24),
            (byte) (value >> 16),
            (byte) (value >> 8),
            (byte) value
        };
    }

    /**
     * 将 4 字节的大端字节数组转换为 int
     */
    public static int bytesToInt(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24) |
               ((bytes[1] & 0xFF) << 16) |
               ((bytes[2] & 0xFF) << 8) |
               (bytes[3] & 0xFF);
    }
}
