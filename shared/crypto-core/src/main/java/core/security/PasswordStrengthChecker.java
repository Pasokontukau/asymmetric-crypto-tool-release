package core.security;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class PasswordStrengthChecker {

    /**
     * 密码强度等级
     */
    public enum StrengthLevel {
        VERY_WEAK("非常弱", 0xFF0000),
        WEAK("弱", 0xFF6600),
        FAIR("一般", 0xFFCC00),
        STRONG("强", 0x00CC00),
        VERY_STRONG("非常强", 0x0099FF);

        private final String displayName;
        private final int color;

        StrengthLevel(String displayName, int color) {
            this.displayName = displayName;
            this.color = color;
        }

        public String getDisplayName() {
            return displayName;
        }

        public int getColor() {
            return color;
        }
    }

    /**
     * 密码强度检查结果
     */
    public static class StrengthResult {
        public final StrengthLevel level;
        public final int score;
        public final Set<String> issues;
        public final Set<String> recommendations;

        public StrengthResult(StrengthLevel level, int score, Set<String> issues, Set<String> recommendations) {
            this.level = level;
            this.score = score;
            this.issues = issues;
            this.recommendations = recommendations;
        }
    }

    // 常见弱密码列表（小写）
    private static final Set<String> COMMON_PASSWORDS = new HashSet<>(Arrays.asList(
            "password", "123456", "12345678", "123456789", "1234567890",
            "qwerty", "qwerty123", "qwerty12345", "abc123", "abcdef",
            "password1", "password123", "password123!", "admin", "admin123",
            "letmein", "welcome", "welcome1", "welcome123",
            "monkey", "dragon", "master", "111111", "000000",
            "password!", "p@ssword", "p@ssw0rd", "password1!",
            "iloveyou", "sunshine", "princess", "football", "secret",
            "changeme", "qwertyuiop", "asdfghjkl", "zxcvbnm",
            "1q2w3e4r", "1qaz2wsx", "qazwsx", "qweasd",
            "trustno1", "pa$$word", "Pa$$w0rd", "P@ssword",
            "login", "starwars", "access", "passw0rd", "hello",
            "shadow", "michael", "654321", "superman", "batman",
            "baseball", "hockey", "soccer", "dragon123",
            "letmein123", "whatever", "blahblah", "asdf1234",
            "pass123", "pass1234", "qwerty1", "admin1", "root",
            "administrator", "guest", "test", "test123", "test1234",
            "password12", "password1234", "pass", "pword", "temp",
            "temp123", "changeme123", "default"
    ));

    /**
     * 检查密码强度（char[] 版本）
     * @param password 密码字符数组
     * @return 强度检查结果
     */
    public static StrengthResult checkPasswordStrength(char[] password) {
        Set<String> issues = new HashSet<>();
        Set<String> recommendations = new HashSet<>();
        int score = 0;

        if (password == null || password.length == 0) {
            issues.add("密码不能为空");
            return new StrengthResult(StrengthLevel.VERY_WEAK, 0, issues, recommendations);
        }

        // 1. 长度检查
        int length = password.length;
        if (length < 12) {
            issues.add("密码长度至少12位");
            recommendations.add("建议使用16位以上的密码");
        } else if (length < 16) {
            score += 10;
        } else if (length < 24) {
            score += 30;
        } else {
            score += 40;
        }

        // 2. 大写字母检查
        boolean hasUpper = false;
        // 3. 小写字母检查
        boolean hasLower = false;
        // 4. 数字检查
        boolean hasDigit = false;
        // 5. 特殊字符检查
        boolean hasSpecial = false;
        
        for (char c : password) {
            if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isDigit(c)) hasDigit = true;
            else hasSpecial = true;
        }

        if (!hasUpper) {
            issues.add("缺少大写字母");
            recommendations.add("添加1个或多个大写字母");
        } else {
            score += 15;
        }

        if (!hasLower) {
            issues.add("缺少小写字母");
            recommendations.add("添加1个或多个小写字母");
        } else {
            score += 15;
        }

        if (!hasDigit) {
            issues.add("缺少数字");
            recommendations.add("添加1个或多个数字");
        } else {
            score += 15;
        }

        if (!hasSpecial) {
            issues.add("缺少特殊字符");
            recommendations.add("添加特殊字符，如 !@#$%^&* 等");
        } else {
            score += 15;
        }

        // 6. 常见弱密码检查（在char[]上检查）
        if (isCommonPassword(password)) {
            issues.add("这是一个常见的弱密码");
            recommendations.add("请避免使用常见密码");
            score = Math.max(0, score - 30);
        }

        // 7. 检查简单模式
        if (hasSequentialPatterns(password)) {
            issues.add("包含简单的顺序模式（如 123, abc）");
            recommendations.add("避免使用简单的顺序或重复字符");
            score = Math.max(0, score - 10);
        }

        if (hasRepeatedPatterns(password)) {
            issues.add("包含重复的字符模式");
            recommendations.add("避免重复的字符模式");
            score = Math.max(0, score - 10);
        }

        // 8. 多样性检查
        long uniqueChars = countUniqueChars(password);
        if (uniqueChars < 5) {
            issues.add("字符多样性不足");
            recommendations.add("使用更多不同的字符");
            score = Math.max(0, score - 5);
        }

        // 确定等级
        StrengthLevel level;
        if (score < 20) {
            level = StrengthLevel.VERY_WEAK;
        } else if (score < 40) {
            level = StrengthLevel.WEAK;
        } else if (score < 60) {
            level = StrengthLevel.FAIR;
        } else if (score < 80) {
            level = StrengthLevel.STRONG;
        } else {
            level = StrengthLevel.VERY_STRONG;
        }

        return new StrengthResult(level, score, issues, recommendations);
    }
    
    /**
     * 检查密码强度（String 版本）- 保持向后兼容
     * @param password 密码
     * @return 强度检查结果
     */
    public static StrengthResult checkPasswordStrength(String password) {
        if (password == null) {
            return checkPasswordStrength((char[]) null);
        }
        return checkPasswordStrength(password.toCharArray());
    }

    /**
     * 验证密码是否符合最低要求（char[] 版本）
     * @param password 密码
     * @return 验证结果，null表示通过，否则返回错误信息
     */
    public static String validatePasswordRequirements(char[] password) {
        if (password == null || password.length == 0) {
            return "密码不能为空";
        }
        if (password.length < 12) {
            return "密码长度至少12位";
        }
        
        boolean hasUpper = false;
        boolean hasLower = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;
        
        for (char c : password) {
            if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isDigit(c)) hasDigit = true;
            else hasSpecial = true;
        }
        
        if (!hasUpper) return "密码必须包含大写字母";
        if (!hasLower) return "密码必须包含小写字母";
        if (!hasDigit) return "密码必须包含数字";
        if (!hasSpecial) return "密码必须包含特殊字符";
        
        return null;
    }

    /**
     * 验证密码是否符合最低要求（String 版本）
     * @param password 密码
     * @return 验证结果，null表示通过，否则返回错误信息
     */
    public static String validatePasswordRequirements(String password) {
        if (password == null) {
            return validatePasswordRequirements((char[]) null);
        }
        return validatePasswordRequirements(password.toCharArray());
    }

    /**
     * 检查密码是否是常见弱密码（char[] 版本）
     */
    private static boolean isCommonPassword(char[] password) {
        // 转换为小写后检查
        char[] lowerPassword = new char[password.length];
        for (int i = 0; i < password.length; i++) {
            lowerPassword[i] = Character.toLowerCase(password[i]);
        }
        String lowerStr = new String(lowerPassword);
        return COMMON_PASSWORDS.contains(lowerStr);
    }

    /**
     * 检查是否有顺序模式（char[] 版本）
     */
    private static boolean hasSequentialPatterns(char[] password) {
        String sequentialChars = "abcdefghijklmnopqrstuvwxyz0123456789";
        
        // 检查3个或更多的连续字符
        for (int i = 0; i <= sequentialChars.length() - 3; i++) {
            char[] seq = new char[]{
                sequentialChars.charAt(i),
                sequentialChars.charAt(i + 1),
                sequentialChars.charAt(i + 2)
            };
            char[] reverseSeq = new char[]{seq[2], seq[1], seq[0]};
            
            if (containsSequence(password, seq) || containsSequence(password, reverseSeq)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否有重复模式（char[] 版本）
     */
    private static boolean hasRepeatedPatterns(char[] password) {
        // 检查同一字符重复3次以上
        for (int i = 0; i < password.length - 2; i++) {
            char c = password[i];
            if (password[i + 1] == c && password[i + 2] == c) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 检查字符数组中是否包含特定序列
     */
    private static boolean containsSequence(char[] source, char[] sequence) {
        if (sequence.length == 0 || source.length < sequence.length) {
            return false;
        }
        
        for (int i = 0; i <= source.length - sequence.length; i++) {
            boolean found = true;
            for (int j = 0; j < sequence.length; j++) {
                if (Character.toLowerCase(source[i + j]) != Character.toLowerCase(sequence[j])) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 统计字符数组中唯一字符的数量
     */
    private static long countUniqueChars(char[] password) {
        Set<Character> unique = new HashSet<>();
        for (char c : password) {
            unique.add(c);
        }
        return unique.size();
    }
}
