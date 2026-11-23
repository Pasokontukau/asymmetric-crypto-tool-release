package core.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import core.security.PasswordStrengthChecker.StrengthLevel;
import core.security.PasswordStrengthChecker.StrengthResult;
import org.junit.Test;

/**
 * 口令强度判定（K-11）。
 *
 * <p>这是用户判断“这个口令够不够强”的唯一依据，判错会直接导致弱口令被当成强口令。
 * 用例只断言“必须成立”的语义，不锁死具体分数，避免以后调整权重时无谓失败。</p>
 */
public class PasswordStrengthCheckerTest {

    private static StrengthResult check(String password) {
        return PasswordStrengthChecker.checkPasswordStrength(password);
    }

    @Test
    public void nullAndEmptyAreVeryWeakWithIssue() {
        for (String candidate : new String[] {null, ""}) {
            StrengthResult result = PasswordStrengthChecker.checkPasswordStrength(
                    candidate == null ? null : candidate.toCharArray());
            assertEquals(StrengthLevel.VERY_WEAK, result.level);
            assertEquals(0, result.score);
            assertTrue(result.issues.contains("密码不能为空"));
        }
    }

    @Test
    public void commonPasswordsAreVeryWeak() {
        String[] common = {"123456", "12345678", "password", "qwerty", "admin", "P@ssw0rd"};
        for (String candidate : common) {
            StrengthResult result = check(candidate);
            assertEquals("常见弱口令应判为非常弱: " + candidate, StrengthLevel.VERY_WEAK, result.level);
            assertTrue("应提示常见弱密码: " + candidate, result.issues.contains("这是一个常见的弱密码"));
        }
    }

    @Test
    public void shortPasswordsNeverReachStrong() {
        // 11 位、四类字符齐全，仍然必须被长度拖住。
        StrengthResult result = check("Aa1!Bb2@Cc3");
        assertTrue("11 位口令不应达到强", result.score < 60);
        assertTrue(result.issues.contains("密码长度至少12位"));
        assertFalse(result.level == StrengthLevel.STRONG);
        assertFalse(result.level == StrengthLevel.VERY_STRONG);
    }

    @Test
    public void strongPassphraseReachesVeryStrong() {
        StrengthResult result = check("Kq7!mZ2#vL9$xR4&");
        assertEquals(StrengthLevel.VERY_STRONG, result.level);
        assertTrue(result.issues.isEmpty());
    }

    @Test
    public void longerIsNotWeaker() {
        int shorter = check("Kq7!mZ2#vL9$xR4&").score;
        int longer = check("Kq7!mZ2#vL9$xR4&Wt8@").score;
        assertTrue("更长且同样多样，分数不应下降", longer >= shorter);
    }

    @Test
    public void sequentialPatternIsReported() {
        StrengthResult result = check("Abcdefgh1234!");
        assertTrue(result.issues.contains("包含简单的顺序模式（如 123, abc）"));
    }

    @Test
    public void repeatedPatternIsReported() {
        StrengthResult result = check("AAAbbb111!!!xyz");
        assertTrue(result.issues.contains("包含重复的字符模式"));
    }

    @Test
    public void lowDiversityIsPenalised() {
        // 只有 4 种字符：真实熵很低，判定至少要被扣分并给出多样性提示。
        StrengthResult result = check("Aa1!Aa1!Aa1!");
        assertTrue(result.issues.contains("字符多样性不足"));
        assertTrue("多样性不足必须扣分", result.score < 70);
    }

    /**
     * 记录当前行为：中文既没有“大写/小写”，又被当成“特殊字符”。
     * 这不是崩溃，但语义上容易误导，见测试计划 K-11 的观察项。
     */
    @Test
    public void cjkOnlyPasswordIsClassifiedAsHavingSpecialChars() {
        StrengthResult result = check("中文密码中文密码甲乙丙丁");
        assertTrue(result.issues.contains("缺少大写字母"));
        assertTrue(result.issues.contains("缺少小写字母"));
        assertFalse("中文不应被算作满足“特殊字符”以外的字母类", result.issues.contains("缺少特殊字符"));
        assertTrue(result.level.compareTo(StrengthLevel.STRONG) < 0);
    }

    @Test
    public void emojiPasswordDoesNotCrashAndIsNotStrong() {
        StrengthResult result = check("😀😀😀😀😀😀😀😀");
        assertNotNull(result);
        assertTrue("纯 emoji 不应被判为强", result.level.compareTo(StrengthLevel.STRONG) < 0);
    }

    @Test
    public void whitespaceOnlyPasswordIsVeryWeak() {
        StrengthResult result = check("            ");
        assertTrue(result.level.compareTo(StrengthLevel.FAIR) < 0);
    }

    @Test
    public void extremeLengthDoesNotCrash() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            builder.append("Aa1!".charAt(i % 4));
        }
        StrengthResult result = check(builder.toString());
        assertNotNull(result);
    }

    @Test
    public void requirementsRejectMissingClasses() {
        assertEquals("密码不能为空", PasswordStrengthChecker.validatePasswordRequirements((char[]) null));
        assertEquals("密码不能为空", PasswordStrengthChecker.validatePasswordRequirements(""));
        assertEquals("密码长度至少12位", PasswordStrengthChecker.validatePasswordRequirements("Aa1!Bb2@Cc3"));
        assertEquals("密码必须包含大写字母", PasswordStrengthChecker.validatePasswordRequirements("aaaa1111!!!!"));
        assertEquals("密码必须包含小写字母", PasswordStrengthChecker.validatePasswordRequirements("AAAA1111!!!!"));
        assertEquals("密码必须包含数字", PasswordStrengthChecker.validatePasswordRequirements("AAAAaaaa!!!!"));
        assertEquals("密码必须包含特殊字符", PasswordStrengthChecker.validatePasswordRequirements("AAAAaaaa1111"));
        assertNull(PasswordStrengthChecker.validatePasswordRequirements("AAAAaaaa111!"));
    }

    @Test
    public void stringAndCharOverloadsAgree() {
        String candidate = "Kq7!mZ2#vL9$xR4&";
        StrengthResult fromString = PasswordStrengthChecker.checkPasswordStrength(candidate);
        StrengthResult fromChars = PasswordStrengthChecker.checkPasswordStrength(candidate.toCharArray());
        assertEquals(fromChars.level, fromString.level);
        assertEquals(fromChars.score, fromString.score);
    }
}
