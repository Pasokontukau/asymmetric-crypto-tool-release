package crypto.key;

import java.awt.Dimension;
import java.time.Instant;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

import crypto.config.CryptoConfig;
import crypto.provider.SecurityProvider;
import keymanagement.model.KeyPairInfo;
import keymanagement.storage.KeyPairStorage;
import keymanagement.storage.KeychainStorage;
import core.security.MemorySecurityUtils;
import core.event.PasswordCacheClearListener;
import settings.storage.SettingsStorage;

/**
 * 统一私钥访问服务类。
 *
 * 设计原则（参见 PRIVATE-KEY-ACCESS-SERVICE-DESIGN.md）：
 * 1. 这是项目中获取私钥和密码的唯一入口
 * 2. 密码获取策略（strict/balanced/convenient）集中在此类中管理
 * 3. 密码缓存（内存 + 系统密钥链）集中在此类中管理
 * 4. 敏感数据（密码明文、私钥明文字节）的生命周期由本类控制
 * 5. 调用方永远只需要调用 public API，不需要关心"密码怎么来的"
 *
 * 文件结构（用注释分区标记，便于后续拆分）：
 *   ├─ 对外公共 API（策略层）     public static 方法
 *   ├─ 内部策略辅助方法（无 UI）   private static 方法
 *   └─ UI 辅助方法（Swing 弹框）   private static 方法（TODO(REFACTOR): 代码量大时拆出）
 */
public class PrivateKeyAccessService {

    // =====================================================================
    // 缓存存储（内存 + 密钥链）
    // =====================================================================

    /** 内存密码缓存：keyPairName → (password, timestamp)。 balanced / convenient 模式下使用。 */
    private static final Map<String, CachedPassword> passwordMemoryCache = new ConcurrentHashMap<>();

    /** 设置页缺省的内存缓存寿命（毫秒）：30 分钟；实际值由 SettingsStorage 配置。 */
    private static final long DEFAULT_CACHE_TTL_MS = 30L * 60 * 1000;

    private static final long MILLIS_PER_MINUTE = 60L * 1000;

    /** 缓存记录（密码 char[] + 记录时间戳）。 */
    private static class CachedPassword {
        final char[] password;
        final long timestamp;

        CachedPassword(char[] password) {
            this.password = password.clone();
            this.timestamp = System.currentTimeMillis();
        }

        /** 判断缓存是否仍在有效期内。 */
        boolean isFresh(long ttlMs) {
            return (System.currentTimeMillis() - timestamp) < ttlMs;
        }

        /** 安全清除密码数组内容（覆写 0）。 */
        void clear() {
            MemorySecurityUtils.clearCharArray(password);
        }
    }

    static {
        // 监听安全等级变更。当切换到 strict 模式时清除所有内存缓存。
        SettingsStorage.addSecurityLevelChangeListener((newLevel, ignoredOldLevel) -> {
            if (SettingsStorage.SECURITY_LEVEL_STRICT.equals(newLevel)) {
                clearAllPasswordCaches();
            }
        });

        // 注册到全局的密码缓存清除事件。
        KeyPairStorage.addPasswordCacheClearListener(new PasswordCacheClearListener() {
            @Override
            public void onClearPasswordCache(String keyPairName) {
                if (keyPairName != null && !keyPairName.isEmpty()) {
                    clearPasswordCache(keyPairName);
                }
            }

            @Override
            public void onClearAllPasswordCaches() {
                clearAllPasswordCaches();
            }
        });
    }

    private PrivateKeyAccessService() {
        // 工具类，禁止实例化。
    }

    // =====================================================================
    // ── 对外公共 API（策略层） ──────────────────────────────────────
    // =====================================================================

    /**
     * 获取指定密钥对的私钥。
     * 按当前安全等级自动决定密码来源（弹框 / 内存缓存 / 系统密钥链）。
     *
     * @param keyPairName 密钥对名称（用于加载加密文件和缓存查找）
     * @return 解密后的 PrivateKey 对象。如果用户取消弹框或密码错误，返回 null。
     */
    public static PrivateKey getPrivateKey(String keyPairName) {
        if (keyPairName == null || keyPairName.isEmpty()) {
            showErrorMessage("参数错误", "密钥对名称为空。");
            return null;
        }

        byte[] encryptedPrivateKey;
        try {
            encryptedPrivateKey = KeyPairStorage.loadEncryptedPrivateKey(keyPairName);
        } catch (java.io.IOException e) {
            showErrorMessage("加载失败", "无法加载密钥对 \"" + keyPairName + "\" 的加密私钥文件：\n" + e.getMessage());
            return null;
        }
        if (encryptedPrivateKey == null || encryptedPrivateKey.length == 0) {
            showErrorMessage("加载失败", "无法加载密钥对 \"" + keyPairName + "\" 的加密私钥文件。\n\n请在密钥对管理中确认该密钥对是否存在。");
            return null;
        }

        try {
            String level = SettingsStorage.getSecurityLevel();
            char[] password = getPasswordFor(keyPairName, level);
            if (password == null) {
                // 用户取消弹框。
                return null;
            }

            try {
                // 验证密码是否正确。
                boolean verified = KeyPairManager.verifyPassword(encryptedPrivateKey, password);
                if (!verified) {
                    showErrorMessage("密码错误", "为密钥对 \"" + keyPairName + "\" 输入的密码不正确。\n\n请检查键盘输入（注意 Caps Lock 状态）。");
                    return null;
                }

                // 密码正确 → 解密。
                byte[] plainPrivateKey = KeyPairManager.decryptPrivateKey(encryptedPrivateKey, password);
                if (plainPrivateKey == null) {
                    showErrorMessage("解密失败", "私钥字节解密返回空。请检查加密文件是否完整。");
                    return null;
                }

                try {
                    // 解析为 Java Security PrivateKey 对象。
                    PrivateKey privateKey = KeyUtils.getPrivateKeyFromBytes(plainPrivateKey);

                    // 只有完整成功后才缓存；失败的操作不应留下密码。
                    cachePasswordIfApplicable(password, keyPairName, level);
                    return privateKey;
                } finally {
                    // PrivateKey 对象由 JCA 管理，独立于明文字节数组。
                    MemorySecurityUtils.clearByteArray(plainPrivateKey);
                }

            } catch (Exception e) {
                showErrorMessage("解密失败", "私钥解密过程中出现错误：\n" + e.getMessage());
                return null;
            } finally {
                // 调用方拿到的私钥对象不依赖这个密码数组；缓存保存的是 clone。
                MemorySecurityUtils.clearCharArray(password);
            }

        } finally {
            // 确保加密字节数组在任何情况下都被清理。
            MemorySecurityUtils.clearByteArray(encryptedPrivateKey);
        }
    }

    /**
     * 验证指定密钥对的密码是否正确。
     * 不修改任何缓存，不自动弹框。仅作为"便捷模式下检查密码是否已被清除"的辅助方法。
     */
    public static boolean verifyKeyPassword(String keyPairName, char[] password) {
        if (keyPairName == null || keyPairName.isEmpty() || password == null) return false;

        try {
            byte[] encryptedPrivateKey = KeyPairStorage.loadEncryptedPrivateKey(keyPairName);
            if (encryptedPrivateKey == null) return false;

            try {
                return KeyPairManager.verifyPassword(encryptedPrivateKey, password);
            } finally {
                MemorySecurityUtils.clearByteArray(encryptedPrivateKey);
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 创建新的 RSA 密钥对并用密码加密后保存。
     * 当前阶段（第一阶段）只实现"无 salt"版本。
     * salt 支持将在第二阶段（KeyPairInfo + KeyPairStorage 加 salt 字段）实现。
     */
    public static boolean createAndSaveKeyPair(String name, int keySize, char[] password) {
        if (name == null || name.trim().isEmpty()) {
            showErrorMessage("参数错误", "密钥对名称为空。");
            return false;
        }
        if (password == null || password.length == 0) {
            showErrorMessage("参数错误", "密码为空。");
            return false;
        }

        try {
            // 1. 生成 RSA KeyPair
            KeyPair keyPair = KeyPairManager.generateKeyPair(keySize);
            if (keyPair == null) {
                showErrorMessage("生成失败", "密钥对生成返回 null。请检查 JCA 安全提供者配置。");
                return false;
            }

            // 2. 提取公钥字节并转成 Base64 字符串（KeyPairInfo 要求的格式）。
            byte[] publicKeyBytes = keyPair.getPublic().getEncoded();
            String publicKeyBase64 = KeyUtils.encodeBase64(publicKeyBytes);

            // 3. 提取私钥字节，并用密码加密。
            byte[] privateKeyBytes = keyPair.getPrivate().getEncoded();
            byte[] encryptedPrivateKey = KeyPairManager.encryptPrivateKey(privateKeyBytes, password);

            if (encryptedPrivateKey == null) {
                showErrorMessage("加密失败", "私钥加密返回空。请检查加密模块。");
                MemorySecurityUtils.clearByteArray(privateKeyBytes);
                return false;
            }

            // 4. 构造 KeyPairInfo 对象（包含元数据：创建时间、过期时间、算法、长度等）。
            KeyPairInfo keyPairInfo = new KeyPairInfo(
                    name,                                 // 名称
                    Instant.now(),                        // 创建时间
                    "RSA",                                // 算法
                    keySize,                              // 密钥长度（位）
                    publicKeyBase64,                      // 公钥（Base64）
                    null                                  // privateKeyPath（由 KeyPairStorage 自动填充）
            );

            // 5. 保存到文件系统。
            KeyPairStorage.saveKeyPair(name, keyPairInfo, encryptedPrivateKey);

            // 6. 清理敏感数据（立即覆写）。
            MemorySecurityUtils.clearByteArray(privateKeyBytes);
            MemorySecurityUtils.clearByteArray(encryptedPrivateKey);
            MemorySecurityUtils.clearByteArray(publicKeyBytes);

            showInfoMessage("创建成功",
                    "密钥对 \"" + name + "\" 已创建并加密保存。\n\n"
                  + "算法：RSA\n"
                  + "长度：" + keySize + " 位\n\n"
                  + "【重要提示】\n请务必牢记您输入的密码。\n"
                  + "密码丢失无法找回，届时该密钥对将无法使用。");
            return true;

        } catch (Exception e) {
            showErrorMessage("创建失败", "密钥对创建过程中出现错误：\n" + e.getMessage());
            return false;
        }
    }

    /**
     * 清除指定密钥对的内存密码缓存和系统密钥链中的密码。
     * 当用户明确执行"清除缓存"或"删除密钥对"操作时调用。
     */
    public static void clearPasswordCache(String keyPairName) {
        if (keyPairName == null || keyPairName.isEmpty()) return;

        // 1. 清除内存缓存
        CachedPassword entry = passwordMemoryCache.remove(keyPairName);
        if (entry != null) entry.clear();

        // 2. 清除系统密钥链
        try {
            KeychainStorage.deletePassword(keyPairName);
        } catch (Exception ignored) {
            // KeychainStorage 内部已处理异常。此处静默忽略。
        }
    }

    /**
     * 只清除进程内的内存密码缓存，保留便捷模式写入系统密钥链的密码。
     * 正常退出应用时使用：退出后内存必然消失，但便捷模式的密码需要跨进程保留。
     */
    public static void clearMemoryPasswordCaches() {
        for (CachedPassword entry : passwordMemoryCache.values()) {
            entry.clear();
        }
        passwordMemoryCache.clear();
    }

    /**
     * 清除所有内存密码缓存和系统密钥链中的所有密码（但不删除密钥对文件）。
     * 当切换到 strict 安全等级、离开便捷模式、删除密钥对或用户手动清除时调用。
     */
    public static void clearAllPasswordCaches() {
        clearMemoryPasswordCaches();

        // 清除系统密钥链
        try {
            KeychainStorage.clearAllPasswords();
        } catch (Exception ignored) {
            // KeychainStorage 内部已处理异常。
        }
    }

    // =====================================================================
    // ── salt 相关（第二阶段实现，当前留占位） ─────────────────────
    // =====================================================================

    /**
     * 获取当前密钥对自己的 salt（明文形式）。
     * 第二阶段：KeyPairInfo/KeyPairStorage 支持 salt 存储后，才实现。
     * 当前返回 null，并弹出提示信息。
     */
    public static byte[] getMySalt(String keyPairName) {
        if (keyPairName == null || keyPairName.isEmpty()) {
            showErrorMessage("参数错误", "密钥对名称为空。");
            return null;
        }

        byte[] encryptedSalt;
        try {
            encryptedSalt = KeyPairStorage.loadEncryptedSalt(keyPairName);
        } catch (java.io.IOException e) {
            showErrorMessage("加载失败", "无法加载密钥对 \"" + keyPairName + "\" 的 salt 文件：\n" + e.getMessage());
            return null;
        }
        if (encryptedSalt == null || encryptedSalt.length == 0) {
            showErrorMessage("未找到 Salt",
                    "该密钥对尚未绑定自己的 salt。\n\n"
                  + "原因：这是一个较早创建的密钥对（salt 协议功能启动前创建）。\n\n"
                  + "解决方法：\n"
                  + "  • 在密钥对管理中重新创建一个新密钥对（将自动生成并加密保存 salt）；或\n"
                  + "  • 为该密钥对重新补充 salt。");
            return null;
        }

        String level = SettingsStorage.getSecurityLevel();
        char[] password = getPasswordFor(keyPairName, level);
        if (password == null) {
            MemorySecurityUtils.clearByteArray(encryptedSalt);
            return null;
        }

        try {
            byte[] plainSalt = KeyPairManager.decryptPrivateKey(encryptedSalt, password);
            if (plainSalt == null) {
                showErrorMessage("解密失败", "salt 解密返回空（密码可能不正确或文件已损坏）。");
                return null;
            }
            // 与 getPrivateKey 共用同一套缓存策略；否则第一次导出 salt 后仍会再次弹框。
            cachePasswordIfApplicable(password, keyPairName, level);
            return plainSalt;
        } catch (Exception e) {
            showErrorMessage("解密失败", "salt 解密过程中出现错误：\n" + e.getMessage());
            return null;
        } finally {
            MemorySecurityUtils.clearCharArray(password);
            MemorySecurityUtils.clearByteArray(encryptedSalt);
        }
    }

    /**
     * 用对方公钥加密我的 salt，返回密文字节（Base64 字符串由调用方处理）。
     * 第二阶段实现。
     */
    public static byte[] encryptSaltForContact(byte[] mySalt, PublicKey contactPublicKey) {
        if (mySalt == null || mySalt.length == 0) {
            showErrorMessage("参数错误", "明文 salt 为空。");
            return null;
        }
        if (contactPublicKey == null) {
            showErrorMessage("参数错误", "联系人公钥为空。");
            return null;
        }

        try {
            Cipher cipher = Cipher.getInstance(CryptoConfig.RSA_CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, contactPublicKey, SecurityProvider.STRONG_RANDOM);
            return cipher.doFinal(mySalt);
        } catch (Exception e) {
            showErrorMessage("加密失败", "使用对方公钥 RSA-OAEP 加密 salt 失败：\n" + e.getMessage());
            return null;
        }
    }

    /**
     * 解密对方传来的 salt 密文，保存到联系人信息中。
     * 第二阶段实现。
     */
    public static boolean decryptAndImportContactSalt(String encryptedSaltBase64, String contactName,
                                                       PrivateKey myPrivateKey, String keyPairName) {
        if (encryptedSaltBase64 == null || encryptedSaltBase64.trim().isEmpty()) {
            showErrorMessage("参数错误", "salt 密文为空。");
            return false;
        }
        if (contactName == null || contactName.isEmpty()) {
            showErrorMessage("参数错误", "联系人名称为空。");
            return false;
        }

        byte[] encryptedSalt;
        try {
            encryptedSalt = java.util.Base64.getDecoder().decode(encryptedSaltBase64.trim());
        } catch (Exception e) {
            showErrorMessage("解码失败", "无法将输入内容解析为 Base64：\n" + e.getMessage());
            return false;
        }

        PrivateKey pk = myPrivateKey;
        if (pk == null) {
            if (keyPairName == null || keyPairName.isEmpty()) {
                showErrorMessage("参数错误", "缺少密钥对名称以加载私钥。");
                return false;
            }
            pk = getPrivateKey(keyPairName);
            if (pk == null) return false;
        }

        byte[] plainSalt;
        try {
            Cipher cipher = Cipher.getInstance(CryptoConfig.RSA_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, pk, SecurityProvider.STRONG_RANDOM);
            plainSalt = cipher.doFinal(encryptedSalt);
        } catch (Exception e) {
            showErrorMessage("解密失败", "使用私钥 RSA-OAEP 解密 salt 失败：\n"
                    + e.getMessage() + "\n\n"
                    + "请确认：\n"
                    + "  • 这是对方专门发给您的 salt（必须用您的公钥加密、用您的私钥解密）；\n"
                    + "  • 粘贴的 Base64 文本未被截断或修改。");
            return false;
        }

        if (plainSalt == null || plainSalt.length < stegano.model.SteganoConfig.CONTACT_SALT_MIN_LENGTH) {
            showErrorMessage("格式错误", "解密得到的 salt 长度不符合协议要求（至少 "
                    + stegano.model.SteganoConfig.CONTACT_SALT_MIN_LENGTH + " 字节）。");
            return false;
        }

        try {
            contacts.model.Contact contact = contacts.storage.ContactStorage.loadContact(contactName);
            if (contact == null) {
                showErrorMessage("未找到联系人", "系统中不存在名为 \"" + contactName + "\" 的联系人。");
                return false;
            }
            contact.setSalt(plainSalt);
            contacts.storage.ContactStorage.saveContact(contact);

            showInfoMessage("导入成功",
                    "已为联系人 \"" + contactName + "\" 导入 salt。\n\n"
                  + "现在您可以在隐写面板中选择该联系人进行隐写通信。");
            return true;
        } catch (Exception e) {
            showErrorMessage("保存失败", "写入联系人信息时出现错误：\n" + e.getMessage());
            return false;
        }
    }

    // =====================================================================
    // ── 内部策略辅助方法（无 UI） ─────────────────────────────────
    // =====================================================================

    /**
     * 按安全等级获取密码。
     *
     * strict     → 每次弹框，不读任何缓存
     * balanced   → 先看内存缓存（按设置页超时），没有才弹框，弹框成功后写内存缓存
     * convenient → ①先看系统密钥链（DPAPI）；②再看内存缓存（按设置页超时）；③都没有才弹框，弹框成功后同时写密钥链和内存缓存
     *
     * @return 用户输入的密码 char[]（新数组，调用方负责清理）。用户取消弹框或读取失败时返回 null。
     */
    private static char[] getPasswordFor(String keyPairName, String level) {
        if (level == null) level = "balanced";

        switch (level.toLowerCase()) {
            case "strict":
                // strict：每次都弹框，不走任何缓存
                return showPasswordDialog(keyPairName);

            case "convenient": {
                long ttlMs = configuredCacheTtlMillis();

                // ① 先试系统密钥链（DPAPI 保护，跨进程持久化）
                char[] fromKeychain = readFromSystemKeychain(keyPairName);
                if (fromKeychain != null && verifyKeyPassword(keyPairName, fromKeychain)) {
                    return fromKeychain;
                }
                if (fromKeychain != null) MemorySecurityUtils.clearCharArray(fromKeychain);

                // ② 再试内存缓存（比弹框快得多，且不打断用户操作）
                char[] fromMemory = readFromMemoryCache(keyPairName, ttlMs);
                if (fromMemory != null && verifyKeyPassword(keyPairName, fromMemory)) {
                    return fromMemory;
                }
                if (fromMemory != null) MemorySecurityUtils.clearCharArray(fromMemory);

                // ③ 弹框，成功后同时写入密钥链和内存缓存（由调用方 cachePasswordIfApplicable 处理）
                return showPasswordDialog(keyPairName);
            }

            case "balanced":
            default: {
                long ttlMs = configuredCacheTtlMillis();

                // balanced：先看内存缓存，没有才弹框。弹框成功后写内存缓存。
                char[] cached = readFromMemoryCache(keyPairName, ttlMs);
                if (cached != null && verifyKeyPassword(keyPairName, cached)) {
                    return cached;
                }
                if (cached != null) MemorySecurityUtils.clearCharArray(cached);

                return showPasswordDialog(keyPairName);
            }
        }
    }

    /** 把设置页的分钟数转换为缓存 TTL；-1 表示本次进程内永不过期。 */
    static long cacheTtlMillis(int minutes) {
        if (minutes == SettingsStorage.TIMEOUT_NEVER) {
            return Long.MAX_VALUE;
        }
        if (minutes <= 0) {
            return DEFAULT_CACHE_TTL_MS;
        }
        return minutes * MILLIS_PER_MINUTE;
    }

    private static long configuredCacheTtlMillis() {
        return cacheTtlMillis(SettingsStorage.getPasswordCacheTimeout());
    }

    /** 从内存缓存读取密码。返回的是密码的 clone，调用方负责清理。 */
    private static char[] readFromMemoryCache(String keyPairName, long ttlMs) {
        CachedPassword entry = passwordMemoryCache.get(keyPairName);
        if (entry == null) return null;
        if (!entry.isFresh(ttlMs)) {
            // 已过期 → 安全清除并移除
            entry.clear();
            passwordMemoryCache.remove(keyPairName);
            return null;
        }
        return entry.password.clone();
    }

    /** 从系统密钥链（DPAPI）读取密码。返回的是可修改的 char[]，调用方负责清理。 */
    private static char[] readFromSystemKeychain(String keyPairName) {
        try {
            if (!KeychainStorage.isAvailable()) return null;
            if (!KeychainStorage.hasPassword(keyPairName)) return null;
            return KeychainStorage.getPassword(keyPairName);
        } catch (Exception e) {
            return null;
        }
    }

    /** 根据安全等级决定是否把密码写入缓存（内存 / 密钥链）。 */
    private static void cachePasswordIfApplicable(char[] password, String keyPairName, String level) {
        if (password == null || keyPairName == null) return;
        if (level == null) level = "balanced";

        switch (level.toLowerCase()) {
            case "strict":
                // strict：不缓存，每次都重新弹框
                return;

            case "balanced":
                // balanced：只写内存缓存（进程退出时自动清除）
                writeToMemoryCache(password, keyPairName);
                return;

            case "convenient":
                // convenient：同时写内存缓存和系统密钥链（DPAPI 保护，跨进程持久化）
                writeToMemoryCache(password, keyPairName);
                writeToSystemKeychain(password, keyPairName);
                return;
        }
    }

    /** 将密码写入内存缓存。内部会 clone 密码后再存储，防止调用方后续修改影响缓存。 */
    private static void writeToMemoryCache(char[] password, String keyPairName) {
        // 先清除旧的（如果有），避免密码数据残留
        CachedPassword old = passwordMemoryCache.remove(keyPairName);
        if (old != null) old.clear();
        // 写入新的（CachedPassword 内部会再次 clone 密码）
        passwordMemoryCache.put(keyPairName, new CachedPassword(password));
    }

    /** 将密码写入系统密钥链（DPAPI 保护，跨进程持久化）。失败时静默忽略（退化为纯内存缓存）。 */
    private static void writeToSystemKeychain(char[] password, String keyPairName) {
        try {
            if (!KeychainStorage.isAvailable()) return;
            KeychainStorage.savePassword(keyPairName, password);
        } catch (Exception e) {
            // 密钥链写入失败静默忽略。下次使用时会再次弹框，但功能不受影响。
        }
    }

    // =====================================================================
    // ══════════ UI 辅助方法（Swing 弹框） ═════════════════════════════
    //
    // TODO(REFACTOR): 如果这部分代码超过 100 行，
    // 或出现复杂的密码强度指示/显示隐藏切换等交互组件，
    // 应拆分为独立类 PrivateKeyPasswordDialog（与本类同包，package-private）。
    // =====================================================================

    /**
     * 弹出标准的密码输入对话框。
     * 不在事件分发线程（EDT）时也能安全调用（内部用 SwingUtilities.invokeAndWait 同步）。
     *
     * @param keyPairName 密钥对名称。用于对话框标题提示"请输入 <keyPairName> 的密码"。
     * @return 用户输入的密码 char[]（新数组，调用方负责清理）。用户点击取消或关闭对话框时返回 null。
     */
    private static char[] showPasswordDialog(String keyPairName) {
        final char[][] resultHolder = new char[1][];  // 用数组当 mutable holder 解决 lambda 无法赋值的问题

        Runnable dialogRunnable = () -> {
            JPanel panel = new JPanel();
            panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));

            JLabel label = new JLabel("请输入密钥对 \"" + keyPairName + "\" 的密码：");
            label.setAlignmentX(JLabel.LEFT_ALIGNMENT);
            panel.add(label);

            JPasswordField passwordField = new JPasswordField(30);
            passwordField.setMaximumSize(new Dimension(Integer.MAX_VALUE, passwordField.getPreferredSize().height));
            passwordField.setAlignmentX(JPasswordField.LEFT_ALIGNMENT);
            panel.add(passwordField);

            // 显示当前安全等级提示（让用户知道是否会被记住）
            String level = SettingsStorage.getSecurityLevel();
            String levelText;
            if (level == null) {
                levelText = "平衡（短期内记住密码）";
            } else {
                switch (level.toLowerCase()) {
                    case "strict":     levelText = "严格（每次使用都需要输入密码）"; break;
                    case "convenient": levelText = "便捷（自动记住密码）"; break;
                    case "balanced":
                    default:           levelText = "平衡（短期内记住密码）"; break;
                }
            }

            JLabel hint = new JLabel("当前安全等级：" + levelText);
            hint.setFont(hint.getFont().deriveFont(java.awt.Font.PLAIN, 11f));
            hint.setForeground(java.awt.Color.GRAY);
            hint.setAlignmentX(JLabel.LEFT_ALIGNMENT);
            hint.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 0, 0, 0));
            panel.add(hint);

            int option = JOptionPane.showConfirmDialog(
                    null,
                    panel,
                    "输入密码",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);

            if (option == JOptionPane.OK_OPTION) {
                char[] pwd = passwordField.getPassword();
                if (pwd != null && pwd.length > 0) {
                    resultHolder[0] = pwd;
                } else {
                    resultHolder[0] = null;
                }
            } else {
                resultHolder[0] = null;
            }

            // 为安全起见，立即在文本组件中清除密码字符（覆写为空字符串）。
            passwordField.setText("");
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                // 已经在 EDT（UI 线程），直接执行。JOptionPane 本身就是阻塞式的。
                dialogRunnable.run();
            } else {
                // 不在 EDT，用 invokeAndWait 同步执行（阻塞到对话框关闭）。
                SwingUtilities.invokeAndWait(dialogRunnable);
            }
        } catch (Exception e) {
            // invokeAndWait 失败（罕见，通常是线程被中断）。给出明确提示但不崩溃。
            showErrorMessage("无法显示对话框",
                    "密码输入对话框无法正常显示：\n" + e.getMessage()
                  + "\n\n请重试，或在设置中将安全等级切换为\"便捷\"。");
            return null;
        }

        return resultHolder[0];
    }

    /** 统一的错误提示框。在非 GUI 线程或 headless 环境下静默忽略。 */
    private static void showErrorMessage(String title, String message) {
        try {
            JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE);
        } catch (Exception ignored) {
            // 在非 GUI 线程或 headless 环境下可能无法弹出。静默忽略。
            // 实际使用时，调用方（Swing/AWT 面板）总是在 GUI 线程中。
        }
    }

    /** 统一的信息提示框。在非 GUI 线程或 headless 环境下静默忽略。 */
    private static void showInfoMessage(String title, String message) {
        try {
            JOptionPane.showMessageDialog(null, message, title, JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ignored) {
            // 同上。非 GUI 环境下静默忽略。
        }
    }
}
