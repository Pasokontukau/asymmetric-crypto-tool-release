package contacts.storage;

import contacts.model.Contact;
import core.storage.StorageManager;
import core.util.LogManager;
import crypto.key.KeyUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class ContactStorage {
    
    private static final String PUBLIC_KEY_EXTENSION = ".pub";
    private static final String BINDINGS_FILE = "bindings.properties";
    private static final String LAST_USED_FILE = "lastused.properties";
    private static final String SALTS_FILE = "salts.properties"; // Phase 2 新增：contactName -> base64(salt)
    
    // 获取绑定文件路径
    private static String getBindingsFilePath() {
        return core.storage.StorageManager.getContactsDir() + File.separator + BINDINGS_FILE;
    }
    
    // 获取最后使用时间文件路径
    private static String getLastUsedFilePath() {
        return core.storage.StorageManager.getContactsDir() + File.separator + LAST_USED_FILE;
    }
    
    // 加载所有最后使用时间
    private static Properties loadLastUsed() throws IOException {
        Properties lastUsed = new Properties();
        File lastUsedFile = new File(getLastUsedFilePath());
        if (lastUsedFile.exists()) {
            try (FileInputStream fis = new FileInputStream(lastUsedFile)) {
                lastUsed.load(fis);
            }
        }
        return lastUsed;
    }
    
    // 保存最后使用时间
    private static void saveLastUsed(Properties lastUsed) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(getLastUsedFilePath())) {
            lastUsed.store(fos, "Contact Last Used Time");
        }
    }
    
    // 更新联系人的最后使用时间为当前时间
    public static void updateLastUsed(String contactName) throws IOException {
        Properties lastUsed = loadLastUsed();
        long currentTime = System.currentTimeMillis();
        lastUsed.setProperty(contactName, String.valueOf(currentTime));
        saveLastUsed(lastUsed);
    }
    
    // 获取联系人的最后使用时间
    public static long getLastUsed(String contactName) throws IOException {
        Properties lastUsed = loadLastUsed();
        String timeStr = lastUsed.getProperty(contactName);
        if (timeStr != null && !timeStr.isEmpty()) {
            try {
                return Long.parseLong(timeStr);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
    
    // 加载所有绑定关系
    private static Properties loadBindings() throws IOException {
        Properties bindings = new Properties();
        File bindingsFile = new File(getBindingsFilePath());
        if (bindingsFile.exists()) {
            try (FileInputStream fis = new FileInputStream(bindingsFile)) {
                bindings.load(fis);
            }
        }
        return bindings;
    }

    // 加载所有隐写 salt（Phase 2 新增）
    private static Properties loadSalts() throws IOException {
        Properties salts = new Properties();
        File saltsFile = new File(core.storage.StorageManager.getContactsDir() + File.separator + SALTS_FILE);
        if (saltsFile.exists()) {
            try (FileInputStream fis = new FileInputStream(saltsFile)) {
                salts.load(fis);
            }
        }
        return salts;
    }

    // 保存隐写 salt（Phase 2 新增）
    private static void saveSalts(Properties salts) throws IOException {
        File saltsFile = new File(core.storage.StorageManager.getContactsDir() + File.separator + SALTS_FILE);
        if (saltsFile.getParentFile() != null) {
            saltsFile.getParentFile().mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(saltsFile)) {
            salts.store(fos, "Contact Stegano Salt (base64)");
        }
    }

    // 从 salt base64 字符串中解析出 salt 字节（Phase 2 新增）
    private static byte[] parseSalt(String saltBase64) {
        if (saltBase64 == null || saltBase64.isEmpty()) return null;
        try {
            return java.util.Base64.getDecoder().decode(saltBase64);
        } catch (Exception e) {
            return null;
        }
    }
    
    // 保存绑定关系
    private static void saveBindings(Properties bindings) throws IOException {
        // 确保目录存在
        File bindingsFile = new File(getBindingsFilePath());
        if (bindingsFile.getParentFile() != null) {
            bindingsFile.getParentFile().mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(bindingsFile)) {
            bindings.store(fos, "Contact to KeyPair Bindings");
        }
    }
    
    // 保存绑定关系
    public static void saveBinding(String contactName, String keyPairName) throws IOException {
        Properties bindings = loadBindings();
        if (keyPairName == null || keyPairName.isEmpty()) {
            bindings.remove(contactName);
        } else {
            bindings.setProperty(contactName, keyPairName);
        }
        saveBindings(bindings);
    }
    
    // 获取联系人绑定的密钥
    public static String getBoundKeyPair(String contactName) throws IOException {
        Properties bindings = loadBindings();
        return bindings.getProperty(contactName);
    }
    
    // 获取密钥绑定的所有联系人
    public static List<String> getContactsByKeyPair(String keyPairName) throws IOException {
        List<String> contacts = new ArrayList<>();
        Properties bindings = loadBindings();
        for (String contactName : bindings.stringPropertyNames()) {
            if (keyPairName.equals(bindings.getProperty(contactName))) {
                contacts.add(contactName);
            }
        }
        return contacts;
    }
    
    // 解绑
    public static void removeBinding(String contactName) throws IOException {
        saveBinding(contactName, null);
    }
    
    // 清空所有绑定关系
    public static void clearAllBindings() {
        File bindingsFile = new File(getBindingsFilePath());
        if (bindingsFile.exists()) {
            bindingsFile.delete();
        }
    }
    
    public static void saveContact(Contact contact) throws IOException {
        String contactPath = core.storage.StorageManager.getContactsDir() + File.separator + contact.getName() + PUBLIC_KEY_EXTENSION;
        core.storage.StorageManager.writeTextFile(contactPath, contact.getPublicKey());

        // 同时保存绑定关系
        if (contact.getBoundKeyPairName() != null && !contact.getBoundKeyPairName().isEmpty()) {
            saveBinding(contact.getName(), contact.getBoundKeyPairName());
        } else {
            removeBinding(contact.getName());
        }

        // Phase 2：若有 salt 也一并保存（base64）
        if (contact.getSalt() != null && contact.getSalt().length > 0) {
            Properties salts = loadSalts();
            salts.setProperty(contact.getName(),
                    java.util.Base64.getEncoder().encodeToString(contact.getSalt()));
            saveSalts(salts);
        }
    }

    public static List<Contact> loadAllContacts() throws Exception {
        List<Contact> contacts = new ArrayList<>();
        List<String> contactFiles = core.storage.StorageManager.listFiles(core.storage.StorageManager.getContactsDir(), PUBLIC_KEY_EXTENSION);

        Properties bindings = loadBindings();
        Properties lastUsed = loadLastUsed();
        Properties salts = loadSalts(); // Phase 2

        for (String contactFile : contactFiles) {
            String name = contactFile.replace(PUBLIC_KEY_EXTENSION, "");
            String contactPath = core.storage.StorageManager.getContactsDir() + File.separator + contactFile;
            String publicKey = core.storage.StorageManager.readTextFile(contactPath);

            String fingerprint = KeyUtils.generateKeyFingerprint(publicKey);
            String boundKeyPair = bindings.getProperty(name);

            Contact contact = new Contact(name, publicKey, "RSA", fingerprint, boundKeyPair);

            // 加载最后使用时间
            String timeStr = lastUsed.getProperty(name);
            if (timeStr != null && !timeStr.isEmpty()) {
                try {
                    contact.setLastUsedTime(Long.parseLong(timeStr));
                } catch (NumberFormatException e) {
                    // 忽略
                }
            }
            // Phase 2：加载 salt
            contact.setSalt(parseSalt(salts.getProperty(name)));

            contacts.add(contact);
        }

        return contacts;
    }
    
    // 加载所有联系人并按最后使用时间排序（最近使用的排在前面）
    public static List<Contact> loadAllContactsSortedByLastUsed() throws Exception {
        List<Contact> contacts = loadAllContacts();
        contacts.sort((c1, c2) -> {
            // 按最后使用时间降序排序（最近使用的在前面）
            return Long.compare(c2.getLastUsedTime(), c1.getLastUsedTime());
        });
        return contacts;
    }
    
    public static Contact loadContact(String name) throws Exception {
        String contactPath = core.storage.StorageManager.getContactsDir() + File.separator + name + PUBLIC_KEY_EXTENSION;
        if (!new File(contactPath).exists()) {
            return null;
        }

        String publicKey = core.storage.StorageManager.readTextFile(contactPath);
        String fingerprint = KeyUtils.generateKeyFingerprint(publicKey);
        String boundKeyPair = getBoundKeyPair(name);

        Contact contact = new Contact(name, publicKey, "RSA", fingerprint, boundKeyPair);
        // Phase 2：加载 salt
        contact.setSalt(parseSalt(loadSalts().getProperty(name)));
        return contact;
    }
    
    public static boolean deleteContact(String name) {
        String contactPath = core.storage.StorageManager.getContactsDir() + File.separator + name + PUBLIC_KEY_EXTENSION;
        boolean deleted = core.storage.StorageManager.deleteFile(contactPath);

        // 同时删除绑定关系
        try {
            removeBinding(name);
        } catch (IOException e) {
            LogManager.warning("删除联系人绑定关系失败", e);
        }

        // Phase 2：同时删除 salt 条目
        try {
            Properties salts = loadSalts();
            if (salts.containsKey(name)) {
                salts.remove(name);
                saveSalts(salts);
            }
        } catch (IOException e) {
            LogManager.warning("删除联系人 salt 失败", e);
        }

        return deleted;
    }
    
    public static boolean updateContact(String name, String newPublicKey) throws IOException {
        String contactPath = core.storage.StorageManager.getContactsDir() + File.separator + name + PUBLIC_KEY_EXTENSION;
        if (!new File(contactPath).exists()) {
            return false;
        }
        
        core.storage.StorageManager.writeTextFile(contactPath, newPublicKey);
        LogManager.debug("更新联系人成功: " + name);
        return true;
    }
    
    // 清空所有联系人
    public static void clearAllContacts() {
        try {
            File dir = new File(core.storage.StorageManager.getContactsDir());
            if (dir.exists()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        file.delete();
                    }
                }
            }
            
            // 同时清空绑定关系
            clearAllBindings();
            LogManager.info("所有联系人已清空");
        } catch (Exception e) {
            LogManager.error("清空所有联系人失败", e);
        }
    }
}
