package history.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import core.model.FileInfo;
import core.util.LogManager;
import history.model.HistoryRecord;
import history.model.HistoryType;
import history.model.RecordCategory;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import platform.windows.LocalCryptoKeyManager;
import platform.windows.LocalDataEncryptor;
import settings.storage.SettingsStorage;

public class HistoryStorage {
    private static final String HISTORY_FILENAME = "history";
    private static final String LEGACY_PLAINTEXT_FILENAME = "history.json";
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    private static final Type HISTORY_LIST_TYPE = new TypeToken<List<HistoryRecord>>() {}.getType();
    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static volatile Boolean encryptionAvailable = null;

    private static Path getHistoryFilePath() {
        String dataDir = core.storage.StorageManager.getDataDir();
        return Paths.get(dataDir, HISTORY_FILENAME + ".dat");
    }

    /** 旧版明文历史已不再读取；启动时直接清掉，避免长期留在数据目录。 */
    private static void deleteLegacyPlaintextIfPresent() {
        Path oldPath = Paths.get(
                core.storage.StorageManager.getDataDir(), LEGACY_PLAINTEXT_FILENAME);
        if (!Files.exists(oldPath)) {
            return;
        }
        try {
            Files.deleteIfExists(oldPath);
            LogManager.info("已删除旧版明文历史记录");
        } catch (IOException e) {
            LogManager.warning("旧明文历史记录删除失败，请手动删除", e);
        }
    }
    
    private static synchronized boolean checkEncryptionAvailable() {
        if (encryptionAvailable != null) {
            return encryptionAvailable;
        }
        
        try {
            LocalCryptoKeyManager.isKeyAvailable();
            encryptionAvailable = true;
            LogManager.info("本地加密功能可用");
        } catch (Exception | NoClassDefFoundError e) {
            encryptionAvailable = false;
            LogManager.warning("本地加密功能不可用，历史记录将不会保存", e);
        }
        
        return encryptionAvailable;
    }
    
    public static List<HistoryRecord> loadHistory() {
        try {
            deleteLegacyPlaintextIfPresent();

            if (!checkEncryptionAvailable()) {
                LogManager.warning("本地加密不可用，历史记录不可读也不可写");
                return new ArrayList<>();
            }
        } catch (Exception e) {
            LogManager.error("检查历史记录文件失败", e);
            return new ArrayList<>();
        }

        lock.readLock().lock();
        try {
            Path historyPath = getHistoryFilePath();
            if (!Files.exists(historyPath)) {
                return new ArrayList<>();
            }

            byte[] dataBytes = Files.readAllBytes(historyPath);
            if (!LocalDataEncryptor.isEncryptedHistoryFile(dataBytes)) {
                LogManager.warning("历史记录文件不是当前加密格式，已忽略；不做旧格式读取");
                return new ArrayList<>();
            }

            String json = LocalDataEncryptor.decryptHistoryData(dataBytes);
            List<HistoryRecord> records = GSON.fromJson(json, HISTORY_LIST_TYPE);
            return records != null ? records : new ArrayList<>();
        } catch (Exception e) {
            LogManager.error("加载加密历史记录失败", e);
            return new ArrayList<>();
        } finally {
            lock.readLock().unlock();
        }
    }

    private static boolean saveHistory(List<HistoryRecord> records) {
        lock.writeLock().lock();
        try {
            if (!checkEncryptionAvailable()) {
                LogManager.warning("本地加密不可用，拒绝以明文保存历史记录");
                return false;
            }

            String json = GSON.toJson(records);
            try {
                byte[] encryptedData = LocalDataEncryptor.encryptHistoryData(json);
                Path historyPath = getHistoryFilePath();
                Files.createDirectories(historyPath.getParent());
                Files.write(historyPath, encryptedData);
                LogManager.info("历史记录已加密保存");
                return true;
            } catch (Exception e) {
                LogManager.error("加密保存历史记录失败，未写入明文", e);
                return false;
            }
        } catch (Exception e) {
            LogManager.error("保存历史记录失败", e);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static Set<String> loadAllContactsFromHistory() {
        List<HistoryRecord> records = loadHistory();
        Set<String> contacts = new HashSet<>();
        for (HistoryRecord record : records) {
            if (record.contactName != null && !record.contactName.isEmpty()) {
                contacts.add(record.contactName);
            }
        }
        return contacts;
    }

    public static List<HistoryRecord> searchHistory(String searchText) {
        List<HistoryRecord> records = loadHistory();
        String lowerSearchText = searchText.toLowerCase();
        return records.stream()
                .filter(record -> {
                    if (record.description != null && record.description.toLowerCase().contains(lowerSearchText)) return true;
                    if (record.inputText != null && record.inputText.toLowerCase().contains(lowerSearchText)) return true;
                    if (record.outputText != null && record.outputText.toLowerCase().contains(lowerSearchText)) return true;
                    if (record.keypairName != null && record.keypairName.toLowerCase().contains(lowerSearchText)) return true;
                    if (record.contactName != null && record.contactName.toLowerCase().contains(lowerSearchText)) return true;
                    if (record.fileInputPath != null && record.fileInputPath.toLowerCase().contains(lowerSearchText)) return true;
                    if (record.fileOutputPath != null && record.fileOutputPath.toLowerCase().contains(lowerSearchText)) return true;
                    if (record.notes != null && record.notes.toLowerCase().contains(lowerSearchText)) return true;
                    if (record.details != null && record.details.toLowerCase().contains(lowerSearchText)) return true;
                    return false;
                })
                .collect(Collectors.toList());
    }

    public static List<HistoryRecord> loadHistoryByContact(String contactName) {
        List<HistoryRecord> records = loadHistory();
        return records.stream()
                .filter(record -> contactName.equals(record.contactName))
                .collect(Collectors.toList());
    }

    public static List<HistoryRecord> loadStarredHistory() {
        List<HistoryRecord> records = loadHistory();
        return records.stream()
                .filter(HistoryRecord::isStarred)
                .collect(Collectors.toList());
    }

    public static void setStarred(String id, boolean starred) {
        lock.writeLock().lock();
        try {
            List<HistoryRecord> records = loadHistory();
            for (HistoryRecord record : records) {
                if (record.id.equals(id)) {
                    record.setStarred(starred);
                    break;
                }
            }
            saveHistory(records);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static void addNotes(String id, String notes) {
        lock.writeLock().lock();
        try {
            List<HistoryRecord> records = loadHistory();
            for (HistoryRecord record : records) {
                if (record.id.equals(id)) {
                    record.setNotes(notes);
                    break;
                }
            }
            saveHistory(records);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static int updateFilePaths(String oldDir, String newDir) {
        lock.writeLock().lock();
        int updatedCount = 0;
        try {
            List<HistoryRecord> records = loadHistory();
            for (HistoryRecord record : records) {
                if (record.fileInputPath != null && record.fileInputPath.startsWith(oldDir)) {
                    record.fileInputPath = record.fileInputPath.replace(oldDir, newDir);
                    updatedCount++;
                }
                if (record.fileOutputPath != null && record.fileOutputPath.startsWith(oldDir)) {
                    record.fileOutputPath = record.fileOutputPath.replace(oldDir, newDir);
                    updatedCount++;
                }
                for (FileInfo file : record.getInputFiles()) {
                    if (file.getFilePath() != null && file.getFilePath().startsWith(oldDir)) {
                        file.setFilePath(file.getFilePath().replace(oldDir, newDir));
                        updatedCount++;
                    }
                }
                for (FileInfo file : record.getOutputFiles()) {
                    if (file.getFilePath() != null && file.getFilePath().startsWith(oldDir)) {
                        file.setFilePath(file.getFilePath().replace(oldDir, newDir));
                        updatedCount++;
                    }
                }
            }
            saveHistory(records);
        } finally {
            lock.writeLock().unlock();
        }
        return updatedCount;
    }

    public static boolean addHistory(HistoryRecord record) {
        lock.writeLock().lock();
        try {
            List<HistoryRecord> records = loadHistory();
            records.add(0, record);
            return saveHistory(records);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    public static void addHistoryRecord(String operationType, String inputText, String outputText, 
                                      String keypairName, String contactName, String fileInputPath, 
                                      String fileOutputPath, String comment) {
        lock.writeLock().lock();
        try {
            List<HistoryRecord> records = loadHistory();
            HistoryRecord newRecord = new HistoryRecord(operationType, inputText, outputText, keypairName, 
                                                   contactName, fileInputPath, fileOutputPath, comment);
            records.add(0, newRecord); 
            saveHistory(records);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    public static void deleteHistoryRecord(String id) {
        lock.writeLock().lock();
        try {
            List<HistoryRecord> records = loadHistory();
            records.removeIf(record -> record.id.equals(id));
            saveHistory(records);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    public static void clearHistory() {
        lock.writeLock().lock();
        try {
            Path historyPath = getHistoryFilePath();
            if (Files.exists(historyPath)) {
                Files.delete(historyPath);
            }
            deleteLegacyPlaintextIfPresent();
        } catch (Exception e) {
            LogManager.error("清空历史记录失败", e);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    public static List<HistoryRecord> filterHistory(String operationType) {
        List<HistoryRecord> records = loadHistory();
        if (operationType == null || operationType.isEmpty() || operationType.equals("全部")) {
            return records;
        }
        return records.stream()
                .filter(record -> record.operationType != null && 
                               record.operationType.equals(operationType))
                .collect(Collectors.toList());
    }
    
    public static void addHistoryRecord(String operationType, String inputText, String outputText, 
                                      String keypairName, String contactName, String fileInputPath, 
                                      String fileOutputPath, String comment, Boolean isSplit, 
                                      Integer totalVolumes, Integer currentVolume) {
        lock.writeLock().lock();
        try {
            List<HistoryRecord> records = loadHistory();
            HistoryRecord newRecord = new HistoryRecord(operationType, inputText, outputText, keypairName, 
                                                   contactName, fileInputPath, fileOutputPath, comment);
            newRecord.isSplit = isSplit;
            newRecord.totalVolumes = totalVolumes;
            newRecord.currentVolume = currentVolume;
            records.add(0, newRecord);
            saveHistory(records);
        } finally {
            lock.writeLock().unlock();
        }
    }
}
