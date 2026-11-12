package core.util;

import crypto.key.KeyUtils;

public class ValidationUtils {

    /**
     * 验证参数不为 null
     * @param value 参数值
     * @param paramName 参数名称
     * @throws IllegalArgumentException 如果为 null
     */
    public static void notNull(Object value, String paramName) {
        if (value == null) {
            throw new IllegalArgumentException("参数 '" + paramName + "' 不能为 null");
        }
    }

    /**
     * 验证字符串不为 null 且不为空
     * @param value 字符串值
     * @param paramName 参数名称
     * @throws IllegalArgumentException 如果为 null 或空
     */
    public static void notNullOrEmpty(String value, String paramName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("参数 '" + paramName + "' 不能为 null 或空字符串");
        }
    }

    /**
     * 验证字节数组不为 null 且不为空
     * @param value 字节数组
     * @param paramName 参数名称
     * @throws IllegalArgumentException 如果为 null 或空
     */
    public static void notNullOrEmpty(byte[] value, String paramName) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("参数 '" + paramName + "' 不能为 null 或空数组");
        }
    }

    /**
     * 验证字符数组不为 null 且不为空
     * @param value 字符数组
     * @param paramName 参数名称
     * @throws IllegalArgumentException 如果为 null 或空
     */
    public static void notNullOrEmpty(char[] value, String paramName) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("参数 '" + paramName + "' 不能为 null 或空数组");
        }
    }

    /**
     * 验证整数在范围内
     * @param value 整数值
     * @param min 最小值（包含）
     * @param max 最大值（包含）
     * @param paramName 参数名称
     * @throws IllegalArgumentException 如果超出范围
     */
    public static void inRange(int value, int min, int max, String paramName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                "参数 '" + paramName + "' 值 " + value + " 不在有效范围 [" + min + ", " + max + "] 内");
        }
    }

    /**
     * 验证正数
     * @param value 值
     * @param paramName 参数名称
     * @throws IllegalArgumentException 如果 <= 0
     */
    public static void positive(int value, String paramName) {
        if (value <= 0) {
            throw new IllegalArgumentException("参数 '" + paramName + "' 必须为正数，当前值：" + value);
        }
    }

    /**
     * 验证 RSA 密钥强度
     * @param keySize 密钥长度
     * @throws IllegalArgumentException 如果密钥强度不足
     */
    public static void validRSAKeySize(int keySize) {
        ValidationResult result = KeyUtils.checkRSAKeyStrengthDetail(keySize);
        if (!result.isValid) {
            throw new IllegalArgumentException(result.errorMessage);
        }
    }

    /**
     * 安全检查状态返回
     */
    public static class ValidationResult {
        public final boolean isValid;
        public final String errorMessage;

        public ValidationResult(boolean isValid, String errorMessage) {
            this.isValid = isValid;
            this.errorMessage = errorMessage;
        }

        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult fail(String error) {
            return new ValidationResult(false, error);
        }
    }

    /**
     * 安全验证（不抛异常，返回结果）
     * @param validator 验证逻辑
     * @return 验证结果
     */
    public static ValidationResult safeValidate(Runnable validator) {
        try {
            validator.run();
            return ValidationResult.ok();
        } catch (IllegalArgumentException e) {
            return ValidationResult.fail(e.getMessage());
        }
    }
}
