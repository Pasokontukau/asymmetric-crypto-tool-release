package contacts.service;

import contacts.model.Contact;
import contacts.storage.ContactStorage;
import crypto.key.KeyUtils;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import keymanagement.model.KeyPairInfo;
import keymanagement.storage.KeyPairStorage;

/**
 * 添加联系人前的校验，与 Android `KeyRepository.addContact` 对齐：
 * 拒绝自己的公钥、拒绝重复指纹、拒绝小于 2048 位的 RSA 公钥。
 */
public final class ContactValidator {

    public static final int MIN_KEY_SIZE = 2048;

    private ContactValidator() {
    }

    /**
     * @return 新联系人的指纹（小写十六进制）
     * @throws IllegalArgumentException 公钥格式无效、长度不足、是自己的公钥或已存在
     */
    public static String validateForAdd(String publicKeyBase64) throws Exception {
        if (publicKeyBase64 == null || publicKeyBase64.trim().isEmpty()) {
            throw new IllegalArgumentException("请输入联系人公钥");
        }
        PublicKey publicKey;
        try {
            publicKey = KeyUtils.getPublicKeyFromBytes(
                    KeyUtils.decodeBase64(publicKeyBase64.trim()));
        } catch (Exception error) {
            throw new IllegalArgumentException("公钥格式无效", error);
        }
        if (!(publicKey instanceof RSAPublicKey)) {
            throw new IllegalArgumentException("只支持 RSA 公钥");
        }
        int keySize = ((RSAPublicKey) publicKey).getModulus().bitLength();
        if (keySize < MIN_KEY_SIZE) {
            throw new IllegalArgumentException(
                    "RSA 公钥长度必须至少为 " + MIN_KEY_SIZE + " 位，当前为 " + keySize + " 位");
        }

        String fingerprint = KeyUtils.generateKeyFingerprint(publicKey);
        List<KeyPairInfo> ownKeys = KeyPairStorage.loadAllKeyPairs();
        for (KeyPairInfo ownKey : ownKeys) {
            String ownFingerprint = KeyUtils.generateKeyFingerprint(ownKey.getPublicKey());
            if (fingerprint.equalsIgnoreCase(ownFingerprint)) {
                throw new IllegalArgumentException("不能把本机自己的公钥添加为联系人");
            }
        }

        List<Contact> contacts = ContactStorage.loadAllContacts();
        for (Contact contact : contacts) {
            if (contact.getFingerprint() != null
                    && fingerprint.equalsIgnoreCase(contact.getFingerprint())) {
                throw new IllegalArgumentException(
                        "该公钥已存在于联系人 \"" + contact.getName() + "\" 中");
            }
        }
        return fingerprint;
    }

    /** 指纹按 4 位分组，便于口头核对；与 Android 的显示方式一致。 */
    public static String formatFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(fingerprint.length() + fingerprint.length() / 4);
        for (int index = 0; index < fingerprint.length(); index++) {
            if (index > 0 && index % 4 == 0) {
                builder.append(' ');
            }
            builder.append(fingerprint.charAt(index));
        }
        return builder.toString();
    }
}
