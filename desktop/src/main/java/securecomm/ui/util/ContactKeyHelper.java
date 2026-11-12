package securecomm.ui.util;

import java.util.List;
import contacts.model.Contact;
import contacts.storage.ContactStorage;
import crypto.key.KeyUtils;
import keymanagement.model.KeyPairInfo;
import keymanagement.storage.KeyPairStorage;
import core.util.LogManager;
import settings.storage.SettingsStorage;

/**
 * 联系人/密钥选择的辅助类
 * 提取自 SecureTextPanel 的纯逻辑方法
 */
public class ContactKeyHelper {

    private ContactKeyHelper() {}

    /**
     * 检查是否是联系人选择项
     */
    public static boolean isContactSelection(String selection) {
        return selection != null && selection.startsWith("联系人 - ");
    }

    /**
     * 检查是否是"我的公钥"选择项
     */
    public static boolean isMyKeySelection(String selection) {
        return selection != null && selection.startsWith("我的公钥 - ");
    }

    /**
     * 从联系人选择项中提取联系人名称
     */
    public static String getContactNameFromSelection(String selection) {
        if (isContactSelection(selection)) {
            return selection.substring("联系人 - ".length());
        }
        return null;
    }

    /**
     * 从"我的公钥"选择项中提取密钥对名称
     */
    public static String getKeyPairNameFromSelection(String selection) {
        if (isMyKeySelection(selection)) {
            return selection.substring("我的公钥 - ".length());
        }
        return null;
    }

    /**
     * 从选择项加载公钥（联系人公钥 或 "我的公钥"）
     */
    public static java.security.PublicKey loadPublicKeyFromSelection(String selection) throws Exception {
        if (isContactSelection(selection)) {
            String contactName = getContactNameFromSelection(selection);
            Contact contact = ContactStorage.loadContact(contactName);
            return KeyUtils.getPublicKeyFromBytes(KeyUtils.decodeBase64(contact.getPublicKey()));
        } else if (isMyKeySelection(selection)) {
            String keyPairName = getKeyPairNameFromSelection(selection);
            List<KeyPairInfo> keyPairs = KeyPairStorage.loadAllKeyPairs();
            for (KeyPairInfo keyPair : keyPairs) {
                if (keyPair.getName().equals(keyPairName)) {
                    return KeyUtils.getPublicKeyFromBytes(KeyUtils.decodeBase64(keyPair.getPublicKey()));
                }
            }
            throw new Exception("未找到密钥对: " + keyPairName);
        }
        throw new Exception("无法识别的选择项: " + selection);
    }

    /**
     * 加载所有公钥到下拉框
     * 包括：测试模式下的"我的公钥" 和 所有联系人
     */
    public static void loadAllPublicKeys(javax.swing.JComboBox<String> comboBox) {
        comboBox.removeAllItems();

        try {
            // 测试模式：加载"我的公钥"
            if (SettingsStorage.isTestMode()) {
                List<KeyPairInfo> keyPairs = KeyPairStorage.loadAllKeyPairs();
                for (KeyPairInfo keyPair : keyPairs) {
                    comboBox.addItem("我的公钥 - " + keyPair.getName());
                }
            }

            // 生产模式：加载所有联系人
            List<Contact> contacts = ContactStorage.loadAllContactsSortedByLastUsed();
            for (Contact contact : contacts) {
                comboBox.addItem("联系人 - " + contact.getName());
            }

            // 如果有选项，默认选中第一个
            if (comboBox.getItemCount() > 0) {
                comboBox.setSelectedIndex(0);
            }

        } catch (Exception ex) {
            LogManager.error("加载公钥列表失败", ex);
        }
    }

    /**
     * 更新联系人的最后使用时间
     */
    public static void updateContactLastUsedIfContact(String selection) {
        if (isContactSelection(selection)) {
            String contactName = getContactNameFromSelection(selection);
            try {
                ContactStorage.updateLastUsed(contactName);
            } catch (Exception e) {
                LogManager.warning("更新联系人最后使用时间失败: " + contactName);
            }
        }
    }

    /**
     * 验证批量操作的前置条件（纯逻辑部分）
     * 返回验证结果：通过 / 失败 + 错误消息
     * 注意：UI 更新（如设置标签颜色）应由调用方处理
     */
    public static ValidationResult validateBatchOperation(String myKeyName, String otherSelection) {
        if (myKeyName == null || myKeyName.isEmpty()) {
            return new ValidationResult(false, "请先选择密钥对！");
        }
        if (otherSelection == null || otherSelection.isEmpty() || otherSelection.startsWith("选择")) {
            return new ValidationResult(false, "请选择联系人！");
        }

        // 更新联系人的最后使用时间
        updateContactLastUsedIfContact(otherSelection);

        return new ValidationResult(true, null);
    }

    /**
     * 联系人选择验证结果
     */
    public static class ValidationResult {
        public final boolean valid;
        public final String message;

        public ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }
    }

    /**
     * 从联系人选择项解析绑定的密钥对名称
     * @return 密钥对名称，如联系人未绑定或异常则返回 null
     */
    public static String getBoundKeyPairName(String selection) {
        if (!isContactSelection(selection)) {
            return null;
        }
        String contactName = getContactNameFromSelection(selection);
        try {
            String boundKeyName = ContactStorage.getBoundKeyPair(contactName);
            if (boundKeyName == null || boundKeyName.trim().isEmpty()) {
                return null;
            }
            return boundKeyName;
        } catch (Exception ex) {
            LogManager.error("获取联系人绑定密钥失败: " + contactName, ex);
            return null;
        }
    }

    /**
     * 验证密钥对是否存在
     */
    public static boolean keyPairExists(String keyPairName) {
        if (keyPairName == null || keyPairName.isEmpty()) {
            return false;
        }
        try {
            List<KeyPairInfo> keyPairs = KeyPairStorage.loadAllKeyPairs();
            for (KeyPairInfo keyPair : keyPairs) {
                if (keyPair.getName().equals(keyPairName)) {
                    return true;
                }
            }
        } catch (Exception ex) {
            LogManager.error("验证密钥对失败: " + keyPairName, ex);
        }
        return false;
    }
}
